package lk.dexlanka.erp.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.quality.QualityCommands;
import lk.dexlanka.erp.quality.QualityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

class TransferAndCountTest extends InventoryTestSupport {
    @Autowired
    QualityService quality;

    // ------------------------------------------------------------------ transfers

    @Test
    void transferOfAReleasedLotIsValueNeutralAndTraceable() throws Exception {
        String store = newWarehouse("Packaging store", "PKG", "RAW");
        var created = json(command(
                        warehouse,
                        "/api/inventory/transfers",
                        new InventoryCommands.Transfer(
                                raw,
                                store,
                                "Replenish",
                                List.of(new InventoryCommands.TransferLine(resinLot, bd("40")))))
                .andExpect(status().isOk()));
        String id = str(created, "id");
        assertThat(created.get("status")).isEqualTo("DRAFT");
        assertThat(quantity(resinLot)).isEqualByComparingTo("100");
        command(warehouse, "/api/inventory/transfers/" + id + "/post", null).andExpect(status().isOk());

        String number = number("stock_transfers", id);
        var line = row("SELECT * FROM stock_transfer_lines WHERE tenant_id=? AND transfer_id=?", t, id);
        String destination = str(line, "destination_lot_id");
        var source = row("SELECT * FROM lots WHERE tenant_id=? AND id=?", t, resinLot);
        var target = row("SELECT * FROM lots WHERE tenant_id=? AND id=?", t, destination);
        assertThat(target.get("warehouse_id")).isEqualTo(store);
        assertThat(target.get("item_id")).isEqualTo(resin);
        assertThat(target.get("status")).isEqualTo("RELEASED");
        assertThat(target.get("source_type")).isEqualTo("TRANSFER");
        assertThat(target.get("source_id")).isEqualTo(resinLot);
        assertThat(dec(target.get("unit_cost"))).isEqualByComparingTo("10");
        assertThat(target.get("created_at")).isEqualTo(source.get("created_at")); // FIFO age is preserved
        assertThat(str(target, "number")).startsWith(str(source, "number") + "-T");
        assertThat(quantity(resinLot)).isEqualByComparingTo("60");
        assertThat(value(resinLot)).isEqualByComparingTo("600");
        assertThat(quantity(destination)).isEqualByComparingTo("40");
        assertThat(value(destination)).isEqualByComparingTo("400");
        assertThat(decimal("SELECT SUM(value_delta) FROM stock_ledger WHERE tenant_id=? AND reference=?", t, number))
                .isZero();
        assertThat(rows("SELECT reason FROM stock_ledger WHERE tenant_id=? AND reference=? ORDER BY delta", t, number))
                .extracting(r -> r.get("reason"))
                .containsExactly("TRANSFER_OUT", "TRANSFER_IN");
        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE tenant_id=? AND reference=?", t, number))
                .isZero();
        var lineage = inventory.lot(warehouse, resinLot);
        assertThat((List<?>) lineage.get("transferredTo")).hasSize(1);
        assertThat(((Map<?, ?>) inventory.lot(warehouse, destination).get("lot")).get("source_lot"))
                .isEqualTo(source.get("number"));

        // A residual-carrying partial move transfers the exact average value.
        String odd = lot(carton, raw, "RELEASED", "OPENING", "3", "3.333334");
        String oddTransfer = transferDraft(warehouse, raw, store, odd, "1");
        transfers.post(warehouse, oddTransfer, id());
        String oddDestination = str(
                row(
                        "SELECT destination_lot_id FROM stock_transfer_lines WHERE tenant_id=? AND transfer_id=?",
                        t,
                        oddTransfer),
                "destination_lot_id");
        assertThat(value(oddDestination)).isEqualByComparingTo("3.333334");
        assertThat(value(odd)).isEqualByComparingTo("6.666668");
    }

    @Test
    void quarantinedLotsStayQuarantinedAndFollowTheSourceInspection() {
        String store = newWarehouse("Quarantine cage", "QC", "QUARANTINE");
        String held = lot(bottle, finished, "QUARANTINE", "OPENING", "50", "5");
        String inspection = id();
        update("INSERT INTO inspections(id,tenant_id,lot_id,type) VALUES(?,?,?,'FINAL')", inspection, t, held);
        String first = transferDraft(warehouse, finished, store, held, "20");
        transfers.post(warehouse, first, id());
        String moved = str(
                row(
                        "SELECT destination_lot_id FROM stock_transfer_lines WHERE tenant_id=? AND transfer_id=?",
                        t,
                        first),
                "destination_lot_id");
        assertThat(row("SELECT status FROM lots WHERE tenant_id=? AND id=?", t, moved)
                        .get("status"))
                .isEqualTo("QUARANTINE");
        // A second hop: the chain still follows the original inspection.
        String second = transferDraft(warehouse, store, finished, moved, "5");
        transfers.post(warehouse, second, id());
        String movedAgain = str(
                row(
                        "SELECT destination_lot_id FROM stock_transfer_lines WHERE tenant_id=? AND transfer_id=?",
                        t,
                        second),
                "destination_lot_id");

        var quality = createUser(t, "QUALITY");
        this.quality.inspect(quality, inspection, new QualityCommands.Inspect("PASSED", "Visual check passed"), id());
        for (String lot : List.of(held, moved, movedAgain))
            assertThat(row("SELECT status FROM lots WHERE tenant_id=? AND id=?", t, lot)
                            .get("status"))
                    .isEqualTo("RELEASED");

        // Rejection follows too, and a rejected lot keeps its status when moved.
        String failed = lot(bottle, finished, "QUARANTINE", "OPENING", "10", "5");
        String failedInspection = id();
        update("INSERT INTO inspections(id,tenant_id,lot_id,type) VALUES(?,?,?,'FINAL')", failedInspection, t, failed);
        String third = transferDraft(warehouse, finished, store, failed, "10");
        transfers.post(warehouse, third, id());
        this.quality.inspect(quality, failedInspection, new QualityCommands.Inspect("FAILED", "Seal leak"), id());
        String rejected = str(
                row(
                        "SELECT destination_lot_id FROM stock_transfer_lines WHERE tenant_id=? AND transfer_id=?",
                        t,
                        third),
                "destination_lot_id");
        assertThat(row("SELECT status FROM lots WHERE tenant_id=? AND id=?", t, rejected)
                        .get("status"))
                .isEqualTo("REJECTED");
        String back = transferDraft(warehouse, store, finished, rejected, "10");
        transfers.post(warehouse, back, id());
        assertThat(row(
                                "SELECT l.status FROM stock_transfer_lines x JOIN lots l ON l.tenant_id=x.tenant_id AND l.id=x.destination_lot_id WHERE x.tenant_id=? AND x.transfer_id=?",
                                t,
                                back)
                        .get("status"))
                .isEqualTo("REJECTED");
    }

    @Test
    void reservedStockAndInvalidRoutesCannotBeTransferred() {
        String store = newWarehouse("Packaging store", "PKG", "RAW");
        reserve(resinLot, "70");
        assertThatThrownBy(() -> transferDraft(warehouse, raw, store, resinLot, "40"))
                .hasMessageContaining("reserved stock cannot be transferred");
        String ok = transferDraft(warehouse, raw, store, resinLot, "30");
        reserve(resinLot, "75");
        assertThatThrownBy(() -> transfers.post(warehouse, ok, id())).hasMessageContaining("reserved");
        assertThat(quantity(resinLot)).isEqualByComparingTo("100");
        assertThatThrownBy(() -> transferDraft(warehouse, raw, raw, resinLot, "1"))
                .hasMessageContaining("two different warehouses");
        assertThatThrownBy(() -> transferDraft(warehouse, store, raw, resinLot, "1"))
                .hasMessageContaining("is not in Packaging store");
        String receipt = lot(resin, raw, "QUARANTINE", "PURCHASE", "10", "10");
        assertThatThrownBy(() -> transferDraft(warehouse, raw, store, receipt, "1"))
                .hasMessageContaining("awaiting its incoming inspection");
        assertThatThrownBy(() -> transferDraft(operator, raw, store, resinLot, "1"))
                .isInstanceOf(AccessDeniedException.class);
        transfers.cancel(warehouse, ok, id());
        assertThatThrownBy(() -> transfers.post(warehouse, ok, id())).hasMessageContaining("Only draft transfers");
    }

    @Test
    void transferRetriesAndOtherTenants() throws Exception {
        String store = newWarehouse("Packaging store", "PKG", "RAW");
        String id = transferDraft(warehouse, raw, store, resinLot, "10");
        String key = id();
        command(warehouse, "/api/inventory/transfers/" + id + "/post", null, key)
                .andExpect(status().isOk());
        command(warehouse, "/api/inventory/transfers/" + id + "/post", null, key)
                .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM stock_ledger WHERE tenant_id=? AND reason='TRANSFER_OUT'", t))
                .isEqualTo(1);
        command(warehouse, "/api/inventory/transfers/" + id + "/cancel", null, key)
                .andExpect(status().isConflict());

        var other = createTenant();
        var outsider = createUser(other.id(), "WAREHOUSE");
        query(outsider, "/api/inventory/transfers/" + id).andExpect(status().isNotFound());
        command(
                        outsider,
                        "/api/inventory/transfers",
                        new InventoryCommands.Transfer(
                                other.rawWarehouse(),
                                other.finishedWarehouse(),
                                null,
                                List.of(new InventoryCommands.TransferLine(resinLot, bd("1")))))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ cycle counts

    @Test
    void blindCountHidesSystemQuantitiesThenPostsTheVarianceAdjustment() throws Exception {
        String cartons = lot(carton, raw, "RELEASED", "OPENING", "50", "2");
        var started = json(command(
                        warehouse,
                        "/api/inventory/counts",
                        new InventoryCommands.Count(raw, null, null, true, "Monthly count"))
                .andExpect(status().isOk()));
        String id = str(started, "id");
        assertThat(started.get("status")).isEqualTo("COUNTING");
        var lines = rows("SELECT * FROM stock_count_lines WHERE tenant_id=? AND count_id=? ORDER BY line_no", t, id);
        assertThat(lines).extracting(l -> l.get("lot_id")).containsExactlyInAnyOrder(resinLot, cartons);

        var blind = json(query(warehouse, "/api/inventory/counts/" + id).andExpect(status().isOk()));
        assertThat(blind.get("hidden")).isEqualTo(true);
        for (var line : (List<Map<String, Object>>) blind.get("lines")) {
            assertThat(line.get("snapshot_qty")).isNull();
            assertThat(line.get("system_qty")).isNull();
            assertThat(line.get("variance_qty")).isNull();
        }

        String resinLine = str(
                lines.stream()
                        .filter(l -> l.get("lot_id").equals(resinLot))
                        .findFirst()
                        .orElseThrow(),
                "id");
        String cartonLine = str(
                lines.stream()
                        .filter(l -> l.get("lot_id").equals(cartons))
                        .findFirst()
                        .orElseThrow(),
                "id");
        command(
                        warehouse,
                        "/api/inventory/counts/" + id + "/entries",
                        new InventoryCommands.CountEntries(
                                List.of(new InventoryCommands.CountEntry(resinLine, bd("97")))))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> counts.submit(warehouse, id, id())).hasMessageContaining("1 lot is still uncounted");
        assertThatThrownBy(() -> counts.enter(
                        warehouse,
                        id,
                        new InventoryCommands.CountEntries(
                                List.of(new InventoryCommands.CountEntry(cartonLine, bd("50.5")))),
                        id()))
                .hasMessageContaining("whole numbers");
        counts.enter(
                warehouse,
                id,
                new InventoryCommands.CountEntries(List.of(new InventoryCommands.CountEntry(cartonLine, bd("50")))),
                id());
        assertThat(counts.submit(warehouse, id, id()).get("status")).isEqualTo("REVIEW");
        assertThatThrownBy(() -> counts.enter(
                        warehouse,
                        id,
                        new InventoryCommands.CountEntries(
                                List.of(new InventoryCommands.CountEntry(cartonLine, bd("49")))),
                        id()))
                .hasMessageContaining("reopen");

        var review = inventory.count(warehouse, id);
        assertThat(review.get("hidden")).isEqualTo(false);
        var resinReview = ((List<Map<String, Object>>) review.get("lines"))
                .stream()
                        .filter(l -> l.get("lot_id").equals(resinLot))
                        .findFirst()
                        .orElseThrow();
        assertThat(dec(resinReview.get("snapshot_qty"))).isEqualByComparingTo("100");
        assertThat(dec(resinReview.get("variance_qty"))).isEqualByComparingTo("-3");
        assertThat(dec(resinReview.get("variance_value"))).isEqualByComparingTo("-30");
        assertThat((List<?>) review.get("moved")).isEmpty();

        var posted = json(command(warehouse, "/api/inventory/counts/" + id + "/post", null)
                .andExpect(status().isOk()));
        assertThat(posted.get("status")).isEqualTo("POSTED");
        var adjustment = (Map<?, ?>) posted.get("adjustment");
        assertThat(adjustment.get("status")).isEqualTo("POSTED");
        var header = row("SELECT * FROM stock_adjustments WHERE tenant_id=? AND id=?", t, adjustment.get("id"));
        assertThat(header.get("reason")).isEqualTo("COUNT_VARIANCE");
        assertThat(row("SELECT adjustment_id FROM stock_counts WHERE tenant_id=? AND id=?", t, id)
                        .get("adjustment_id"))
                .isEqualTo(adjustment.get("id"));
        assertThat(count(
                        "SELECT COUNT(*) FROM stock_adjustment_lines WHERE tenant_id=? AND adjustment_id=?",
                        t,
                        adjustment.get("id")))
                .isEqualTo(1);
        assertThat(quantity(resinLot)).isEqualByComparingTo("97");
        assertThat(journal(str(header, "number"), "RAW_INVENTORY")).isEqualByComparingTo("-30");
        assertThat(count("SELECT COUNT(*) FROM stock_count_lines WHERE tenant_id=? AND count_id=? AND open", t, id))
                .isZero();
    }

    @Test
    void stockThatMovedAfterTheSnapshotIsComparedAtPostingTime() {
        String id =
                str(counts.start(warehouse, new InventoryCommands.Count(raw, resin, null, false, null), id()), "id");
        // Ten kilograms are written off while the count is open.
        post(warehouse, draft(warehouse, "DAMAGE", decrease(resinLot, "10")));
        String line = str(row("SELECT id FROM stock_count_lines WHERE tenant_id=? AND count_id=?", t, id), "id");
        counts.enter(
                warehouse,
                id,
                new InventoryCommands.CountEntries(List.of(new InventoryCommands.CountEntry(line, bd("88")))),
                id());
        counts.submit(warehouse, id, id());
        var review = inventory.count(planner, id);
        assertThat((List<?>) review.get("moved")).hasSize(1);
        var reviewLine = ((List<Map<String, Object>>) review.get("lines")).getFirst();
        assertThat(dec(reviewLine.get("system_qty"))).isEqualByComparingTo("90");
        assertThat(dec(reviewLine.get("variance_qty"))).isEqualByComparingTo("-2");
        assertThat(reviewLine.get("moved")).isEqualTo(true);

        var posted = counts.post(planner, id, id());
        assertThat((List<Object>) posted.get("moved"))
                .containsExactly(
                        row("SELECT number FROM lots WHERE id=?", resinLot).get("number"));
        assertThat(quantity(resinLot)).isEqualByComparingTo("88");
        var stored = row("SELECT * FROM stock_count_lines WHERE tenant_id=? AND id=?", t, line);
        assertThat(dec(stored.get("system_qty"))).isEqualByComparingTo("90");
        assertThat(dec(stored.get("variance_qty"))).isEqualByComparingTo("-2");
        assertThat(stored.get("moved")).isEqualTo(true);
    }

    @Test
    void aLotCanBeInOnlyOneOpenCount() {
        String cartons = lot(carton, raw, "RELEASED", "OPENING", "50", "2");
        String receipt = lot(resin, raw, "QUARANTINE", "PURCHASE", "10", "10");
        String first = str(
                counts.start(warehouse, new InventoryCommands.Count(raw, null, "RAW_MATERIAL", false, null), id()),
                "id");
        // Lots awaiting incoming inspection are not counted.
        assertThat(rows("SELECT lot_id FROM stock_count_lines WHERE tenant_id=? AND count_id=?", t, first))
                .extracting(r -> r.get("lot_id"))
                .containsExactly(resinLot)
                .doesNotContain(receipt);
        assertThatThrownBy(
                        () -> counts.start(warehouse, new InventoryCommands.Count(raw, null, null, false, null), id()))
                .hasMessageContaining("already in an open count")
                .hasMessageContaining(number("stock_counts", first));
        // A count over other lots of the same warehouse is fine.
        String other =
                str(counts.start(warehouse, new InventoryCommands.Count(raw, carton, null, false, null), id()), "id");
        assertThat(rows("SELECT lot_id FROM stock_count_lines WHERE tenant_id=? AND count_id=?", t, other))
                .extracting(r -> r.get("lot_id"))
                .containsExactly(cartons);
        // The database enforces the rule as well.
        assertThatThrownBy(() -> update(
                        "INSERT INTO stock_count_lines(id,tenant_id,count_id,line_no,lot_id,item_id,snapshot_qty,unit_cost) VALUES(?,?,?,9,?,?,1,1)",
                        id(),
                        t,
                        other,
                        resinLot,
                        resin))
                .hasMessageContaining("stock_count_lines_one_open_count");
        counts.cancel(warehouse, first, id());
        String again =
                str(counts.start(warehouse, new InventoryCommands.Count(raw, resin, null, false, null), id()), "id");
        assertThat(count("SELECT COUNT(*) FROM stock_count_lines WHERE tenant_id=? AND count_id=?", t, again))
                .isEqualTo(1);
    }

    @Test
    void countVariancesUseTheApprovalThresholdAndReservations() {
        adjustments.settings(admin, new InventoryCommands.Settings(bd("10")), id());
        String id =
                str(counts.start(warehouse, new InventoryCommands.Count(raw, resin, null, false, null), id()), "id");
        String line = str(row("SELECT id FROM stock_count_lines WHERE tenant_id=? AND count_id=?", t, id), "id");
        counts.enter(
                warehouse,
                id,
                new InventoryCommands.CountEntries(List.of(new InventoryCommands.CountEntry(line, bd("95")))),
                id());
        counts.submit(warehouse, id, id());
        assertThat(counts.reopen(planner, id, id()).get("status")).isEqualTo("COUNTING");
        counts.submit(warehouse, id, id());

        reserve(resinLot, "96");
        assertThatThrownBy(() -> counts.post(warehouse, id, id())).hasMessageContaining("below the reserved");
        reserve(resinLot, "0");

        var posted = counts.post(warehouse, id, id());
        var adjustment = (Map<?, ?>) posted.get("adjustment");
        assertThat(adjustment.get("status")).isEqualTo("APPROVAL_REQUIRED");
        assertThat(quantity(resinLot)).isEqualByComparingTo("100");
        String adjustmentId = adjustment.get("id").toString();
        assertThatThrownBy(() -> adjustments.approve(warehouse, adjustmentId, id()))
                .hasMessageContaining("different authorized person");
        adjustments.approve(planner, adjustmentId, id());
        assertThat(quantity(resinLot)).isEqualByComparingTo("95");
        assertThat(row("SELECT status FROM stock_counts WHERE tenant_id=? AND id=?", t, id)
                        .get("status"))
                .isEqualTo("POSTED");
    }

    @Test
    void countPermissionsAndOtherTenants() throws Exception {
        command(operator, "/api/inventory/counts", new InventoryCommands.Count(raw, null, null, false, null))
                .andExpect(status().isForbidden());
        var other = createTenant();
        var outsider = createUser(other.id(), "WAREHOUSE");
        command(outsider, "/api/inventory/counts", new InventoryCommands.Count(raw, null, null, false, null))
                .andExpect(status().isNotFound());
        String id = str(counts.start(warehouse, new InventoryCommands.Count(raw, null, null, false, null), id()), "id");
        query(outsider, "/api/inventory/counts/" + id).andExpect(status().isNotFound());
        command(outsider, "/api/inventory/counts/" + id + "/cancel", null).andExpect(status().isNotFound());
        assertThat((List<?>)
                        inventory.counts(outsider, null, null, null, 50, null).get("items"))
                .isEmpty();
    }

    // ------------------------------------------------------------------ read model

    @Test
    void readEndpointsPageFilterAndValue() throws Exception {
        for (int i = 0; i < 3; i++) draft(warehouse, "SAMPLE", decrease(resinLot, "1"));
        var page = json(query(warehouse, "/api/inventory/adjustments?limit=2").andExpect(status().isOk()));
        assertThat((List<?>) page.get("items")).hasSize(2);
        assertThat(str(page, "next")).isNotBlank();
        var rest = json(query(warehouse, "/api/inventory/adjustments?limit=2&before=" + page.get("next"))
                .andExpect(status().isOk()));
        assertThat((List<?>) rest.get("items")).hasSize(1);
        assertThat(rest.get("next")).isEqualTo("");
        query(warehouse, "/api/inventory/adjustments?limit=500").andExpect(status().isConflict());

        String store = newWarehouse("Packaging store", "PKG", "RAW");
        transfers.post(warehouse, transferDraft(warehouse, raw, store, resinLot, "25"), id());
        var lots = json(
                query(warehouse, "/api/inventory/lots?warehouseId=" + store).andExpect(status().isOk()));
        assertThat((List<?>) lots.get("items")).hasSize(1);
        var search = json(
                query(warehouse, "/api/inventory/lots?q=resin&status=RELEASED").andExpect(status().isOk()));
        assertThat((List<?>) search.get("items")).hasSize(2);

        String today = LocalDate.now(java.time.ZoneId.of("Asia/Colombo")).toString();
        var ledger = json(query(warehouse, "/api/inventory/ledger?itemId=" + resin + "&from=" + today + "&to=" + today)
                .andExpect(status().isOk()));
        assertThat((List<?>) ledger.get("items")).hasSize(3); // opening + transfer out + transfer in
        var byReference = json(query(warehouse, "/api/inventory/ledger?reference=TRF-&warehouseId=" + store)
                .andExpect(status().isOk()));
        assertThat((List<?>) byReference.get("items")).hasSize(1);
        query(warehouse, "/api/inventory/ledger?from=yesterday").andExpect(status().isBadRequest());

        var valuation = json(query(planner, "/api/inventory/valuation").andExpect(status().isOk()));
        assertThat(dec(valuation.get("total"))).isEqualByComparingTo("1000");
        query(operator, "/api/inventory/valuation").andExpect(status().isForbidden());
        json(query(operator, "/api/inventory/lots").andExpect(status().isOk()));
    }
}
