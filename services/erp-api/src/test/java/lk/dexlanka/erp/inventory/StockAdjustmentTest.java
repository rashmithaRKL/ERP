package lk.dexlanka.erp.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

class StockAdjustmentTest extends InventoryTestSupport {

    @Test
    void decreaseAndIncreasePostLedgerAndBalancedJournal() throws Exception {
        var created =
                json(command(warehouse, "/api/inventory/adjustments", adjustment("DAMAGE", decrease(resinLot, "30")))
                        .andExpect(status().isOk()));
        String id = str(created, "id");
        assertThat(created.get("status")).isEqualTo("DRAFT");
        assertThat(quantity(resinLot)).isEqualByComparingTo("100");

        var posted = json(command(warehouse, "/api/inventory/adjustments/" + id + "/post", null)
                .andExpect(status().isOk()));
        assertThat(posted.get("status")).isEqualTo("POSTED");
        String number = number("stock_adjustments", id);
        assertThat(quantity(resinLot)).isEqualByComparingTo("70");
        assertThat(value(resinLot)).isEqualByComparingTo("700");
        var ledger = row("SELECT * FROM stock_ledger WHERE tenant_id=? AND reference=?", t, number);
        assertThat(dec(ledger.get("delta"))).isEqualByComparingTo("-30");
        assertThat(dec(ledger.get("value_delta"))).isEqualByComparingTo("-300");
        assertThat(ledger.get("reason")).isEqualTo("ADJUSTMENT_DAMAGE");
        assertThat(journal(number, "INVENTORY_ADJUSTMENT")).isEqualByComparingTo("300");
        assertThat(journal(number, "RAW_INVENTORY")).isEqualByComparingTo("-300");

        String gain = draft(planner, "OTHER", increase(resinLot, "5"));
        post(planner, gain);
        String gainNumber = number("stock_adjustments", gain);
        assertThat(quantity(resinLot)).isEqualByComparingTo("75");
        assertThat(value(resinLot)).isEqualByComparingTo("750");
        assertThat(journal(gainNumber, "RAW_INVENTORY")).isEqualByComparingTo("50");
        assertThat(journal(gainNumber, "INVENTORY_ADJUSTMENT")).isEqualByComparingTo("-50");

        // Every journal entry of the tenant balances, and the lot value equals its ledger history.
        assertThat(count(
                        "SELECT COUNT(*) FROM (SELECT entry_id FROM journal_lines WHERE tenant_id=? GROUP BY entry_id HAVING SUM(debit)<>SUM(credit)) x",
                        t))
                .isZero();
        assertThat(decimal("SELECT SUM(value_delta) FROM stock_ledger WHERE tenant_id=? AND lot_id=?", t, resinLot))
                .isEqualByComparingTo(value(resinLot));
        assertThat(rows(
                        "SELECT action FROM audit_events WHERE tenant_id=? AND reference=? ORDER BY created_at",
                        t,
                        number))
                .extracting(r -> r.get("action"))
                .containsExactly("ADJUSTMENT_CREATED", "ADJUSTMENT_POSTED");
        var detail = inventory.adjustment(admin, id);
        assertThat((List<?>) detail.get("lines")).hasSize(1);
        assertThat((List<?>) detail.get("journal")).hasSize(2);
        assertThat((List<?>) inventory.adjustment(warehouse, id).get("journal")).isEmpty();
    }

    @Test
    void foundStockBecomesAReleasedLotAtItsEnteredCost() {
        String id = draft(
                warehouse,
                "FOUND",
                found(carton, raw, "12", "2.5", "FOUND-" + t.substring(0, 6)),
                found(bottle, finished, "4", "5", null));
        post(warehouse, id);
        String number = number("stock_adjustments", id);
        var lines = rows(
                "SELECT x.*,l.status,l.source_type,l.source_id,l.unit_cost AS lot_cost,l.number AS lot FROM stock_adjustment_lines x JOIN lots l ON l.tenant_id=x.tenant_id AND l.id=x.lot_id WHERE x.tenant_id=? AND x.adjustment_id=? ORDER BY x.line_no",
                t,
                id);
        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst().get("lot")).isEqualTo("FOUND-" + t.substring(0, 6));
        assertThat(lines.getFirst().get("status")).isEqualTo("RELEASED");
        assertThat(lines.getFirst().get("source_type")).isEqualTo("ADJUSTMENT");
        assertThat(lines.getFirst().get("source_id")).isEqualTo(id);
        assertThat(dec(lines.getFirst().get("lot_cost"))).isEqualByComparingTo("2.5");
        assertThat(quantity(str(lines.getFirst(), "lot_id"))).isEqualByComparingTo("12");
        assertThat(value(str(lines.getFirst(), "lot_id"))).isEqualByComparingTo("30");
        assertThat(str(lines.get(1), "lot")).startsWith("FND-");
        assertThat(journal(number, "RAW_INVENTORY")).isEqualByComparingTo("30");
        assertThat(journal(number, "FG_INVENTORY")).isEqualByComparingTo("20");
        assertThat(journal(number, "INVENTORY_ADJUSTMENT")).isEqualByComparingTo("-50");

        // Lot numbers stay unique, pieces stay whole, and reasons constrain the direction.
        assertThatThrownBy(() -> draft(warehouse, "FOUND", found(carton, raw, "1", "2", "FOUND-" + t.substring(0, 6))))
                .hasMessageContaining("already used");
        assertThatThrownBy(() -> draft(warehouse, "FOUND", found(carton, raw, "1.5", "2", null)))
                .hasMessageContaining("whole numbers");
        assertThatThrownBy(() -> draft(warehouse, "DAMAGE", found(carton, raw, "1", "2", null)))
                .hasMessageContaining("cannot add found stock");
        assertThatThrownBy(() -> draft(warehouse, "FOUND", decrease(resinLot, "1")))
                .hasMessageContaining("cannot decrease");
    }

    @Test
    void partialDecreasesKeepRoundingResidualsAndFullDepletionClearsTheValue() {
        String lot = lot(carton, raw, "RELEASED", "OPENING", "3", "3.333334");
        assertThat(value(lot)).isEqualByComparingTo("10.000002");
        String first = draft(warehouse, "DAMAGE", decrease(lot, "1"));
        post(warehouse, first);
        assertThat(decimal(
                        "SELECT value_delta FROM stock_ledger WHERE tenant_id=? AND reference=?",
                        t,
                        number("stock_adjustments", first)))
                .isEqualByComparingTo("-3.333334");
        String rest = draft(warehouse, "EXPIRY", decrease(lot, "2"));
        post(warehouse, rest);
        assertThat(decimal(
                        "SELECT value_delta FROM stock_ledger WHERE tenant_id=? AND reference=?",
                        t,
                        number("stock_adjustments", rest)))
                .isEqualByComparingTo("-6.666668");
        assertThat(quantity(lot)).isZero();
        assertThat(value(lot)).isZero();
        assertThat(journal(number("stock_adjustments", first), "RAW_INVENTORY")
                        .add(journal(number("stock_adjustments", rest), "RAW_INVENTORY")))
                .isEqualByComparingTo("-10.000002");
        assertThat(row("SELECT value_decrease FROM stock_adjustments WHERE tenant_id=? AND id=?", t, rest)
                        .get("value_decrease"))
                .satisfies(v -> assertThat(dec(v)).isEqualByComparingTo("6.666668"));
    }

    @Test
    void adjustmentsAboveTheThresholdNeedADifferentApprover() throws Exception {
        command(planner, "/api/inventory/settings", new InventoryCommands.Settings(bd("300")))
                .andExpect(status().isForbidden());
        command(admin, "/api/inventory/settings", new InventoryCommands.Settings(bd("300")))
                .andExpect(status().isOk());
        assertThat(dec(inventory.settings(warehouse).get("approvalThreshold"))).isEqualByComparingTo("300");

        // Exactly at the threshold posts directly ("exceeds" is strict).
        String atThreshold = draft(warehouse, "DAMAGE", decrease(resinLot, "30"));
        assertThat(post(warehouse, atThreshold).get("status")).isEqualTo("POSTED");

        String id = draft(warehouse, "DAMAGE", decrease(resinLot, "31"));
        var submitted = post(warehouse, id);
        assertThat(submitted.get("status")).isEqualTo("APPROVAL_REQUIRED");
        assertThat(dec(submitted.get("approval_threshold"))).isEqualByComparingTo("300");
        assertThat(quantity(resinLot)).isEqualByComparingTo("70");
        assertThat(journal(number("stock_adjustments", id), "RAW_INVENTORY")).isZero();

        assertThatThrownBy(() -> adjustments.approve(warehouse, id, id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("different authorized person");
        assertThatThrownBy(() -> adjustments.approve(operator, id, id())).isInstanceOf(AccessDeniedException.class);
        command(operator, "/api/inventory/adjustments/" + id + "/approve", null).andExpect(status().isForbidden());
        var approved = json(command(planner, "/api/inventory/adjustments/" + id + "/approve", null)
                .andExpect(status().isOk()));
        assertThat(approved.get("status")).isEqualTo("POSTED");
        assertThat(quantity(resinLot)).isEqualByComparingTo("39");
        var header = row("SELECT * FROM stock_adjustments WHERE tenant_id=? AND id=?", t, id);
        assertThat(header.get("approved_by")).isEqualTo(planner.id());
        assertThat(rows(
                        "SELECT action FROM audit_events WHERE tenant_id=? AND reference=? ORDER BY created_at",
                        t,
                        header.get("number")))
                .extracting(r -> r.get("action"))
                .containsExactly("ADJUSTMENT_CREATED", "ADJUSTMENT_APPROVAL_REQUESTED", "ADJUSTMENT_APPROVED");

        // Self-approval is blocked for ADMIN too.
        String own = draft(admin, "DAMAGE", decrease(resinLot, "35"));
        assertThat(post(admin, own).get("status")).isEqualTo("APPROVAL_REQUIRED");
        assertThatThrownBy(() -> adjustments.approve(admin, own, id()))
                .hasMessageContaining("different authorized person");
        assertThat(adjustments.cancel(warehouse, own, id()).get("status")).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> adjustments.approve(planner, own, id())).hasMessageContaining("awaiting approval");
    }

    @Test
    void reservedAndNegativeStockAreRejected() {
        reserve(resinLot, "80");
        assertThatThrownBy(() -> draft(warehouse, "DAMAGE", decrease(resinLot, "25")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("20 kg available (80 kg reserved)");
        String ok = draft(warehouse, "DAMAGE", decrease(resinLot, "20"));
        reserve(resinLot, "90"); // stock reserved after the draft was saved
        assertThatThrownBy(() -> post(warehouse, ok)).hasMessageContaining("reserved stock cannot be adjusted");
        assertThat(quantity(resinLot)).isEqualByComparingTo("100");
        assertThat(row("SELECT status FROM stock_adjustments WHERE tenant_id=? AND id=?", t, ok)
                        .get("status"))
                .isEqualTo("DRAFT");
        reserve(resinLot, "0");
        assertThatThrownBy(() -> draft(warehouse, "DAMAGE", decrease(resinLot, "100.5")))
                .hasMessageContaining("100 kg available");
        // Increases are always possible on an active warehouse.
        post(warehouse, draft(warehouse, "OTHER", increase(resinLot, "1")));
        assertThat(quantity(resinLot)).isEqualByComparingTo("101");
        // A lot appears once per document.
        assertThatThrownBy(() -> draft(warehouse, "OTHER", increase(resinLot, "1"), decrease(resinLot, "1")))
                .hasMessageContaining("more than once");
    }

    @Test
    void quarantinedReceiptsCannotBeAdjusted() {
        String receipt = lot(resin, raw, "QUARANTINE", "PURCHASE", "10", "10");
        assertThatThrownBy(() -> draft(warehouse, "DAMAGE", decrease(receipt, "1")))
                .hasMessageContaining("awaiting its incoming inspection");
        // Rejected and production-quarantined stock can be written off.
        String rejected = lot(bottle, finished, "REJECTED", "MANUFACTURING", "5", "5");
        post(warehouse, draft(warehouse, "DAMAGE", decrease(rejected, "5")));
        assertThat(quantity(rejected)).isZero();
    }

    @Test
    void cancelledAndPostedAdjustmentsAreFinalAndImmutable() {
        String id = draft(warehouse, "SAMPLE", decrease(resinLot, "1"));
        adjustments.cancel(planner, id, id());
        assertThatThrownBy(() -> post(warehouse, id)).hasMessageContaining("Only draft adjustments");
        String posted = draft(warehouse, "SAMPLE", decrease(resinLot, "1"));
        post(warehouse, posted);
        assertThatThrownBy(() -> adjustments.cancel(warehouse, posted, id())).hasMessageContaining("can be cancelled");
        assertThatThrownBy(() ->
                        update("UPDATE stock_adjustments SET notes='edited' WHERE tenant_id=? AND id=?", t, posted))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> update(
                        "UPDATE stock_adjustment_lines SET quantity=2 WHERE tenant_id=? AND adjustment_id=?",
                        t,
                        posted))
                .hasMessageContaining("immutable");
    }

    @Test
    void closedPeriodRollsBackTheWholePosting() {
        String id = draft(warehouse, "DAMAGE", decrease(resinLot, "10"));
        update("INSERT INTO financial_periods(tenant_id,end_date,closed) VALUES(?,CURRENT_DATE,true)", t);
        assertThatThrownBy(() -> post(warehouse, id)).hasMessageContaining("period is closed");
        assertThat(quantity(resinLot)).isEqualByComparingTo("100");
        assertThat(row("SELECT status FROM stock_adjustments WHERE tenant_id=? AND id=?", t, id)
                        .get("status"))
                .isEqualTo("DRAFT");
    }

    @Test
    void retriesAreIdempotentAndOtherTenantsAreDenied() throws Exception {
        String key = id();
        var body = adjustment("DAMAGE", decrease(resinLot, "2"));
        var first =
                json(command(warehouse, "/api/inventory/adjustments", body, key).andExpect(status().isOk()));
        var again =
                json(command(warehouse, "/api/inventory/adjustments", body, key).andExpect(status().isOk()));
        assertThat(again.get("id")).isEqualTo(first.get("id"));
        assertThat(count("SELECT COUNT(*) FROM stock_adjustments WHERE tenant_id=?", t))
                .isEqualTo(1);
        command(warehouse, "/api/inventory/adjustments", adjustment("DAMAGE", decrease(resinLot, "3")), key)
                .andExpect(status().isConflict());

        String postKey = id();
        String path = "/api/inventory/adjustments/" + first.get("id") + "/post";
        command(warehouse, path, null, postKey).andExpect(status().isOk());
        command(warehouse, path, null, postKey).andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM stock_ledger WHERE tenant_id=? AND reason='ADJUSTMENT_DAMAGE'", t))
                .isEqualTo(1);
        assertThat(quantity(resinLot)).isEqualByComparingTo("98");

        var other = createTenant();
        var outsider = createUser(other.id(), "ADMIN");
        query(outsider, "/api/inventory/adjustments/" + first.get("id")).andExpect(status().isNotFound());
        command(outsider, "/api/inventory/adjustments/" + first.get("id") + "/cancel", null)
                .andExpect(status().isNotFound());
        command(outsider, "/api/inventory/adjustments", adjustment("DAMAGE", decrease(resinLot, "1")))
                .andExpect(status().isNotFound());
        command(operator, "/api/inventory/adjustments", adjustment("DAMAGE", decrease(resinLot, "1")))
                .andExpect(status().isForbidden());
        command(warehouse, "/api/inventory/adjustments", Map.of("reason", "THEFT", "lines", List.of()))
                .andExpect(status().isBadRequest());
        assertThat((List<?>) inventory
                        .adjustments(outsider, null, null, null, 50, null)
                        .get("items"))
                .isEmpty();
    }
}
