package lk.dexlanka.erp.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.MultiLevelTestBase;
import lk.dexlanka.erp.manufacturing.ManufacturingCommands;
import lk.dexlanka.erp.manufacturing.OrderTreeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Multi-level MRP (V7): open order demand explodes through the semi-finished level into planned production, whose
 * components join the raw material's gross demand; netting against released stock, stock in inspection, open (child)
 * orders, safety stock and incoming purchases; pegging, low-level codes and lead-time dates.
 *
 * <p>Structure: BTL (1 out) = PRE 1 pcs; PRE (1000 out, lead 2 days, safety 100) = RES 20 kg; RES lead 5 days, safety
 * 10 kg.
 */
class MultiLevelMrpTest extends MultiLevelTestBase {
    @Autowired
    OrderTreeService tree;

    String res, pre, btl, preBom, btlBom, supplier, p1, p2, child;
    LocalDate d1, d2;

    @BeforeEach
    void plan() {
        res = item("RES", "RAW_MATERIAL", "kg", "10", "10", 5);
        pre = item("PRE", "SEMI_FINISHED", "pcs", "0.5", "100", 2);
        btl = item("BTL", "FINISHED_GOOD", "pcs", "2");
        preBom = approvedBom(pre, "R1", "1000", "1", res, "20");
        btlBom = approvedBom(btl, "R1", "1", "1", pre, "1");
        stock(pre, "200", "0.5");
        quarantine(res, "5", "10");
        supplier = id();
        update("INSERT INTO partners(id,tenant_id,name,type) VALUES(?,?,'Resin supplier','SUPPLIER')", supplier, t);
        // A partially received purchase (V6 header + line): 25 kg ordered, 5 kg received -> 20 kg incoming.
        var po = id();
        update(
                "INSERT INTO purchase_orders(id,tenant_id,number,supplier_id,due_date,total,status,created_by) VALUES(?,?,?,?,?,250,'PARTIAL',?)",
                po,
                t,
                "PO-" + id().substring(0, 8),
                supplier,
                LocalDate.now().plusDays(3),
                planner.id());
        update(
                "INSERT INTO purchase_order_lines(id,tenant_id,purchase_order_id,line_no,item_id,quantity,unit,factor,base_quantity,unit_cost,base_unit_cost,received) VALUES(?,?,?,1,?,25,'kg',1,25,10,10,5)",
                id(),
                t,
                po,
                res);
        d1 = LocalDate.now().plusDays(20);
        d2 = LocalDate.now().plusDays(30);
        // P1 (1,000 bottles) gets a child order for its preform shortage: 1,000 - 200 in stock = 800.
        p1 = order(btlBom, "1000", d1);
        child = str(
                list(tree.createChildren(planner, p1, new ManufacturingCommands.ChildOrders(null, null, null), id())
                                .get("created"))
                        .getFirst(),
                "id");
        // P2 (500 bottles) has no child order.
        p2 = order(btlBom, "500", d2);
    }

    Map<String, Object> mrp(lk.dexlanka.erp.platform.Actor actor) throws Exception {
        return json(query(actor, "/api/planning/mrp").andExpect(status().isOk()));
    }

    @Test
    void demandExplodesThroughLevelsAndNetsEverySupply() throws Exception {
        var plan = mrp(planner);
        var rows = list(plan.get("rows"));
        assertThat(rows).noneMatch(r -> btl.equals(r.get("id"))); // end products are not planned here
        String p1Number = str(orderRow(p1), "number");
        String p2Number = str(orderRow(p2), "number");
        String childNumber = str(orderRow(child), "number");

        // Level 1, the preform: 1,500 gross + 100 safety - (200 stock + 800 on the open child order) = 600.
        var preform = find(rows, "id", pre);
        assertThat(preform.get("level")).isEqualTo(1);
        assertThat(num(preform.get("gross"))).isEqualByComparingTo("1500");
        assertThat(num(preform.get("available"))).isEqualByComparingTo("200");
        assertThat(num(preform.get("on_order"))).isEqualByComparingTo("800");
        assertThat(num(preform.get("safety"))).isEqualByComparingTo("100");
        assertThat(num(preform.get("shortage"))).isEqualByComparingTo("600");
        assertThat(preform).containsEntry("action", "PRODUCE").containsEntry("bom_id", preBom);
        assertThat(preform.get("supply_orders")).isEqualTo(childNumber);
        // Pegging: P1 (due first) is covered by stock and its child; P2 drives the need.
        var pegging = list(preform.get("pegging"));
        assertThat(pegging).extracting(p -> p.get("order_number")).containsExactly(p1Number, p2Number);
        assertThat(num(find(pegging, "order_id", p1).get("uncovered"))).isZero();
        assertThat(num(find(pegging, "order_id", p2).get("uncovered"))).isEqualByComparingTo("500");
        assertThat(preform.get("need_date")).isEqualTo(d2.toString());
        assertThat(preform.get("start_date")).isEqualTo(d2.minusDays(2).toString());
        assertThat(preform.get("drivers")).isEqualTo(p2Number);

        var planned = find(list(plan.get("plannedProduction")), "id", pre);
        assertThat(num(planned.get("quantity"))).isEqualByComparingTo("600");
        assertThat(planned.get("start_date")).isEqualTo(d2.minusDays(2).toString());
        assertThat(planned.get("late")).isEqualTo(false);
        assertThat(list(planned.get("parent_orders")))
                .extracting(p -> p.get("order_id"))
                .containsExactly(p2);

        // Level 2, the resin: the child's snapshot (800 x 0.02 = 16 kg, due with the child) plus the planned
        // production's components (600 x 0.02 = 12 kg, needed when it starts).
        var resin = find(rows, "id", res);
        assertThat(resin.get("level")).isEqualTo(2);
        assertThat(num(resin.get("independent_demand"))).isEqualByComparingTo("16");
        assertThat(num(resin.get("dependent_demand"))).isEqualByComparingTo("12");
        assertThat(num(resin.get("inspection"))).isEqualByComparingTo("5");
        assertThat(num(resin.get("incoming"))).isEqualByComparingTo("20");
        // 28 gross + 10 safety - (0 released + 5 in inspection + 20 incoming) = 13.
        assertThat(num(resin.get("shortage"))).isEqualByComparingTo("13");
        assertThat(resin.get("action")).isEqualTo("PURCHASE");
        var resinPegging = list(resin.get("pegging"));
        var fromChild = find(resinPegging, "source", "ORDER");
        assertThat(fromChild.get("order_id")).isEqualTo(child);
        assertThat(fromChild.get("need_date")).isEqualTo(d1.minusDays(2).toString());
        assertThat(num(fromChild.get("uncovered"))).isZero();
        var fromPlan = find(resinPegging, "source", "PLANNED");
        assertThat(fromPlan.get("product_id")).isEqualTo(pre);
        assertThat(num(fromPlan.get("uncovered"))).isEqualByComparingTo("3");
        assertThat(fromPlan.get("drivers")).isEqualTo(List.of(p2Number));
        // The order date leads the need date (the planned preform start) by the resin's 5-day lead time.
        var purchase = find(list(plan.get("purchases")), "id", res);
        assertThat(purchase.get("need_date")).isEqualTo(d2.minusDays(2).toString());
        assertThat(purchase.get("order_date")).isEqualTo(d2.minusDays(7).toString());
        assertThat(num(purchase.get("shortage"))).isEqualByComparingTo("13");
        assertThat(map(plan.get("summary")).get("levels")).isEqualTo(2);

        // The workspace snapshot carries the same netting (no pegging lists).
        var state = json(query(viewer, "/api/state?days=14"));
        var stateRow = find(list(state.get("mrp")), "id", pre);
        assertThat(num(stateRow.get("shortage"))).isEqualByComparingTo("600");
        assertThat(stateRow).containsEntry("level", 1).doesNotContainKey("pegging");
    }

    @Test
    void creatingTheSuggestedChildOrderClosesTheShortage() throws Exception {
        // "Create MO" for the planned production, linked to the driving parent and due on the suggested start date.
        command(
                        planner,
                        "/api/manufacturing",
                        new ManufacturingCommands.Manufacture(
                                preBom,
                                machine,
                                null,
                                bd("600"),
                                "NORMAL",
                                d2.minusDays(2).toString(),
                                null,
                                p2))
                .andExpect(status().isOk());
        var plan = mrp(warehouse);
        var preform = find(list(plan.get("rows")), "id", pre);
        assertThat(num(preform.get("on_order"))).isEqualByComparingTo("1400");
        assertThat(num(preform.get("shortage"))).isZero();
        assertThat(preform.get("action")).isEqualTo("NONE");
        assertThat(list(plan.get("plannedProduction"))).noneMatch(p -> pre.equals(p.get("id")));
        // The new order's own snapshot replaces the planned dependent demand for the resin (same 12 kg, same date).
        var resin = find(list(plan.get("rows")), "id", res);
        assertThat(num(resin.get("independent_demand"))).isEqualByComparingTo("28");
        assertThat(num(resin.get("dependent_demand"))).isZero();
        assertThat(num(resin.get("shortage"))).isEqualByComparingTo("13");
        assertThat(resin.get("need_date")).isEqualTo(d2.minusDays(2).toString());
    }

    @Test
    void stockReservationsSafetyStockAndInactiveItemsAreNetted() throws Exception {
        // A released resin lot, partly reserved by another order: only the unreserved part is available.
        String lot = stock(res, "40", "10");
        update("UPDATE stock_balances SET reserved=15 WHERE tenant_id=? AND lot_id=?", t, lot);
        var resin = find(list(mrp(planner).get("rows")), "id", res);
        assertThat(num(resin.get("available"))).isEqualByComparingTo("25");
        // 28 + 10 - (25 + 5 + 20) = -12: covered.
        assertThat(num(resin.get("shortage"))).isZero();
        assertThat(resin.get("action")).isEqualTo("NONE");

        // A semi-finished item with no demand below its safety stock is planned for today (and is late by its lead).
        String film = item("FILM", "SEMI_FINISHED", "kg", "1", "50", 1);
        approvedBom(film, "R1", "1", "1", res, "1");
        var plan = mrp(planner);
        var filmRow = find(list(plan.get("rows")), "id", film);
        assertThat(num(filmRow.get("shortage"))).isEqualByComparingTo("50");
        assertThat(filmRow).containsEntry("action", "PRODUCE").containsEntry("safety_shortfall", true);
        assertThat(filmRow.get("need_date")).isEqualTo(LocalDate.now().toString());
        assertThat(find(list(plan.get("plannedProduction")), "id", film).get("late"))
                .isEqualTo(true);
        assertThat(find(list(plan.get("plannedProduction")), "id", film).get("drivers"))
                .isEqualTo(List.of());

        // Inactive items keep their open demand but ask for no safety stock and get no recommendation.
        update("UPDATE items SET active=false WHERE tenant_id=? AND id=?", t, film);
        var inactive = find(list(mrp(planner).get("rows")), "id", film);
        assertThat(num(inactive.get("safety"))).isZero();
        assertThat(inactive.get("action")).isEqualTo("NONE");
    }

    @Test
    void planningIsRoleGatedAndTenantScoped() throws Exception {
        query(operator, "/api/planning/mrp").andExpect(status().isForbidden());
        query(viewer, "/api/planning/mrp").andExpect(status().isForbidden());
        query(admin, "/api/planning/mrp").andExpect(status().isOk());
        var other = createTenant("Other organization");
        var stranger = createUser(other.id(), "PLANNER");
        var plan = mrp(stranger);
        assertThat(list(plan.get("rows"))).noneMatch(r -> pre.equals(r.get("id")) || res.equals(r.get("id")));
        assertThat(list(plan.get("plannedProduction"))).isEmpty();
    }
}
