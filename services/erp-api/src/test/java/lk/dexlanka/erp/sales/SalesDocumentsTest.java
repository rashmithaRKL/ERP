package lk.dexlanka.erp.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.manufacturing.ManufacturingCommands;
import lk.dexlanka.erp.manufacturing.ManufacturingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

class SalesDocumentsTest extends SalesFixture {
    @Autowired
    ManufacturingService mo;

    @Test
    void multiLineDraftIsEditableThenConfirmed() throws Exception {
        var created = json(command(
                        salesRep,
                        "/api/sales/orders",
                        order(line(bottle, "100", "25", "10"), line(cap, "200", "5", null)))
                .andExpect(status().isOk()));
        String so = str(created, "id");
        var h = header(so);
        assertThat(h.get("status")).isEqualTo("DRAFT");
        assertThat(dec(h.get("subtotal"))).isEqualByComparingTo("3500");
        assertThat(dec(h.get("discount_total"))).isEqualByComparingTo("250");
        assertThat(dec(h.get("total"))).isEqualByComparingTo("3250");
        assertThat(dec(salesLine(so, 1).get("line_total"))).isEqualByComparingTo("2250");
        String first = lineId(so, 1);

        // Edit: keep line 1 (new quantity), drop line 2, add a new line and change the header.
        var edit = new SalesCommands.Order(
                customer,
                "PO-456",
                LocalDate.now().plusDays(9).toString(),
                "Split deliveries allowed",
                List.of(
                        new SalesCommands.Line(first, bottle, bd("120"), bd("25"), bd("10")),
                        line(cap, "50", "4.5", "0")));
        command(salesRep, "/api/sales/orders/" + so, edit).andExpect(status().isOk());
        assertThat(lineId(so, 1)).isEqualTo(first);
        assertThat(dec(salesLine(so, 1).get("quantity"))).isEqualByComparingTo("120");
        assertThat(salesLine(so, 2).get("product_id")).isEqualTo(cap);
        assertThat(count("SELECT COUNT(*) FROM sales_order_lines WHERE tenant_id=? AND sales_order_id=?", t, so))
                .isEqualTo(2);
        h = header(so);
        assertThat(h.get("customer_reference")).isEqualTo("PO-456");
        assertThat(h.get("notes")).isEqualTo("Split deliveries allowed");
        assertThat(dec(h.get("total"))).isEqualByComparingTo("2925"); // 120*25*0.9 + 50*4.5

        var confirmed = json(
                command(salesRep, "/api/sales/orders/" + so + "/confirm", null).andExpect(status().isOk()));
        assertThat(confirmed.get("status")).isEqualTo("CONFIRMED");
        assertThat((List<?>) confirmed.get("warnings")).isEmpty();
        assertThat(header(so).get("confirmed_by")).isEqualTo(salesRep.id());
        assertThat(rows(
                        "SELECT action FROM audit_events WHERE tenant_id=? AND reference=? ORDER BY created_at,action",
                        t,
                        h.get("number")))
                .extracting(r -> r.get("action"))
                .contains("SO_DRAFT_CREATED", "SO_DRAFT_UPDATED", "SALE_CONFIRMED");
    }

    @Test
    void onlyDraftsAreEditableAndConfirmationNeedsLines() throws Exception {
        String empty = str(sales.create(salesRep, order(), id()), "id");
        command(salesRep, "/api/sales/orders/" + empty + "/confirm", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Add at least one line before confirming the order"));
        String so = confirmed(line(bottle, "10", "25", null));
        command(salesRep, "/api/sales/orders/" + so, order(line(bottle, "11", "25", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Only draft sales orders can be edited"));
        assertThatThrownBy(() -> sales.confirm(salesRep, so, id())).hasMessageContaining("Only draft");
        // A line id from another order cannot be smuggled into a draft.
        String other = draft(line(cap, "5", "2", null));
        var foreign = new SalesCommands.Order(
                customer,
                null,
                LocalDate.now().toString(),
                null,
                List.of(new SalesCommands.Line(lineId(so, 1), bottle, bd("1"), bd("1"), null)));
        assertThatThrownBy(() -> sales.update(salesRep, other, foreign, id())).hasMessageContaining("does not belong");
        // Whole pieces and finished goods only; invalid input is a 400.
        assertThatThrownBy(() -> sales.create(salesRep, order(line(bottle, "1.5", "2", null)), id()))
                .hasMessageContaining("whole numbers");
        command(salesRep, "/api/sales/orders", order(line(bottle, "1", "2", "150")))
                .andExpect(status().isBadRequest());
        assertThat(count("SELECT COUNT(*) FROM sales_order_lines WHERE tenant_id=? AND sales_order_id=?", t, other))
                .isEqualTo(1);
    }

    @Test
    void perLinePartialDispatchPostsRevenueReceivableAndCogsPerDelivery() {
        String so = confirmed(line(bottle, "100", "25", "10"), line(cap, "50", "5", null));
        String lotA = finishedLot(bottle, "60", "10");
        String lotB = finishedLot(bottle, "60", "12");
        String caps = finishedLot(cap, "80", "2");

        var first = dispatch(so, 1, "DN-1", lotA, "60", lotB, "20");
        assertThat(first.get("orderStatus")).isEqualTo("PARTIAL");
        assertThat((List<?>) first.get("deliveries")).hasSize(2);
        assertThat(rows(
                        "SELECT quantity,revenue_amount,cost_amount FROM deliveries WHERE tenant_id=? AND sales_order_id=? ORDER BY quantity DESC",
                        t,
                        so))
                .extracting(r -> dec(r.get("revenue_amount")).toPlainString() + "/" + dec(r.get("cost_amount")))
                .containsExactly("1350.000000/600.000000", "450.000000/240.000000");
        assertThat(account("SALES_REVENUE", "DN-1")).isEqualByComparingTo("-1800");
        assertThat(account("ACCOUNTS_RECEIVABLE", "DN-1")).isEqualByComparingTo("1800");
        assertThat(account("COST_OF_GOODS_SOLD", "DN-1")).isEqualByComparingTo("840");
        assertThat(account("FG_INVENTORY", "DN-1")).isEqualByComparingTo("-840");
        assertThat(stock(lotA)).isZero();
        assertThat(stock(lotB)).isEqualByComparingTo("40");
        assertThat(dec(salesLine(so, 1).get("delivered"))).isEqualByComparingTo("80");
        assertThat(header(so).get("status")).isEqualTo("PARTIAL");

        // The completing delivery absorbs any rounding; line revenue equals the line total.
        dispatch(so, 1, "DN-2", lotB, "20");
        assertThat(decimal(
                        "SELECT SUM(revenue_amount) FROM deliveries WHERE tenant_id=? AND sales_order_line_id=?",
                        t,
                        lineId(so, 1)))
                .isEqualByComparingTo(dec(salesLine(so, 1).get("line_total")));
        assertThat(header(so).get("status")).isEqualTo("PARTIAL");
        assertThatThrownBy(() -> dispatch(so, 2, "DN-3", caps, "51")).hasMessageContaining("outstanding");
        assertThatThrownBy(() -> dispatch(so, 2, "DN-3", lotB, "5")).hasMessageContaining("does not match");
        var done = dispatch(so, 2, "DN-3", caps, "50");
        assertThat(done.get("orderStatus")).isEqualTo("DELIVERED");
        assertThat(header(so).get("status")).isEqualTo("DELIVERED");
        assertThat(account("ACCOUNTS_RECEIVABLE", null)).isEqualByComparingTo("2500"); // 100*22.5 + 50*5
        assertThat(journalImbalance()).isZero();
        assertThat(count(
                        "SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='DISPATCH_POSTED' AND reference=?",
                        t,
                        header(so).get("number")))
                .isEqualTo(3);
    }

    @Test
    void heldLotsAndOverDispatchRollBackWithoutPostings() {
        String so = confirmed(line(bottle, "10", "25", null));
        String held = finishedLot(bottle, "10", "10");
        update("UPDATE lots SET status='QUARANTINE' WHERE tenant_id=? AND id=?", t, held);
        String good = finishedLot(bottle, "5", "10");
        long journals = count("SELECT COUNT(*) FROM journal_entries WHERE tenant_id=?", t);
        assertThatThrownBy(() -> dispatch(so, 1, "DN-H", good, "5", held, "5")).hasMessageContaining("Quality-held");
        assertThatThrownBy(() -> dispatch(so, 1, "DN-H", good, "6")).hasMessageContaining("Insufficient");
        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE tenant_id=?", t))
                .isEqualTo(journals);
        assertThat(stock(good)).isEqualByComparingTo("5");
        assertThat(count("SELECT COUNT(*) FROM deliveries WHERE tenant_id=?", t))
                .isZero();
        assertThat(dec(salesLine(so, 1).get("delivered"))).isZero();
    }

    @Test
    void cancellationRequiresNoDeliveryAndNoProductionInProgress() {
        String draft = draft(line(bottle, "10", "25", null));
        assertThat(sales.cancel(salesRep, draft, new SalesCommands.Reason("Duplicate entry"), id())
                        .get("status"))
                .isEqualTo("CANCELLED");
        assertThatThrownBy(() -> sales.cancel(salesRep, draft, new SalesCommands.Reason("Again"), id()))
                .hasMessageContaining("already cancelled");

        // Production started for the order: cancellation is blocked.
        String so = confirmed(line(bottle, "10", "25", null));
        openingStock(admin, resin, raw, "100", "10");
        String running = str(
                mo.create(
                        planner,
                        new ManufacturingCommands.Manufacture(
                                bom,
                                machine,
                                null,
                                bd("5"),
                                "NORMAL",
                                LocalDate.now().toString(),
                                lineId(so, 1)),
                        id()),
                "id");
        for (String step : List.of("approve", "reserve", "issue")) mo.transition(admin, running, step, id());
        assertThatThrownBy(() -> sales.cancel(salesRep, so, new SalesCommands.Reason("Customer withdrew"), id()))
                .hasMessageContaining("in progress");

        // Only a not-yet-issued order is linked: cancellation succeeds with a warning.
        String other = confirmed(line(bottle, "10", "25", null));
        mo.create(
                planner,
                new ManufacturingCommands.Manufacture(
                        bom, machine, null, bd("5"), "NORMAL", LocalDate.now().toString(), lineId(other, 1)),
                id());
        var cancelled = sales.cancel(salesRep, other, new SalesCommands.Reason("Customer withdrew"), id());
        assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
        assertThat((List<?>) cancelled.get("warnings")).hasSize(1);
        assertThat(header(other).get("status_reason")).isEqualTo("Customer withdrew");

        // Delivered goods: cancellation is blocked; close the remainder short instead.
        String partial = confirmed(line(bottle, "10", "25", null));
        dispatch(partial, 1, "DN-C", finishedLot(bottle, "4", "10"), "4");
        assertThatThrownBy(() -> sales.cancel(salesRep, partial, new SalesCommands.Reason("No"), id()))
                .hasMessageContaining("close the remaining quantity short");
    }

    @Test
    void closeShortNeedsPartialDeliveryAndAReason() throws Exception {
        String so = confirmed(line(bottle, "10", "25", null), line(cap, "5", "2", null));
        assertThatThrownBy(() -> sales.close(salesRep, so, new SalesCommands.Reason("Short"), id()))
                .hasMessageContaining("partially delivered");
        String lot = finishedLot(bottle, "10", "10");
        dispatch(so, 1, "DN-S", lot, "6");
        command(salesRep, "/api/sales/orders/" + so + "/close", Map.of("reason", " "))
                .andExpect(status().isBadRequest());
        command(salesRep, "/api/sales/orders/" + so + "/close", new SalesCommands.Reason("Customer accepts 6 pcs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        assertThat(header(so).get("status_reason")).isEqualTo("Customer accepts 6 pcs");
        assertThatThrownBy(() -> dispatch(so, 1, "DN-S2", lot, "1")).hasMessageContaining("Only confirmed");
        assertThat(rows("SELECT detail FROM audit_events WHERE tenant_id=? AND action='SO_CLOSED_SHORT'", t)
                        .getFirst()
                        .get("detail"))
                .asString()
                .contains("line 1 4 pcs", "line 2 5 pcs");
    }

    @Test
    void rolesAreEnforced() throws Exception {
        command(warehouse, "/api/sales/orders", order(line(bottle, "1", "1", null)))
                .andExpect(status().isForbidden());
        command(viewer, "/api/sales/orders", order(line(bottle, "1", "1", null)))
                .andExpect(status().isForbidden());
        String so = confirmed(line(bottle, "10", "25", null));
        assertThatThrownBy(() -> sales.dispatchLine(
                        salesRep,
                        so,
                        lineId(so, 1),
                        new SalesCommands.DispatchLine(
                                "DN", List.of(new SalesCommands.Allocation(finishedLot(bottle, "1", "1"), bd("1")))),
                        id()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> sales.cancel(operator, so, new SalesCommands.Reason("x"), id()))
                .isInstanceOf(AccessDeniedException.class);
        // PLANNER and ADMIN may run the document lifecycle; confirmation is not an approval, so the creator may
        // confirm their own order.
        String own = str(sales.create(planner, order(line(cap, "1", "1", null)), id()), "id");
        assertThat(sales.confirm(planner, own, id()).get("status")).isEqualTo("CONFIRMED");
        query(viewer, "/api/sales/orders/" + so).andExpect(status().isOk());
    }

    @Test
    void retriesReplayTheStoredResultAndKeyReuseIsRejected() throws Exception {
        var body = order(line(bottle, "10", "25", null));
        String key = id();
        var a = json(command(salesRep, "/api/sales/orders", body, key).andExpect(status().isOk()));
        var b = json(command(salesRep, "/api/sales/orders", body, key).andExpect(status().isOk()));
        assertThat(b.get("id")).isEqualTo(a.get("id"));
        assertThat(count("SELECT COUNT(*) FROM sales_orders WHERE tenant_id=?", t))
                .isEqualTo(1);
        command(salesRep, "/api/sales/orders", order(line(bottle, "11", "25", null)), key)
                .andExpect(status().isConflict());

        String so = str(a, "id");
        sales.confirm(salesRep, so, id());
        String lot = finishedLot(bottle, "10", "10");
        var dispatchBody = new SalesCommands.DispatchLine("DN-R", List.of(new SalesCommands.Allocation(lot, bd("4"))));
        String path = "/api/sales/orders/" + so + "/lines/" + lineId(so, 1) + "/dispatch";
        String dispatchKey = id();
        command(warehouse, path, dispatchBody, dispatchKey).andExpect(status().isOk());
        command(warehouse, path, dispatchBody, dispatchKey).andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM deliveries WHERE tenant_id=?", t))
                .isEqualTo(1);
        assertThat(stock(lot)).isEqualByComparingTo("6");
        assertThat(account("ACCOUNTS_RECEIVABLE", "DN-R")).isEqualByComparingTo("100");
    }

    @Test
    void anotherTenantsDocumentsAreNotFound() throws Exception {
        String so = confirmed(line(bottle, "10", "25", null));
        String lot = finishedLot(bottle, "10", "10");
        var other = createTenant("Other");
        var stranger = createUser(other.id(), "ADMIN");
        query(stranger, "/api/sales/orders/" + so).andExpect(status().isNotFound());
        command(stranger, "/api/sales/orders/" + so + "/confirm", null).andExpect(status().isNotFound());
        command(stranger, "/api/sales/orders/" + so + "/cancel", new SalesCommands.Reason("x"))
                .andExpect(status().isNotFound());
        command(
                        stranger,
                        "/api/sales/orders/" + so + "/lines/" + lineId(so, 1) + "/dispatch",
                        new SalesCommands.DispatchLine("DN", List.of(new SalesCommands.Allocation(lot, bd("1")))))
                .andExpect(status().isNotFound());
        // Our customer and product cannot be used from the other tenant.
        command(stranger, "/api/sales/orders", order(line(bottle, "1", "1", null)))
                .andExpect(status().isNotFound());
        assertThat(json(query(stranger, "/api/sales/orders").andExpect(status().isOk()))
                        .get("total"))
                .isEqualTo(0);
        assertThat(header(so).get("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void listIsKeysetPagedSearchableAndFilteredDetailHasEverything() throws Exception {
        String first = confirmed(line(bottle, "10", "25", null));
        String second = draft(line(cap, "10", "2", null));
        String third = confirmed(line(cap, "10", "2", null), line(bottle, "3", "25", null));
        update("UPDATE sales_orders SET created_at=now()-interval '3 minutes' WHERE tenant_id=? AND id=?", t, first);
        update("UPDATE sales_orders SET created_at=now()-interval '2 minutes' WHERE tenant_id=? AND id=?", t, second);
        update("UPDATE sales_orders SET created_at=now()-interval '1 minutes' WHERE tenant_id=? AND id=?", t, third);

        var page1 = json(query(viewer, "/api/sales/orders?limit=2").andExpect(status().isOk()));
        assertThat((List<?>) page1.get("items")).hasSize(2);
        assertThat(((Map<?, ?>) ((List<?>) page1.get("items")).getFirst()).get("id"))
                .isEqualTo(third);
        assertThat(page1.get("total")).isEqualTo(3);
        var page2 = json(query(viewer, "/api/sales/orders?limit=2&before=" + page1.get("next")));
        assertThat((List<?>) page2.get("items")).hasSize(1);
        assertThat(page2.get("next")).isEqualTo("");
        assertThat(((Map<?, ?>) ((List<?>) page2.get("items")).getFirst()).get("id"))
                .isEqualTo(first);

        var open = json(query(viewer, "/api/sales/orders?status=open"));
        assertThat((List<?>) open.get("items")).hasSize(2);
        assertThat(((Map<?, ?>) open.get("counts")).get("DRAFT")).isEqualTo(1);
        var drafts = json(query(viewer, "/api/sales/orders?status=DRAFT"));
        assertThat((List<?>) drafts.get("items")).hasSize(1);
        var search = json(query(viewer, "/api/sales/orders?q=FG-C"));
        assertThat((List<?>) search.get("items")).hasSize(2);
        var mixed = (Map<?, ?>) ((List<?>) search.get("items")).getFirst();
        assertThat(mixed.get("line_count")).isEqualTo(2);
        assertThat(mixed.get("products")).isEqualTo("Cap, Bottle");
        query(viewer, "/api/sales/orders?status=BOGUS").andExpect(status().isConflict());

        dispatch(first, 1, "DN-D", finishedLot(bottle, "10", "10"), "4");
        var detail = json(query(viewer, "/api/sales/orders/" + first).andExpect(status().isOk()));
        assertThat(((Map<?, ?>) detail.get("order")).get("customer")).isEqualTo("Customer Co");
        assertThat((List<?>) detail.get("lines")).hasSize(1);
        assertThat((List<?>) detail.get("deliveries")).hasSize(1);
        assertThat((List<?>) detail.get("lots")).hasSize(1);
        assertThat(values(detail.get("activity"), "action"))
                .contains("SO_DRAFT_CREATED", "SALE_CONFIRMED", "DISPATCH_POSTED");
        assertThat(((Map<?, ?>) detail.get("credit")).get("exceeded")).isEqualTo(false);
    }

    @Test
    void manufacturingOrdersLinkToAConfirmedSalesLine() {
        String so = confirmed(line(cap, "5", "2", null), line(bottle, "10", "25", null));
        String bottleLine = lineId(so, 2);
        var created = mo.create(
                planner,
                new ManufacturingCommands.Manufacture(
                        bom, machine, null, bd("6"), "NORMAL", LocalDate.now().toString(), bottleLine),
                id());
        assertThat(created.get("sales_order_id")).isEqualTo(so);
        assertThat(created.get("sales_order_line_id")).isEqualTo(bottleLine);
        assertThatThrownBy(() -> mo.create(
                        planner,
                        new ManufacturingCommands.Manufacture(
                                bom,
                                machine,
                                null,
                                bd("5"),
                                "NORMAL",
                                LocalDate.now().toString(),
                                bottleLine),
                        id()))
                .hasMessageContaining("exceeds unplanned");
        assertThatThrownBy(() -> mo.create(
                        planner,
                        new ManufacturingCommands.Manufacture(
                                bom,
                                machine,
                                null,
                                bd("1"),
                                "NORMAL",
                                LocalDate.now().toString(),
                                lineId(so, 1)),
                        id()))
                .hasMessageContaining("BOM product differ");
        // The original contract (salesOrderId only) resolves to the order's line of the BOM product.
        var legacy = mo.create(
                planner,
                new ManufacturingCommands.Manufacture(
                        bom, machine, so, bd("4"), "NORMAL", LocalDate.now().toString()),
                id());
        assertThat(legacy.get("sales_order_line_id")).isEqualTo(bottleLine);
        String draftOrder = draft(line(bottle, "10", "25", null));
        assertThatThrownBy(() -> mo.create(
                        planner,
                        new ManufacturingCommands.Manufacture(
                                bom,
                                machine,
                                null,
                                bd("1"),
                                "NORMAL",
                                LocalDate.now().toString(),
                                lineId(draftOrder, 1)),
                        id()))
                .hasMessageContaining("confirmed, open sales orders");
        assertThatThrownBy(() -> mo.create(
                        planner,
                        new ManufacturingCommands.Manufacture(
                                bom,
                                machine,
                                draftOrder,
                                bd("1"),
                                "NORMAL",
                                LocalDate.now().toString(),
                                bottleLine),
                        id()))
                .hasMessageContaining("different sales order");
    }

    @Test
    void confirmationWarnsWhenTheCreditLimitIsExceeded() {
        update("UPDATE partners SET credit_limit=500 WHERE tenant_id=? AND id=?", t, customer);
        String small = confirmed(line(bottle, "10", "25", null)); // 250 of 500
        dispatch(small, 1, "DN-CL", finishedLot(bottle, "10", "10"), "10"); // receivables 250
        String big = draft(line(bottle, "12", "25", null)); // 250 + 300 > 500
        var result = sales.confirm(salesRep, big, id());
        assertThat(result.get("status")).isEqualTo("CONFIRMED");
        assertThat((List<?>) result.get("warnings"))
                .singleElement()
                .asString()
                .contains("Credit limit exceeded", "Rs. 250.00", "Rs. 300.00", "Rs. 500.00");
        assertThat(rows("SELECT detail FROM audit_events WHERE tenant_id=? AND action='SALE_CONFIRMED'", t))
                .extracting(r -> r.get("detail").toString())
                .anyMatch(d -> d.contains("Credit limit exceeded"));
    }

    @Test
    void originalSingleItemEndpointsStillWork() throws Exception {
        var sale = json(command(
                        salesRep,
                        "/api/sales",
                        new SalesCommands.Sale(
                                customer,
                                bottle,
                                bd("10"),
                                bd("25"),
                                LocalDate.now().toString()))
                .andExpect(status().isOk()));
        String so = str(sale, "id");
        assertThat(sale.get("status")).isEqualTo("CONFIRMED");
        assertThat(dec(header(so).get("total"))).isEqualByComparingTo("250");
        String lot = finishedLot(bottle, "10", "10");
        // No lineId in the JSON body: the only open line of the lot's product is used.
        command(
                        warehouse,
                        "/api/sales/" + so + "/dispatch",
                        Map.of("lotId", lot, "quantity", "10", "reference", "DN-L"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPATCHED"));
        assertThat(row("SELECT sales_order_line_id FROM deliveries WHERE tenant_id=? AND sales_order_id=?", t, so)
                        .get("sales_order_line_id"))
                .isEqualTo(lineId(so, 1));
        assertThat(header(so).get("status")).isEqualTo("DELIVERED");
        assertThat(account("SALES_REVENUE", "DN-L")).isEqualByComparingTo("-250");
    }
}
