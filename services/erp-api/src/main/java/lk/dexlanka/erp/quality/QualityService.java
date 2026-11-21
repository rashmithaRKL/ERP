package lk.dexlanka.erp.quality;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Incoming and final quality decisions. A failed incoming lot is returned to the supplier (reversing stock and GRNI);
 * passing the last final inspection of a fully reported manufacturing order completes that order.
 */
@Service
public class QualityService {
    private final Store s;

    QualityService(Store s) {
        this.s = s;
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
            s.db().update(
                            "UPDATE inspections SET status=?,notes=?,inspected_by=?,inspected_at=now() WHERE tenant_id=? AND id=?",
                            c.result(),
                            c.notes(),
                            a.name(),
                            a.tenant(),
                            id);
            s.db().update(
                            "UPDATE lots SET status=? WHERE tenant_id=? AND id=?",
                            c.result().equals("PASSED") ? "RELEASED" : "REJECTED",
                            a.tenant(),
                            q.get("lot_id"));
            if (c.result().equals("FAILED") && q.get("source_type").equals("PURCHASE")) {
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
                s.db().update(
                                "UPDATE purchase_orders SET received=received-?,status='PARTIAL' WHERE tenant_id=? AND id=?",
                                b.get("quantity"),
                                a.tenant(),
                                q.get("source_id"));
            }
            if (q.get("source_type").equals("MANUFACTURING") && c.result().equals("PASSED")) {
                var o = s.one(
                        "SELECT * FROM manufacturing_orders WHERE tenant_id=? AND id=?",
                        a.tenant(),
                        q.get("source_id"));
                var pending = s.list(
                        "SELECT 1 FROM inspections i JOIN lots l ON l.tenant_id=i.tenant_id AND l.id=i.lot_id WHERE i.tenant_id=? AND l.source_type='MANUFACTURING' AND l.source_id=? AND i.status<>'PASSED'",
                        a.tenant(),
                        q.get("source_id"));
                if (pending.isEmpty()
                        && dec(o.get("good_qty")).add(dec(o.get("scrap_qty"))).compareTo(dec(o.get("quantity"))) == 0)
                    s.db().update(
                                    "UPDATE manufacturing_orders SET status='COMPLETED' WHERE tenant_id=? AND id=?",
                                    a.tenant(),
                                    q.get("source_id"));
            }
            s.audit(a, "QC_" + c.result(), str(q, "lot_id"), c.notes());
            return Map.of("id", id, "status", c.result());
        });
    }
}
