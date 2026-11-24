package lk.dexlanka.erp.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanningRecommendationsTest extends ProcurementFixture {
    static ProcurementCommands.Recommendation rec(String item, String quantity, String supplierId) {
        return new ProcurementCommands.Recommendation(item, bd(quantity), supplierId);
    }

    @Test
    void shortagesBecomeOneDraftPerPreferredSupplierPlusOneWithoutSupplier() throws Exception {
        // Purchase history: resin from the first supplier at 480/kg (bought in bags), cartons from the second.
        approved(line(resin, "2", "bag", "12000"));
        String cartons = str(orders.create(planner, order(supplier2, line(carton, "9", "box", "93")), id()), "id");
        orders.submit(planner, cartons, id());
        String spare = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level,lead_time_days) VALUES(?,?,'RM-2','Colour masterbatch','RAW_MATERIAL','kg',1850,0,10)",
                spare,
                t);

        var sources = json(query(planner, "/api/purchases/sources?items=" + resin + "," + carton + "," + spare));
        @SuppressWarnings("unchecked")
        var bySource = ((List<Map<String, Object>>) sources.get("items"));
        assertThat(bySource)
                .extracting(r -> r.get("supplier"))
                .containsExactlyInAnyOrder("Polymer Traders", "Pack Co", null);

        var body = new ProcurementCommands.Recommendations(
                List.of(rec(resin, "120.5", null), rec(carton, "40.2", null), rec(spare, "12", null)), null);
        var result = json(command(planner, "/api/purchases/recommendations", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3)));
        @SuppressWarnings("unchecked")
        var created = (List<Map<String, Object>>) result.get("purchases");
        var bySupplier = created.stream()
                .collect(
                        java.util.stream.Collectors.toMap(p -> String.valueOf(p.get("supplierId")), p -> str(p, "id")));
        assertThat(bySupplier).containsOnlyKeys(supplier, supplier2, "null");

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));
        String resinPo = bySupplier.get(supplier);
        var resinHeader = header(resinPo);
        assertThat(resinHeader.get("status")).isEqualTo("DRAFT");
        assertThat(resinHeader.get("created_by")).isEqualTo(planner.id());
        var resinLine = poLine(resinPo, 1);
        assertThat(resinLine).containsEntry("unit", "kg");
        assertThat(dec(resinLine.get("quantity"))).isEqualByComparingTo("120.5");
        assertThat(dec(resinLine.get("unit_cost"))).isEqualByComparingTo("480"); // last purchase price
        assertThat(resinLine.get("expected_date").toString())
                .isEqualTo(today.plusDays(7).toString());
        assertThat(resinHeader.get("due_date").toString())
                .isEqualTo(today.plusDays(7).toString());
        var cartonLine = poLine(bySupplier.get(supplier2), 1);
        assertThat(dec(cartonLine.get("quantity"))).isEqualByComparingTo("41"); // whole pieces, rounded up
        assertThat(dec(cartonLine.get("unit_cost"))).isEqualByComparingTo("31"); // 93 per box of 3
        String open = bySupplier.get("null");
        assertThat(header(open).get("supplier_id")).isNull();
        assertThat(dec(poLine(open, 1).get("unit_cost"))).isEqualByComparingTo("1850"); // standard cost
        assertThat(count(
                        "SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='PO_DRAFT_CREATED' AND detail LIKE 'From material planning%'",
                        t))
                .isEqualTo(3);

        // The planner completes the draft without a supplier, then it follows the normal approval path.
        assertThatThrownBy(() -> orders.submit(planner, open, id())).hasMessageContaining("Choose a supplier");
        orders.update(
                planner,
                open,
                new ProcurementCommands.Order(
                        supplier,
                        null,
                        today.plusDays(10).toString(),
                        "",
                        List.of(new ProcurementCommands.OrderLine(
                                lineId(open, 1), spare, bd("12"), null, bd("1800"), null))),
                id());
        orders.submit(planner, open, id());
        assertThat(orders.approve(planner2, open, id()).get("status")).isEqualTo("APPROVED");
        assertThat(dec(supply.openSupplyByItem(t).get(spare))).isEqualByComparingTo("12");
    }

    @Test
    void explicitSuppliersRulesAndIdempotency() throws Exception {
        var body = new ProcurementCommands.Recommendations(
                List.of(rec(resin, "10", supplier2), rec(carton, "5", supplier2)),
                LocalDate.now().plusDays(4).toString());
        String key = id();
        var first = json(
                command(warehouse, "/api/purchases/recommendations", body, key).andExpect(status().isOk()));
        var replay = json(
                command(warehouse, "/api/purchases/recommendations", body, key).andExpect(status().isOk()));
        assertThat(replay).isEqualTo(first);
        @SuppressWarnings("unchecked")
        var created = (List<Map<String, Object>>) first.get("purchases");
        assertThat(created).hasSize(1);
        String po = str(created.getFirst(), "id");
        assertThat(header(po).get("supplier_id")).isEqualTo(supplier2);
        assertThat(header(po).get("due_date").toString())
                .isEqualTo(LocalDate.now().plusDays(4).toString());
        assertThat(count("SELECT COUNT(*) FROM purchase_order_lines WHERE tenant_id=? AND purchase_order_id=?", t, po))
                .isEqualTo(2);

        command(viewer, "/api/purchases/recommendations", body).andExpect(status().isForbidden());
        command(planner, "/api/purchases/recommendations", new ProcurementCommands.Recommendations(List.of(), null))
                .andExpect(status().isBadRequest());
        command(
                        planner,
                        "/api/purchases/recommendations",
                        new ProcurementCommands.Recommendations(
                                List.of(rec(resin, "1", null), rec(resin, "2", null)), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Each material can appear only once in a planning request"));
        command(
                        planner,
                        "/api/purchases/recommendations",
                        new ProcurementCommands.Recommendations(List.of(rec(bottle, "1", null)), null))
                .andExpect(status().isConflict());
        update("UPDATE partners SET active=false WHERE tenant_id=? AND id=?", t, supplier);
        command(
                        planner,
                        "/api/purchases/recommendations",
                        new ProcurementCommands.Recommendations(List.of(rec(resin, "1", supplier)), null))
                .andExpect(status().isConflict());
        // A failed request creates nothing.
        assertThat(count("SELECT COUNT(*) FROM purchase_orders WHERE tenant_id=?", t))
                .isEqualTo(1);
    }
}
