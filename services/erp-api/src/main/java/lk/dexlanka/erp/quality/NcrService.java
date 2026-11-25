package lk.dexlanka.erp.quality;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;
import static lk.dexlanka.erp.quality.QualityRules.QUALITY_LOSS;
import static lk.dexlanka.erp.quality.QualityRules.SCRAP_REASON;
import static lk.dexlanka.erp.quality.QualityRules.blankToEmpty;
import static lk.dexlanka.erp.quality.QualityRules.blankToNull;
import static lk.dexlanka.erp.quality.QualityRules.inventoryAccount;
import static lk.dexlanka.erp.quality.QualityRules.plain;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.procurement.ProcurementCommands;
import lk.dexlanka.erp.procurement.SupplierReturnService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nonconformance reports ({@code NCR-…}, V12) and their dispositions.
 *
 * <ul>
 *   <li>Raised manually (QUALITY/ADMIN) or automatically by every FAILED inspection ({@link #raiseForFailedInspection}).
 *       A manual NCR on a released lot puts the whole lot on hold (QUARANTINE) as containment.
 *   <li>Dispositions split the affected quantity (their total never exceeds it): USE_AS_IS (concession, approved by a
 *       different QUALITY/ADMIN user), SCRAP (ledger {@code QUALITY_SCRAP}, Dr {@code QUALITY_LOSS} / Cr inventory),
 *       RETURN_TO_SUPPLIER (decided by quality, posted by the warehouse through {@code procurement.SupplierReturnService}
 *       — the same supplier return document and postings as any return) and REWORK ({@link ReworkService}).
 *   <li>OPEN → DISPOSITIONED when posted dispositions cover the affected quantity (the lot hold is then settled and a
 *       manufacturing order waiting on the failed lot can complete) → CLOSED; or OPEN → CANCELLED while nothing was
 *       dispositioned. An NCR with nothing to disposition (no lot or a zero quantity) closes directly.
 * </ul>
 */
@Service
public class NcrService {
    private final Store s;
    private final QualityRules rules;
    private final ReworkService rework;
    private final SupplierReturnService supplierReturns;

    NcrService(Store s, QualityRules rules, ReworkService rework, SupplierReturnService supplierReturns) {
        this.s = s;
        this.rules = rules;
        this.rework = rework;
        this.supplierReturns = supplierReturns;
    }

    // ------------------------------------------------------------------ raise

    @Transactional
    public Map<String, Object> raise(Actor a, NcrCommands.Raise c, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "ncr-raise", c, () -> {
            rules.activeDefect(a, c.defectCode());
            String lotId = blankToNull(c.lotId());
            String itemId = blankToNull(c.itemId());
            String mo = blankToNull(c.manufacturingOrderId());
            String supplier = blankToNull(c.supplierId());
            String customerReturn = null;
            String reworkOrder = null;
            String uom = null;
            boolean hold = false;
            Map<String, Object> lot = null;
            if (lotId != null) {
                lot = rules.lockedLot(a, lotId);
                require(
                        itemId == null || itemId.equals(lot.get("item_id")),
                        "Lot " + lot.get("number") + " holds a different item");
                itemId = str(lot, "item_id");
                uom = str(lot, "uom");
                require(
                        !Boolean.TRUE.equals(lot.get("inspection_pending")),
                        "Lot " + lot.get("number")
                                + " is awaiting its inspection; failing that inspection raises a nonconformance automatically");
                var open = s.list(
                        "SELECT number FROM ncrs WHERE tenant_id=? AND lot_id=? AND status='OPEN'", a.tenant(), lotId);
                require(
                        open.isEmpty(),
                        open.isEmpty()
                                ? ""
                                : "Lot " + lot.get("number") + " already has open nonconformance "
                                        + open.getFirst().get("number"));
                require(c.quantity().signum() > 0, "Enter the affected quantity of lot " + lot.get("number"));
                s.quantity(uom, c.quantity());
                require(
                        c.quantity().compareTo(dec(lot.get("quantity"))) <= 0,
                        "Lot " + lot.get("number") + " holds only " + plain(dec(lot.get("quantity"))) + " " + uom);
                var links = sourceLinks(a, lot);
                if (mo == null) mo = links.get("mo");
                if (supplier == null) supplier = links.get("supplier");
                customerReturn = links.get("customerReturn");
                reworkOrder = links.get("rework");
                hold = lot.get("status").equals("RELEASED");
            } else if (itemId != null)
                uom = str(s.one("SELECT uom FROM items WHERE tenant_id=? AND id=?", a.tenant(), itemId), "uom");
            if (mo != null) s.one("SELECT id FROM manufacturing_orders WHERE tenant_id=? AND id=?", a.tenant(), mo);
            if (supplier != null)
                require(
                        "SUPPLIER"
                                .equals(s.one(
                                                "SELECT type FROM partners WHERE tenant_id=? AND id=?",
                                                a.tenant(),
                                                supplier)
                                        .get("type")),
                        "Choose a supplier, not a customer");
            String id = id();
            String number = rules.documentNumber(a, "ncrs", "NCR");
            s.db().update(
                            "INSERT INTO ncrs(id,tenant_id,number,source,severity,defect_code,title,description,containment,item_id,lot_id,manufacturing_order_id,supplier_id,customer_return_id,rework_order_id,quantity,uom,hold_applied,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            id,
                            a.tenant(),
                            number,
                            c.source(),
                            c.severity(),
                            c.defectCode(),
                            c.title().strip(),
                            c.description().strip(),
                            blankToEmpty(c.containment()),
                            itemId,
                            lotId,
                            mo,
                            supplier,
                            customerReturn,
                            reworkOrder,
                            c.quantity(),
                            uom,
                            hold,
                            a.id());
            if (hold)
                s.db().update("UPDATE lots SET status='QUARANTINE' WHERE tenant_id=? AND id=?", a.tenant(), lotId);
            s.audit(
                    a,
                    "NCR_RAISED",
                    number,
                    c.severity() + " · " + c.defectCode() + " · " + c.title().strip()
                            + (lot == null
                                    ? ""
                                    : " · " + plain(c.quantity()) + " " + uom + " of lot " + lot.get("number")
                                            + (hold ? " (lot put on quality hold)" : "")));
            var out = new LinkedHashMap<String, Object>();
            out.put("id", id);
            out.put("number", number);
            out.put("status", "OPEN");
            out.put("holdApplied", hold);
            return out;
        });
    }

    /** MO, supplier, customer return and rework order behind a lot, from its source. */
    private Map<String, String> sourceLinks(Actor a, Map<String, Object> lot) {
        var out = new LinkedHashMap<String, String>();
        String source = str(lot, "source_type");
        String sourceId = str(lot, "source_id");
        switch (source) {
            case "MANUFACTURING" -> out.put("mo", sourceId);
            case "CUSTOMER_RETURN" -> out.put("customerReturn", sourceId);
            case "REWORK" -> out.put("rework", sourceId);
            case "PURCHASE" -> {
                var r = s.list(
                        "SELECT po.supplier_id FROM receipts r JOIN purchase_orders po ON po.tenant_id=r.tenant_id AND po.id=r.purchase_order_id WHERE r.tenant_id=? AND r.lot_id=? LIMIT 1",
                        a.tenant(),
                        lot.get("id"));
                if (!r.isEmpty() && r.getFirst().get("supplier_id") != null)
                    out.put("supplier", str(r.getFirst(), "supplier_id"));
            }
            default -> {}
        }
        return out;
    }

    /**
     * The NCR of a FAILED inspection, inside {@link QualityService#inspect}. Severity CRITICAL when a critical
     * characteristic was out of specification, MAJOR otherwise; the defect code comes from the failing characteristic
     * (system code {@code INSP} without one). A failed incoming lot was already returned to the supplier by the Phase 1
     * reversal: that return is recorded as an automatic RETURN_TO_SUPPLIER disposition and the NCR is DISPOSITIONED.
     */
    Map<String, Object> raiseForFailedInspection(
            Actor a,
            Map<String, Object> inspection,
            String notes,
            InspectionPlanService.Evaluation evaluation,
            BigDecimal returnedQuantity,
            BigDecimal returnedValue) {
        var lot = rules.lot(a, str(inspection, "lot_id"));
        var links = sourceLinks(a, lot);
        boolean returned = returnedQuantity != null && returnedQuantity.signum() > 0;
        BigDecimal quantity = returned ? returnedQuantity : dec(lot.get("quantity"));
        boolean critical = evaluation != null && evaluation.criticalFailure();
        String defect = evaluation != null && evaluation.defectCode() != null ? evaluation.defectCode() : "INSP";
        String type = str(inspection, "type");
        String description = blankToEmpty(notes);
        if (evaluation != null && !evaluation.outOfSpec().isEmpty())
            description += (description.isEmpty() ? "" : "\n") + "Out of specification: "
                    + String.join(", ", evaluation.outOfSpec())
                    + (critical ? " (critical: " + String.join(", ", evaluation.criticalFailures()) + ")" : "");
        if (description.isEmpty()) description = "Inspection failed";
        String id = id();
        String number = rules.documentNumber(a, "ncrs", "NCR");
        s.db().update(
                        "INSERT INTO ncrs(id,tenant_id,number,source,severity,defect_code,title,description,containment,item_id,lot_id,inspection_id,manufacturing_order_id,supplier_id,customer_return_id,rework_order_id,quantity,uom,status,dispositioned_at,created_by) VALUES(?,?,?,'INSPECTION',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        id,
                        a.tenant(),
                        number,
                        critical ? "CRITICAL" : "MAJOR",
                        defect,
                        "Failed " + type.toLowerCase() + " inspection · " + lot.get("sku") + " lot "
                                + lot.get("number"),
                        description,
                        returned
                                ? "Whole lot returned to the supplier by the failed incoming inspection"
                                : "Lot " + lot.get("number") + " rejected and held by the failed inspection",
                        lot.get("item_id"),
                        lot.get("id"),
                        inspection.get("id"),
                        links.get("mo"),
                        links.get("supplier"),
                        links.get("customerReturn"),
                        links.get("rework"),
                        quantity,
                        lot.get("uom"),
                        returned ? "DISPOSITIONED" : "OPEN",
                        returned ? java.sql.Timestamp.from(java.time.Instant.now()) : null,
                        a.id());
        if (returned) {
            var receipt = s.list(
                    "SELECT r.id,po.number FROM receipts r JOIN purchase_orders po ON po.tenant_id=r.tenant_id AND po.id=r.purchase_order_id WHERE r.tenant_id=? AND r.lot_id=? LIMIT 1",
                    a.tenant(),
                    lot.get("id"));
            s.db().update(
                            "INSERT INTO ncr_dispositions(id,tenant_id,ncr_id,line_no,type,status,lot_id,quantity,value_amount,note,reference,receipt_id,automatic,created_by,posted_at) VALUES(?,?,?,1,'RETURN_TO_SUPPLIER','POSTED',?,?,?,?,?,?,true,?,now())",
                            id(),
                            a.tenant(),
                            id,
                            lot.get("id"),
                            returnedQuantity,
                            returnedValue == null ? BigDecimal.ZERO : returnedValue,
                            "Returned to the supplier by the failed incoming inspection (stock and goods-received clearing reversed)",
                            receipt.isEmpty() ? null : receipt.getFirst().get("number"),
                            receipt.isEmpty() ? null : receipt.getFirst().get("id"),
                            a.id());
        }
        s.audit(
                a,
                "NCR_RAISED",
                number,
                (critical ? "CRITICAL" : "MAJOR") + " · " + defect + " · failed " + type.toLowerCase()
                        + " inspection of lot " + lot.get("number") + " · " + plain(quantity) + " " + lot.get("uom")
                        + (returned ? " returned to the supplier" : " held"));
        var out = new LinkedHashMap<String, Object>();
        out.put("id", id);
        out.put("number", number);
        return out;
    }

    // ------------------------------------------------------------------ edit, close, cancel

    @Transactional
    public Map<String, Object> update(Actor a, String id, NcrCommands.Update c, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "ncr-update:" + id, c, () -> {
            var n = locked(a, id);
            require(n.get("status").equals("OPEN"), "Only an open nonconformance can be edited");
            if (!c.defectCode().equals(n.get("defect_code"))) rules.activeDefect(a, c.defectCode());
            s.db().update(
                            "UPDATE ncrs SET severity=?,defect_code=?,title=?,description=?,containment=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            c.severity(),
                            c.defectCode(),
                            c.title().strip(),
                            c.description().strip(),
                            blankToEmpty(c.containment()),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "NCR_UPDATED",
                    str(n, "number"),
                    c.severity() + " · " + c.defectCode() + " · " + c.title().strip());
            return Map.of("id", id, "status", "OPEN");
        });
    }

    @Transactional
    public Map<String, Object> close(Actor a, String id, NcrCommands.Close c, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "ncr-close:" + id, c, () -> {
            var n = locked(a, id);
            boolean nothingToDisposition =
                    n.get("lot_id") == null || dec(n.get("quantity")).signum() == 0;
            require(
                    n.get("status").equals("DISPOSITIONED") || (n.get("status").equals("OPEN") && nothingToDisposition),
                    n.get("status").equals("OPEN")
                            ? "Disposition the whole affected quantity before closing"
                            : "This nonconformance is already "
                                    + str(n, "status").toLowerCase());
            var openRework = s.list(
                    "SELECT number FROM rework_orders WHERE tenant_id=? AND ncr_id=? AND status='OPEN' ORDER BY number LIMIT 1",
                    a.tenant(),
                    id);
            require(
                    openRework.isEmpty(),
                    openRework.isEmpty()
                            ? ""
                            : "Complete rework order " + openRework.getFirst().get("number") + " first");
            s.db().update(
                            "UPDATE ncrs SET status='CLOSED',closed_by=?,closed_at=now(),close_note=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            a.id(),
                            c.note().strip(),
                            a.tenant(),
                            id);
            if (n.get("status").equals("OPEN")) rules.settleLot(a, (String) n.get("lot_id"));
            s.audit(a, "NCR_CLOSED", str(n, "number"), c.note().strip());
            return Map.of("id", id, "status", "CLOSED");
        });
    }

    @Transactional
    public Map<String, Object> cancel(Actor a, String id, NcrCommands.Cancel c, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "ncr-cancel:" + id, c, () -> {
            var n = locked(a, id);
            require(n.get("status").equals("OPEN"), "Only an open nonconformance can be cancelled");
            require(
                    s.list(
                                    "SELECT 1 FROM ncr_dispositions WHERE tenant_id=? AND ncr_id=? AND status<>'REJECTED'",
                                    a.tenant(),
                                    id)
                            .isEmpty(),
                    "Dispositions were recorded; disposition the rest of the quantity instead of cancelling");
            s.db().update(
                            "UPDATE ncrs SET status='CANCELLED',cancelled_by=?,cancelled_at=now(),cancel_reason=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            a.id(),
                            c.reason().strip(),
                            a.tenant(),
                            id);
            rules.settleLot(a, (String) n.get("lot_id"));
            s.audit(a, "NCR_CANCELLED", str(n, "number"), c.reason().strip());
            return Map.of("id", id, "status", "CANCELLED");
        });
    }

    // ------------------------------------------------------------------ dispositions

    @Transactional
    public Map<String, Object> disposition(Actor a, String ncrId, NcrCommands.Disposition c, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "ncr-disposition:" + ncrId, c, () -> {
            var n = locked(a, ncrId);
            require(n.get("status").equals("OPEN"), "Only an open nonconformance takes dispositions");
            require(
                    n.get("lot_id") != null,
                    "This nonconformance has no lot to disposition; close it with a note instead");
            String number = str(n, "number");
            var lot = rules.lockedLot(a, str(n, "lot_id"));
            String uom = str(lot, "uom");
            s.quantity(uom, c.quantity());
            BigDecimal allocated = dec(s.one(
                            "SELECT COALESCE(SUM(quantity),0) AS q FROM ncr_dispositions WHERE tenant_id=? AND ncr_id=? AND status<>'REJECTED'",
                            a.tenant(),
                            ncrId)
                    .get("q"));
            BigDecimal remaining = dec(n.get("quantity")).subtract(allocated);
            require(
                    c.quantity().compareTo(remaining) <= 0,
                    "Only " + plain(remaining) + " " + uom + " of " + number + " is still without a disposition");
            require(
                    !Boolean.TRUE.equals(lot.get("inspection_pending")),
                    "Lot " + lot.get("number") + " is awaiting an inspection decision");
            int line = ((Number) s.one(
                                    "SELECT COALESCE(MAX(line_no),0)+1 AS n FROM ncr_dispositions WHERE tenant_id=? AND ncr_id=?",
                                    a.tenant(),
                                    ncrId)
                            .get("n"))
                    .intValue();
            String id = id();
            String type = c.type();
            String status = "POSTED";
            BigDecimal value = BigDecimal.ZERO;
            String reference = null;
            String receipt = null;
            if (type.equals("SCRAP") || type.equals("REWORK")) {
                s.openPeriod(a);
                BigDecimal unreserved = dec(lot.get("quantity")).subtract(dec(lot.get("reserved")));
                require(
                        c.quantity().compareTo(unreserved) <= 0,
                        "Only " + plain(unreserved) + " " + uom + " of lot " + lot.get("number")
                                + " is unreserved; release its manufacturing reservations first");
            }
            switch (type) {
                case "USE_AS_IS" -> status = "PENDING_APPROVAL";
                case "SCRAP" -> {
                    value = s.ledger(a, str(lot, "id"), c.quantity().negate(), number, SCRAP_REASON)
                            .abs();
                    s.journal(
                            a,
                            number,
                            "Quality scrap of lot " + lot.get("number"),
                            QUALITY_LOSS,
                            inventoryAccount(lot.get("item_type")),
                            value);
                    reference = number;
                }
                case "RETURN_TO_SUPPLIER" -> {
                    var r = s.list(
                            "SELECT r.*,po.number AS order_number FROM receipts r JOIN purchase_orders po ON po.tenant_id=r.tenant_id AND po.id=r.purchase_order_id WHERE r.tenant_id=? AND r.lot_id=? LIMIT 1",
                            a.tenant(),
                            lot.get("id"));
                    require(
                            lot.get("source_type").equals("PURCHASE") && !r.isEmpty(),
                            "Only purchased lots can be returned to the supplier");
                    BigDecimal returnable = dec(r.getFirst().get("quantity"))
                            .subtract(dec(r.getFirst().get("returned")));
                    require(
                            c.quantity().compareTo(returnable) <= 0,
                            "Return exceeds the received quantity not yet returned (" + plain(returnable) + " " + uom
                                    + ")");
                    status = "AWAITING_RETURN";
                    receipt = str(r.getFirst(), "id");
                    reference = str(r.getFirst(), "order_number");
                }
                default -> {}
            }
            s.db().update(
                            "INSERT INTO ncr_dispositions(id,tenant_id,ncr_id,line_no,type,status,lot_id,quantity,value_amount,note,reference,receipt_id,created_by,posted_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            id,
                            a.tenant(),
                            ncrId,
                            line,
                            type,
                            status,
                            lot.get("id"),
                            c.quantity(),
                            value,
                            c.note().strip(),
                            reference,
                            receipt,
                            a.id(),
                            status.equals("POSTED") ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
            var out = new LinkedHashMap<String, Object>();
            out.put("id", id);
            out.put("ncrId", ncrId);
            out.put("type", type);
            out.put("status", status);
            String detail = type + " · " + plain(c.quantity()) + " " + uom + " of lot " + lot.get("number");
            switch (type) {
                case "USE_AS_IS" ->
                    s.audit(a, "NCR_CONCESSION_REQUESTED", number, detail + " · awaiting independent approval");
                case "SCRAP" -> s.audit(a, "NCR_SCRAP_POSTED", number, detail + " written off at Rs. " + value);
                case "RETURN_TO_SUPPLIER" ->
                    s.audit(a, "NCR_RETURN_REQUESTED", number, detail + " to be returned against " + reference);
                default -> {
                    var rw = rework.open(a, n, id, lot, c.quantity(), c.instructions());
                    value = dec(rw.get("value"));
                    s.db().update(
                                    "UPDATE ncr_dispositions SET value_amount=?,reference=?,rework_order_id=? WHERE tenant_id=? AND id=?",
                                    value,
                                    rw.get("number"),
                                    rw.get("id"),
                                    a.tenant(),
                                    id);
                    out.put("reworkOrderId", rw.get("id"));
                    out.put("reworkNumber", rw.get("number"));
                }
            }
            out.put("value", value);
            out.put("ncrStatus", refresh(a, ncrId));
            return out;
        });
    }

    /** Approves (POSTED) or rejects a pending concession; the approver must not be the person who requested it. */
    @Transactional
    public Map<String, Object> decide(Actor a, String dispositionId, NcrCommands.Decision c, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "ncr-concession:" + dispositionId, c, () -> {
            var d = disposition(a, dispositionId);
            require(d.get("status").equals("PENDING_APPROVAL"), "This disposition is not awaiting approval");
            var n = locked(a, str(d, "ncr_id"));
            require(n.get("status").equals("OPEN"), "The nonconformance is no longer open");
            require(!a.id().equals(d.get("created_by")), "A different authorized person must approve this concession");
            boolean approve = c.decision().equals("APPROVE");
            s.db().update(
                            "UPDATE ncr_dispositions SET status=?,decided_by=?,decided_at=now(),decision_note=?,posted_at=CASE WHEN ? THEN now() END WHERE tenant_id=? AND id=?",
                            approve ? "POSTED" : "REJECTED",
                            a.id(),
                            c.note().strip(),
                            approve,
                            a.tenant(),
                            dispositionId);
            String detail = plain(dec(d.get("quantity"))) + " " + n.get("uom") + " used as is · "
                    + c.note().strip();
            if (approve) s.audit(a, "NCR_CONCESSION_APPROVED", str(n, "number"), detail);
            else s.audit(a, "NCR_CONCESSION_REJECTED", str(n, "number"), detail);
            return Map.of(
                    "id",
                    dispositionId,
                    "status",
                    approve ? "POSTED" : "REJECTED",
                    "ncrStatus",
                    refresh(a, str(n, "id")));
        });
    }

    /**
     * Posts the supplier return of a RETURN_TO_SUPPLIER disposition through {@code SupplierReturnService} (same
     * document, ledger reason {@code SUPPLIER_RETURN} and GRNI / accounts-payable postings as any supplier return). That
     * service returns quality-released stock only: the held lot is released for the duration of the call, inside this
     * transaction and the tenant lock, and its hold restored afterwards (lots transferred from it keep theirs).
     */
    @Transactional
    public Map<String, Object> shipReturn(Actor a, String dispositionId, NcrCommands.ShipReturn c, String key) {
        a.allow("WAREHOUSE");
        return s.once(a, key, "ncr-return:" + dispositionId, c, () -> {
            var d = disposition(a, dispositionId);
            require(d.get("status").equals("AWAITING_RETURN"), "This disposition is not awaiting a supplier return");
            var n = locked(a, str(d, "ncr_id"));
            require(n.get("status").equals("OPEN"), "The nonconformance is no longer open");
            var lot = rules.lockedLot(a, str(d, "lot_id"));
            String held = str(lot, "status");
            List<Object> children = s
                    .list(
                            "SELECT id FROM lots WHERE tenant_id=? AND source_type='TRANSFER' AND source_id=? AND status='QUARANTINE'",
                            a.tenant(),
                            lot.get("id"))
                    .stream()
                    .map(r -> r.get("id"))
                    .toList();
            s.db().update("UPDATE lots SET status='RELEASED' WHERE tenant_id=? AND id=?", a.tenant(), lot.get("id"));
            String reason = blankToNull(c.reason()) == null
                    ? "Nonconformance " + n.get("number") + ": " + n.get("title")
                    : c.reason().strip();
            var result = supplierReturns.returnToSupplier(
                    a,
                    str(d, "receipt_id"),
                    new ProcurementCommands.SupplierReturn(
                            dec(d.get("quantity")),
                            reason.length() > 400 ? reason.substring(0, 400) : reason,
                            c.debitNote()),
                    "ncr-rts-" + dispositionId);
            s.db().update("UPDATE lots SET status=? WHERE tenant_id=? AND id=?", held, a.tenant(), lot.get("id"));
            for (Object child : children)
                s.db().update("UPDATE lots SET status='QUARANTINE' WHERE tenant_id=? AND id=?", a.tenant(), child);
            s.db().update(
                            "UPDATE ncr_dispositions SET status='POSTED',value_amount=?,reference=?,supplier_return_id=?,decided_by=?,decided_at=now(),decision_note=?,posted_at=now() WHERE tenant_id=? AND id=?",
                            dec(result.get("value")),
                            result.get("number"),
                            result.get("id"),
                            a.id(),
                            "Debit note " + c.debitNote().strip(),
                            a.tenant(),
                            dispositionId);
            s.audit(
                    a,
                    "NCR_RETURN_POSTED",
                    str(n, "number"),
                    plain(dec(d.get("quantity"))) + " " + n.get("uom") + " of lot " + lot.get("number")
                            + " returned to the supplier as " + result.get("number") + " · debit note "
                            + c.debitNote().strip());
            var out = new LinkedHashMap<String, Object>();
            out.put("id", dispositionId);
            out.put("status", "POSTED");
            out.put("supplierReturn", result.get("number"));
            out.put("value", result.get("value"));
            out.put("ncrStatus", refresh(a, str(n, "id")));
            return out;
        });
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> locked(Actor a, String id) {
        return s.one("SELECT * FROM ncrs WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
    }

    private Map<String, Object> disposition(Actor a, String id) {
        return s.one("SELECT * FROM ncr_dispositions WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
    }

    /**
     * OPEN → DISPOSITIONED once posted dispositions cover the affected quantity; then the lot hold is settled and a
     * manufacturing order waiting on the failed lot may complete. Returns the NCR status.
     */
    private String refresh(Actor a, String ncrId) {
        var n = s.one("SELECT * FROM ncrs WHERE tenant_id=? AND id=?", a.tenant(), ncrId);
        if (!n.get("status").equals("OPEN")) return str(n, "status");
        BigDecimal posted = dec(s.one(
                        "SELECT COALESCE(SUM(quantity),0) AS q FROM ncr_dispositions WHERE tenant_id=? AND ncr_id=? AND status='POSTED'",
                        a.tenant(),
                        ncrId)
                .get("q"));
        if (posted.compareTo(dec(n.get("quantity"))) < 0) return "OPEN";
        s.db().update(
                        "UPDATE ncrs SET status='DISPOSITIONED',dispositioned_at=now(),updated_at=now() WHERE tenant_id=? AND id=?",
                        a.tenant(),
                        ncrId);
        rules.settleLot(a, (String) n.get("lot_id"));
        rules.completeOrderIfResolved(a, (String) n.get("manufacturing_order_id"));
        s.audit(a, "NCR_DISPOSITIONED", str(n, "number"), "Affected quantity fully dispositioned");
        return "DISPOSITIONED";
    }
}
