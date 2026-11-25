package lk.dexlanka.erp.quality;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/**
 * Lookups and rules shared by the V12 quality commands. Deliberately not {@code @Transactional}: every method runs
 * inside the calling command's transaction, after its tenant lock.
 *
 * <p>Lot holds: a lot is held while its status is {@code QUARANTINE} or {@code REJECTED}. A failed inspection rejects
 * the lot (Phase 1 behaviour); raising an NCR on a released lot quarantines it ({@code ncrs.hold_applied}). When the
 * lot's open NCR is dispositioned or cancelled, {@link #settleLot} lifts the hold: a quarantined lot (no pending
 * inspection) is released, and a rejected lot is released only when everything left in it was accepted by approved
 * concessions (use as is).
 */
@Component
class QualityRules {
    /** Ledger reasons written by the quality package (V12), read by genealogy (V14) and OEE (V15). */
    static final String SCRAP_REASON = "QUALITY_SCRAP";

    static final String REWORK_ISSUE = "REWORK_ISSUE";
    static final String REWORK_MATERIAL = "REWORK_MATERIAL";
    static final String REWORK_OUTPUT = "REWORK_OUTPUT";
    /** Journal accounts introduced by V12. */
    static final String QUALITY_LOSS = "QUALITY_LOSS";

    static final String REWORK_WIP = "REWORK_WIP";
    /** Rework labour is credited like production conversion cost. */
    static final String CONVERSION_CLEARING = "CONVERSION_CLEARING";

    private final Store s;

    QualityRules(Store s) {
        this.s = s;
    }

    /** Inventory account a lot of this item type is valued in (same mapping as receipts, issues and output). */
    static String inventoryAccount(Object itemType) {
        return "FINISHED_GOOD".equals(itemType) ? "FG_INVENTORY" : "RAW_INVENTORY";
    }

    static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    static String blankToEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** A document number such as {@code NCR-2026-1A2B3C}, unique in {@code table}. */
    String documentNumber(Actor a, String table, String prefix) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = s.number(a, prefix);
            if (s.list("SELECT 1 FROM " + table + " WHERE tenant_id=? AND number=?", a.tenant(), candidate)
                    .isEmpty()) return candidate;
        }
        throw new IllegalStateException("Could not allocate a unique document number");
    }

    /** A lot number such as {@code RW-2026-1A2B3C}, unique in the tenant. */
    String lotNumber(Actor a, String prefix) {
        return documentNumber(a, "lots", prefix);
    }

    /** A tenant-owned lot with item, warehouse and balance, locked for the rest of the command (404 when not ours). */
    Map<String, Object> lockedLot(Actor a, String lotId) {
        s.one("SELECT id FROM lots WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), lotId);
        return lot(a, lotId);
    }

    Map<String, Object> lot(Actor a, String lotId) {
        return s.one("""
                SELECT l.*,i.name AS item,i.sku,i.type AS item_type,i.uom,
                       COALESCE(b.quantity,0) AS quantity,COALESCE(b.reserved,0) AS reserved,
                       COALESCE(b.value_amount,0) AS value_amount,
                       EXISTS(SELECT 1 FROM inspections q WHERE q.tenant_id=l.tenant_id AND q.lot_id=l.id
                              AND q.status='PENDING') AS inspection_pending
                FROM lots l
                JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                LEFT JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                WHERE l.tenant_id=? AND l.id=?""", a.tenant(), lotId);
    }

    /** A user of the actor's tenant (404 otherwise); inactive users cannot be given new work. */
    Map<String, Object> activeUser(Actor a, String userId) {
        var u = s.one("SELECT id,name,role,active FROM users WHERE tenant_id=? AND id=?", a.tenant(), userId);
        require(Boolean.TRUE.equals(u.get("active")), u.get("name") + " is deactivated; choose an active user");
        return u;
    }

    /** An active defect code of the tenant (404 when unknown, 409 when deactivated). */
    void activeDefect(Actor a, String code) {
        var d = s.one("SELECT active,name FROM defect_codes WHERE tenant_id=? AND code=?", a.tenant(), code);
        require(Boolean.TRUE.equals(d.get("active")), "Defect code " + code + " is inactive");
    }

    /**
     * Lifts the hold on {@code lotId} once no open NCR covers it (see the class comment). Status changes cascade to
     * lots transferred from it (V5 trigger {@code transferred_lots_follow_quality}).
     */
    void settleLot(Actor a, String lotId) {
        if (lotId == null) return;
        var lot = lot(a, lotId);
        if (!s.list("SELECT 1 FROM ncrs WHERE tenant_id=? AND lot_id=? AND status='OPEN'", a.tenant(), lotId)
                .isEmpty()) return;
        if (Boolean.TRUE.equals(lot.get("inspection_pending"))) return;
        String status = str(lot, "status");
        boolean release = false;
        if (status.equals("QUARANTINE")) release = true;
        else if (status.equals("REJECTED")) {
            BigDecimal remaining = dec(lot.get("quantity"));
            BigDecimal conceded = dec(s.one("""
                            SELECT COALESCE(SUM(d.quantity),0) AS q FROM ncr_dispositions d
                            JOIN ncrs n ON n.tenant_id=d.tenant_id AND n.id=d.ncr_id
                            WHERE d.tenant_id=? AND n.lot_id=? AND d.type='USE_AS_IS' AND d.status='POSTED'
                              AND n.status IN ('DISPOSITIONED','CLOSED')""", a.tenant(), lotId).get("q"));
            release = remaining.signum() > 0 && remaining.compareTo(conceded) <= 0;
        }
        if (release) s.db().update("UPDATE lots SET status='RELEASED' WHERE tenant_id=? AND id=?", a.tenant(), lotId);
    }

    /**
     * Completes a fully reported manufacturing order once every final inspection of its output is resolved: passed, or
     * failed with its nonconformance dispositioned (or closed). Cross-domain SQL inside the caller's transaction,
     * exactly like the Phase 1 completion after the last final release (which this generalizes: without NCR
     * dispositions a failed final inspection still keeps the order in quality review).
     */
    void completeOrderIfResolved(Actor a, String orderId) {
        if (orderId == null) return;
        var rows = s.list("SELECT * FROM manufacturing_orders WHERE tenant_id=? AND id=?", a.tenant(), orderId);
        if (rows.isEmpty()) return;
        var o = rows.getFirst();
        if (java.util.Set.of("COMPLETED", "CLOSED", "CANCELLED").contains(str(o, "status"))) return;
        var unresolved = s.list("""
                SELECT 1 FROM inspections i JOIN lots l ON l.tenant_id=i.tenant_id AND l.id=i.lot_id
                WHERE i.tenant_id=? AND l.source_type='MANUFACTURING' AND l.source_id=?
                  AND NOT (i.status='PASSED'
                           OR (i.status='FAILED' AND EXISTS(
                                 SELECT 1 FROM ncrs n WHERE n.tenant_id=i.tenant_id AND n.inspection_id=i.id
                                   AND n.source='INSPECTION' AND n.status IN ('DISPOSITIONED','CLOSED'))))""", a.tenant(), orderId);
        if (unresolved.isEmpty()
                && dec(o.get("good_qty")).add(dec(o.get("scrap_qty"))).compareTo(dec(o.get("quantity"))) == 0)
            s.db().update(
                            "UPDATE manufacturing_orders SET status='COMPLETED' WHERE tenant_id=? AND id=?",
                            a.tenant(),
                            orderId);
    }
}
