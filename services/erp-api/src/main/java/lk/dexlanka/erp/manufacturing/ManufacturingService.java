package lk.dexlanka.erp.manufacturing;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.money;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.admin.ApprovalPolicyService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manufacturing-order lifecycle: BOM snapshot, approval, FIFO reservation, material issue to WIP and output.
 *
 * <p>Approval follows the tenant's {@code MANUFACTURING_ORDER} approval policy (V9, {@link ApprovalPolicyService}): a new
 * order whose estimated cost needs no second person is approved on creation ("auto-approved by policy"); otherwise it
 * stays a draft until an approver role of the policy other than its creator approves it. The default policy (every
 * order, PLANNER or ADMIN) is the behaviour before policies existed.
 */
@Service
public class ManufacturingService {
    private final Store s;
    private final ApprovalPolicyService policies;

    ManufacturingService(Store s, ApprovalPolicyService policies) {
        this.s = s;
        this.policies = policies;
    }

    public Map<String, Object> order(Actor a, String id) {
        return s.one("SELECT * FROM manufacturing_orders WHERE tenant_id=? AND id=?", a.tenant(), id);
    }

    @Transactional
    public Map<String, Object> create(Actor a, ManufacturingCommands.Manufacture c, String key) {
        a.allow("PLANNER");
        s.validDate(c.dueDate());
        return s.once(a, key, "manufacture", c, () -> insert(a, c));
    }

    /**
     * Snapshots the BOM into a new draft order. Runs inside the caller's command ({@link #create}, or a child-order run
     * in {@link OrderTreeService}): tenant locked, idempotency handled by the caller.
     */
    Map<String, Object> insert(Actor a, ManufacturingCommands.Manufacture c) {
        {
            var b = s.one(
                    "SELECT b.*,i.type,i.active FROM boms b JOIN items i ON i.id=b.product_id AND i.tenant_id=b.tenant_id WHERE b.tenant_id=? AND b.id=?",
                    a.tenant(),
                    c.bomId());
            require(b.get("status").equals("APPROVED"), "Use an approved BOM revision");
            require(Boolean.TRUE.equals(b.get("active")), "This product is inactive and cannot be planned");
            s.quantity(
                    str(
                            s.one("SELECT uom FROM items WHERE tenant_id=? AND id=?", a.tenant(), b.get("product_id")),
                            "uom"),
                    c.quantity());
            var m = s.one("SELECT * FROM machines WHERE tenant_id=? AND id=?", a.tenant(), c.machineId());
            var demand = salesLine(a, c, str(b, "product_id"));
            var parent = parentOrder(a, c, str(b, "product_id"), demand != null);
            if (demand != null) {
                var planned = s.db().queryForObject(
                                "SELECT COALESCE(SUM(quantity-scrap_qty),0) FROM manufacturing_orders WHERE tenant_id=? AND sales_order_line_id=? AND status<>'CANCELLED'",
                                BigDecimal.class,
                                a.tenant(),
                                demand.get("id"));
                require(
                        planned.add(c.quantity()).compareTo(dec(demand.get("base_quantity"))) <= 0,
                        "Manufacturing quantity exceeds unplanned customer demand");
            }
            String id = id();
            String num = s.number(a, "MO");
            BigDecimal outputBase = dec(b.get("output_qty")).multiply(dec(b.get("yield_factor")));
            var lines = s.list(
                    "SELECT bl.*,i.standard_cost,i.uom,i.sku,i.active FROM bom_lines bl JOIN items i ON i.tenant_id=bl.tenant_id AND i.id=bl.item_id WHERE bl.tenant_id=? AND bl.bom_id=? ORDER BY bl.item_id",
                    a.tenant(),
                    c.bomId());
            require(!lines.isEmpty(), "The BOM has no materials");
            var inactive = lines.stream()
                    .filter(l -> !Boolean.TRUE.equals(l.get("active")))
                    .map(l -> str(l, "sku"))
                    .toList();
            require(
                    inactive.isEmpty(),
                    "Inactive materials in this BOM: " + String.join(", ", inactive)
                            + ". Reactivate them or approve a new revision");
            BigDecimal estimate = lines.stream()
                    .map(l -> requirement(l, c.quantity(), outputBase).multiply(dec(l.get("standard_cost"))))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            s.db().update(
                            "INSERT INTO manufacturing_orders(id,tenant_id,number,product_id,bom_id,bom_revision,sales_order_id,sales_order_line_id,machine_id,factory_id,quantity,priority,due_date,estimated_cost,created_by,parent_order_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?::date,?,?,?)",
                            id,
                            a.tenant(),
                            num,
                            b.get("product_id"),
                            c.bomId(),
                            b.get("revision"),
                            demand == null ? null : demand.get("sales_order_id"),
                            demand == null ? null : demand.get("id"),
                            c.machineId(),
                            m.get("factory_id"),
                            c.quantity(),
                            c.priority(),
                            c.dueDate(),
                            money(estimate),
                            a.id(),
                            parent == null ? null : parent.get("id"));
            for (var l : lines)
                s.db().update(
                                "INSERT INTO order_materials(id,tenant_id,order_id,item_id,required_qty) VALUES(?,?,?,?,?)",
                                id(),
                                a.tenant(),
                                id,
                                l.get("item_id"),
                                requirement(l, c.quantity(), outputBase));
            s.audit(
                    a,
                    "MO_CREATED",
                    num,
                    "BOM " + b.get("revision") + " snapshotted; " + c.quantity() + " units"
                            + (parent == null ? "" : "; child of " + parent.get("number")));
            var decision =
                    policies.evaluate(a.tenant(), ApprovalPolicyService.MANUFACTURING_ORDER, money(estimate), a.id());
            if (!decision.approvalRequired()) {
                s.db().update(
                                "UPDATE manufacturing_orders SET status='APPROVED' WHERE tenant_id=? AND id=?",
                                a.tenant(),
                                id);
                s.audit(a, "MO_AUTO_APPROVED", num, ApprovalPolicyService.autoApproval(decision, money(estimate)));
            }
            return order(a, id);
        }
    }

    /**
     * V7: the parent order a child order supplies, or null. The parent must be a draft or approved order of the tenant
     * that consumes the child's product, due no earlier than the child; a child is never also a sales-order run.
     */
    private Map<String, Object> parentOrder(
            Actor a, ManufacturingCommands.Manufacture c, String product, boolean forSalesLine) {
        if (c.parentOrderId() == null || c.parentOrderId().isBlank()) return null;
        var parent =
                s.one("SELECT * FROM manufacturing_orders WHERE tenant_id=? AND id=?", a.tenant(), c.parentOrderId());
        require(
                Set.of("DRAFT", "APPROVED").contains(str(parent, "status")),
                "Child orders can be added only to a draft or approved parent order");
        require(!forSalesLine, "A child order supplies its parent order; do not link it to a sales order line");
        require(
                !s.list(
                                "SELECT 1 FROM order_materials WHERE tenant_id=? AND order_id=? AND item_id=?",
                                a.tenant(),
                                c.parentOrderId(),
                                product)
                        .isEmpty(),
                "Parent order " + str(parent, "number") + " does not consume this product");
        require(
                java.time.LocalDate.parse(c.dueDate()).compareTo(java.time.LocalDate.parse(str(parent, "due_date")))
                        <= 0,
                "A child order must be due on or before its parent order (" + str(parent, "due_date") + ")");
        return parent;
    }

    @Transactional
    public Map<String, Object> transition(Actor a, String id, String action, String key) {
        return s.once(a, key, "mo:" + id + ":" + action, Map.of(), () -> {
            var o = order(a, id);
            String state = str(o, "status");
            String num = str(o, "number");
            String next;
            switch (action) {
                case "approve" -> {
                    // The approver roles of the MANUFACTURING_ORDER policy (default PLANNER; ADMIN always), never the
                    // order's creator.
                    var approver = policies.approverDecision(
                            a.tenant(), ApprovalPolicyService.MANUFACTURING_ORDER, str(o, "created_by"));
                    if (!approver.roleMayApprove(a))
                        throw new AccessDeniedException("Your role cannot perform this action");
                    require(state.equals("DRAFT"), "Only a draft can be approved");
                    require(
                            !a.id().equals(o.get("created_by")),
                            "A different authorized person must approve this order");
                    next = "APPROVED";
                }
                case "reserve" -> {
                    a.allow("PLANNER", "WAREHOUSE");
                    require(state.equals("APPROVED"), "Approve the order before reserving materials");
                    for (var l : s.list(
                            "SELECT * FROM order_materials WHERE tenant_id=? AND order_id=? ORDER BY item_id",
                            a.tenant(),
                            id)) {
                        BigDecimal remaining = dec(l.get("required_qty"));
                        for (var lot : s.list(
                                "SELECT l.id,b.quantity-b.reserved AS available FROM lots l JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id WHERE l.tenant_id=? AND l.item_id=? AND l.status='RELEASED' AND b.quantity>b.reserved ORDER BY l.created_at,l.id FOR UPDATE OF b",
                                a.tenant(),
                                l.get("item_id"))) {
                            BigDecimal qty = remaining.min(dec(lot.get("available")));
                            if (qty.signum() <= 0) continue;
                            s.db().update(
                                            "UPDATE stock_balances SET reserved=reserved+? WHERE tenant_id=? AND lot_id=?",
                                            qty,
                                            a.tenant(),
                                            lot.get("id"));
                            s.db().update(
                                            "INSERT INTO reservations(id,tenant_id,order_id,lot_id,item_id,quantity,status) VALUES(?,?,?,?,?,?,'RESERVED')",
                                            id(),
                                            a.tenant(),
                                            id,
                                            lot.get("id"),
                                            l.get("item_id"),
                                            qty);
                            remaining = remaining.subtract(qty);
                        }
                        require(
                                remaining.signum() == 0,
                                "Insufficient released stock. Run material planning to resolve shortages.");
                    }
                    next = "MATERIALS_RESERVED";
                }
                case "issue" -> {
                    a.allow("WAREHOUSE");
                    s.openPeriod(a);
                    require(state.equals("MATERIALS_RESERVED"), "Reserve materials before issue");
                    var machine = s.one(
                            "SELECT status FROM machines WHERE tenant_id=? AND id=?", a.tenant(), o.get("machine_id"));
                    require(!machine.get("status").equals("MAINTENANCE"), "Assigned machine is under maintenance");
                    BigDecimal cost = BigDecimal.ZERO;
                    for (var r : s.list(
                            "SELECT r.*,l.unit_cost,l.status AS lot_status FROM reservations r JOIN lots l ON l.tenant_id=r.tenant_id AND l.id=r.lot_id WHERE r.tenant_id=? AND r.order_id=? AND r.status='RESERVED'",
                            a.tenant(),
                            id)) {
                        require(r.get("lot_status").equals("RELEASED"), "A reserved lot is held by quality control");
                        s.db().update(
                                        "UPDATE stock_balances SET reserved=reserved-? WHERE tenant_id=? AND lot_id=?",
                                        r.get("quantity"),
                                        a.tenant(),
                                        r.get("lot_id"));
                        cost = cost.add(s.ledger(
                                        a,
                                        str(r, "lot_id"),
                                        dec(r.get("quantity")).negate(),
                                        num,
                                        "MATERIAL_ISSUE")
                                .abs());
                    }
                    s.db().update(
                                    "UPDATE reservations SET status='ISSUED' WHERE tenant_id=? AND order_id=? AND status='RESERVED'",
                                    a.tenant(),
                                    id);
                    s.db().update(
                                    "UPDATE order_materials SET issued_qty=required_qty WHERE tenant_id=? AND order_id=?",
                                    a.tenant(),
                                    id);
                    s.db().update(
                                    "UPDATE manufacturing_orders SET material_cost=?,wip_cost=? WHERE tenant_id=? AND id=?",
                                    money(cost),
                                    money(cost),
                                    a.tenant(),
                                    id);
                    s.journal(a, num, "Materials issued to production", "WIP", "RAW_INVENTORY", cost);
                    next = "RELEASED";
                }
                case "start" -> {
                    a.allow("OPERATOR", "PLANNER");
                    require(state.equals("RELEASED"), "Release the order before starting");
                    require(
                            !s.one(
                                            "SELECT status FROM machines WHERE tenant_id=? AND id=?",
                                            a.tenant(),
                                            o.get("machine_id"))
                                    .get("status")
                                    .equals("MAINTENANCE"),
                            "Machine is under maintenance");
                    s.db().update(
                                    "UPDATE manufacturing_orders SET started_at=now() WHERE tenant_id=? AND id=?",
                                    a.tenant(),
                                    id);
                    next = "IN_PROGRESS";
                }
                case "pause" -> {
                    a.allow("OPERATOR", "PLANNER");
                    require(
                            state.equals("IN_PROGRESS") || state.equals("PARTIALLY_COMPLETED"),
                            "Only active work can be paused");
                    next = "ON_HOLD";
                }
                case "resume" -> {
                    a.allow("OPERATOR", "PLANNER");
                    require(state.equals("ON_HOLD"), "Order is not on hold");
                    next = "IN_PROGRESS";
                }
                case "close" -> {
                    a.allow("PLANNER");
                    require(state.equals("COMPLETED"), "All production and quality reviews must be completed first");
                    require(dec(o.get("wip_cost")).signum() == 0, "WIP must reconcile before closing");
                    next = "CLOSED";
                }
                case "cancel" -> {
                    a.allow("PLANNER");
                    require(
                            Set.of("DRAFT", "APPROVED", "MATERIALS_RESERVED").contains(state),
                            "Issued production requires a controlled material-return procedure; cancellation is blocked");
                    for (var r : s.list(
                            "SELECT * FROM reservations WHERE tenant_id=? AND order_id=? AND status='RESERVED'",
                            a.tenant(),
                            id))
                        s.db().update(
                                        "UPDATE stock_balances SET reserved=reserved-? WHERE tenant_id=? AND lot_id=?",
                                        r.get("quantity"),
                                        a.tenant(),
                                        r.get("lot_id"));
                    s.db().update(
                                    "UPDATE reservations SET status='RELEASED' WHERE tenant_id=? AND order_id=? AND status='RESERVED'",
                                    a.tenant(),
                                    id);
                    next = "CANCELLED";
                }
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown manufacturing action");
            }
            s.db().update("UPDATE manufacturing_orders SET status=? WHERE tenant_id=? AND id=?", next, a.tenant(), id);
            s.audit(a, "MO_" + action.toUpperCase(Locale.ROOT), num, state + " → " + next);
            return order(a, id);
        });
    }

    @Transactional
    public Map<String, Object> complete(Actor a, String id, ManufacturingCommands.Complete c, String key) {
        a.allow("OPERATOR");
        return s.once(a, key, "complete:" + id, c, () -> {
            s.openPeriod(a);
            var o = order(a, id);
            require(
                    Set.of("IN_PROGRESS", "PARTIALLY_COMPLETED").contains(o.get("status")),
                    "Start or resume production before recording output");
            var product =
                    s.one("SELECT uom,type FROM items WHERE tenant_id=? AND id=?", a.tenant(), o.get("product_id"));
            String uom = str(product, "uom");
            // Semi-finished output is an input of later production: it is stored with (and valued as) production
            // inputs (RAW warehouse, RAW_INVENTORY), since material issue credits RAW_INVENTORY when it is consumed.
            boolean semi = "SEMI_FINISHED".equals(product.get("type"));
            s.quantity(uom, c.goodQty());
            s.quantity(uom, c.scrapQty());
            BigDecimal remaining =
                    dec(o.get("quantity")).subtract(dec(o.get("good_qty"))).subtract(dec(o.get("scrap_qty")));
            BigDecimal total = c.goodQty().add(c.scrapQty());
            require(
                    total.signum() > 0 && total.compareTo(remaining) <= 0,
                    "Output must be positive and cannot exceed remaining planned quantity");
            require(c.scrapQty().signum() == 0 || !c.notes().isBlank(), "Provide a reason for scrap");
            BigDecimal added = c.labourCost().add(c.machineCost());
            BigDecimal wip = dec(o.get("wip_cost")).add(added);
            BigDecimal allocated = total.compareTo(remaining) == 0
                    ? wip
                    : money(wip.multiply(total).divide(remaining, 12, RoundingMode.HALF_UP));
            BigDecimal goodCost = c.goodQty().signum() == 0
                    ? BigDecimal.ZERO
                    : money(allocated.multiply(c.goodQty()).divide(total, 12, RoundingMode.HALF_UP));
            BigDecimal scrapCost = allocated.subtract(goodCost);
            String num = str(o, "number");
            String lot = null;
            s.journal(a, num, "Actual labour and machine costs", "WIP", "CONVERSION_CLEARING", added);
            if (c.goodQty().signum() > 0) {
                lot = id();
                String wh = str(
                        s.one(
                                "SELECT id FROM warehouses WHERE tenant_id=? AND kind=? ORDER BY id LIMIT 1",
                                a.tenant(),
                                semi ? "RAW" : "FINISHED"),
                        "id");
                s.db().update(
                                "INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost) VALUES(?,?,?,?,?,'QUARANTINE','MANUFACTURING',?,?)",
                                lot,
                                a.tenant(),
                                s.number(a, semi ? "SF" : "FG"),
                                o.get("product_id"),
                                wh,
                                id,
                                goodCost.divide(c.goodQty(), 6, RoundingMode.HALF_UP));
                s.ledger(a, lot, c.goodQty(), num, "PRODUCTION_RECEIPT", goodCost);
                s.db().update(
                                "INSERT INTO inspections(id,tenant_id,lot_id,type) VALUES(?,?,?,'FINAL')",
                                id(),
                                a.tenant(),
                                lot);
                s.journal(
                        a,
                        num,
                        "Accepted output awaiting final quality review",
                        semi ? "RAW_INVENTORY" : "FG_INVENTORY",
                        "WIP",
                        goodCost);
            }
            s.journal(a, num, "Scrap recognized separately from WIP", "SCRAP_EXPENSE", "WIP", scrapCost);
            s.db().update(
                            "INSERT INTO production_actuals(id,tenant_id,order_id,lot_id,good_qty,scrap_qty,labour_cost,machine_cost,notes) VALUES(?,?,?,?,?,?,?,?,?)",
                            id(),
                            a.tenant(),
                            id,
                            lot,
                            c.goodQty(),
                            c.scrapQty(),
                            c.labourCost(),
                            c.machineCost(),
                            c.notes());
            String state = total.compareTo(remaining) == 0
                    ? (c.goodQty().signum() > 0
                                    || !s.list(
                                                    "SELECT 1 FROM inspections i JOIN lots l ON l.tenant_id=i.tenant_id AND l.id=i.lot_id WHERE l.tenant_id=? AND l.source_id=? AND i.status<>'PASSED'",
                                                    a.tenant(),
                                                    id)
                                            .isEmpty()
                            ? "QUALITY_REVIEW"
                            : "COMPLETED")
                    : "PARTIALLY_COMPLETED";
            s.db().update(
                            "UPDATE manufacturing_orders SET good_qty=good_qty+?,scrap_qty=scrap_qty+?,conversion_cost=conversion_cost+?,wip_cost=?,finished_cost=finished_cost+?,scrap_cost=scrap_cost+?,status=? WHERE tenant_id=? AND id=?",
                            c.goodQty(),
                            c.scrapQty(),
                            added,
                            wip.subtract(allocated),
                            goodCost,
                            scrapCost,
                            state,
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "PRODUCTION_POSTED",
                    num,
                    c.goodQty() + " good; " + c.scrapQty() + " scrap; WIP " + wip.subtract(allocated));
            return order(a, id);
        });
    }

    /**
     * The confirmed customer order line an order is planned for, or null for make-to-stock. A bare {@code salesOrderId}
     * (original contract) resolves to that order's only line of the BOM product.
     */
    private Map<String, Object> salesLine(Actor a, ManufacturingCommands.Manufacture c, String product) {
        boolean byLine = c.salesOrderLineId() != null && !c.salesOrderLineId().isBlank();
        boolean byOrder = c.salesOrderId() != null && !c.salesOrderId().isBlank();
        if (!byLine && !byOrder) return null;
        Map<String, Object> line;
        if (byLine) {
            line = s.one(
                    "SELECT l.*,so.status AS order_status FROM sales_order_lines l JOIN sales_orders so ON so.tenant_id=l.tenant_id AND so.id=l.sales_order_id WHERE l.tenant_id=? AND l.id=?",
                    a.tenant(),
                    c.salesOrderLineId());
            require(
                    !byOrder || c.salesOrderId().equals(line.get("sales_order_id")),
                    "The sales order line belongs to a different sales order");
        } else {
            s.one("SELECT id FROM sales_orders WHERE tenant_id=? AND id=?", a.tenant(), c.salesOrderId());
            var lines = s.list(
                    "SELECT l.*,so.status AS order_status FROM sales_order_lines l JOIN sales_orders so ON so.tenant_id=l.tenant_id AND so.id=l.sales_order_id WHERE l.tenant_id=? AND l.sales_order_id=? AND l.product_id=? ORDER BY l.line_no",
                    a.tenant(),
                    c.salesOrderId(),
                    product);
            require(!lines.isEmpty(), "Sales product and BOM product differ");
            require(lines.size() == 1, "Several lines carry this product; choose the sales order line to plan");
            line = lines.getFirst();
        }
        require(line.get("product_id").equals(product), "Sales product and BOM product differ");
        require(
                Set.of("CONFIRMED", "PARTIAL").contains(str(line, "order_status")),
                "Plan production only for confirmed, open sales orders");
        return line;
    }

    public static BigDecimal requirement(Map<String, Object> line, BigDecimal output, BigDecimal outputBase) {
        return dec(line.get("quantity"))
                .multiply(output)
                .divide(outputBase, line.get("uom").equals("pcs") ? 0 : 6, RoundingMode.CEILING);
    }
}
