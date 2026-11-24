package lk.dexlanka.erp.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

class PurchaseOrderDocumentsTest extends ProcurementFixture {
    @Test
    void multiLineDraftIsEditedSubmittedAndIndependentlyApproved() throws Exception {
        var created = json(command(
                        planner,
                        "/api/purchases/orders",
                        order(supplier, line(resin, "4", "bag", "12000"), line(carton, "300", null, "30")))
                .andExpect(status().isOk()));
        String po = str(created, "id");
        var h = header(po);
        assertThat(h.get("status")).isEqualTo("DRAFT");
        assertThat(h.get("supplier_reference")).isEqualTo("Q-100");
        assertThat(dec(h.get("total"))).isEqualByComparingTo("57000"); // 4 x 12000 + 300 x 30
        var resinLine = poLine(po, 1);
        assertThat(resinLine.get("unit")).isEqualTo("bag");
        assertThat(dec(resinLine.get("factor"))).isEqualByComparingTo("25");
        assertThat(dec(resinLine.get("base_quantity"))).isEqualByComparingTo("100");
        assertThat(dec(resinLine.get("base_unit_cost"))).isEqualByComparingTo("480");
        assertThat(poLine(po, 2).get("unit")).isEqualTo("pcs");
        String first = lineId(po, 1);

        // Edit: keep line 1 (new quantity), drop line 2, add a line with its own expected date, change the header.
        var edit = new ProcurementCommands.Order(
                supplier2,
                "Q-200",
                LocalDate.now().plusDays(10).toString(),
                "Deliver to gate 2",
                List.of(
                        new ProcurementCommands.OrderLine(first, resin, bd("6"), "bag", bd("11900"), null),
                        new ProcurementCommands.OrderLine(
                                null,
                                carton,
                                bd("10"),
                                "box",
                                bd("95"),
                                LocalDate.now().plusDays(3).toString())));
        command(planner, "/api/purchases/orders/" + po, edit).andExpect(status().isOk());
        assertThat(lineId(po, 1)).isEqualTo(first);
        assertThat(dec(poLine(po, 1).get("base_quantity"))).isEqualByComparingTo("150");
        assertThat(dec(poLine(po, 2).get("base_quantity"))).isEqualByComparingTo("30");
        assertThat(poLine(po, 2).get("expected_date").toString())
                .isEqualTo(LocalDate.now().plusDays(3).toString());
        h = header(po);
        assertThat(h.get("supplier_id")).isEqualTo(supplier2);
        assertThat(h.get("notes")).isEqualTo("Deliver to gate 2");
        assertThat(dec(h.get("total"))).isEqualByComparingTo("72350"); // 6 x 11900 + 10 x 95

        command(planner, "/api/purchases/orders/" + po + "/submit", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        command(planner, "/api/purchases/orders/" + po, edit)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Only draft purchase orders can be edited"));
        // Self-approval is refused (creator and submitter), a warehouse user may not approve at all.
        command(planner, "/api/purchases/orders/" + po + "/approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A different authorized person must approve this purchase"));
        command(warehouse, "/api/purchases/orders/" + po + "/approve", null).andExpect(status().isForbidden());
        command(planner2, "/api/purchases/orders/" + po + "/approve", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        h = header(po);
        assertThat(h.get("approved_by")).isEqualTo(planner2.id());
        assertThat(h.get("submitted_by")).isEqualTo(planner.id());
        assertThat(rows("SELECT action FROM audit_events WHERE tenant_id=? AND reference=?", t, h.get("number")))
                .extracting(r -> r.get("action"))
                .contains("PO_DRAFT_CREATED", "PO_DRAFT_UPDATED", "PO_SUBMITTED", "PO_APPROVED");
    }

    @Test
    void administratorsCannotApproveTheirOwnOrders() {
        String po = str(orders.create(admin, order(supplier, line(resin, "10", null, "480")), id()), "id");
        orders.submit(admin, po, id());
        assertThatThrownBy(() -> orders.approve(admin, po, id())).hasMessageContaining("A different authorized person");
        // A draft must be submitted first, and somebody else who submitted it cannot approve either.
        String other = draft(line(resin, "10", null, "480"));
        assertThatThrownBy(() -> orders.approve(planner2, other, id())).hasMessageContaining("Submit the draft");
        orders.submit(planner2, other, id());
        assertThatThrownBy(() -> orders.approve(planner2, other, id())).hasMessageContaining("different authorized");
        assertThat(orders.approve(admin, other, id()).get("status")).isEqualTo("APPROVED");
    }

    @Test
    void unitConvertedLinesAreExactInTheBaseUnit() {
        // 1 box = 3 pcs at Rs 100 a box: the base unit cost is rounded at 6 dp, the line total stays exact.
        // 1500 g of resin is 1.5 kg through the standard mass ratio.
        String po = draft(line(carton, "7", "box", "100"), line(resin, "1500", "g", "0.5"));
        var cartons = poLine(po, 1);
        assertThat(dec(cartons.get("base_quantity"))).isEqualByComparingTo("21");
        assertThat(dec(cartons.get("base_unit_cost"))).isEqualByComparingTo("33.333333");
        assertThat(dec(cartons.get("line_total"))).isEqualByComparingTo("700");
        var grams = poLine(po, 2);
        assertThat(dec(grams.get("factor"))).isEqualByComparingTo("0.001");
        assertThat(dec(grams.get("base_quantity"))).isEqualByComparingTo("1.5");
        assertThat(dec(grams.get("base_unit_cost"))).isEqualByComparingTo("500");
        assertThat(dec(header(po).get("total"))).isEqualByComparingTo("1450");

        // Whole boxes only, sales-only and unknown units are refused, and pieces stay whole.
        assertThatThrownBy(() -> draft(line(carton, "2.5", "box", "100")))
                .hasMessageContaining("allows at most 0 decimal places");
        assertThatThrownBy(() -> draft(line(carton, "1", "pallet", "100")))
                .hasMessageContaining("is not purchased in pallet");
        assertThatThrownBy(() -> draft(line(carton, "1", "barrel", "100"))).hasMessageContaining("Unknown unit");
        assertThatThrownBy(() -> draft(line(carton, "1.5", null, "100"))).hasMessageContaining("at most 0 decimal");
        assertThatThrownBy(() -> draft(line(resin, "1", "pcs", "100"))).hasMessageContaining("No conversion");
    }

    @Test
    void draftsMayOmitTheSupplierButSubmissionNeedsOneAndLines() throws Exception {
        String po = str(orders.create(planner, order(null, line(resin, "10", null, "480")), id()), "id");
        assertThat(header(po).get("supplier_id")).isNull();
        command(planner, "/api/purchases/orders/" + po + "/submit", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Choose a supplier before the order is submitted"));
        String empty = str(orders.create(planner, order(supplier), id()), "id");
        assertThatThrownBy(() -> orders.submit(planner, empty, id())).hasMessageContaining("Add at least one line");
        // A supplier is required for anything but a draft, also in the database.
        assertThatThrownBy(
                        () -> update("UPDATE purchase_orders SET status='SUBMITTED' WHERE tenant_id=? AND id=?", t, po))
                .isInstanceOf(DataIntegrityViolationException.class);
        orders.update(planner, po, order(supplier, line(resin, "10", null, "480")), id());
        assertThat(orders.submit(planner, po, id()).get("status")).isEqualTo("SUBMITTED");
    }

    @Test
    void inactiveSuppliersAndItemsAreRejectedWithAClearMessage() throws Exception {
        update("UPDATE partners SET active=false WHERE tenant_id=? AND id=?", t, supplier2);
        command(planner, "/api/purchases/orders", order(supplier2, line(resin, "1", null, "1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Supplier Pack Co is inactive; inactive suppliers cannot be used on new purchase"
                                + " orders. Reactivate the supplier or choose another one"));
        assertThatThrownBy(() -> draft(line(bottle, "1", null, "1"))).hasMessageContaining("is a finished good");
        assertThatThrownBy(() -> orders.create(planner, order(customer, line(resin, "1", null, "1")), id()))
                .hasMessageContaining("is a customer");

        // A supplier or item deactivated after drafting blocks submission and approval.
        String po = draft(line(resin, "1", null, "1"), line(carton, "3", null, "1"));
        update("UPDATE items SET active=false WHERE tenant_id=? AND id=?", t, carton);
        assertThatThrownBy(() -> orders.submit(planner, po, id()))
                .hasMessageContaining("SKU PK-1 on line 2 is inactive");
        assertThatThrownBy(() -> draft(line(carton, "3", null, "1")))
                .hasMessageContaining("SKU PK-1 is inactive; inactive items cannot be used on new purchase orders");
        update("UPDATE items SET active=true WHERE tenant_id=? AND id=?", t, carton);
        orders.submit(planner, po, id());
        update("UPDATE partners SET active=false WHERE tenant_id=? AND id=?", t, supplier);
        assertThatThrownBy(() -> orders.approve(planner2, po, id())).hasMessageContaining("is inactive");
        // The database still refuses an inactive supplier if a code path forgot the check.
        assertThatThrownBy(() -> update(
                        "INSERT INTO purchase_orders(id,tenant_id,number,supplier_id,due_date,created_by) VALUES(?,?,'PO-X',?,CURRENT_DATE,?)",
                        id(),
                        t,
                        supplier,
                        planner.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Inactive partners");
    }

    @Test
    void cancelAndCloseShortRules() throws Exception {
        // A warehouse user may discard a draft but not cancel a submitted order.
        String mine = str(orders.create(warehouse, order(supplier, line(resin, "1", null, "1")), id()), "id");
        assertThat(orders.cancel(warehouse, mine, new ProcurementCommands.Reason("Duplicate"), id())
                        .get("status"))
                .isEqualTo("CANCELLED");
        assertThatThrownBy(() -> orders.submit(warehouse, mine, id())).hasMessageContaining("Only draft");
        String submitted = submitted(line(resin, "1", null, "1"));
        assertThatThrownBy(() -> orders.cancel(warehouse, submitted, new ProcurementCommands.Reason("No"), id()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(orders.cancel(planner, submitted, new ProcurementCommands.Reason("Price changed"), id())
                        .get("status"))
                .isEqualTo("CANCELLED");
        assertThat(header(submitted).get("status_reason")).isEqualTo("Price changed");

        // Approved and partially received: cancelling is refused, closing short ends the remainder.
        String po = approved(line(resin, "100", null, "480"), line(carton, "30", null, "30"));
        command(planner, "/api/purchases/orders/" + po + "/close", Map.of("reason", "Early"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Only partially received purchase orders can be closed short"));
        receive(po, receiptLine(lineId(po, 1), "40", null, "L-" + id().substring(0, 8)));
        assertThat(header(po).get("status")).isEqualTo("PARTIAL");
        command(planner, "/api/purchases/orders/" + po + "/cancel", Map.of("reason", "No longer needed"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Purchase orders with received goods cannot be cancelled; close the remaining quantity"
                                + " short instead"));
        assertThat(supply.openSupplyByItem(t)).containsKeys(resin, carton);
        command(warehouse, "/api/purchases/orders/" + po + "/close", Map.of("reason", "Supplier out of stock"))
                .andExpect(status().isForbidden());
        command(planner, "/api/purchases/orders/" + po + "/close", Map.of("reason", "Supplier out of stock"))
                .andExpect(status().isOk());
        var h = header(po);
        assertThat(h.get("status")).isEqualTo("CLOSED");
        assertThat(h.get("closed_by")).isEqualTo(planner.id());
        assertThat(supply.openSupplyByItem(t)).doesNotContainKeys(resin, carton);
        assertThat(row(
                                "SELECT detail FROM audit_events WHERE tenant_id=? AND action='PO_CLOSED_SHORT' AND reference=?",
                                t,
                                h.get("number"))
                        .get("detail"))
                .isEqualTo("line 1 60 kg, line 2 30 pcs not received: Supplier out of stock");
        assertThatThrownBy(() -> receive(po, receiptLine(lineId(po, 1), "1", null, null)))
                .hasMessageContaining("Only approved, outstanding purchases");
    }

    @Test
    void permissionsIdempotencyAndTenantIsolation() throws Exception {
        var body = order(supplier, line(resin, "2", "bag", "12000"));
        command(viewer, "/api/purchases/orders", body).andExpect(status().isForbidden());
        command(salesRep, "/api/purchases/orders", body).andExpect(status().isForbidden());
        String key = id();
        var first = json(command(planner, "/api/purchases/orders", body, key).andExpect(status().isOk()));
        var replay = json(command(planner, "/api/purchases/orders", body, key).andExpect(status().isOk()));
        assertThat(replay.get("id")).isEqualTo(first.get("id"));
        assertThat(count("SELECT COUNT(*) FROM purchase_orders WHERE tenant_id=?", t))
                .isEqualTo(1);
        command(planner, "/api/purchases/orders", order(supplier, line(resin, "3", "bag", "12000")), key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Idempotency key was already used for a different request"));
        // A failed command leaves nothing behind (the line error rolls back the header and the key).
        command(
                        planner,
                        "/api/purchases/orders",
                        order(supplier, line(resin, "1", null, "1"), line(bottle, "1", null, "1")))
                .andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM purchase_orders WHERE tenant_id=?", t))
                .isEqualTo(1);
        // Invalid input is a 400 before any rule runs.
        command(
                        planner,
                        "/api/purchases/orders",
                        Map.of("supplierId", supplier, "dueDate", "tomorrow", "lines", List.of()))
                .andExpect(status().isBadRequest());

        String po = str(first, "id");
        var other = createTenant("Other procurement tenant");
        var stranger = createUser(other.id(), "ADMIN");
        query(stranger, "/api/purchases/orders/" + po).andExpect(status().isNotFound());
        command(stranger, "/api/purchases/orders/" + po + "/submit", null).andExpect(status().isNotFound());
        command(stranger, "/api/purchases/orders/" + po, body).andExpect(status().isNotFound());
        assertThat(((Number) json(query(stranger, "/api/purchases/orders?status=all"))
                                .get("total"))
                        .longValue())
                .isZero();
        // Another tenant's supplier or item cannot be referenced.
        command(stranger, "/api/purchases/orders", order(supplier, line(resin, "1", null, "1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void readModelPagesSearchesAndCountsOrders() throws Exception {
        String a = draft(line(resin, "1", null, "480"));
        String b = submitted(line(carton, "3", null, "30"));
        String c = approved(line(resin, "2", "bag", "12000"), line(carton, "6", "box", "90"));
        var all = json(query(viewer, "/api/purchases/orders?status=all&limit=2").andExpect(status().isOk()));
        assertThat(((List<?>) all.get("items"))).hasSize(2);
        assertThat(((Number) all.get("total")).longValue()).isEqualTo(3);
        @SuppressWarnings("unchecked")
        var counts = (Map<String, Object>) all.get("counts");
        assertThat(counts)
                .containsEntry("DRAFT", 1)
                .containsEntry("SUBMITTED", 1)
                .containsEntry("APPROVED", 1);
        var next = json(query(viewer, "/api/purchases/orders?status=all&limit=2&before=" + all.get("next")));
        assertThat(ids(next.get("items"))).containsExactly(a);
        assertThat(ids(json(query(viewer, "/api/purchases/orders?status=action"))
                        .get("items")))
                .containsExactlyInAnyOrder(a, b);
        assertThat(ids(json(query(viewer, "/api/purchases/orders?status=open")).get("items")))
                .containsExactly(c);
        assertThat(ids(json(query(viewer, "/api/purchases/orders?q=carton")).get("items")))
                .containsExactlyInAnyOrder(b, c);
        query(viewer, "/api/purchases/orders?status=SHIPPED").andExpect(status().isConflict());

        var detail = json(query(viewer, "/api/purchases/orders/" + c).andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var lines = (List<Map<String, Object>>) detail.get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst()).containsEntry("unit", "bag").containsEntry("item", "HDPE resin");
        assertThat(dec(lines.getFirst().get("outstanding"))).isEqualByComparingTo("50");
        assertThat(((List<?>) detail.get("warehouses"))).hasSize(1);
        assertThat(((List<?>) detail.get("activity"))).hasSizeGreaterThanOrEqualTo(3);
    }
}
