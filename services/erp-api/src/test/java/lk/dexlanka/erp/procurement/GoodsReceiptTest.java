package lk.dexlanka.erp.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class GoodsReceiptTest extends ProcurementFixture {
    @Test
    void multiLineReceiptPostsOneQuarantinedLotPerLine() throws Exception {
        String po = approved(line(resin, "4", "bag", "12000"), line(carton, "9", "box", "100"));
        String number = str(header(po), "number");
        var body = new ProcurementCommands.GoodsReceipt(
                raw,
                "DN-7781",
                List.of(
                        receiptLine(lineId(po, 1), "2", null, "SUP-LOT-1"),
                        receiptLine(lineId(po, 2), "21", "pcs", null)));
        var result = json(command(warehouse, "/api/purchases/orders/" + po + "/receipts", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("PARTIAL")));
        @SuppressWarnings("unchecked")
        var posted = (List<Map<String, Object>>) result.get("receipts");
        assertThat(posted).hasSize(2);
        String resinLot = str(posted.get(0), "lotId");
        String cartonLot = str(posted.get(1), "lotId");

        // Resin: 2 bags = 50 kg at 480/kg, lot named after the supplier lot.
        var lot = row("SELECT * FROM lots WHERE tenant_id=? AND id=?", t, resinLot);
        assertThat(lot).containsEntry("number", "SUP-LOT-1").containsEntry("status", "QUARANTINE");
        assertThat(lot).containsEntry("source_type", "PURCHASE").containsEntry("source_id", po);
        assertThat(dec(lot.get("unit_cost"))).isEqualByComparingTo("480");
        assertThat(stock(resinLot)).isEqualByComparingTo("50");
        assertThat(stockValue(resinLot)).isEqualByComparingTo("24000");
        var resinReceipt = receiptOfLot(resinLot);
        assertThat(resinReceipt.get("purchase_order_line_id")).isEqualTo(lineId(po, 1));
        assertThat(resinReceipt).containsEntry("delivery_note", "DN-7781").containsEntry("entered_unit", "bag");
        assertThat(dec(resinReceipt.get("entered_quantity"))).isEqualByComparingTo("2");
        assertThat(dec(resinReceipt.get("value_amount"))).isEqualByComparingTo("24000");
        // Cartons entered in pieces against a line bought in boxes; the lot number comes from the GRN number.
        var cartonReceipt = receiptOfLot(cartonLot);
        assertThat(cartonReceipt.get("number")).isEqualTo(resinReceipt.get("number"));
        assertThat(str(row("SELECT number FROM lots WHERE tenant_id=? AND id=?", t, cartonLot), "number"))
                .isEqualTo(resinReceipt.get("number") + "-2");
        assertThat(dec(cartonReceipt.get("value_amount"))).isEqualByComparingTo("699.999993"); // 21 x 33.333333

        assertThat(count(
                        "SELECT COUNT(*) FROM inspections WHERE tenant_id=? AND lot_id IN (?,?) AND type='INCOMING' AND status='PENDING'",
                        t,
                        resinLot,
                        cartonLot))
                .isEqualTo(2);
        assertThat(dec(poLine(po, 1).get("received"))).isEqualByComparingTo("50");
        assertThat(dec(poLine(po, 2).get("received"))).isEqualByComparingTo("21");
        assertThat(account("RAW_INVENTORY", number)).isEqualByComparingTo("24699.999993");
        assertThat(account("GRNI", number)).isEqualByComparingTo("-24699.999993");
        assertThat(count(
                        "SELECT COUNT(*) FROM stock_ledger WHERE tenant_id=? AND reference=? AND reason='GOODS_RECEIPT'",
                        t,
                        number))
                .isEqualTo(2);

        // The rest of the resin completes the order.
        receive(po, receiptLine(lineId(po, 1), "50", "kg", null), receiptLine(lineId(po, 2), "6", "pcs", null));
        assertThat(header(po).get("status")).isEqualTo("RECEIVED");
        assertThat(supply.openSupplyByItem(t)).isEmpty();
        assertThat(journalImbalance()).isZero();
        assertThat(ledgerReconciles()).isTrue();
    }

    @Test
    void onlyActiveRawMaterialWarehousesReceive() throws Exception {
        String po = approved(line(resin, "100", null, "480"));
        String spare = id();
        update(
                "INSERT INTO warehouses(id,tenant_id,factory_id,name,kind,code,active) SELECT ?,tenant_id,factory_id,'Old store','RAW','OLD',false FROM warehouses WHERE tenant_id=? AND id=?",
                spare,
                t,
                raw);
        var body = new ProcurementCommands.GoodsReceipt(
                spare, "DN-1", List.of(receiptLine(lineId(po, 1), "10", null, "LOT-INACTIVE")));
        command(warehouse, "/api/purchases/orders/" + po + "/receipts", body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Warehouse OLD (Old store) is inactive; choose an active raw-material warehouse"));
        var finishedBody = new ProcurementCommands.GoodsReceipt(
                finished, "DN-1", List.of(receiptLine(lineId(po, 1), "10", null, "LOT-FG")));
        command(warehouse, "/api/purchases/orders/" + po + "/receipts", finishedBody)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Purchased goods are received into a raw-material warehouse; Finished is a finished"
                                + " warehouse"));
        // Nothing was posted by the refused receipts.
        assertThat(count("SELECT COUNT(*) FROM lots WHERE tenant_id=? AND source_id=?", t, po))
                .isZero();
        assertThat(dec(poLine(po, 1).get("received"))).isZero();
        // The detail offers only active raw-material warehouses.
        var detail = json(query(warehouse, "/api/purchases/orders/" + po));
        assertThat(ids(detail.get("warehouses"))).containsExactly(raw);
    }

    @Test
    void receivingRulesRolesAndIdempotency() throws Exception {
        String draft = draft(line(resin, "10", null, "480"));
        assertThatThrownBy(() -> receive(draft, receiptLine(lineId(draft, 1), "1", null, null)))
                .hasMessageContaining("Only approved, outstanding purchases can be received");
        String po = approved(line(resin, "10", null, "480"), line(carton, "6", null, "30"));
        assertThatThrownBy(() -> receive(po, receiptLine(lineId(po, 1), "11", null, null)))
                .hasMessageContaining("Receipt exceeds the outstanding quantity of line 1 (10 kg of RM-1 open)");
        assertThatThrownBy(() -> receive(
                        po, receiptLine(lineId(po, 1), "1", null, null), receiptLine(lineId(po, 1), "1", null, null)))
                .hasMessageContaining("only once per goods receipt");
        assertThatThrownBy(() -> receive(po, receiptLine(lineId(po, 2), "3", "box", null)))
                .hasMessageContaining("exceeds the outstanding quantity of line 2");
        assertThatThrownBy(() -> receive(po, receiptLine(lineId(draft, 1), "1", null, null)))
                .hasMessageContaining("Record not found");
        receive(po, receiptLine(lineId(po, 1), "1", null, "DUP-LOT"));
        assertThatThrownBy(() -> receive(po, receiptLine(lineId(po, 2), "1", null, "DUP-LOT")))
                .hasMessageContaining("Lot number DUP-LOT already exists");
        var body =
                new ProcurementCommands.GoodsReceipt(raw, "DN-9", List.of(receiptLine(lineId(po, 2), "2", null, null)));
        assertThatThrownBy(() -> receiving.receive(planner, po, body, id())).isInstanceOf(AccessDeniedException.class);
        command(viewer, "/api/purchases/orders/" + po + "/receipts", body).andExpect(status().isForbidden());
        String key = id();
        var first = json(command(warehouse, "/api/purchases/orders/" + po + "/receipts", body, key)
                .andExpect(status().isOk()));
        var replay = json(command(warehouse, "/api/purchases/orders/" + po + "/receipts", body, key)
                .andExpect(status().isOk()));
        assertThat(replay.get("number")).isEqualTo(first.get("number"));
        assertThat(dec(poLine(po, 2).get("received"))).isEqualByComparingTo("2");
    }

    @Test
    void incomingQualityDecisionsWorkPerReceivedLot() {
        String po = approved(line(resin, "100", null, "480"), line(carton, "60", null, "30"));
        var result = receive(
                po, receiptLine(lineId(po, 1), "100", null, "QC-A"), receiptLine(lineId(po, 2), "60", null, "QC-B"));
        assertThat(header(po).get("status")).isEqualTo("RECEIVED");
        String resinLot = str(receipts(result).get(0), "lotId");
        String cartonLot = str(receipts(result).get(1), "lotId");
        String number = str(header(po), "number");

        // The carton lot fails: stock and clearing are reversed, the line reopens, the order is partial again.
        inspect(cartonLot, "FAILED");
        assertThat(stock(cartonLot)).isZero();
        assertThat(dec(poLine(po, 2).get("received"))).isZero();
        assertThat(header(po).get("status")).isEqualTo("PARTIAL");
        assertThat(account("GRNI", po)).isEqualByComparingTo("1800");
        assertThat(account("RAW_INVENTORY", po)).isEqualByComparingTo("-1800");
        assertThat(supply.openSupplyByItem(t)).containsOnlyKeys(carton);
        assertThat(dec(supply.openSupplyByItem(t).get(carton))).isEqualByComparingTo("60");

        // The resin lot passes and becomes usable stock.
        inspect(resinLot, "PASSED");
        assertThat(row("SELECT status FROM lots WHERE tenant_id=? AND id=?", t, resinLot)
                        .get("status"))
                .isEqualTo("RELEASED");
        assertThat(stock(resinLot)).isEqualByComparingTo("100");
        assertThat(account("GRNI", number)).isEqualByComparingTo("-49800");
        assertThat(journalImbalance()).isZero();

        // A replacement delivery for the rejected cartons completes the order again.
        receive(po, receiptLine(lineId(po, 2), "60", null, "QC-C"));
        assertThat(header(po).get("status")).isEqualTo("RECEIVED");

        // All receipts of an order rejected: nothing is received any more and it can be cancelled.
        String other = approved(line(carton, "5", null, "30"));
        var only = receive(other, receiptLine(lineId(other, 1), "5", null, null));
        inspect(str(receipts(only).getFirst(), "lotId"), "FAILED");
        assertThat(header(other).get("status")).isEqualTo("APPROVED");
        assertThat(orders.cancel(planner, other, new ProcurementCommands.Reason("Supplier failed QC"), id())
                        .get("status"))
                .isEqualTo("CANCELLED");
    }

    @Test
    void originalSingleLotReceiptStillWorks() throws Exception {
        var po = json(command(
                        planner,
                        "/api/purchases",
                        new ProcurementCommands.Purchase(
                                supplier,
                                resin,
                                bd("10"),
                                bd("12"),
                                java.time.LocalDate.now().toString()))
                .andExpect(status().isOk()));
        String id = str(po, "id");
        assertThat(header(id).get("status")).isEqualTo("SUBMITTED");
        command(planner2, "/api/purchases/" + id + "/approve", null).andExpect(status().isOk());
        var r = json(command(
                        warehouse,
                        "/api/purchases/" + id + "/receive",
                        new ProcurementCommands.Receive(bd("4"), raw, "OLD-STYLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUARANTINE")));
        assertThat(receiptOfLot(str(r, "lot_id")).get("id")).isEqualTo(r.get("id"));
        assertThat(header(id).get("status")).isEqualTo("PARTIAL");
        String multi = approved(line(resin, "1", null, "1"), line(carton, "1", null, "1"));
        command(warehouse, "/api/purchases/" + multi + "/receive", new ProcurementCommands.Receive(bd("1"), raw, "X-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("This purchase order has several open lines; receive it line by line"));
    }

    @Test
    void planningIncomingSupplyUsesOpenApprovedLines() throws Exception {
        String po = approved(line(resin, "4", "bag", "12000"));
        draft(line(resin, "999", null, "1")); // drafts are not supply
        submitted(line(resin, "999", null, "1")); // neither is an order awaiting approval
        receive(po, receiptLine(lineId(po, 1), "1", null, null));
        assertThat(dec(supply.openSupplyByItem(t).get(resin))).isEqualByComparingTo("75");
        var state = json(query(planner, "/api/state"));
        @SuppressWarnings("unchecked")
        var mrp = (List<Map<String, Object>>) state.get("mrp");
        var row =
                mrp.stream().filter(m -> resin.equals(m.get("id"))).findFirst().orElseThrow();
        assertThat(dec(row.get("incoming"))).isEqualByComparingTo("75");
        @SuppressWarnings("unchecked")
        var purchases = (List<Map<String, Object>>) state.get("purchases");
        assertThat(purchases).hasSize(3);
        assertThat(purchases.stream()
                        .filter(p -> po.equals(p.get("id")))
                        .findFirst()
                        .orElseThrow())
                .containsEntry("status", "PARTIAL")
                .containsEntry("supplier", "Polymer Traders")
                .containsEntry("item", "HDPE resin");
    }
}
