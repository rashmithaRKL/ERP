package lk.dexlanka.erp.procurement;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;
import static lk.dexlanka.erp.procurement.PurchaseRules.amount;
import static lk.dexlanka.erp.procurement.PurchaseRules.blankToNull;
import static lk.dexlanka.erp.procurement.PurchaseRules.lines;
import static lk.dexlanka.erp.procurement.PurchaseRules.plain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lk.dexlanka.erp.catalog.UnitService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase-order documents: draft multi-line orders in purchase units, submission, independent approval,
 * cancellation, close-short and draft orders created from material-planning recommendations.
 */
@Service
public class PurchaseOrderService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Colombo");

    private final Store s;
    private final PurchaseRules rules;
    private final UnitService units;

    PurchaseOrderService(Store s, PurchaseRules rules, UnitService units) {
        this.s = s;
        this.rules = rules;
        this.units = units;
    }

    // ------------------------------------------------------------------ documents

    @Transactional
    public Map<String, Object> create(Actor a, ProcurementCommands.Order c, String key) {
        a.allow("PLANNER", "WAREHOUSE");
        validDates(c);
        return s.once(a, key, "po-create", c, () -> {
            String supplier = supplierOrNull(a, c.supplierId());
            String id = insertHeader(a, supplier, c.supplierReference(), c.dueDate(), c.notes(), "DRAFT");
            writeLines(a, id, c.lines(), null);
            rules.totals(a, id);
            var po = rules.header(a, id);
            s.audit(
                    a,
                    "PO_DRAFT_CREATED",
                    str(po, "number"),
                    lines(c.lines().size()) + (supplier == null ? " · supplier to be chosen" : " for " + name(a, po))
                            + " · total " + amount(dec(po.get("total"))));
            return po;
        });
    }

    /** Replaces a draft's header and lines. Lines that keep their {@code id} keep their identity. */
    @Transactional
    public Map<String, Object> update(Actor a, String id, ProcurementCommands.Order c, String key) {
        a.allow("PLANNER", "WAREHOUSE");
        validDates(c);
        return s.once(a, key, "po-update:" + id, c, () -> {
            var po = rules.locked(a, id);
            require(po.get("status").equals("DRAFT"), "Only draft purchase orders can be edited");
            String supplier = supplierOrNull(a, c.supplierId());
            var existing = s
                    .list(
                            "SELECT id FROM purchase_order_lines WHERE tenant_id=? AND purchase_order_id=?",
                            a.tenant(),
                            id)
                    .stream()
                    .map(r -> str(r, "id"))
                    .collect(Collectors.toSet());
            s.db().update("DELETE FROM purchase_order_lines WHERE tenant_id=? AND purchase_order_id=?", a.tenant(), id);
            writeLines(a, id, c.lines(), existing);
            s.db().update(
                            "UPDATE purchase_orders SET supplier_id=?,supplier_reference=?,due_date=?::date,notes=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            supplier,
                            blankToNull(c.supplierReference()),
                            c.dueDate(),
                            c.notes() == null ? "" : c.notes().strip(),
                            a.tenant(),
                            id);
            rules.totals(a, id);
            var updated = rules.header(a, id);
            s.audit(
                    a,
                    "PO_DRAFT_UPDATED",
                    str(po, "number"),
                    lines(c.lines().size()) + " after edit · total " + amount(dec(updated.get("total"))));
            return updated;
        });
    }

    /** Sends a complete draft for independent approval. */
    @Transactional
    public Map<String, Object> submit(Actor a, String id, String key) {
        a.allow("PLANNER", "WAREHOUSE");
        return s.once(a, key, "po-submit:" + id, Map.of(), () -> {
            var po = rules.locked(a, id);
            require(po.get("status").equals("DRAFT"), "Only draft purchase orders can be submitted for approval");
            rules.requireOrderable(a, po, "submitted");
            s.db().update(
                            "UPDATE purchase_orders SET status='SUBMITTED',submitted_by=?,submitted_at=now(),updated_at=now() WHERE tenant_id=? AND id=?",
                            a.id(),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "PO_SUBMITTED",
                    str(po, "number"),
                    "Total " + amount(dec(po.get("total"))) + " · awaiting independent approval");
            return result(po, "SUBMITTED");
        });
    }

    /**
     * Independent approval: the approver must be a PLANNER or ADMIN other than the person who created or submitted the
     * order (the rule applies to ADMIN too).
     */
    @Transactional
    public Map<String, Object> approve(Actor a, String id, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "approve-po:" + id, Map.of(), () -> {
            var po = rules.locked(a, id);
            require(
                    po.get("status").equals("SUBMITTED"),
                    po.get("status").equals("DRAFT")
                            ? "Submit the draft for approval first"
                            : "Only purchase orders awaiting approval can be approved");
            require(
                    !a.id().equals(po.get("created_by")) && !a.id().equals(po.get("submitted_by")),
                    "A different authorized person must approve this purchase");
            rules.requireOrderable(a, po, "approved");
            s.db().update(
                            "UPDATE purchase_orders SET status='APPROVED',approved_by=?,approved_at=now(),updated_at=now() WHERE tenant_id=? AND id=?",
                            a.id(),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "PO_APPROVED",
                    str(po, "number"),
                    "Approved for receipt · total " + amount(dec(po.get("total"))));
            return result(po, "APPROVED");
        });
    }

    /** Cancels an order while nothing is received. A warehouse user may only discard drafts. */
    @Transactional
    public Map<String, Object> cancel(Actor a, String id, ProcurementCommands.Reason c, String key) {
        a.allow("PLANNER", "WAREHOUSE");
        return s.once(a, key, "po-cancel:" + id, c, () -> {
            var po = rules.locked(a, id);
            String state = str(po, "status");
            require(!state.equals("CANCELLED"), "The purchase order is already cancelled");
            if (!state.equals("DRAFT")) a.allow("PLANNER");
            require(
                    Set.of("DRAFT", "SUBMITTED", "APPROVED", "PARTIAL").contains(state)
                            && rules.received(a, id).signum() == 0,
                    "Purchase orders with received goods cannot be cancelled; close the remaining quantity short instead");
            s.db().update(
                            "UPDATE purchase_orders SET status='CANCELLED',status_reason=?,closed_by=?,closed_at=now(),updated_at=now() WHERE tenant_id=? AND id=?",
                            c.reason().strip(),
                            a.id(),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "PO_CANCELLED",
                    str(po, "number"),
                    state + " → CANCELLED: " + c.reason().strip());
            return result(po, "CANCELLED");
        });
    }

    /** Ends a partially received order; the remainder is no longer expected (and leaves MRP supply). */
    @Transactional
    public Map<String, Object> close(Actor a, String id, ProcurementCommands.Reason c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "po-close:" + id, c, () -> {
            var po = rules.locked(a, id);
            require(po.get("status").equals("PARTIAL"), "Only partially received purchase orders can be closed short");
            require(
                    rules.received(a, id).signum() > 0,
                    "Nothing is received on this order; cancel it instead of closing it short");
            var open = s.list(
                    "SELECT l.line_no,l.base_quantity-l.received AS open,i.uom FROM purchase_order_lines l JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id WHERE l.tenant_id=? AND l.purchase_order_id=? AND l.received<l.base_quantity ORDER BY l.line_no",
                    a.tenant(),
                    id);
            s.db().update(
                            "UPDATE purchase_orders SET status='CLOSED',status_reason=?,closed_by=?,closed_at=now(),updated_at=now() WHERE tenant_id=? AND id=?",
                            c.reason().strip(),
                            a.id(),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "PO_CLOSED_SHORT",
                    str(po, "number"),
                    open.stream()
                                    .map(l -> "line " + l.get("line_no") + " " + plain(l.get("open")) + " "
                                            + l.get("uom"))
                                    .collect(Collectors.joining(", "))
                            + " not received: " + c.reason().strip());
            return result(po, "CLOSED");
        });
    }

    // ------------------------------------------------------------------ original single-item contract

    /** Single-item purchase created and submitted in one step (the original {@code POST /api/purchases}). */
    @Transactional
    public Map<String, Object> purchase(Actor a, ProcurementCommands.Purchase c, String key) {
        a.allow("PLANNER", "WAREHOUSE");
        s.validDate(c.dueDate());
        return s.once(a, key, "purchase", c, () -> {
            var supplier = rules.activeSupplier(a, c.supplierId());
            String id = insertHeader(a, c.supplierId(), null, c.dueDate(), "", "SUBMITTED");
            s.db().update(
                            "UPDATE purchase_orders SET submitted_by=?,submitted_at=now() WHERE tenant_id=? AND id=?",
                            a.id(),
                            a.tenant(),
                            id);
            var item = writeLines(
                            a,
                            id,
                            List.of(new ProcurementCommands.OrderLine(
                                    null, c.itemId(), c.quantity(), null, c.unitCost(), null)),
                            null)
                    .getFirst();
            rules.totals(a, id);
            var po = rules.header(a, id);
            s.audit(
                    a,
                    "PO_CREATED",
                    str(po, "number"),
                    plain(c.quantity()) + " " + item.get("uom") + " of " + item.get("sku") + " from "
                            + supplier.get("name") + " · purchase awaiting independent approval");
            return po;
        });
    }

    // ------------------------------------------------------------------ planning

    /**
     * Draft purchase orders from material-planning shortages: one draft per supplier. A line's supplier is the one in
     * the request, else the item's preferred supplier (the active supplier it was last ordered from); lines without a
     * known supplier share one draft without a supplier for the planner to complete. Lines are in the base unit,
     * priced at the last purchase price from that supplier (else the standard cost), expected after the lead time.
     */
    @Transactional
    public Map<String, Object> fromRecommendations(Actor a, ProcurementCommands.Recommendations c, String key) {
        a.allow("PLANNER", "WAREHOUSE");
        if (c.dueDate() != null) s.validDate(c.dueDate());
        return s.once(a, key, "po-recommendations", c, () -> {
            LocalDate today = LocalDate.now(BUSINESS_ZONE);
            var groups = new LinkedHashMap<String, List<ProcurementCommands.OrderLine>>();
            var seen = new HashSet<String>();
            for (var r : c.lines()) {
                require(seen.add(r.itemId()), "Each material can appear only once in a planning request");
                var item = rules.purchasable(a, r.itemId());
                int decimals = ((Number) s.one(
                                        "SELECT decimals FROM units WHERE tenant_id=? AND code=?",
                                        a.tenant(),
                                        item.get("uom"))
                                .get("decimals"))
                        .intValue();
                // Shortages are bought in whole base units of the unit's precision, rounded up.
                BigDecimal quantity = r.quantity().setScale(decimals, RoundingMode.CEILING);
                String supplier = r.supplierId() == null || r.supplierId().isBlank()
                        ? preferredSupplier(a, r.itemId())
                        : str(rules.activeSupplier(a, r.supplierId()), "id");
                BigDecimal cost = supplier == null ? null : lastCost(a, r.itemId(), supplier);
                int lead = item.get("lead_time_days") == null ? 0 : ((Number) item.get("lead_time_days")).intValue();
                groups.computeIfAbsent(supplier == null ? "" : supplier, k -> new ArrayList<>())
                        .add(new ProcurementCommands.OrderLine(
                                null,
                                r.itemId(),
                                quantity,
                                null,
                                cost == null ? dec(item.get("standard_cost")) : cost,
                                today.plusDays(lead).toString()));
            }
            var created = new ArrayList<Map<String, Object>>();
            for (var group : groups.entrySet()) {
                String supplier = group.getKey().isEmpty() ? null : group.getKey();
                String due = c.dueDate() != null
                        ? c.dueDate()
                        : group.getValue().stream()
                                .map(ProcurementCommands.OrderLine::expectedDate)
                                .max(String::compareTo)
                                .orElse(today.toString());
                String id = insertHeader(a, supplier, null, due, "Created from material planning shortages", "DRAFT");
                writeLines(a, id, group.getValue(), null);
                rules.totals(a, id);
                var po = rules.header(a, id);
                s.audit(
                        a,
                        "PO_DRAFT_CREATED",
                        str(po, "number"),
                        "From material planning: " + lines(group.getValue().size())
                                + (supplier == null ? " · supplier to be chosen" : " for " + name(a, po)) + " · total "
                                + amount(dec(po.get("total"))));
                var out = new LinkedHashMap<String, Object>();
                out.put("id", id);
                out.put("number", po.get("number"));
                out.put("supplierId", supplier);
                out.put("supplier", supplier == null ? null : name(a, po));
                out.put("lines", group.getValue().size());
                out.put("total", po.get("total"));
                created.add(out);
            }
            return Map.of("purchases", created, "count", created.size());
        });
    }

    /** The active supplier an item was most recently ordered from (cancelled orders ignored), or null. */
    String preferredSupplier(Actor a, String itemId) {
        var rows = s.list("""
                SELECT po.supplier_id FROM purchase_order_lines l
                JOIN purchase_orders po ON po.tenant_id=l.tenant_id AND po.id=l.purchase_order_id
                JOIN partners p ON p.tenant_id=po.tenant_id AND p.id=po.supplier_id
                WHERE l.tenant_id=? AND l.item_id=? AND po.status NOT IN ('CANCELLED','DRAFT') AND p.active
                ORDER BY po.created_at DESC,po.id DESC
                LIMIT 1""", a.tenant(), itemId);
        return rows.isEmpty() ? null : str(rows.getFirst(), "supplier_id");
    }

    /** The base-unit cost of the item's most recent order line with this supplier, or null. */
    private BigDecimal lastCost(Actor a, String itemId, String supplierId) {
        var rows = s.list("""
                SELECT l.base_unit_cost FROM purchase_order_lines l
                JOIN purchase_orders po ON po.tenant_id=l.tenant_id AND po.id=l.purchase_order_id
                WHERE l.tenant_id=? AND l.item_id=? AND po.supplier_id=? AND po.status NOT IN ('CANCELLED','DRAFT')
                ORDER BY po.created_at DESC,po.id DESC,l.line_no
                LIMIT 1""", a.tenant(), itemId, supplierId);
        return rows.isEmpty() ? null : dec(rows.getFirst().get("base_unit_cost"));
    }

    // ------------------------------------------------------------------ helpers

    private String insertHeader(Actor a, String supplier, String reference, String due, String notes, String status) {
        String id = id();
        s.db().update(
                        "INSERT INTO purchase_orders(id,tenant_id,number,supplier_id,supplier_reference,due_date,notes,status,created_by) VALUES(?,?,?,?,?,?::date,?,?,?)",
                        id,
                        a.tenant(),
                        rules.documentNumber(a, "purchase_orders", "PO"),
                        supplier,
                        blankToNull(reference),
                        due,
                        notes == null ? "" : notes.strip(),
                        status,
                        a.id());
        return id;
    }

    /**
     * Inserts lines 1..n in the given order and returns the item rows. Each quantity is converted to the item's base
     * unit exactly ({@link UnitService#toBaseExact}); a unit other than the base unit must be a conversion the item may
     * be purchased in (or a standard unit of the same kind). {@code keep}: ids an edited draft may reuse.
     */
    List<Map<String, Object>> writeLines(
            Actor a, String orderId, List<ProcurementCommands.OrderLine> lines, Set<String> keep) {
        var items = new ArrayList<Map<String, Object>>();
        var ids = new HashSet<String>();
        int number = 0;
        for (var l : lines) {
            var item = rules.purchasable(a, l.itemId());
            String base = str(item, "uom");
            String unit =
                    l.unit() == null || l.unit().isBlank() ? base : l.unit().strip();
            if (!unit.equals(base)) {
                var conversion = s.list(
                        "SELECT purchase FROM item_unit_conversions WHERE tenant_id=? AND item_id=? AND unit_code=?",
                        a.tenant(),
                        l.itemId(),
                        unit);
                require(
                        conversion.isEmpty()
                                || Boolean.TRUE.equals(conversion.getFirst().get("purchase")),
                        "SKU " + item.get("sku") + " is not purchased in " + unit
                                + "; enable the conversion for purchasing or use another unit");
            }
            BigDecimal factor = units.factor(a, l.itemId(), unit);
            BigDecimal baseQuantity = units.toBaseExact(a, l.itemId(), l.quantity(), unit);
            s.quantity(base, baseQuantity);
            require(baseQuantity.signum() > 0, "Line " + (number + 1) + " converts to zero " + base);
            if (l.expectedDate() != null) s.validDate(l.expectedDate());
            String lineId = id();
            if (keep != null && l.id() != null && !l.id().isBlank()) {
                require(keep.contains(l.id()), "A line does not belong to this purchase order");
                lineId = l.id();
            }
            require(ids.add(lineId), "Each line can appear only once");
            s.db().update(
                            "INSERT INTO purchase_order_lines(id,tenant_id,purchase_order_id,line_no,item_id,quantity,unit,factor,base_quantity,unit_cost,base_unit_cost,expected_date) VALUES(?,?,?,?,?,?,?,?,?,?,?,?::date)",
                            lineId,
                            a.tenant(),
                            orderId,
                            ++number,
                            l.itemId(),
                            l.quantity(),
                            unit,
                            factor,
                            baseQuantity,
                            l.unitCost(),
                            PurchaseRules.baseUnitCost(l.unitCost(), factor),
                            l.expectedDate());
            items.add(item);
        }
        return items;
    }

    private String supplierOrNull(Actor a, String supplierId) {
        return supplierId == null || supplierId.isBlank() ? null : str(rules.activeSupplier(a, supplierId), "id");
    }

    private String name(Actor a, Map<String, Object> po) {
        return str(
                s.one("SELECT name FROM partners WHERE tenant_id=? AND id=?", a.tenant(), po.get("supplier_id")),
                "name");
    }

    private void validDates(ProcurementCommands.Order c) {
        s.validDate(c.dueDate());
        for (var l : c.lines()) if (l.expectedDate() != null) s.validDate(l.expectedDate());
    }

    private static Map<String, Object> result(Map<String, Object> po, String status) {
        var out = new LinkedHashMap<String, Object>();
        out.put("id", po.get("id"));
        out.put("number", po.get("number"));
        out.put("status", status);
        return out;
    }
}
