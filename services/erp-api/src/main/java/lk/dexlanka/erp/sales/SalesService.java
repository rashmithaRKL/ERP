package lk.dexlanka.erp.sales;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.money;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales documents: draft multi-line orders, confirmation (with a credit-limit warning), cancellation, close-short and
 * per-line dispatch from released lots, which recognizes revenue/receivables and cost of goods sold per delivery.
 *
 * <p>Lifecycle: {@code DRAFT} (editable) → {@code CONFIRMED} → {@code PARTIAL} → {@code DELIVERED}; {@code CANCELLED}
 * before any delivery and while no linked manufacturing order is in progress; {@code CLOSED} (short, with a reason)
 * from {@code PARTIAL}.
 */
@Service
public class SalesService {
    /** Manufacturing statuses between material issue and completion: the order's production is underway. */
    static final Set<String> MO_IN_PROGRESS =
            Set.of("RELEASED", "IN_PROGRESS", "ON_HOLD", "PARTIALLY_COMPLETED", "QUALITY_REVIEW");

    /**
     * Receivables recognized for one customer (tenant, customer, tenant, customer): dispatch revenue less return
     * credits. Cash receipts are not recorded in this release.
     */
    static final String RECEIVABLES_SQL = """
            SELECT COALESCE((SELECT SUM(d.revenue_amount) FROM deliveries d
                             JOIN sales_orders so ON so.tenant_id=d.tenant_id AND so.id=d.sales_order_id
                             WHERE d.tenant_id=? AND so.customer_id=?),0)
                 - COALESCE((SELECT SUM(r.credit_amount) FROM customer_returns r
                             JOIN sales_orders so ON so.tenant_id=r.tenant_id AND so.id=r.sales_order_id
                             WHERE r.tenant_id=? AND so.customer_id=?),0)""";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final Store s;

    SalesService(Store s) {
        this.s = s;
    }

    // ------------------------------------------------------------------ documents

    /** Single-item order confirmed immediately (the original {@code POST /api/sales} contract). */
    @Transactional
    public Map<String, Object> sale(Actor a, SalesCommands.Sale c, String key) {
        a.allow("SALES", "PLANNER");
        s.validDate(c.dueDate());
        return s.once(a, key, "sale", c, () -> {
            activeCustomer(a, c.customerId());
            String id = id();
            String num = s.number(a, "SO");
            s.db().update(
                            "INSERT INTO sales_orders(id,tenant_id,number,customer_id,due_date,status,created_by,confirmed_by,confirmed_at) VALUES(?,?,?,?,?::date,'CONFIRMED',?,?,now())",
                            id,
                            a.tenant(),
                            num,
                            c.customerId(),
                            c.dueDate(),
                            a.id(),
                            a.id());
            var item = writeLines(
                            a,
                            id,
                            List.of(new SalesCommands.Line(null, c.productId(), c.quantity(), c.unitPrice(), null)),
                            null)
                    .getFirst();
            totals(a, id);
            s.audit(a, "SALE_CONFIRMED", num, c.quantity() + " " + item.get("uom") + " confirmed");
            return header(a, id);
        });
    }

    @Transactional
    public Map<String, Object> create(Actor a, SalesCommands.Order c, String key) {
        a.allow("SALES", "PLANNER");
        s.validDate(c.dueDate());
        return s.once(a, key, "so-create", c, () -> {
            var customer = activeCustomer(a, c.customerId());
            String id = id();
            String num = s.number(a, "SO");
            s.db().update(
                            "INSERT INTO sales_orders(id,tenant_id,number,customer_id,customer_reference,due_date,notes,status,created_by) VALUES(?,?,?,?,?,?::date,?,'DRAFT',?)",
                            id,
                            a.tenant(),
                            num,
                            c.customerId(),
                            blankToNull(c.customerReference()),
                            c.dueDate(),
                            c.notes() == null ? "" : c.notes().strip(),
                            a.id());
            writeLines(a, id, c.lines(), null);
            totals(a, id);
            s.audit(a, "SO_DRAFT_CREATED", num, lines(c.lines().size()) + " for " + customer.get("name"));
            return header(a, id);
        });
    }

    /** Replaces a draft's header and lines. Lines that keep their {@code id} keep their identity. */
    @Transactional
    public Map<String, Object> update(Actor a, String id, SalesCommands.Order c, String key) {
        a.allow("SALES", "PLANNER");
        s.validDate(c.dueDate());
        return s.once(a, key, "so-update:" + id, c, () -> {
            var so = locked(a, id);
            require(so.get("status").equals("DRAFT"), "Only draft sales orders can be edited");
            activeCustomer(a, c.customerId());
            var existing = s
                    .list("SELECT id FROM sales_order_lines WHERE tenant_id=? AND sales_order_id=?", a.tenant(), id)
                    .stream()
                    .map(r -> str(r, "id"))
                    .collect(Collectors.toSet());
            s.db().update("DELETE FROM sales_order_lines WHERE tenant_id=? AND sales_order_id=?", a.tenant(), id);
            writeLines(a, id, c.lines(), existing);
            s.db().update(
                            "UPDATE sales_orders SET customer_id=?,customer_reference=?,due_date=?::date,notes=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            c.customerId(),
                            blankToNull(c.customerReference()),
                            c.dueDate(),
                            c.notes() == null ? "" : c.notes().strip(),
                            a.tenant(),
                            id);
            totals(a, id);
            s.audit(a, "SO_DRAFT_UPDATED", str(so, "number"), lines(c.lines().size()) + " after edit");
            return header(a, id);
        });
    }

    @Transactional
    public Map<String, Object> confirm(Actor a, String id, String key) {
        a.allow("SALES", "PLANNER");
        return s.once(a, key, "so-confirm:" + id, Map.of(), () -> {
            var so = locked(a, id);
            require(so.get("status").equals("DRAFT"), "Only draft sales orders can be confirmed");
            var customer = activeCustomer(a, str(so, "customer_id"));
            var lines = s.list(
                    "SELECT l.line_no,i.sku,i.active FROM sales_order_lines l JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.product_id WHERE l.tenant_id=? AND l.sales_order_id=? ORDER BY l.line_no",
                    a.tenant(),
                    id);
            require(!lines.isEmpty(), "Add at least one line before confirming the order");
            for (var l : lines)
                require(
                        Boolean.TRUE.equals(l.get("active")),
                        "Product " + l.get("sku") + " on line " + l.get("line_no") + " is inactive");
            var warnings = creditWarnings(a, customer, dec(so.get("total")));
            s.db().update(
                            "UPDATE sales_orders SET status='CONFIRMED',confirmed_by=?,confirmed_at=now(),updated_at=now() WHERE tenant_id=? AND id=?",
                            a.id(),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "SALE_CONFIRMED",
                    str(so, "number"),
                    lines(lines.size()) + " · total " + amount(dec(so.get("total")))
                            + (warnings.isEmpty() ? "" : " · " + warnings.getFirst()));
            return result(so, "CONFIRMED", warnings);
        });
    }

    @Transactional
    public Map<String, Object> cancel(Actor a, String id, SalesCommands.Reason c, String key) {
        a.allow("SALES", "PLANNER");
        return s.once(a, key, "so-cancel:" + id, c, () -> {
            var so = locked(a, id);
            String state = str(so, "status");
            require(!state.equals("CANCELLED"), "The sales order is already cancelled");
            require(
                    !Set.of("PARTIAL", "DELIVERED", "CLOSED").contains(state)
                            && delivered(a, id).signum() == 0,
                    "Orders with deliveries cannot be cancelled; close the remaining quantity short instead");
            var running = linkedOrders(a, id, MO_IN_PROGRESS);
            require(
                    running.isEmpty(),
                    "Manufacturing order " + String.join(", ", running)
                            + " is in progress for this sales order; complete it before cancelling");
            var open = linkedOrders(a, id, Set.of("DRAFT", "APPROVED", "MATERIALS_RESERVED"));
            s.db().update(
                            "UPDATE sales_orders SET status='CANCELLED',status_reason=?,closed_by=?,closed_at=now(),updated_at=now() WHERE tenant_id=? AND id=?",
                            c.reason().strip(),
                            a.id(),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "SO_CANCELLED",
                    str(so, "number"),
                    state + " → CANCELLED: " + c.reason().strip());
            return result(
                    so,
                    "CANCELLED",
                    open.isEmpty()
                            ? List.of()
                            : List.of("Linked manufacturing order " + String.join(", ", open)
                                    + " is still open; cancel it in Manufacturing if the stock is not needed"));
        });
    }

    /** Ends a partially delivered order; the undelivered remainder is no longer due. */
    @Transactional
    public Map<String, Object> close(Actor a, String id, SalesCommands.Reason c, String key) {
        a.allow("SALES", "PLANNER");
        return s.once(a, key, "so-close:" + id, c, () -> {
            var so = locked(a, id);
            require(so.get("status").equals("PARTIAL"), "Only partially delivered orders can be closed short");
            var open = s.list(
                    "SELECT l.line_no,l.quantity-l.delivered AS open,i.uom FROM sales_order_lines l JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.product_id WHERE l.tenant_id=? AND l.sales_order_id=? AND l.delivered<l.quantity ORDER BY l.line_no",
                    a.tenant(),
                    id);
            s.db().update(
                            "UPDATE sales_orders SET status='CLOSED',status_reason=?,closed_by=?,closed_at=now(),updated_at=now() WHERE tenant_id=? AND id=?",
                            c.reason().strip(),
                            a.id(),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "SO_CLOSED_SHORT",
                    str(so, "number"),
                    open.stream()
                                    .map(l -> "line " + l.get("line_no") + " " + plain(l.get("open")) + " "
                                            + l.get("uom"))
                                    .collect(Collectors.joining(", "))
                            + " not delivered: " + c.reason().strip());
            return result(so, "CLOSED", List.of());
        });
    }

    // ------------------------------------------------------------------ dispatch

    /** Single-lot dispatch (the original {@code POST /api/sales/{id}/dispatch} contract). */
    @Transactional
    public Map<String, Object> dispatch(Actor a, String id, SalesCommands.Dispatch c, String key) {
        a.allow("WAREHOUSE");
        return s.once(a, key, "dispatch:" + id, c, () -> {
            String line = c.lineId() == null || c.lineId().isBlank() ? lineForLot(a, id, c.lotId()) : c.lineId();
            post(a, id, line, c.reference(), List.of(new SalesCommands.Allocation(c.lotId(), c.quantity())));
            return Map.of("id", id, "status", "DISPATCHED");
        });
    }

    /** Dispatches one order line from one or more released lots; one delivery (and journal pair) per lot. */
    @Transactional
    public Map<String, Object> dispatchLine(
            Actor a, String id, String lineId, SalesCommands.DispatchLine c, String key) {
        a.allow("WAREHOUSE");
        return s.once(a, key, "dispatch:" + id + ":" + lineId, c, () -> post(a, id, lineId, c.reference(), c.lots()));
    }

    private Map<String, Object> post(
            Actor a, String id, String lineId, String reference, List<SalesCommands.Allocation> lots) {
        s.openPeriod(a);
        var so = locked(a, id);
        var line = s.one(
                "SELECT l.*,i.uom,i.sku FROM sales_order_lines l JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.product_id WHERE l.tenant_id=? AND l.sales_order_id=? AND l.id=?",
                a.tenant(),
                id,
                lineId);
        require(
                Set.of("CONFIRMED", "PARTIAL").contains(str(so, "status")),
                "Only confirmed sales orders with an open quantity can be dispatched");
        var seen = new HashSet<String>();
        for (var lot : lots) require(seen.add(lot.lotId()), "Each lot can appear only once per dispatch");
        BigDecimal ordered = dec(line.get("quantity"));
        BigDecimal delivered = dec(line.get("delivered"));
        BigDecimal recognized = s.db().queryForObject(
                        "SELECT COALESCE(SUM(revenue_amount),0) FROM deliveries WHERE tenant_id=? AND sales_order_line_id=?",
                        BigDecimal.class,
                        a.tenant(),
                        lineId);
        BigDecimal total = BigDecimal.ZERO;
        var deliveries = new ArrayList<String>();
        var shipped = new ArrayList<String>();
        for (var allocation : lots) {
            var lot = s.one(
                    "SELECT l.*,b.quantity-b.reserved AS available FROM lots l JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id WHERE l.tenant_id=? AND l.id=? FOR UPDATE OF b",
                    a.tenant(),
                    allocation.lotId());
            require(lot.get("status").equals("RELEASED"), "Quality-held or rejected lots cannot be dispatched");
            require(lot.get("item_id").equals(line.get("product_id")), "Lot does not match the sales product");
            require(
                    allocation.quantity().compareTo(dec(lot.get("available"))) <= 0,
                    "Insufficient unreserved finished stock");
            require(
                    delivered.add(allocation.quantity()).compareTo(ordered) <= 0,
                    "Dispatch exceeds the outstanding customer quantity");
            BigDecimal cost = s.ledger(
                            a, allocation.lotId(), allocation.quantity().negate(), reference, "CUSTOMER_DISPATCH")
                    .abs();
            delivered = delivered.add(allocation.quantity());
            // The delivery that completes a line absorbs the rounding residual, so line revenue equals the line total.
            BigDecimal revenue = delivered.compareTo(ordered) == 0
                    ? dec(line.get("line_total")).subtract(recognized).max(BigDecimal.ZERO)
                    : net(allocation.quantity(), line);
            recognized = recognized.add(revenue);
            total = total.add(allocation.quantity());
            String delivery = id();
            s.db().update(
                            "INSERT INTO deliveries(id,tenant_id,sales_order_id,sales_order_line_id,lot_id,quantity,reference,revenue_amount,cost_amount,created_by) VALUES(?,?,?,?,?,?,?,?,?,?)",
                            delivery,
                            a.tenant(),
                            id,
                            lineId,
                            allocation.lotId(),
                            allocation.quantity(),
                            reference,
                            money(revenue),
                            money(cost),
                            a.id());
            s.journal(a, reference, "Finished goods dispatched", "COST_OF_GOODS_SOLD", "FG_INVENTORY", cost);
            s.journal(
                    a,
                    reference,
                    "Sales invoice recognized on dispatch",
                    "ACCOUNTS_RECEIVABLE",
                    "SALES_REVENUE",
                    money(revenue));
            deliveries.add(delivery);
            shipped.add(plain(allocation.quantity()) + " from lot " + lot.get("number"));
        }
        s.db().update(
                        "UPDATE sales_order_lines SET delivered=? WHERE tenant_id=? AND id=?",
                        delivered,
                        a.tenant(),
                        lineId);
        String next = Boolean.TRUE.equals(s.db().queryForObject(
                                "SELECT bool_and(delivered=quantity) FROM sales_order_lines WHERE tenant_id=? AND sales_order_id=?",
                                Boolean.class,
                                a.tenant(),
                                id))
                ? "DELIVERED"
                : "PARTIAL";
        s.db().update(
                        "UPDATE sales_orders SET status=?,updated_at=now() WHERE tenant_id=? AND id=?",
                        next,
                        a.tenant(),
                        id);
        s.audit(
                a,
                "DISPATCH_POSTED",
                str(so, "number"),
                reference + ": " + plain(total) + " " + line.get("uom") + " of " + line.get("sku") + " (line "
                        + line.get("line_no") + ") · " + String.join(", ", shipped));
        var out = new LinkedHashMap<String, Object>();
        out.put("id", id);
        out.put("status", "DISPATCHED");
        out.put("orderStatus", next);
        out.put("deliveries", deliveries);
        return out;
    }

    /** The order line a legacy single-lot dispatch belongs to: the only open line of the lot's product. */
    private String lineForLot(Actor a, String id, String lotId) {
        s.one("SELECT id FROM sales_orders WHERE tenant_id=? AND id=?", a.tenant(), id);
        var lot = s.one("SELECT item_id FROM lots WHERE tenant_id=? AND id=?", a.tenant(), lotId);
        var lines = s.list(
                "SELECT id,quantity-delivered AS open FROM sales_order_lines WHERE tenant_id=? AND sales_order_id=? AND product_id=? ORDER BY line_no",
                a.tenant(),
                id,
                lot.get("item_id"));
        require(!lines.isEmpty(), "Lot does not match the sales product");
        var open = lines.stream().filter(l -> dec(l.get("open")).signum() > 0).toList();
        if (open.isEmpty()) return str(lines.getFirst(), "id");
        require(open.size() == 1, "Several lines carry this product; choose the sales order line to dispatch");
        return str(open.getFirst(), "id");
    }

    // ------------------------------------------------------------------ helpers

    Map<String, Object> header(Actor a, String id) {
        return s.one("SELECT * FROM sales_orders WHERE tenant_id=? AND id=?", a.tenant(), id);
    }

    private Map<String, Object> locked(Actor a, String id) {
        return s.one("SELECT * FROM sales_orders WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
    }

    private Map<String, Object> activeCustomer(Actor a, String customerId) {
        var p = s.one("SELECT * FROM partners WHERE tenant_id=? AND id=? AND type='CUSTOMER'", a.tenant(), customerId);
        require(Boolean.TRUE.equals(p.get("active")), "Inactive customers cannot be used on new documents");
        return p;
    }

    /** Inserts lines 1..n in the given order and returns the product rows. {@code keep}: ids allowed to be reused. */
    private List<Map<String, Object>> writeLines(
            Actor a, String orderId, List<SalesCommands.Line> lines, Set<String> keep) {
        var products = new ArrayList<Map<String, Object>>();
        var ids = new HashSet<String>();
        int number = 0;
        for (var l : lines) {
            var item = s.one(
                    "SELECT * FROM items WHERE tenant_id=? AND id=? AND type='FINISHED_GOOD' AND active=true",
                    a.tenant(),
                    l.productId());
            s.quantity(str(item, "uom"), l.quantity());
            String lineId = id();
            if (keep != null && l.id() != null && !l.id().isBlank()) {
                require(keep.contains(l.id()), "A line does not belong to this sales order");
                lineId = l.id();
            }
            require(ids.add(lineId), "Each line can appear only once");
            s.db().update(
                            "INSERT INTO sales_order_lines(id,tenant_id,sales_order_id,line_no,product_id,quantity,unit_price,discount_pct) VALUES(?,?,?,?,?,?,?,?)",
                            lineId,
                            a.tenant(),
                            orderId,
                            ++number,
                            l.productId(),
                            l.quantity(),
                            l.unitPrice(),
                            l.discountPct() == null ? BigDecimal.ZERO : l.discountPct());
            products.add(item);
        }
        return products;
    }

    private void totals(Actor a, String id) {
        s.db().update("""
                        UPDATE sales_orders so SET subtotal=t.subtotal,total=t.total,discount_total=t.subtotal-t.total
                        FROM (SELECT COALESCE(SUM(round(quantity*unit_price,6)),0) AS subtotal,
                                     COALESCE(SUM(line_total),0) AS total
                              FROM sales_order_lines WHERE tenant_id=? AND sales_order_id=?) t
                        WHERE so.tenant_id=? AND so.id=?""", a.tenant(), id, a.tenant(), id);
    }

    private BigDecimal delivered(Actor a, String id) {
        return s.db().queryForObject(
                        "SELECT COALESCE(SUM(delivered),0) FROM sales_order_lines WHERE tenant_id=? AND sales_order_id=?",
                        BigDecimal.class,
                        a.tenant(),
                        id);
    }

    private List<String> linkedOrders(Actor a, String id, Set<String> statuses) {
        return s
                .list(
                        "SELECT number,status FROM manufacturing_orders WHERE tenant_id=? AND sales_order_id=? ORDER BY number",
                        a.tenant(),
                        id)
                .stream()
                .filter(o -> statuses.contains(str(o, "status")))
                .map(o -> str(o, "number"))
                .toList();
    }

    private List<String> creditWarnings(Actor a, Map<String, Object> customer, BigDecimal orderTotal) {
        if (customer.get("credit_limit") == null) return List.of();
        BigDecimal limit = dec(customer.get("credit_limit"));
        BigDecimal receivables = s.db().queryForObject(
                        RECEIVABLES_SQL,
                        BigDecimal.class,
                        a.tenant(),
                        customer.get("id"),
                        a.tenant(),
                        customer.get("id"));
        if (receivables.add(orderTotal).compareTo(limit) <= 0) return List.of();
        return List.of("Credit limit exceeded: open receivables " + amount(receivables) + " plus this order "
                + amount(orderTotal) + " are above the limit of " + amount(limit));
    }

    /** Net revenue of {@code quantity} at the line's unit price and discount. */
    private static BigDecimal net(BigDecimal quantity, Map<String, Object> line) {
        return money(quantity.multiply(dec(line.get("unit_price")))
                .multiply(HUNDRED.subtract(dec(line.get("discount_pct"))))
                .divide(HUNDRED));
    }

    private static Map<String, Object> result(Map<String, Object> so, String status, List<String> warnings) {
        var out = new LinkedHashMap<String, Object>();
        out.put("id", so.get("id"));
        out.put("number", so.get("number"));
        out.put("status", status);
        out.put("warnings", warnings);
        return out;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String lines(int count) {
        return count + (count == 1 ? " line" : " lines");
    }

    private static String plain(Object value) {
        return dec(value).stripTrailingZeros().toPlainString();
    }

    private static String amount(BigDecimal value) {
        return "Rs. " + String.format(Locale.ROOT, "%,.2f", value.setScale(2, RoundingMode.HALF_UP));
    }
}
