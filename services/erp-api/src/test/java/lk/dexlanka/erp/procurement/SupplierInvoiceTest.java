package lk.dexlanka.erp.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class SupplierInvoiceTest extends ProcurementFixture {
    @Test
    void invoiceLinesShowAndPostQuantityAndPriceVariances() throws Exception {
        String po = approved(line(resin, "100", null, "10"), line(carton, "50", null, "2"));
        var received = receive(
                po, receiptLine(lineId(po, 1), "100", null, null), receiptLine(lineId(po, 2), "50", null, null));
        String resinReceipt = str(receipts(received).get(0), "id");
        String cartonReceipt = str(receipts(received).get(1), "id");
        inspect(str(receipts(received).get(0), "lotId"), "PASSED");
        inspect(str(receipts(received).get(1), "lotId"), "PASSED");

        // Resin billed at 10.50 instead of 10 (price variance), only 40 of 50 cartons billed (quantity variance).
        var result = json(command(
                        admin,
                        "/api/purchases/orders/" + po + "/invoices",
                        invoice(
                                "SUP-INV-1",
                                invoiceLine(resinReceipt, "100", "10.5"),
                                invoiceLine(cartonReceipt, "40", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VARIANCE")));
        var inv = row("SELECT * FROM supplier_invoices WHERE tenant_id=? AND id=?", t, result.get("id"));
        assertThat(dec(inv.get("amount"))).isEqualByComparingTo("1130");
        assertThat(dec(inv.get("matched_amount"))).isEqualByComparingTo("1080");
        assertThat(dec(inv.get("price_variance"))).isEqualByComparingTo("50");
        assertThat(inv).containsEntry("supplier_id", supplier).containsEntry("supplier_reference", "SUP-INV-1");
        var lines = rows(
                "SELECT * FROM supplier_invoice_lines WHERE tenant_id=? AND invoice_id=? ORDER BY line_no",
                t,
                inv.get("id"));
        assertThat(dec(lines.get(0).get("price_variance"))).isEqualByComparingTo("50");
        assertThat(dec(lines.get(0).get("quantity_variance"))).isZero();
        assertThat(dec(lines.get(1).get("quantity_variance"))).isEqualByComparingTo("-10");
        assertThat(dec(lines.get(1).get("open_quantity"))).isEqualByComparingTo("50");
        // GRNI is released at the receipt cost, payables carry what the supplier billed, the difference is a variance.
        assertThat(account("GRNI", "SUP-INV-1")).isEqualByComparingTo("1080");
        assertThat(account("ACCOUNTS_PAYABLE", "SUP-INV-1")).isEqualByComparingTo("-1130");
        assertThat(account("PURCHASE_PRICE_VARIANCE", "SUP-INV-1")).isEqualByComparingTo("50");
        assertThat(dec(receiptOfLot(str(receipts(received).get(1), "lotId")).get("invoiced")))
                .isEqualByComparingTo("40");

        // The 10 cartons still open are billed later, slightly cheaper: the residual clearing is released exactly.
        var second = invoices.match(admin, po, invoice("SUP-INV-2", invoiceLine(cartonReceipt, "10", "1.9")), id());
        assertThat(second.get("status")).isEqualTo("VARIANCE");
        assertThat(account("GRNI", "SUP-INV-2")).isEqualByComparingTo("20");
        assertThat(account("ACCOUNTS_PAYABLE", "SUP-INV-2")).isEqualByComparingTo("-19");
        assertThat(account("PURCHASE_PRICE_VARIANCE", "SUP-INV-2")).isEqualByComparingTo("-1");
        assertThat(account("GRNI", null)).isZero(); // every receipt is fully cleared
        assertThat(account("ACCOUNTS_PAYABLE", null)).isEqualByComparingTo("-1149");
        assertThat(journalImbalance()).isZero();

        // Over-billing, duplicates and unreleased receipts are refused.
        assertThatThrownBy(() ->
                        invoices.match(admin, po, invoice("SUP-INV-3", invoiceLine(cartonReceipt, "1", "2")), id()))
                .hasMessageContaining("is already fully invoiced");
        assertThatThrownBy(() ->
                        invoices.match(admin, po, invoice("sup-inv-1", invoiceLine(resinReceipt, "1", "2")), id()))
                .hasMessageContaining("Supplier invoice sup-inv-1 from this supplier is already recorded as");
        String other = approved(line(resin, "10", null, "10"));
        var pending = receive(other, receiptLine(lineId(other, 1), "10", null, null));
        String pendingReceipt = str(receipts(pending).getFirst(), "id");
        assertThatThrownBy(() -> invoices.match(
                        admin, other, invoice("SUP-INV-4", invoiceLine(pendingReceipt, "10", "10")), id()))
                .hasMessageContaining("is not quality-released");
        assertThatThrownBy(() ->
                        invoices.match(admin, other, invoice("SUP-INV-5", invoiceLine(resinReceipt, "1", "10")), id()))
                .hasMessageContaining("Record not found");
        inspect(str(receipts(pending).getFirst(), "lotId"), "PASSED");
        assertThatThrownBy(() -> invoices.match(
                        admin, other, invoice("SUP-INV-6", invoiceLine(pendingReceipt, "12", "10")), id()))
                .hasMessageContaining("Invoice quantity 12 kg exceeds the 10 kg received and not yet invoiced");

        // Posted invoices are immutable history.
        assertThatThrownBy(() ->
                        update("UPDATE supplier_invoices SET amount=0 WHERE tenant_id=? AND id=?", t, inv.get("id")))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> update(
                        "DELETE FROM supplier_invoice_lines WHERE tenant_id=? AND invoice_id=?", t, inv.get("id")))
                .hasMessageContaining("immutable");
    }

    @Test
    void exactMatchHasNoVarianceAndOnlyAdministratorsPost() throws Exception {
        String po = approved(line(carton, "9", "box", "100"));
        String receipt = releasedReceipt(po, 1, "27", null);
        var body = invoice("INV-EXACT", invoiceLine(receipt, "27", "33.333333"));
        command(planner, "/api/purchases/orders/" + po + "/invoices", body).andExpect(status().isForbidden());
        assertThatThrownBy(() -> invoices.match(warehouse, po, body, id())).isInstanceOf(AccessDeniedException.class);
        String key = id();
        var first = json(command(admin, "/api/purchases/orders/" + po + "/invoices", body, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MATCHED")));
        var replay = json(command(admin, "/api/purchases/orders/" + po + "/invoices", body, key));
        assertThat(replay.get("id")).isEqualTo(first.get("id"));
        assertThat(count("SELECT COUNT(*) FROM supplier_invoices WHERE tenant_id=?", t))
                .isEqualTo(1);
        // 27 pcs x 33.333333 equals the posted receipt value, so payables and clearing agree to the cent.
        assertThat(account("GRNI", "INV-EXACT")).isEqualByComparingTo("899.999991");
        assertThat(account("ACCOUNTS_PAYABLE", "INV-EXACT")).isEqualByComparingTo("-899.999991");
        assertThat(account("PURCHASE_PRICE_VARIANCE", null)).isZero();

        // Paged list with a variance filter; another tenant sees nothing.
        var page = json(query(viewer, "/api/purchases/invoices?status=MATCHED"));
        assertThat(ids(page.get("items"))).containsExactly(first.get("id"));
        assertThat(((List<?>) json(query(viewer, "/api/purchases/invoices?status=VARIANCE"))
                        .get("items")))
                .isEmpty();
        var stranger = createUser(createTenant("Invoice stranger").id(), "ADMIN");
        command(stranger, "/api/purchases/orders/" + po + "/invoices", invoice("X-1", invoiceLine(receipt, "1", "1")))
                .andExpect(status().isNotFound());
        var detail = json(query(viewer, "/api/purchases/orders/" + po));
        @SuppressWarnings("unchecked")
        var invoiceLines = (List<Map<String, Object>>) detail.get("invoiceLines");
        assertThat(invoiceLines).hasSize(1);
        assertThat(invoiceLines.getFirst()).containsEntry("item", "Carton");
    }

    @Test
    void originalReceiptMatchBecomesAMatchedInvoiceDocument() throws Exception {
        String po = approved(line(resin, "10", null, "12"));
        String receipt = releasedReceipt(po, 1, "10", "SUP-OLD");
        String key = id();
        command(admin, "/api/receipts/" + receipt + "/invoice", Map.of("reference", "INV-OLD"), key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice_reference").value("INV-OLD"));
        command(admin, "/api/receipts/" + receipt + "/invoice", Map.of("reference", "INV-OLD"), key)
                .andExpect(status().isOk());
        var inv = row("SELECT * FROM supplier_invoices WHERE tenant_id=? AND purchase_order_id=?", t, po);
        assertThat(inv).containsEntry("status", "MATCHED").containsEntry("supplier_reference", "INV-OLD");
        assertThat(dec(inv.get("amount"))).isEqualByComparingTo("120");
        assertThat(account("GRNI", "INV-OLD")).isEqualByComparingTo("120");
        assertThat(account("ACCOUNTS_PAYABLE", "INV-OLD")).isEqualByComparingTo("-120");
        assertThat(receiptOfLot(str(
                                row("SELECT lot_id FROM receipts WHERE tenant_id=? AND id=?", t, receipt), "lot_id"))
                        .get("invoice_reference"))
                .isEqualTo("INV-OLD");
        command(admin, "/api/receipts/" + receipt + "/invoice", Map.of("reference", "INV-AGAIN"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This receipt is already invoiced"));
        command(planner, "/api/receipts/" + receipt + "/invoice", Map.of("reference", "INV-P"))
                .andExpect(status().isForbidden());
    }
}
