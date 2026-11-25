package lk.dexlanka.erp.traceability;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.quality.NcrCommands;
import lk.dexlanka.erp.quality.NcrService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Recalls ({@code RCL-…}, V14): OPEN → CONTAINED → CLOSED, or OPEN → CANCELLED.
 *
 * <ul>
 *   <li><b>Open</b> (QUALITY/ADMIN) from suspect lots or from a supplier and receipt-date range; the affected set is
 *       computed through {@link TraceService} and stored as a snapshot (affected lots with their stock, shipments,
 *       customers). <b>Refresh</b> recomputes it while the recall is open.
 *   <li><b>Contain</b> recomputes the snapshot one last time and puts every affected lot that is in stock and released
 *       on hold by raising a {@code source='RECALL'} nonconformance report through the public {@code
 *       quality.NcrService#raise} (a deliberate cross-package call that joins this transaction; the NCR quarantines the
 *       lot — this package never sets a lot status). Lots already quarantined/rejected, under an open NCR or awaiting
 *       an inspection are skipped and listed. From then on the snapshot is immutable (database triggers).
 *   <li><b>Notify</b> records, per affected customer, the day, contact and notes (PENDING → NOTIFIED, once).
 *   <li><b>Close</b> needs every customer notified and every lot the recall held dispositioned through V12 (its NCR
 *       DISPOSITIONED or CLOSED) — or an override reason from a QUALITY/ADMIN user other than the one who opened the
 *       recall (also a database {@code CHECK}).
 * </ul>
 */
@Service
public class RecallService {
    static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

    private final Store s;
    private final TraceService trace;
    private final NcrService ncrs;

    RecallService(Store s, TraceService trace, NcrService ncrs) {
        this.s = s;
        this.trace = trace;
        this.ncrs = ncrs;
    }

    // ------------------------------------------------------------------ open

    @Transactional
    public Map<String, Object> open(Actor a, RecallCommands.Open c, String key) {
        a.allow("QUALITY");
        LocalDate from = date(c.receivedFrom());
        LocalDate to = date(c.receivedTo());
        int depth = c.depth() == null ? TraceService.MAX_DEPTH : c.depth();
        return s.once(a, key, "recall-open", c, () -> {
            var defect =
                    s.one("SELECT active FROM defect_codes WHERE tenant_id=? AND code=?", a.tenant(), c.defectCode());
            require(Boolean.TRUE.equals(defect.get("active")), "Defect code " + c.defectCode() + " is inactive");
            var suspects = new LinkedHashSet<String>();
            String supplier = null;
            String item = null;
            if (c.basis().equals("LOTS")) {
                require(
                        c.lotIds() != null && !c.lotIds().isEmpty(),
                        "Choose at least one suspect lot, or recall by supplier");
                require(
                        blank(c.supplierId()) && blank(c.itemId()) && from == null && to == null,
                        "A recall of chosen lots takes no supplier, item or receipt dates");
                for (String lot : c.lotIds())
                    suspects.add(str(
                            s.one("SELECT id FROM lots WHERE tenant_id=? AND id=?", a.tenant(), lot.strip()), "id"));
            } else {
                require(c.lotIds() == null || c.lotIds().isEmpty(), "A supplier recall takes no chosen lots");
                require(!blank(c.supplierId()), "Choose the supplier");
                require(from != null && to != null, "Enter the first and last receipt date");
                require(!from.isAfter(to), "The first receipt date must not be after the last one");
                var partner = s.one(
                        "SELECT name,type FROM partners WHERE tenant_id=? AND id=?", a.tenant(), c.supplierId());
                require("SUPPLIER".equals(partner.get("type")), "Choose a supplier, not a customer");
                supplier = c.supplierId();
                if (!blank(c.itemId())) {
                    s.one("SELECT id FROM items WHERE tenant_id=? AND id=?", a.tenant(), c.itemId());
                    item = c.itemId();
                }
                suspects.addAll(trace.supplierLots(a.tenant(), supplier, item, from, to));
                require(
                        !suspects.isEmpty(),
                        "No lots were received from " + partner.get("name") + " between " + from + " and " + to);
            }
            String id = id();
            String number = number(a);
            s.db().update(
                            "INSERT INTO recalls(id,tenant_id,number,title,reason,severity,scope,basis,defect_code,supplier_id,item_id,received_from,received_to,trace_depth,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            id,
                            a.tenant(),
                            number,
                            c.title().strip(),
                            c.reason().strip(),
                            c.severity(),
                            c.scope(),
                            c.basis(),
                            c.defectCode(),
                            supplier,
                            item,
                            from,
                            to,
                            depth,
                            a.id());
            insertSuspects(a, id, suspects);
            var snapshot = compute(a, id, List.copyOf(suspects), c.scope(), depth);
            s.audit(
                    a,
                    "RECALL_OPENED",
                    number,
                    c.severity() + " · " + c.title().strip() + " · " + suspects.size() + " suspect lot(s), "
                            + snapshot.get("affectedLots") + " affected, " + snapshot.get("inStockLots")
                            + " in stock, " + snapshot.get("customers") + " customer(s)");
            var out = new LinkedHashMap<String, Object>();
            out.put("id", id);
            out.put("number", number);
            out.put("status", "OPEN");
            out.putAll(snapshot);
            return out;
        });
    }

    /** Recomputes the affected set of an open recall (stock moved, more deliveries or receipts since it was opened). */
    @Transactional
    public Map<String, Object> refresh(Actor a, String id, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "recall-refresh:" + id, Map.of(), () -> {
            var r = locked(a, id);
            require(r.get("status").equals("OPEN"), "Recall " + r.get("number") + " is no longer open");
            var snapshot = recompute(a, r);
            s.audit(
                    a,
                    "RECALL_REFRESHED",
                    str(r, "number"),
                    snapshot.get("affectedLots") + " affected lot(s), " + snapshot.get("inStockLots") + " in stock, "
                            + snapshot.get("customers") + " customer(s)");
            var out = new LinkedHashMap<String, Object>();
            out.put("id", id);
            out.put("status", "OPEN");
            out.putAll(snapshot);
            return out;
        });
    }

    // ------------------------------------------------------------------ contain

    @Transactional
    public Map<String, Object> contain(Actor a, String id, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "recall-contain:" + id, Map.of(), () -> {
            var r = locked(a, id);
            require(r.get("status").equals("OPEN"), "Recall " + r.get("number") + " is " + lower(r) + "; it was already contained or ended");
            var snapshot = recompute(a, r);
            String number = str(r, "number");
            int held = 0;
            var skipped = new ArrayList<String>();
            for (var lot : s.list(
                    """
                    SELECT x.id,x.lot_id,l.number,l.status,i.uom,COALESCE(b.quantity,0) AS quantity,
                           EXISTS(SELECT 1 FROM inspections q WHERE q.tenant_id=l.tenant_id AND q.lot_id=l.id
                                  AND q.status='PENDING') AS inspection_pending,
                           (SELECT n.id FROM ncrs n WHERE n.tenant_id=l.tenant_id AND n.lot_id=l.id AND n.status='OPEN'
                            LIMIT 1) AS open_ncr
                    FROM recall_lots x
                    JOIN lots l ON l.tenant_id=x.tenant_id AND l.id=x.lot_id
                    JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                    LEFT JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                    WHERE x.tenant_id=? AND x.recall_id=? AND COALESCE(b.quantity,0) > 0
                    ORDER BY x.depth,l.number""",
                    a.tenant(),
                    id)) {
                String containment;
                String ncr = null;
                if (!lot.get("status").equals("RELEASED")) containment = "ALREADY_HELD";
                else if (Boolean.TRUE.equals(lot.get("inspection_pending"))) containment = "AWAITING_INSPECTION";
                else if (lot.get("open_ncr") != null) {
                    containment = "UNDER_NCR";
                    ncr = str(lot, "open_ncr");
                } else {
                    // V14 → V12: the RECALL NCR holds the lot (quarantine); same transaction, nested idempotency key.
                    var raised = ncrs.raise(
                            a,
                            new NcrCommands.Raise(
                                    "RECALL",
                                    str(r, "severity"),
                                    str(r, "defect_code"),
                                    truncate("Recall " + number + " · " + r.get("title"), 160),
                                    truncate(str(r, "reason"), 4000),
                                    "Held by recall " + number + " (containment)",
                                    null,
                                    str(lot, "lot_id"),
                                    dec(lot.get("quantity")),
                                    null,
                                    null),
                            ncrKey(id, str(lot, "lot_id")));
                    containment = "HELD";
                    ncr = str(raised, "id");
                    held++;
                }
                if (!containment.equals("HELD")) skipped.add(lot.get("number") + " (" + containment.toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ")");
                s.db().update(
                                "UPDATE recall_lots SET containment=?,ncr_id=? WHERE tenant_id=? AND id=?",
                                containment,
                                ncr,
                                a.tenant(),
                                lot.get("id"));
            }
            s.db().update(
                            "UPDATE recalls SET status='CONTAINED',held_lots=?,contained_by=?,contained_at=now(),updated_at=now() WHERE tenant_id=? AND id=?",
                            held,
                            a.id(),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "RECALL_CONTAINED",
                    number,
                    held + " lot(s) put on hold" + (skipped.isEmpty() ? "" : "; skipped " + String.join(", ", skipped))
                            + "; " + snapshot.get("customers") + " customer(s) to notify");
            var out = new LinkedHashMap<String, Object>();
            out.put("id", id);
            out.put("status", "CONTAINED");
            out.put("heldLots", held);
            out.put("skippedLots", skipped.size());
            out.putAll(snapshot);
            return out;
        });
    }

    /** The idempotency key of the RECALL NCR of one lot: unique per recall and lot, never reused. */
    static String ncrKey(String recall, String lot) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((recall + ":" + lot).getBytes(StandardCharsets.UTF_8));
            return "recall-ncr-" + HexFormat.of().formatHex(digest).substring(0, 40);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ notify, close, cancel

    @Transactional
    public Map<String, Object> notify(Actor a, String id, String customerId, RecallCommands.Notify c, String key) {
        a.allow("QUALITY");
        LocalDate on = date(c.notifiedOn());
        require(!on.isAfter(LocalDate.now(ZONE)), "The notification date cannot be in the future");
        return s.once(a, key, "recall-notify:" + id + ":" + customerId, c, () -> {
            var r = locked(a, id);
            String number = str(r, "number");
            require(
                    !r.get("status").equals("OPEN"),
                    "Contain recall " + number + " before recording customer notifications");
            require(r.get("status").equals("CONTAINED"), "Recall " + number + " is " + lower(r));
            var customer = s.one(
                    """
                    SELECT c.status,p.name FROM recall_customers c
                    JOIN partners p ON p.tenant_id=c.tenant_id AND p.id=c.customer_id
                    WHERE c.tenant_id=? AND c.recall_id=? AND c.customer_id=?""",
                    a.tenant(),
                    id,
                    customerId);
            require(
                    customer.get("status").equals("PENDING"),
                    customer.get("name") + " was already notified about recall " + number);
            s.db().update(
                            "UPDATE recall_customers SET status='NOTIFIED',notified_on=?,contact=?,notes=?,notified_by=?,recorded_at=now() WHERE tenant_id=? AND recall_id=? AND customer_id=?",
                            on,
                            c.contact().strip(),
                            c.notes() == null ? "" : c.notes().strip(),
                            a.id(),
                            a.tenant(),
                            id,
                            customerId);
            s.db().update("UPDATE recalls SET updated_at=now() WHERE tenant_id=? AND id=?", a.tenant(), id);
            s.audit(
                    a,
                    "RECALL_CUSTOMER_NOTIFIED",
                    number,
                    customer.get("name") + " notified on " + on + " · " + c.contact().strip());
            long pending = s.db()
                    .queryForObject(
                            "SELECT COUNT(*) FROM recall_customers WHERE tenant_id=? AND recall_id=? AND status='PENDING'",
                            Long.class,
                            a.tenant(),
                            id);
            var out = new LinkedHashMap<String, Object>();
            out.put("id", id);
            out.put("customerId", customerId);
            out.put("status", "NOTIFIED");
            out.put("pendingCustomers", pending);
            return out;
        });
    }

    @Transactional
    public Map<String, Object> close(Actor a, String id, RecallCommands.Close c, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "recall-close:" + id, c, () -> {
            var r = locked(a, id);
            String number = str(r, "number");
            require(
                    !r.get("status").equals("OPEN"),
                    "Contain recall " + number + " before closing it, or cancel it if it was opened by mistake");
            require(r.get("status").equals("CONTAINED"), "Recall " + number + " is " + lower(r));
            var open = outstanding(a.tenant(), id);
            long pending = (Long) open.get("pendingCustomers");
            long unsettled = (Long) open.get("unsettledLots");
            String override = blank(c.overrideReason()) ? null : c.overrideReason().strip();
            boolean outstanding = pending > 0 || unsettled > 0;
            if (outstanding) {
                require(
                        override != null,
                        "Recall " + number + " still has " + pending + " customer(s) to notify and " + unsettled
                                + " held lot(s) without a disposition; record an override reason to close it anyway");
                require(
                        !a.id().equals(r.get("created_by")),
                        "A different quality user than the one who opened recall " + number
                                + " must approve closing it with an override");
            } else override = null;
            s.db().update(
                            "UPDATE recalls SET status='CLOSED',closed_by=?,closed_at=now(),close_note=?,override_reason=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            a.id(),
                            c.note() == null ? "" : c.note().strip(),
                            override,
                            a.tenant(),
                            id);
            if (override == null)
                s.audit(a, "RECALL_CLOSED", number, "All customers notified and every held lot dispositioned");
            else
                s.audit(
                        a,
                        "RECALL_CLOSED_OVERRIDE",
                        number,
                        "Closed with " + pending + " customer(s) not notified and " + unsettled
                                + " held lot(s) without a disposition · " + override);
            var out = new LinkedHashMap<String, Object>();
            out.put("id", id);
            out.put("status", "CLOSED");
            out.put("override", override != null);
            return out;
        });
    }

    @Transactional
    public Map<String, Object> cancel(Actor a, String id, RecallCommands.Cancel c, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "recall-cancel:" + id, c, () -> {
            var r = locked(a, id);
            require(
                    r.get("status").equals("OPEN"),
                    "Only an open recall can be cancelled; recall " + r.get("number") + " is " + lower(r));
            s.db().update(
                            "UPDATE recalls SET status='CANCELLED',cancelled_by=?,cancelled_at=now(),cancel_reason=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            a.id(),
                            c.reason().strip(),
                            a.tenant(),
                            id);
            s.audit(a, "RECALL_CANCELLED", str(r, "number"), c.reason().strip());
            return Map.of("id", id, "status", "CANCELLED");
        });
    }

    /** Downloads of the notification list (customer contact data) are audited, like audit-trail exports. */
    @Transactional
    public void recordExport(Actor a, String id) {
        var r = s.one("SELECT number,customers FROM recalls WHERE tenant_id=? AND id=?", a.tenant(), id);
        s.audit(
                a,
                "RECALL_NOTIFICATIONS_EXPORTED",
                str(r, "number"),
                "Notification list of " + r.get("customers") + " customer(s) exported");
    }

    /** Customers still PENDING and lots this recall held whose RECALL NCR is not yet DISPOSITIONED or CLOSED. */
    Map<String, Object> outstanding(String tenant, String id) {
        return s.one(
                """
                SELECT (SELECT COUNT(*) FROM recall_customers c WHERE c.tenant_id=r.tenant_id AND c.recall_id=r.id
                          AND c.status='PENDING') AS "pendingCustomers",
                       (SELECT COUNT(*) FROM recall_lots x JOIN ncrs n ON n.tenant_id=x.tenant_id AND n.id=x.ncr_id
                        WHERE x.tenant_id=r.tenant_id AND x.recall_id=r.id AND x.containment='HELD'
                          AND n.status NOT IN ('DISPOSITIONED','CLOSED')) AS "unsettledLots"
                FROM recalls r WHERE r.tenant_id=? AND r.id=?""",
                tenant,
                id);
    }

    // ------------------------------------------------------------------ snapshot

    private Map<String, Object> recompute(Actor a, Map<String, Object> r) {
        String id = str(r, "id");
        var suspects = new LinkedHashSet<String>();
        if (r.get("basis").equals("SUPPLIER")) {
            suspects.addAll(trace.supplierLots(
                    a.tenant(),
                    str(r, "supplier_id"),
                    (String) r.get("item_id"),
                    LocalDate.parse(r.get("received_from").toString()),
                    LocalDate.parse(r.get("received_to").toString())));
            s.db().update("DELETE FROM recall_suspects WHERE tenant_id=? AND recall_id=?", a.tenant(), id);
            insertSuspects(a, id, suspects);
        } else
            for (var row : s.list(
                    "SELECT lot_id FROM recall_suspects WHERE tenant_id=? AND recall_id=? ORDER BY lot_id",
                    a.tenant(),
                    id)) suspects.add(str(row, "lot_id"));
        s.db().update("DELETE FROM recall_lots WHERE tenant_id=? AND recall_id=?", a.tenant(), id);
        s.db().update("DELETE FROM recall_shipments WHERE tenant_id=? AND recall_id=?", a.tenant(), id);
        s.db().update("DELETE FROM recall_customers WHERE tenant_id=? AND recall_id=?", a.tenant(), id);
        return compute(a, id, List.copyOf(suspects), str(r, "scope"), ((Number) r.get("trace_depth")).intValue());
    }

    private void insertSuspects(Actor a, String recall, Iterable<String> lots) {
        for (String lot : lots)
            s.db().update(
                            "INSERT INTO recall_suspects(tenant_id,recall_id,lot_id) VALUES(?,?,?)",
                            a.tenant(),
                            recall,
                            lot);
    }

    /**
     * Writes the affected-lot, shipment and customer snapshot of a recall and its counts. Relations: SUSPECT (a suspect
     * lot), DOWNSTREAM (made from a suspect), SOURCE (a suspect was made from it; sibling scope), SIBLING (made from
     * the same origin lots; sibling scope).
     */
    private Map<String, Object> compute(Actor a, String id, List<String> suspects, String scope, int depth) {
        String t = a.tenant();
        var forward = trace.forward(t, suspects, depth);
        var relation = new LinkedHashMap<String, String>();
        var depths = new LinkedHashMap<String, Integer>();
        boolean truncated = forward.truncated();
        for (var e : forward.lots().entrySet()) {
            relation.put(e.getKey(), suspects.contains(e.getKey()) ? "SUSPECT" : "DOWNSTREAM");
            depths.put(e.getKey(), e.getValue());
        }
        if (scope.equals("FORWARD_AND_SIBLINGS")) {
            var backward = trace.backward(t, suspects, depth);
            var origins = trace.origins(t, backward.ids());
            var wide = trace.forward(t, origins, Math.min(depth * 2, 2 * TraceService.MAX_DEPTH));
            truncated |= backward.truncated() || wide.truncated();
            for (var e : backward.lots().entrySet())
                if (!relation.containsKey(e.getKey())) {
                    relation.put(e.getKey(), "SOURCE");
                    depths.put(e.getKey(), e.getValue());
                }
            for (var e : wide.lots().entrySet())
                if (!relation.containsKey(e.getKey())) {
                    relation.put(e.getKey(), "SIBLING");
                    depths.put(e.getKey(), e.getValue());
                }
        }
        int inStock = 0;
        if (!relation.isEmpty()) {
            var rows = s.list(
                    """
                    SELECT l.id,l.item_id,l.warehouse_id,l.status,i.uom,COALESCE(b.quantity,0) AS quantity,
                           COALESCE(b.reserved,0) AS reserved,COALESCE(b.value_amount,0) AS value_amount
                    FROM lots l JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                    LEFT JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                    WHERE l.tenant_id=? AND l.id IN (SELECT jsonb_array_elements_text(?::jsonb))""",
                    t,
                    s.encode(relation.keySet()));
            var batch = new ArrayList<Object[]>();
            for (var l : rows) {
                String lot = str(l, "id");
                BigDecimal qty = dec(l.get("quantity"));
                if (qty.signum() > 0) inStock++;
                batch.add(new Object[] {
                    id(),
                    t,
                    id,
                    lot,
                    l.get("item_id"),
                    l.get("warehouse_id"),
                    relation.get(lot),
                    depths.get(lot),
                    qty,
                    l.get("reserved"),
                    l.get("value_amount"),
                    l.get("uom"),
                    l.get("status")
                });
            }
            s.db().batchUpdate(
                            "INSERT INTO recall_lots(id,tenant_id,recall_id,lot_id,item_id,warehouse_id,relation,depth,quantity,reserved,value_amount,uom,lot_status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            batch);
        }
        var shipments = relation.isEmpty()
                ? List.<Map<String, Object>>of()
                : s.list(
                        """
                        SELECT d.id,d.sales_order_id,so.customer_id,d.lot_id,l.item_id,d.reference,d.quantity,d.returned,
                               d.created_at
                        FROM deliveries d
                        JOIN sales_orders so ON so.tenant_id=d.tenant_id AND so.id=d.sales_order_id
                        JOIN lots l ON l.tenant_id=d.tenant_id AND l.id=d.lot_id
                        WHERE d.tenant_id=? AND d.lot_id IN (SELECT jsonb_array_elements_text(?::jsonb))
                        ORDER BY d.created_at,d.id""",
                        t,
                        s.encode(relation.keySet()));
        var customers = new LinkedHashSet<String>();
        var batch = new ArrayList<Object[]>();
        for (var d : shipments) {
            customers.add(str(d, "customer_id"));
            batch.add(new Object[] {
                id(),
                t,
                id,
                d.get("id"),
                d.get("sales_order_id"),
                d.get("customer_id"),
                d.get("lot_id"),
                d.get("item_id"),
                d.get("reference"),
                d.get("quantity"),
                d.get("returned"),
                d.get("created_at")
            });
        }
        if (!batch.isEmpty())
            s.db().batchUpdate(
                            "INSERT INTO recall_shipments(id,tenant_id,recall_id,delivery_id,sales_order_id,customer_id,lot_id,item_id,reference,quantity,returned,delivered_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                            batch);
        for (String customer : customers)
            s.db().update(
                            "INSERT INTO recall_customers(tenant_id,recall_id,customer_id) VALUES(?,?,?)",
                            t,
                            id,
                            customer);
        s.db().update(
                        "UPDATE recalls SET affected_lots=?,in_stock_lots=?,shipments=?,customers=?,truncated=?,computed_at=now(),updated_at=now() WHERE tenant_id=? AND id=?",
                        relation.size(),
                        inStock,
                        shipments.size(),
                        customers.size(),
                        truncated,
                        t,
                        id);
        var out = new LinkedHashMap<String, Object>();
        out.put("suspectLots", suspects.size());
        out.put("affectedLots", relation.size());
        out.put("inStockLots", inStock);
        out.put("shipments", shipments.size());
        out.put("customers", customers.size());
        out.put("truncated", truncated);
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> locked(Actor a, String id) {
        return s.one("SELECT * FROM recalls WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
    }

    private String number(Actor a) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = s.number(a, "RCL");
            if (s.list("SELECT 1 FROM recalls WHERE tenant_id=? AND number=?", a.tenant(), candidate)
                    .isEmpty()) return candidate;
        }
        throw new IllegalStateException("Could not allocate a unique recall number");
    }

    private static String lower(Map<String, Object> r) {
        return str(r, "status").toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.strip());
        } catch (java.time.DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use valid yyyy-MM-dd dates");
        }
    }
}
