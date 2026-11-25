package lk.dexlanka.erp.quality;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Incoming and final quality decisions. A failed incoming lot is returned to the supplier (reversing stock and GRNI);
 * passing the last final inspection of a fully reported manufacturing order completes that order.
 *
 * <p>V12: when an inspection plan governs the inspection ({@link InspectionPlanService}), the decision carries one
 * measurement per characteristic; a critical characteristic out of specification makes it FAILED, and a non-critical
 * one passes only with a note. Every FAILED inspection raises a nonconformance report ({@link NcrService}).
 * Inspections without a plan behave exactly as in Phase 1.
 */
@Service
public class QualityService {
    private final Store s;
    private final InspectionPlanService plans;
    private final NcrService ncrs;
    private final QualityRules rules;

    QualityService(Store s, InspectionPlanService plans, NcrService ncrs, QualityRules rules) {
        this.s = s;
        this.plans = plans;
        this.ncrs = ncrs;
        this.rules = rules;
    }

    @Transactional
    public Map<String, Object> inspect(Actor a, String id, QualityCommands.Inspect c, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "inspect:" + id, c, () -> {
            var q = s.one(
                    "SELECT i.*,l.source_id,l.source_type,l.item_id FROM inspections i JOIN lots l ON l.tenant_id=i.tenant_id AND l.id=i.lot_id WHERE i.tenant_id=? AND i.id=?",
                    a.tenant(),
                    id);
            require(q.get("status").equals("PENDING"), "Inspection has already been finalized");
            String plan = plans.applicablePlan(a, q.get("item_id"), q.get("type"));
            InspectionPlanService.Evaluation evaluation = null;
            String result = c.result();
            if (plan == null)
                require(
                        c.measurements() == null || c.measurements().isEmpty(),
                        "No inspection plan governs this inspection; record the decision without measurements");
            else {
                evaluation = plans.evaluate(a, plan, c.measurements());
                if (evaluation.criticalFailure()) result = "FAILED";
                else if (result.equals("PASSED"))
                    require(
                            evaluation.unexplained().isEmpty(),
                            "Explain the out-of-specification result of " + String.join(", ", evaluation.unexplained())
                                    + " in its measurement note, or fail the inspection");
            }
            s.db().update(
                            "UPDATE inspections SET status=?,notes=?,inspected_by=?,inspected_at=now() WHERE tenant_id=? AND id=?",
                            result,
                            c.notes(),
                            a.name(),
                            a.tenant(),
                            id);
            s.db().update(
                            "UPDATE lots SET status=? WHERE tenant_id=? AND id=?",
                            result.equals("PASSED") ? "RELEASED" : "REJECTED",
                            a.tenant(),
                            q.get("lot_id"));
            if (evaluation != null) plans.record(a, id, evaluation);
            BigDecimal returnedQuantity = null;
            BigDecimal returnedValue = null;
            if (result.equals("FAILED") && q.get("source_type").equals("PURCHASE")) {
                var b = s.one(
                        "SELECT * FROM stock_balances WHERE tenant_id=? AND lot_id=?", a.tenant(), q.get("lot_id"));
                BigDecimal cost = s.ledger(
                                a,
                                str(q, "lot_id"),
                                dec(b.get("quantity")).negate(),
                                str(q, "source_id"),
                                "SUPPLIER_RETURN_QC")
                        .abs();
                s.journal(
                        a,
                        str(q, "source_id"),
                        "Rejected incoming lot returned to supplier",
                        "GRNI",
                        "RAW_INVENTORY",
                        cost);
                // The receipt's order line (V6) takes the rejected quantity back and the order reopens.
                s.db().update(
                                "UPDATE purchase_order_lines SET received=received-? WHERE tenant_id=? AND id=(SELECT purchase_order_line_id FROM receipts WHERE tenant_id=? AND lot_id=?)",
                                b.get("quantity"),
                                a.tenant(),
                                a.tenant(),
                                q.get("lot_id"));
                s.db().update(
                                "UPDATE purchase_orders po SET status=CASE WHEN EXISTS(SELECT 1 FROM purchase_order_lines l WHERE l.tenant_id=po.tenant_id AND l.purchase_order_id=po.id AND l.received>0) THEN 'PARTIAL' ELSE 'APPROVED' END,updated_at=now() WHERE po.tenant_id=? AND po.id=? AND po.status IN ('APPROVED','PARTIAL','RECEIVED')",
                                a.tenant(),
                                q.get("source_id"));
                returnedQuantity = dec(b.get("quantity"));
                returnedValue = cost;
            }
            if (q.get("source_type").equals("MANUFACTURING") && result.equals("PASSED"))
                rules.completeOrderIfResolved(a, str(q, "source_id"));
            Map<String, Object> ncr = result.equals("FAILED")
                    ? ncrs.raiseForFailedInspection(a, q, c.notes(), evaluation, returnedQuantity, returnedValue)
                    : null;
            String detail = c.notes();
            if (evaluation != null && evaluation.criticalFailure() && c.result().equals("PASSED"))
                detail += " · failed: critical characteristic out of specification ("
                        + String.join(", ", evaluation.criticalFailures()) + ")";
            s.audit(a, "QC_" + result, str(q, "lot_id"), detail);
            var out = new LinkedHashMap<String, Object>();
            out.put("id", id);
            out.put("status", result);
            if (ncr != null) {
                out.put("ncrId", ncr.get("id"));
                out.put("ncrNumber", ncr.get("number"));
            }
            return out;
        });
    }
}
