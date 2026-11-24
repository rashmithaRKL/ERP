package lk.dexlanka.erp.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class SupplierReturnTest extends ProcurementFixture {
    static ProcurementCommands.SupplierReturn back(String quantity, String debitNote) {
        return new ProcurementCommands.SupplierReturn(bd(quantity), "Torn bags found when opened", debitNote);
    }

    String lotOf(String receipt) {
        return str(row("SELECT lot_id FROM receipts WHERE tenant_id=? AND id=?", t, receipt), "lot_id");
    }

    @Test
    void returnOfUninvoicedStockReversesGoodsReceivedClearing() throws Exception {
        String po = approved(line(resin, "100", null, "10"));
        String receipt = releasedReceipt(po, 1, "100", "RET-A");
        String lot = lotOf(receipt);
        var result = json(command(warehouse, "/api/purchases/receipts/" + receipt + "/returns", back("30", "DBN-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED")));
        String number = str(result, "number");
        assertThat(stock(lot)).isEqualByComparingTo("70");
        assertThat(stockValue(lot)).isEqualByComparingTo("700");
        var entry =
                row("SELECT * FROM stock_ledger WHERE tenant_id=? AND lot_id=? AND reason='SUPPLIER_RETURN'", t, lot);
        assertThat(dec(entry.get("delta"))).isEqualByComparingTo("-30");
        assertThat(dec(entry.get("value_delta"))).isEqualByComparingTo("-300");
        assertThat(entry.get("reference")).isEqualTo(number);
        assertThat(account("GRNI", number)).isEqualByComparingTo("300");
        assertThat(account("RAW_INVENTORY", number)).isEqualByComparingTo("-300");
        assertThat(account("ACCOUNTS_PAYABLE", number)).isZero();
        var posted = row("SELECT * FROM supplier_returns WHERE tenant_id=? AND number=?", t, number);
        assertThat(posted).containsEntry("debit_note", "DBN-1").containsEntry("supplier_id", supplier);
        assertThat(dec(posted.get("clearing_quantity"))).isEqualByComparingTo("30");
        assertThat(dec(posted.get("payable_amount"))).isZero();
        assertThat(dec(receiptOfLot(lot).get("returned"))).isEqualByComparingTo("30");
        assertThat(dec(poLine(po, 1).get("returned"))).isEqualByComparingTo("30");
        assertThat(dec(poLine(po, 1).get("received"))).isEqualByComparingTo("100");
        assertThat(header(po).get("status")).isEqualTo("RECEIVED");

        // The invoice covers what was kept and releases exactly the clearing that is left.
        invoices.match(admin, po, invoice("INV-R1", invoiceLine(receipt, "70", "10")), id());
        assertThat(account("GRNI", null)).isZero();
        assertThat(account("ACCOUNTS_PAYABLE", null)).isEqualByComparingTo("-700");
        assertThat(account("RAW_INVENTORY", null)).isEqualByComparingTo("700");
        assertThat(journalImbalance()).isZero();
        assertThat(ledgerReconciles()).isTrue();
        assertThat(rows(
                        "SELECT action FROM audit_events WHERE tenant_id=? AND reference=?",
                        t,
                        header(po).get("number")))
                .extracting(r -> r.get("action"))
                .contains("SUPPLIER_RETURN_POSTED");
    }

    @Test
    void returnAfterInvoicingIsSettledAgainstPayablesByTheDebitNote() {
        String po = approved(line(resin, "100", null, "10"));
        String receipt = releasedReceipt(po, 1, "100", "RET-B");
        invoices.match(admin, po, invoice("INV-R2", invoiceLine(receipt, "60", "10")), id());
        var result = returns.returnToSupplier(warehouse, receipt, back("50", "DBN-2"), id());
        assertThat(dec(result.get("clearingAmount"))).isEqualByComparingTo("400"); // the 40 kg never invoiced
        assertThat(dec(result.get("payableAmount"))).isEqualByComparingTo("100"); // 10 kg already invoiced
        String number = str(result, "number");
        assertThat(account("GRNI", number)).isEqualByComparingTo("400");
        assertThat(account("ACCOUNTS_PAYABLE", number)).isEqualByComparingTo("100");
        assertThat(account("RAW_INVENTORY", number)).isEqualByComparingTo("-500");
        var r = receiptOfLot(lotOf(receipt));
        assertThat(dec(r.get("returned"))).isEqualByComparingTo("50");
        assertThat(dec(r.get("credited"))).isEqualByComparingTo("10");
        // Nothing is left to invoice; clearing is fully released and payables equal the 50 kg kept.
        assertThatThrownBy(() -> invoices.match(admin, po, invoice("INV-R3", invoiceLine(receipt, "1", "10")), id()))
                .hasMessageContaining("already fully invoiced");
        assertThat(account("GRNI", null)).isZero();
        assertThat(account("ACCOUNTS_PAYABLE", null)).isEqualByComparingTo("-500");
        assertThat(journalImbalance()).isZero();
    }

    @Test
    void returnsLeaveAtTheLotBalanceValueAndReconcileExactly() {
        // 1 box = 3 pcs at Rs 31: 10.333333 per piece, the 9 pieces were received at 92.999997.
        String po = approved(line(carton, "3", "box", "31"));
        String receipt = releasedReceipt(po, 1, "9", null);
        String lot = lotOf(receipt);
        var first = returns.returnToSupplier(warehouse, receipt, back("4", "DBN-3"), id());
        assertThat(dec(first.get("value"))).isEqualByComparingTo("41.333332");
        var rest = returns.returnToSupplier(warehouse, receipt, back("5", "DBN-4"), id());
        assertThat(dec(rest.get("value"))).isEqualByComparingTo("51.666665"); // the exact remaining value
        assertThat(stock(lot)).isZero();
        assertThat(stockValue(lot)).isZero();
        assertThat(account("GRNI", null)).isZero();
        assertThat(account("RAW_INVENTORY", null)).isZero();
        assertThat(ledgerReconciles()).isTrue();
        assertThatThrownBy(() -> returns.returnToSupplier(warehouse, receipt, back("1", "DBN-5"), id()))
                .hasMessageContaining("Return exceeds the received quantity not yet returned (0 pcs)");
    }

    @Test
    void returnRulesRolesIdempotencyAndHistory() throws Exception {
        String po = approved(line(resin, "100", null, "10"), line(carton, "10", null, "2"));
        var received = receive(
                po, receiptLine(lineId(po, 1), "100", null, "RET-C"), receiptLine(lineId(po, 2), "10", null, "RET-D"));
        String resinReceipt = str(receipts(received).get(0), "id");
        String cartonReceipt = str(receipts(received).get(1), "id");
        // Quarantined stock goes back through the incoming inspection, not a supplier return.
        assertThatThrownBy(() -> returns.returnToSupplier(warehouse, resinReceipt, back("1", "DBN"), id()))
                .hasMessageContaining("awaiting its incoming inspection");
        inspect(lotOf(resinReceipt), "PASSED");
        inspect(lotOf(cartonReceipt), "FAILED");
        assertThatThrownBy(() -> returns.returnToSupplier(warehouse, cartonReceipt, back("1", "DBN"), id()))
                .hasMessageContaining("was rejected at incoming inspection");
        // Reserved stock is not available for a return.
        update("UPDATE stock_balances SET reserved=95 WHERE tenant_id=? AND lot_id=?", t, lotOf(resinReceipt));
        assertThatThrownBy(() -> returns.returnToSupplier(warehouse, resinReceipt, back("10", "DBN"), id()))
                .hasMessageContaining("Only 5 kg of lot RET-C is available");
        update("UPDATE stock_balances SET reserved=0 WHERE tenant_id=? AND lot_id=?", t, lotOf(resinReceipt));
        assertThatThrownBy(() -> returns.returnToSupplier(warehouse, resinReceipt, back("101", "DBN"), id()))
                .hasMessageContaining("Return exceeds the received quantity not yet returned (100 kg)");
        assertThatThrownBy(() -> returns.returnToSupplier(planner, resinReceipt, back("1", "DBN"), id()))
                .isInstanceOf(AccessDeniedException.class);
        command(viewer, "/api/purchases/receipts/" + resinReceipt + "/returns", back("1", "DBN"))
                .andExpect(status().isForbidden());
        command(warehouse, "/api/purchases/receipts/" + resinReceipt + "/returns", Map.of("quantity", "1"))
                .andExpect(status().isBadRequest());
        var stranger = createUser(createTenant("Return stranger").id(), "ADMIN");
        command(stranger, "/api/purchases/receipts/" + resinReceipt + "/returns", back("1", "DBN"))
                .andExpect(status().isNotFound());

        String key = id();
        var first =
                json(command(warehouse, "/api/purchases/receipts/" + resinReceipt + "/returns", back("5", "DBN-9"), key)
                        .andExpect(status().isOk()));
        var replay =
                json(command(warehouse, "/api/purchases/receipts/" + resinReceipt + "/returns", back("5", "DBN-9"), key)
                        .andExpect(status().isOk()));
        assertThat(replay.get("id")).isEqualTo(first.get("id"));
        command(warehouse, "/api/purchases/receipts/" + resinReceipt + "/returns", back("6", "DBN-9"), key)
                .andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM supplier_returns WHERE tenant_id=?", t))
                .isEqualTo(1);
        assertThat(stock(lotOf(resinReceipt))).isEqualByComparingTo("95");

        // Posted returns are immutable history; the read model lists them.
        assertThatThrownBy(() -> update("UPDATE supplier_returns SET quantity=1 WHERE tenant_id=?", t))
                .hasMessageContaining("immutable");
        var page = json(query(viewer, "/api/purchases/returns?q=DBN-9"));
        assertThat(ids(page.get("items"))).containsExactly(first.get("id"));
        var detail = json(query(viewer, "/api/purchases/orders/" + po));
        @SuppressWarnings("unchecked")
        var receiptRows = (List<Map<String, Object>>) detail.get("receipts");
        var resinRow = receiptRows.stream()
                .filter(r -> resinReceipt.equals(r.get("id")))
                .findFirst()
                .orElseThrow();
        assertThat(dec(resinRow.get("returnable"))).isEqualByComparingTo("95");
        assertThat(dec(resinRow.get("invoiceable"))).isEqualByComparingTo("95");
        assertThat(resinRow.get("inspection_status")).isEqualTo("PASSED");
        assertThat(((List<?>) detail.get("returns"))).hasSize(1);
        assertThat(journalImbalance()).isZero();
    }
}
