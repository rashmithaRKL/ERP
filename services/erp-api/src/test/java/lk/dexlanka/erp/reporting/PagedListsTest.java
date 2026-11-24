package lk.dexlanka.erp.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.MultiLevelTestBase;
import lk.dexlanka.erp.platform.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V9 server-side pagination rollout: {@code GET /api/manufacturing/orders}, {@code /api/quality/inspections}, {@code
 * /api/reports/costs} and {@code /api/reports/journal} (keyset paging, filters, counts/totals, roles, tenant isolation),
 * and the {@code /api/state} keys no screen reads any more.
 */
class PagedListsTest extends MultiLevelTestBase {
    String resin, bottle, cap, bom, capBom;

    @BeforeEach
    void products() {
        resin = item("RM-L", "RAW_MATERIAL", "kg", "10");
        bottle = item("FG-BOTTLE", "FINISHED_GOOD", "pcs", "0");
        cap = item("FG-CAP", "FINISHED_GOOD", "pcs", "0");
        bom = approvedBom(bottle, "R01", "1", "1", resin, "2");
        capBom = approvedBom(cap, "R01", "1", "1", resin, "1");
    }

    Map<String, Object> page(Actor actor, String path) throws Exception {
        return json(query(actor, path).andExpect(status().isOk()));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    static List<Object> numbers(Map<String, Object> page) {
        return items(page).stream().map(r -> r.get("number")).toList();
    }

    @Test
    void manufacturingOrdersArePagedNewestFirstWithFiltersAndCounts() throws Exception {
        var due = LocalDate.now().plusDays(10);
        String first = order(bom, "10", due);
        String second = order(capBom, "5", due);
        String third = order(bom, "3", due);
        String fourth = order(capBom, "7", due);
        String fifth = order(bom, "1", due);
        command(otherPlanner, "/api/manufacturing/" + second + "/actions/approve", null)
                .andExpect(status().isOk());
        String number = str(orderRow(fifth), "number");

        var one = page(operator, "/api/manufacturing/orders?limit=2");
        assertThat(items(one)).extracting(r -> r.get("id")).containsExactly(fifth, fourth);
        assertThat(one.get("total")).isEqualTo(5);
        assertThat(map(one.get("counts")))
                .containsEntry("DRAFT", 4)
                .containsEntry("APPROVED", 1)
                .containsEntry("CLOSED", 0);
        assertThat(items(one).getFirst())
                .containsEntry("product", "FG-BOTTLE name")
                .containsEntry("sku", "FG-BOTTLE")
                .containsEntry("machine", "M-01")
                .containsKey("parent_number");
        var two = page(operator, "/api/manufacturing/orders?limit=2&before=" + one.get("next"));
        assertThat(items(two)).extracting(r -> r.get("id")).containsExactly(third, second);
        var three = page(operator, "/api/manufacturing/orders?limit=2&before=" + two.get("next"));
        assertThat(items(three)).extracting(r -> r.get("id")).containsExactly(first);
        assertThat(three.get("next")).isEqualTo("");

        // Status codes and groups, free text on product SKU and order number.
        assertThat(items(page(viewer, "/api/manufacturing/orders?status=APPROVED")))
                .extracting(r -> r.get("id"))
                .containsExactly(second);
        assertThat(page(viewer, "/api/manufacturing/orders?status=active").get("total"))
                .isEqualTo(5);
        assertThat(page(viewer, "/api/manufacturing/orders?status=floor").get("total"))
                .isEqualTo(0);
        var caps = page(viewer, "/api/manufacturing/orders?q=fg-cap");
        assertThat(items(caps)).extracting(r -> r.get("id")).containsExactly(fourth, second);
        assertThat(caps.get("total")).isEqualTo(2);
        assertThat(map(caps.get("counts"))).containsEntry("DRAFT", 4); // counts ignore the filter
        assertThat(items(page(viewer, "/api/manufacturing/orders?q=" + number)))
                .extracting(r -> r.get("id"))
                .containsExactly(fifth);

        // Validation, and another tenant sees nothing (not even through our cursor).
        query(viewer, "/api/manufacturing/orders?status=SHIPPED").andExpect(status().isConflict());
        query(viewer, "/api/manufacturing/orders?limit=0").andExpect(status().isConflict());
        query(viewer, "/api/manufacturing/orders?limit=201").andExpect(status().isConflict());
        var other = createUser(createTenant("Other orders").id(), "PLANNER");
        var stranger = page(other, "/api/manufacturing/orders");
        assertThat(items(stranger)).isEmpty();
        assertThat(stranger.get("total")).isEqualTo(0);
        assertThat(items(page(other, "/api/manufacturing/orders?before=" + third)))
                .isEmpty();
    }

    @Test
    void inspectionsArePagedWithStatusTypeAndTextFilters() throws Exception {
        String mo = order(bom, "10", LocalDate.now().plusDays(3));
        String moNumber = str(orderRow(mo), "number");
        String[] lots = new String[4];
        String[] types = {"INCOMING", "FINAL", "FINAL", "RETURN"};
        String[] statuses = {"PASSED", "PENDING", "FAILED", "PENDING"};
        for (int i = 0; i < lots.length; i++) {
            lots[i] = stock(i == 1 ? bottle : resin, "5", "10");
            if (i == 1)
                update(
                        "UPDATE lots SET source_type='MANUFACTURING',source_id=?,number='FG-PAGED-1' WHERE tenant_id=? AND id=?",
                        mo,
                        t,
                        lots[i]);
            update(
                    "INSERT INTO inspections(id,tenant_id,lot_id,type,status,notes,created_at) VALUES(?,?,?,?,?,?,now()-(?||' minutes')::interval)",
                    id(),
                    t,
                    lots[i],
                    types[i],
                    statuses[i],
                    i == 2 ? "Wall thickness out of tolerance" : "",
                    String.valueOf(10 - i));
        }

        var first = page(quality, "/api/quality/inspections?limit=3");
        assertThat(items(first)).extracting(r -> r.get("lot_id")).containsExactly(lots[3], lots[2], lots[1]);
        assertThat(first.get("total")).isEqualTo(4);
        assertThat(map(first.get("counts")))
                .containsEntry("PENDING", 2)
                .containsEntry("PASSED", 1)
                .containsEntry("FAILED", 1);
        var mine = items(first).get(2);
        assertThat(mine)
                .containsEntry("lot", "FG-PAGED-1")
                .containsEntry("item", "FG-BOTTLE name")
                .containsEntry("source_number", moNumber)
                .containsEntry("type", "FINAL");
        assertThat(num(mine.get("quantity"))).isEqualByComparingTo("5");
        var rest = page(quality, "/api/quality/inspections?limit=3&before=" + first.get("next"));
        assertThat(items(rest)).extracting(r -> r.get("lot_id")).containsExactly(lots[0]);
        assertThat(rest.get("next")).isEqualTo("");

        assertThat(page(viewer, "/api/quality/inspections?status=pending").get("total"))
                .isEqualTo(2);
        assertThat(items(page(viewer, "/api/quality/inspections?status=PENDING&type=RETURN")))
                .extracting(r -> r.get("lot_id"))
                .containsExactly(lots[3]);
        assertThat(items(page(viewer, "/api/quality/inspections?q=thickness")))
                .extracting(r -> r.get("lot_id"))
                .containsExactly(lots[2]);
        query(viewer, "/api/quality/inspections?type=VISUAL").andExpect(status().isConflict());
        var other = createUser(createTenant("Other inspections").id(), "QUALITY");
        assertThat(page(other, "/api/quality/inspections").get("total")).isEqualTo(0);
    }

    @Test
    void costRegisterAndJournalArePagedForFinanceRolesOnly() throws Exception {
        var due = LocalDate.now().plusDays(4);
        String a = order(bom, "10", due); // estimated Rs. 200
        String b = order(capBom, "4", due); // Rs. 40
        String c = order(bom, "1", due); // Rs. 20
        update("UPDATE manufacturing_orders SET material_cost=150,wip_cost=150 WHERE tenant_id=? AND id=?", t, a);

        var costs = page(planner, "/api/reports/costs?limit=2");
        assertThat(items(costs)).extracting(r -> r.get("id")).containsExactly(c, b);
        assertThat(costs.get("total")).isEqualTo(3);
        var summary = map(costs.get("summary"));
        assertThat(num(summary.get("estimated_cost"))).isEqualByComparingTo("260");
        assertThat(num(summary.get("material_cost"))).isEqualByComparingTo("150");
        assertThat(num(summary.get("wip_cost"))).isEqualByComparingTo("150");
        assertThat(summary.get("orders")).isEqualTo(3);
        var older = page(admin, "/api/reports/costs?limit=2&before=" + costs.get("next"));
        assertThat(items(older)).extracting(r -> r.get("id")).containsExactly(a);
        assertThat(num(items(older).getFirst().get("actual_cost"))).isEqualByComparingTo("150");
        assertThat(page(planner, "/api/reports/costs?q=FG-CAP").get("total")).isEqualTo(1);
        query(warehouse, "/api/reports/costs").andExpect(status().isForbidden());
        query(viewer, "/api/reports/costs").andExpect(status().isForbidden());

        // Journal: the opening stocks of the fixture post one balanced entry (two lines) each.
        stock(resin, "1", "10");
        stock(resin, "2", "10");
        stock(resin, "3", "10");
        var journal = page(planner, "/api/reports/journal?limit=4");
        var lines = items(journal);
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0).get("entry_id")).isEqualTo(lines.get(1).get("entry_id"));
        assertThat(lines.get(0)).containsEntry("account", "RAW_INVENTORY"); // debit before credit
        assertThat(num(lines.get(0).get("debit"))).isEqualByComparingTo("30");
        assertThat(lines.get(1)).containsEntry("account", "OPENING_EQUITY");
        assertThat((List<Object>) journal.get("accounts")).contains("OPENING_EQUITY", "RAW_INVENTORY");
        var totals = map(journal.get("totals"));
        assertThat(num(totals.get("debit"))).isEqualByComparingTo("60");
        assertThat(num(totals.get("credit"))).isEqualByComparingTo("60");
        var rest = page(planner, "/api/reports/journal?limit=4&before=" + journal.get("next"));
        assertThat(items(rest)).hasSize(2);
        assertThat(rest.get("next")).isEqualTo("");
        assertThat(rest).doesNotContainKey("accounts");

        assertThat(items(page(planner, "/api/reports/journal?account=OPENING_EQUITY")))
                .hasSize(3)
                .allMatch(r -> num(r.get("credit")).signum() > 0);
        String today = LocalDate.now(java.time.ZoneId.of("Asia/Colombo")).toString();
        String tomorrow = LocalDate.parse(today).plusDays(1).toString();
        assertThat(items(page(planner, "/api/reports/journal?from=" + today + "&to=" + today)))
                .hasSize(6);
        assertThat(items(page(planner, "/api/reports/journal?from=" + tomorrow)))
                .isEmpty();
        assertThat(items(page(planner, "/api/reports/journal?q=opening"))).hasSize(6);
        query(planner, "/api/reports/journal?from=24-11-2026").andExpect(status().isBadRequest());
        query(planner, "/api/reports/journal?from=" + tomorrow + "&to=" + today).andExpect(status().isConflict());
        query(operator, "/api/reports/journal").andExpect(status().isForbidden());

        var other = createUser(createTenant("Other journal").id(), "ADMIN");
        assertThat(items(page(other, "/api/reports/journal"))).isEmpty();
        assertThat(items(page(other, "/api/reports/costs"))).isEmpty();
    }

    @Test
    void stateNoLongerCarriesKeysThatScreensReadServerPaged() throws Exception {
        order(bom, "1", LocalDate.now().plusDays(1));
        stock(resin, "1", "10");
        query(admin, "/api/state?days=14")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").doesNotExist())
                .andExpect(jsonPath("$.ledger").doesNotExist())
                .andExpect(jsonPath("$.receipts").doesNotExist())
                .andExpect(jsonPath("$.journal").doesNotExist())
                // The dashboard and shared pickers keep their keys.
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.financial.wip").exists())
                .andExpect(jsonPath("$.metrics.active_orders").value(1))
                .andExpect(jsonPath("$.inspections").isArray())
                .andExpect(jsonPath("$.lots.length()").value(1))
                .andExpect(jsonPath("$.trend.length()").value(14));
    }
}
