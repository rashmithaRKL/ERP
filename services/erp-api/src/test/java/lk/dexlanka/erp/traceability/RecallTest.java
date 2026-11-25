package lk.dexlanka.erp.traceability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.quality.NcrCommands;
import org.junit.jupiter.api.Test;

/** V14 recalls: affected set, containment through RECALL NCRs, notifications, CSV, close rules and immutability. */
class RecallTest extends TraceTestSupport {
    private static final String TODAY = LocalDate.now(ZoneId.of("Asia/Colombo")).toString();

    /** Resin R (100 kg) → preforms S (1,000) → bottles F1 (400) and F2 (300); F1 shipped to Alpha (100) and Beta (50). */
    private record Chain(String[] receipt, String[] preforms, String[] bottles1, String[] bottles2, String[] saleA, String[] saleB) {
        String resin() {
            return receipt[1];
        }
    }

    private Chain chain() {
        var receipt = purchasedResin("100");
        var preforms = produce(preformBom, "1000");
        var bottles1 = produce(bottleBom, "400");
        var bottles2 = produce(bottleBom, "300");
        var saleA = sell(customerA, bottles1[1], "100", "DN-RA");
        var saleB = sell(customerB, bottles1[1], "50", "DN-RB");
        return new Chain(receipt, preforms, bottles1, bottles2, saleA, saleB);
    }

    private static RecallCommands.Open lots(String scope, String... lotIds) {
        return new RecallCommands.Open(
                "Contaminated resin",
                "Supplier reported metal contamination in the resin batch",
                "CRITICAL",
                scope,
                "LOTS",
                List.of(lotIds),
                null,
                null,
                null,
                null,
                "CONT",
                null);
    }

    private Map<String, Object> open(RecallCommands.Open body) throws Exception {
        return json(command(qa, "/api/recalls", body).andExpect(status().isOk()));
    }

    private Map<String, Object> detail(String id) throws Exception {
        return json(query(viewer, "/api/recalls/" + id).andExpect(status().isOk()));
    }

    private static Map<String, Object> lotRow(Map<String, Object> detail, String lot) {
        return list(detail.get("lots")).stream()
                .filter(l -> lot.equals(l.get("lot_id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Lot " + lot + " not affected: " + detail.get("lots")));
    }

    private Map<String, Object> notifyBody(String date) {
        return Map.of("notifiedOn", date, "contact", "Purchasing manager by phone", "notes", "Collect pallets");
    }

    @Test
    void opensFromARawLotAndContainsThroughRecallNcrs() throws Exception {
        var c = chain();
        // F2 is already on hold by an earlier production NCR: containment skips it.
        ncrs.raise(
                qa,
                new NcrCommands.Raise(
                        "PRODUCTION", "MINOR", "VIS", "Scuffs", "Scuffed", "", null, c.bottles2()[1], bd("10"), null, null),
                id());
        assertThat(lotStatus(c.bottles2()[1])).isEqualTo("QUARANTINE");

        var opened = open(lots("FORWARD", c.resin()));
        String id = str(opened, "id");
        assertThat(str(opened, "number")).startsWith("RCL-");
        assertThat(opened)
                .containsEntry("status", "OPEN")
                .containsEntry("affectedLots", 4)
                .containsEntry("inStockLots", 4)
                .containsEntry("shipments", 2)
                .containsEntry("customers", 2)
                .containsEntry("truncated", false);

        var before = detail(id);
        assertThat(lotRow(before, c.resin())).containsEntry("relation", "SUSPECT").containsEntry("depth", 0);
        assertThat(num(lotRow(before, c.resin()).get("quantity"))).isEqualByComparingTo("80");
        assertThat(lotRow(before, c.preforms()[1])).containsEntry("relation", "DOWNSTREAM");
        assertThat(num(lotRow(before, c.bottles1()[1]).get("quantity"))).isEqualByComparingTo("250");
        assertThat(list(before.get("shipments")))
                .extracting(r -> r.get("customer"))
                .containsExactlyInAnyOrder("Alpha Beverages", "Beta Foods");
        assertThat(list(before.get("customers"))).allSatisfy(r -> assertThat(r).containsEntry("status", "PENDING"));
        assertThat(list(before.get("suspects"))).singleElement().satisfies(r -> assertThat(r).containsEntry("lot_id", c.resin()));

        String key = id();
        var contained = json(command(qa, "/api/recalls/" + id + "/contain", null, key).andExpect(status().isOk()));
        assertThat(contained)
                .containsEntry("status", "CONTAINED")
                .containsEntry("heldLots", 3)
                .containsEntry("skippedLots", 1);
        for (String lot : List.of(c.resin(), c.preforms()[1], c.bottles1()[1]))
            assertThat(lotStatus(lot)).isEqualTo("QUARANTINE");
        var recallNcrs = rows(
                "SELECT n.* FROM ncrs n WHERE n.tenant_id=? AND n.source='RECALL' ORDER BY n.number", t);
        assertThat(recallNcrs).hasSize(3).allSatisfy(n -> {
            assertThat(n).containsEntry("status", "OPEN").containsEntry("severity", "CRITICAL");
            assertThat(n).containsEntry("defect_code", "CONT").containsEntry("hold_applied", true);
        });
        var resinNcr = row("SELECT * FROM ncrs WHERE tenant_id=? AND source='RECALL' AND lot_id=?", t, c.resin());
        assertThat(dec(resinNcr.get("quantity"))).isEqualByComparingTo("80");
        var after = detail(id);
        assertThat(after).containsEntry("status", "CONTAINED").containsEntry("held_lots", 3);
        assertThat(lotRow(after, c.resin())).containsEntry("containment", "HELD").containsEntry("ncr", resinNcr.get("number"));
        assertThat(lotRow(after, c.bottles2()[1])).containsEntry("containment", "ALREADY_HELD");
        assertThat(lotRow(after, c.bottles2()[1]).get("ncr_id")).isNull();
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='RECALL_CONTAINED'", t))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='RECALL_OPENED'", t))
                .isEqualTo(1);

        // Retrying the same containment replays its result; a new attempt is refused.
        var replay = json(command(qa, "/api/recalls/" + id + "/contain", null, key).andExpect(status().isOk()));
        assertThat(replay).containsEntry("heldLots", 3);
        assertThat(count("SELECT COUNT(*) FROM ncrs WHERE tenant_id=? AND source='RECALL'", t)).isEqualTo(3);
        command(qa, "/api/recalls/" + id + "/contain", null).andExpect(status().isConflict());
        command(qa, "/api/recalls/" + id + "/refresh", null).andExpect(status().isConflict());
        command(qa, "/api/recalls/" + id + "/cancel", Map.of("reason", "x")).andExpect(status().isConflict());

        // The contained snapshot is immutable history, enforced by the database.
        assertThatThrownBy(() -> update("UPDATE recall_lots SET quantity=0 WHERE tenant_id=? AND recall_id=?", t, id))
                .hasMessageContaining("immutable history");
        assertThatThrownBy(() -> update("DELETE FROM recall_shipments WHERE tenant_id=? AND recall_id=?", t, id))
                .hasMessageContaining("immutable history");
        assertThatThrownBy(() -> update("UPDATE recalls SET title='Changed' WHERE tenant_id=? AND id=?", t, id))
                .hasMessageContaining("contained");
        assertThatThrownBy(() -> update("DELETE FROM recalls WHERE tenant_id=? AND id=?", t, id))
                .hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> update(
                        "INSERT INTO recall_customers(tenant_id,recall_id,customer_id) VALUES(?,?,?)", t, id, customerA))
                .isInstanceOf(Exception.class);
    }

    @Test
    void tracksNotificationsExportsCsvAndClosesOnlyWithAnIndependentOverride() throws Exception {
        var c = chain();
        String id = str(open(lots("FORWARD", c.resin())), "id");
        command(qa, "/api/recalls/" + id + "/customers/" + customerA + "/notify", notifyBody(TODAY))
                .andExpect(status().isConflict()); // not contained yet
        command(qa, "/api/recalls/" + id + "/contain", null).andExpect(status().isOk());

        // Nothing notified, nothing dispositioned: closing needs an override …
        command(qa, "/api/recalls/" + id + "/close", Map.of("note", "Done")).andExpect(status().isConflict());
        // … and the person who opened the recall cannot approve it.
        command(qa, "/api/recalls/" + id + "/close", Map.of("note", "Done", "overrideReason", "Customer unreachable"))
                .andExpect(status().isConflict());

        var notified = json(command(qa, "/api/recalls/" + id + "/customers/" + customerA + "/notify", notifyBody(TODAY))
                .andExpect(status().isOk()));
        assertThat(notified).containsEntry("status", "NOTIFIED").containsEntry("pendingCustomers", 1);
        command(qa, "/api/recalls/" + id + "/customers/" + customerA + "/notify", notifyBody(TODAY))
                .andExpect(status().isConflict()); // already notified
        command(
                        qa,
                        "/api/recalls/" + id + "/customers/" + customerB + "/notify",
                        notifyBody(LocalDate.now(ZoneId.of("Asia/Colombo")).plusDays(2).toString()))
                .andExpect(status().isConflict()); // future date
        command(qa, "/api/recalls/" + id + "/customers/" + id() + "/notify", notifyBody(TODAY))
                .andExpect(status().isNotFound());
        command(warehouse, "/api/recalls/" + id + "/customers/" + customerB + "/notify", notifyBody(TODAY))
                .andExpect(status().isForbidden());
        var alpha = row(
                "SELECT * FROM recall_customers WHERE tenant_id=? AND recall_id=? AND customer_id=?", t, id, customerA);
        assertThat(alpha).containsEntry("status", "NOTIFIED").containsEntry("contact", "Purchasing manager by phone");
        assertThat(str(alpha, "notified_on")).isEqualTo(TODAY);
        assertThatThrownBy(() -> update(
                        "UPDATE recall_customers SET contact='Someone else' WHERE tenant_id=? AND recall_id=? AND customer_id=?",
                        t,
                        id,
                        customerA))
                .hasMessageContaining("already recorded");

        // The notification list as CSV, one row per delivery; the download is audited.
        var csv = query(viewer, "/api/recalls/" + id + "/notifications/export")
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        assertThat(csv.getContentType()).startsWith("text/csv");
        assertThat(csv.getHeader("Content-Disposition")).contains("-notifications.csv");
        String text = csv.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var lines = text.strip().split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).contains("Recall,Customer,Email,Phone");
        assertThat(text)
                .contains("\"Alpha Beverages\"", "\"buyer@alpha.test\"", "\"NOTIFIED\"", "\"DN-RA\"")
                .contains("\"Beta Foods\"", "\"PENDING\"", "\"DN-RB\"", "\"FG-BOT\"", "\"100\"", "\"50\"");
        assertThat(count(
                        "SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='RECALL_NOTIFICATIONS_EXPORTED'",
                        t))
                .isEqualTo(1);

        // A different quality user may close with a documented override.
        var check = map(detail(id).get("closeCheck"));
        assertThat(check).containsEntry("pendingCustomers", 1).containsEntry("unsettledLots", 4);
        var closed = json(command(
                        qa2, "/api/recalls/" + id + "/close", Map.of("note", "Beta closed", "overrideReason", "Beta Foods ceased trading"))
                .andExpect(status().isOk()));
        assertThat(closed).containsEntry("status", "CLOSED").containsEntry("override", true);
        var recall = row("SELECT * FROM recalls WHERE tenant_id=? AND id=?", t, id);
        assertThat(recall)
                .containsEntry("status", "CLOSED")
                .containsEntry("override_reason", "Beta Foods ceased trading")
                .containsEntry("closed_by", qa2.id());
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='RECALL_CLOSED_OVERRIDE'", t))
                .isEqualTo(1);

        // Closed: nothing changes any more.
        command(qa, "/api/recalls/" + id + "/customers/" + customerB + "/notify", notifyBody(TODAY))
                .andExpect(status().isConflict());
        assertThatThrownBy(() -> update(
                        "UPDATE recall_customers SET status='NOTIFIED',notified_on=CURRENT_DATE,contact='x',notified_by=?,recorded_at=now() WHERE tenant_id=? AND recall_id=? AND customer_id=?",
                        qa.id(),
                        t,
                        id,
                        customerB))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> update("UPDATE recalls SET close_note='x' WHERE tenant_id=? AND id=?", t, id))
                .hasMessageContaining("immutable");
        // The database also refuses an override closed by the person who opened the recall.
        assertThat(recall.get("created_by")).isEqualTo(qa.id());
    }

    @Test
    void closesWithoutOverrideOnceEveryCustomerIsNotifiedAndEveryHeldLotDispositioned() throws Exception {
        var c = chain();
        String id = str(open(lots("FORWARD", c.resin())), "id");
        command(qa, "/api/recalls/" + id + "/contain", null).andExpect(status().isOk());
        for (String customer : List.of(customerA, customerB))
            command(qa, "/api/recalls/" + id + "/customers/" + customer + "/notify", notifyBody(TODAY))
                    .andExpect(status().isOk());
        assertThat(map(detail(id).get("closeCheck"))).containsEntry("pendingCustomers", 0).containsEntry("unsettledLots", 4);
        command(qa, "/api/recalls/" + id + "/close", Map.of("note", "x")).andExpect(status().isConflict());

        for (var ncr : rows("SELECT id,quantity FROM ncrs WHERE tenant_id=? AND source='RECALL'", t))
            ncrs.disposition(
                    qa,
                    str(ncr, "id"),
                    new NcrCommands.Disposition("SCRAP", dec(ncr.get("quantity")), "Recalled stock destroyed", null),
                    id());
        assertThat(map(detail(id).get("closeCheck"))).containsEntry("unsettledLots", 0);
        // The opener may close it now: no override needed (an override reason is ignored).
        var closed = json(command(qa, "/api/recalls/" + id + "/close", Map.of("note", "All stock scrapped", "overrideReason", "n/a"))
                .andExpect(status().isOk()));
        assertThat(closed).containsEntry("override", false);
        assertThat(row("SELECT override_reason,close_note FROM recalls WHERE tenant_id=? AND id=?", t, id))
                .containsEntry("close_note", "All stock scrapped");
        assertThat(row("SELECT override_reason FROM recalls WHERE tenant_id=? AND id=?", t, id).get("override_reason"))
                .isNull();
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='RECALL_CLOSED'", t)).isEqualTo(1);
        assertThat(onHand(c.resin())).isZero();
    }

    @Test
    void scopesSuppliersRefreshAndCancel() throws Exception {
        var c = chain();
        // Forward from a finished lot: the lot itself (its deliveries are terminal).
        var forward = open(lots("FORWARD", c.bottles1()[1]));
        assertThat(forward).containsEntry("affectedLots", 1).containsEntry("customers", 2);

        // With siblings: the resin and preform lots it came from and the other bottles made from them.
        var siblings = open(lots("FORWARD_AND_SIBLINGS", c.bottles1()[1]));
        assertThat(siblings).containsEntry("affectedLots", 4);
        var wide = detail(str(siblings, "id"));
        assertThat(lotRow(wide, c.bottles1()[1])).containsEntry("relation", "SUSPECT");
        assertThat(lotRow(wide, c.resin())).containsEntry("relation", "SOURCE");
        assertThat(lotRow(wide, c.preforms()[1])).containsEntry("relation", "SOURCE");
        assertThat(lotRow(wide, c.bottles2()[1])).containsEntry("relation", "SIBLING");

        // By supplier and receipt dates.
        var bySupplier = open(new RecallCommands.Open(
                "Supplier recall", "Supplier notice", "MAJOR", "FORWARD", "SUPPLIER", null, supplier, resin, TODAY, TODAY, "MAT", 20));
        assertThat(bySupplier).containsEntry("suspectLots", 1).containsEntry("affectedLots", 4);
        var supplierDetail = detail(str(bySupplier, "id"));
        assertThat(supplierDetail).containsEntry("basis", "SUPPLIER").containsEntry("supplier", "Resin Supplier");
        assertThat(supplierDetail).containsEntry("trace_depth", 20);
        command(
                        qa,
                        "/api/recalls",
                        new RecallCommands.Open(
                                "Old", "Old", "MINOR", "FORWARD", "SUPPLIER", null, supplier, null, "2020-01-01", "2020-01-31", "MAT", null))
                .andExpect(status().isConflict());
        command(
                        qa,
                        "/api/recalls",
                        new RecallCommands.Open(
                                "Bad", "Bad", "MINOR", "FORWARD", "SUPPLIER", List.of(c.resin()), supplier, null, TODAY, TODAY, "MAT", null))
                .andExpect(status().isConflict());
        command(qa, "/api/recalls", lots("FORWARD")).andExpect(status().isConflict());
        command(qa, "/api/recalls", new RecallCommands.Open(
                        "x", "x", "MINOR", "FORWARD", "SUPPLIER", null, customerA, null, TODAY, TODAY, "MAT", null))
                .andExpect(status().isConflict()); // a customer is not a supplier

        // Refresh picks up a later shipment to a third customer while the recall is open.
        String recall = str(open(lots("FORWARD", c.resin())), "id");
        String gamma = id();
        update("INSERT INTO partners(id,tenant_id,name,type) VALUES(?,?,'Gamma Stores','CUSTOMER')", gamma, t);
        sell(gamma, c.bottles2()[1], "25", "DN-RG");
        var refreshed = json(command(qa, "/api/recalls/" + recall + "/refresh", null).andExpect(status().isOk()));
        assertThat(refreshed).containsEntry("customers", 3).containsEntry("shipments", 3);

        // Cancel an open recall; it can no longer be contained.
        command(qa, "/api/recalls/" + recall + "/cancel", Map.of("reason", "Opened twice")).andExpect(status().isOk());
        assertThat(row("SELECT status,cancel_reason FROM recalls WHERE tenant_id=? AND id=?", t, recall))
                .containsEntry("status", "CANCELLED")
                .containsEntry("cancel_reason", "Opened twice");
        command(qa, "/api/recalls/" + recall + "/contain", null).andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM ncrs WHERE tenant_id=? AND source='RECALL'", t)).isZero();
    }

    @Test
    void enforcesRolesIdempotencyTenantsAndServesListAndState() throws Exception {
        var c = chain();
        var body = lots("FORWARD", c.resin());
        for (var actor : List.of(viewer, warehouse, salesRep, planner, operator))
            command(actor, "/api/recalls", body).andExpect(status().isForbidden());

        String key = id();
        var first = json(command(qa, "/api/recalls", body, key).andExpect(status().isOk()));
        var again = json(command(qa, "/api/recalls", body, key).andExpect(status().isOk()));
        assertThat(again.get("id")).isEqualTo(first.get("id"));
        assertThat(count("SELECT COUNT(*) FROM recalls WHERE tenant_id=?", t)).isEqualTo(1);
        command(qa, "/api/recalls", lots("FORWARD_AND_SIBLINGS", c.resin()), key).andExpect(status().isConflict());
        String id = str(first, "id");
        command(viewer, "/api/recalls/" + id + "/contain", null).andExpect(status().isForbidden());

        // Another tenant sees nothing and can act on nothing.
        var other = createTenant("Other recall tenant");
        var stranger = createUser(other.id(), "QUALITY");
        query(stranger, "/api/recalls/" + id).andExpect(status().isNotFound());
        query(stranger, "/api/recalls/" + id + "/notifications/export").andExpect(status().isNotFound());
        command(stranger, "/api/recalls/" + id + "/contain", null).andExpect(status().isNotFound());
        command(stranger, "/api/recalls/" + id + "/customers/" + customerA + "/notify", notifyBody(TODAY))
                .andExpect(status().isNotFound());
        command(stranger, "/api/recalls", body).andExpect(status().isNotFound());
        assertThat(list(json(query(stranger, "/api/recalls")).get("items"))).isEmpty();

        // An admin opens a second, minor recall; the list filters and pages.
        var second = json(command(admin, "/api/recalls", new RecallCommands.Open(
                        "Label smudge", "Smudged print", "MINOR", "FORWARD", "LOTS", List.of(c.bottles2()[1]), null, null, null, null, "PACK", null))
                .andExpect(status().isOk()));
        var all = json(query(viewer, "/api/recalls?limit=1").andExpect(status().isOk()));
        assertThat(all).containsEntry("total", 2);
        assertThat(list(all.get("items"))).singleElement().satisfies(r -> assertThat(r).containsEntry("id", second.get("id")));
        var page2 = json(query(viewer, "/api/recalls?limit=1&before=" + all.get("next")));
        assertThat(list(page2.get("items"))).singleElement().satisfies(r -> assertThat(r).containsEntry("id", id));
        assertThat(page2).containsEntry("next", "");
        var critical = json(query(viewer, "/api/recalls?severity=CRITICAL&status=active"));
        assertThat(list(critical.get("items"))).extracting(r -> r.get("id")).containsExactly(id);
        var byLot = json(query(viewer, "/api/recalls?q=" + lotNumber(c.bottles2()[1])));
        assertThat(list(byLot.get("items"))).extracting(r -> r.get("id")).containsExactly(second.get("id"));
        query(viewer, "/api/recalls?status=NOPE").andExpect(status().isConflict());

        // The overview/attention key.
        command(qa, "/api/recalls/" + id + "/contain", null).andExpect(status().isOk());
        var state = map(json(query(viewer, "/api/state?days=7").andExpect(status().isOk())).get("recalls"));
        assertThat(state).containsEntry("open", 1).containsEntry("contained", 1).containsEntry("pending_customers", 2);
        assertThat(list(state.get("active"))).first().satisfies(r -> assertThat(r)
                .containsEntry("id", id)
                .containsEntry("severity", "CRITICAL")
                .containsEntry("pending_customers", 2));
    }
}
