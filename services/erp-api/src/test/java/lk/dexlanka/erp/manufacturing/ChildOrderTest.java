package lk.dexlanka.erp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import lk.dexlanka.erp.MultiLevelTestBase;
import lk.dexlanka.erp.quality.QualityCommands;
import lk.dexlanka.erp.quality.QualityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Parent/child manufacturing orders (V7): child orders for semi-finished shortages (quantity, BOM, work center, due date
 * offset by lead time, linkage), the independent approval rule, the order tree and the end-to-end flow where a child's
 * released output lot satisfies the parent's ordinary reservation.
 *
 * <p>Structure: BTL (1 out) = PRE 1 pcs; PRE (1000 out, lead 3 days) = CMP 20 kg; CMP (1 kg out, lead 1 day) = RES 1 kg.
 */
class ChildOrderTest extends MultiLevelTestBase {
    @Autowired
    OrderTreeService tree;

    @Autowired
    QualityService qc;

    String res, cmp, pre, btl, cmpBom, preBom, btlBom;
    LocalDate due;

    @BeforeEach
    void structure() {
        res = item("RES", "RAW_MATERIAL", "kg", "10");
        cmp = item("CMP", "SEMI_FINISHED", "kg", "12", "0", 1);
        pre = item("PRE", "SEMI_FINISHED", "pcs", "0.5", "0", 3);
        btl = item("BTL", "FINISHED_GOOD", "pcs", "2");
        cmpBom = approvedBom(cmp, "R1", "1", "1", res, "1");
        preBom = approvedBom(pre, "R1", "1000", "1", cmp, "20");
        btlBom = approvedBom(btl, "R1", "1", "1", pre, "1");
        stock(res, "1000", "10");
        stock(pre, "300", "0.5");
        due = LocalDate.now().plusDays(10);
    }

    @Test
    void childOutputSatisfiesTheParentReservationEndToEnd() throws Exception {
        stock(cmp, "100", "12");
        String parent = order(btlBom, "1000", due);
        mo.transition(admin, parent, "approve", id());

        var before =
                json(query(viewer, "/api/manufacturing/" + parent + "/tree").andExpect(status().isOk()));
        var shortage = list(before.get("shortages")).getFirst();
        assertThat(shortage).containsEntry("sku", "PRE").containsEntry("plannable", true);
        assertThat(num(shortage.get("required_qty"))).isEqualByComparingTo("1000");
        assertThat(num(shortage.get("available"))).isEqualByComparingTo("300");
        assertThat(num(shortage.get("shortage"))).isEqualByComparingTo("700");
        assertThat(shortage.get("suggested_due_date"))
                .isEqualTo(due.minusDays(3).toString());
        assertThat(before.get("canPlanChildren")).isEqualTo(true);

        String key = id();
        var result = json(command(
                        planner,
                        "/api/manufacturing/" + parent + "/children",
                        new ManufacturingCommands.ChildOrders(null, null, null),
                        key)
                .andExpect(status().isOk()));
        var created = list(result.get("created"));
        assertThat(created).hasSize(1);
        String child = str(created.getFirst(), "id");
        assertThat(num(created.getFirst().get("quantity"))).isEqualByComparingTo("700");
        var childRow = orderRow(child);
        assertThat(childRow)
                .containsEntry("parent_order_id", parent)
                .containsEntry("bom_id", preBom)
                .containsEntry("machine_id", machine)
                .containsEntry("status", "DRAFT")
                .containsEntry("priority", "NORMAL")
                .containsEntry("created_by", planner.id());
        assertThat(str(childRow, "due_date")).isEqualTo(due.minusDays(3).toString());
        // Snapshot of the child: 20 kg x 700 / 1000.
        assertThat(decimal(
                        "SELECT required_qty FROM order_materials WHERE tenant_id=? AND order_id=? AND item_id=?",
                        t,
                        child,
                        cmp))
                .isEqualByComparingTo("14");
        assertThat(count(
                        "SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='MO_CHILDREN_CREATED' AND reference=?",
                        t,
                        orderRow(parent).get("number")))
                .isEqualTo(1);
        assertThat(str(
                        row(
                                "SELECT detail FROM audit_events WHERE tenant_id=? AND action='MO_CREATED' AND reference=?",
                                t,
                                childRow.get("number")),
                        "detail"))
                .contains("child of " + orderRow(parent).get("number"));

        // Idempotent replay; the same key with another body is rejected; a new run finds nothing short.
        var replay = json(command(
                        planner,
                        "/api/manufacturing/" + parent + "/children",
                        new ManufacturingCommands.ChildOrders(null, null, null),
                        key)
                .andExpect(status().isOk()));
        assertThat(str(list(replay.get("created")).getFirst(), "id")).isEqualTo(child);
        command(
                        planner,
                        "/api/manufacturing/" + parent + "/children",
                        new ManufacturingCommands.ChildOrders(null, "URGENT", null),
                        key)
                .andExpect(status().isConflict());
        command(
                        planner,
                        "/api/manufacturing/" + parent + "/children",
                        new ManufacturingCommands.ChildOrders(null, null, null))
                .andExpect(status().isConflict());
        assertThat(count(
                        "SELECT COUNT(*) FROM manufacturing_orders WHERE tenant_id=? AND parent_order_id=?", t, parent))
                .isEqualTo(1);

        // Independent approval applies to child orders too.
        assertThatThrownBy(() -> mo.transition(planner, child, "approve", id()))
                .hasMessageContaining("different authorized person");
        mo.transition(otherPlanner, child, "approve", id());

        // The child runs like any order; its output lands in the raw store under quality review.
        mo.transition(warehouse, child, "reserve", id());
        mo.transition(warehouse, child, "issue", id());
        mo.transition(operator, child, "start", id());
        mo.complete(
                operator, child, new ManufacturingCommands.Complete(bd("700"), bd("0"), bd("0"), bd("0"), "Run"), id());
        var lot = row("SELECT * FROM lots WHERE tenant_id=? AND source_id=?", t, child);
        assertThat(str(lot, "number")).startsWith("SF-");
        assertThat(lot).containsEntry("warehouse_id", tenant.rawWarehouse()).containsEntry("status", "QUARANTINE");

        // Output awaiting quality control still counts as the child's supply: nothing is short.
        var during = json(query(planner, "/api/manufacturing/" + parent + "/tree"));
        assertThat(num(list(during.get("shortages")).getFirst().get("shortage")))
                .isZero();
        assertThat(num(list(during.get("shortages")).getFirst().get("child_supply")))
                .isEqualByComparingTo("700");
        var node = list(during.get("children")).getFirst();
        assertThat(node).containsEntry("id", child).containsEntry("status", "QUALITY_REVIEW");
        assertThat(num(node.get("progress"))).isEqualByComparingTo("100");

        var inspection = row("SELECT id FROM inspections WHERE tenant_id=? AND lot_id=?", t, lot.get("id"));
        qc.inspect(quality, str(inspection, "id"), new QualityCommands.Inspect("PASSED", "ok"), id());
        assertThat(orderRow(child).get("status")).isEqualTo("COMPLETED");

        // The parent's ordinary FIFO reservation now finds the opening preforms and the child's released lot.
        mo.transition(warehouse, parent, "reserve", id());
        assertThat(decimal(
                        "SELECT SUM(quantity) FROM reservations WHERE tenant_id=? AND order_id=? AND lot_id=?",
                        t,
                        parent,
                        lot.get("id")))
                .isEqualByComparingTo("700");
        assertThat(decimal(
                        "SELECT SUM(quantity) FROM reservations WHERE tenant_id=? AND order_id=? AND item_id=?",
                        t,
                        parent,
                        pre))
                .isEqualByComparingTo("1000");
        mo.transition(warehouse, parent, "issue", id());
        assertThat(decimal("SELECT quantity FROM stock_balances WHERE tenant_id=? AND lot_id=?", t, lot.get("id")))
                .isZero();

        // The child's tree points back to its parent.
        var up = json(query(operator, "/api/manufacturing/" + child + "/tree").andExpect(status().isOk()));
        assertThat(map(up.get("parent")).get("id")).isEqualTo(parent);
        assertThat(list(up.get("ancestors"))).hasSize(1);
    }

    @Test
    void childrenNestAcrossLevelsWithLeadTimeOffsets() throws Exception {
        String parent = order(btlBom, "5000", due);
        String child = str(
                list(tree.createChildren(planner, parent, new ManufacturingCommands.ChildOrders(null, null, null), id())
                                .get("created"))
                        .getFirst(),
                "id");
        // The child (4,700 preforms) needs 94 kg of compound, none in stock: a grandchild one level down.
        var grand = list(tree.createChildren(
                                otherPlanner, child, new ManufacturingCommands.ChildOrders(null, "HIGH", null), id())
                        .get("created"))
                .getFirst();
        assertThat(num(grand.get("quantity"))).isEqualByComparingTo("94");
        // Due one compound lead-time day before the child, which is due three preform days before the parent.
        assertThat(grand.get("due_date")).isEqualTo(due.minusDays(4).toString());
        assertThat(orderRow(str(grand, "id")).get("priority")).isEqualTo("HIGH");

        var top = json(query(planner, "/api/manufacturing/" + parent + "/tree"));
        assertThat(top.get("descendants")).isEqualTo(2);
        var level1 = list(top.get("children"));
        assertThat(level1).singleElement().satisfies(n -> {
            assertThat(n.get("id")).isEqualTo(child);
            assertThat(list(n.get("children"))).extracting(c -> c.get("id")).containsExactly(grand.get("id"));
        });
        var bottom = json(query(planner, "/api/manufacturing/" + grand.get("id") + "/tree"));
        assertThat(list(bottom.get("ancestors"))).extracting(a -> a.get("id")).containsExactly(parent, child);
        assertThat(map(bottom.get("parent")).get("id")).isEqualTo(child);
    }

    @Test
    void releasedStockIsAllocatedToEarlierDueOrdersFirst() throws Exception {
        String later = order(btlBom, "500", due.plusDays(5));
        String earlier = order(btlBom, "1000", due);
        // The 300 released preforms belong to the earlier-due order: the later one is short of all 500.
        var laterShortage = list(json(query(planner, "/api/manufacturing/" + later + "/tree"))
                        .get("shortages"))
                .getFirst();
        assertThat(num(laterShortage.get("earlier_demand"))).isEqualByComparingTo("1000");
        assertThat(num(laterShortage.get("available_for_order"))).isZero();
        assertThat(num(laterShortage.get("shortage"))).isEqualByComparingTo("500");
        var earlierShortage = list(json(query(planner, "/api/manufacturing/" + earlier + "/tree"))
                        .get("shortages"))
                .getFirst();
        assertThat(num(earlierShortage.get("shortage"))).isEqualByComparingTo("700");

        // Once the earlier order's child covers its gap, the later order still cannot count on the same stock.
        tree.createChildren(planner, earlier, new ManufacturingCommands.ChildOrders(null, null, null), id());
        var after = list(json(query(planner, "/api/manufacturing/" + later + "/tree"))
                        .get("shortages"))
                .getFirst();
        assertThat(num(after.get("earlier_demand"))).isEqualByComparingTo("300");
        assertThat(num(after.get("shortage"))).isEqualByComparingTo("500");
        var created = list(tree.createChildren(
                                planner, later, new ManufacturingCommands.ChildOrders(null, null, null), id())
                        .get("created"))
                .getFirst();
        assertThat(num(created.get("quantity"))).isEqualByComparingTo("500");
    }

    @Test
    void childOrderRulesPermissionsAndTenancy() throws Exception {
        String parent = order(btlBom, "1000", due);
        String children = "/api/manufacturing/" + parent + "/children";
        var none = new ManufacturingCommands.ChildOrders(null, null, null);
        command(operator, children, none).andExpect(status().isForbidden());
        command(warehouse, children, none).andExpect(status().isForbidden());

        var other = createTenant("Other organization");
        var stranger = createUser(other.id(), "ADMIN");
        command(stranger, children, none).andExpect(status().isNotFound());
        query(stranger, "/api/manufacturing/" + parent + "/tree").andExpect(status().isNotFound());

        // Explicit child through the ordinary create command: due no later than the parent, consumed by the parent.
        command(
                        planner,
                        "/api/manufacturing",
                        new ManufacturingCommands.Manufacture(
                                preBom,
                                machine,
                                null,
                                bd("100"),
                                "NORMAL",
                                due.plusDays(1).toString(),
                                null,
                                parent))
                .andExpect(status().isConflict());
        command(
                        planner,
                        "/api/manufacturing",
                        new ManufacturingCommands.Manufacture(
                                cmpBom, machine, null, bd("1"), "NORMAL", due.toString(), null, parent))
                .andExpect(status().isConflict());
        command(
                        stranger,
                        "/api/manufacturing",
                        new ManufacturingCommands.Manufacture(
                                preBom, machine, null, bd("1"), "NORMAL", due.toString(), null, parent))
                .andExpect(status().isNotFound());
        var explicit = json(command(
                        planner,
                        "/api/manufacturing",
                        new ManufacturingCommands.Manufacture(
                                preBom, machine, null, bd("100"), "NORMAL", due.toString(), null, parent))
                .andExpect(status().isOk()));
        assertThat(explicit.get("parent_order_id")).isEqualTo(parent);

        // Limit a run to some components and pick the work center and priority; only semi-finished materials qualify.
        String second = id();
        update(
                "INSERT INTO machines(id,tenant_id,factory_id,code,name,kind,status,hourly_rate) VALUES(?,?,?,'M-02','Line 2','Moulding','IDLE',10)",
                second,
                t,
                tenant.plant());
        command(planner, children, new ManufacturingCommands.ChildOrders(second, null, List.of(res)))
                .andExpect(status().isConflict());
        var run = json(command(planner, children, new ManufacturingCommands.ChildOrders(second, "URGENT", List.of(pre)))
                .andExpect(status().isOk()));
        var created = list(run.get("created")).getFirst();
        // 1000 required - 300 in stock - 100 on the explicit child.
        assertThat(num(created.get("quantity"))).isEqualByComparingTo("600");
        assertThat(orderRow(str(created, "id")))
                .containsEntry("machine_id", second)
                .containsEntry("priority", "URGENT");

        // The parent link never changes.
        assertThatThrownBy(() -> update(
                        "UPDATE manufacturing_orders SET parent_order_id=NULL WHERE tenant_id=? AND id=?",
                        t,
                        str(created, "id")))
                .hasMessageContaining("parent order cannot change");

        // Only draft or approved parents get children.
        mo.transition(planner, parent, "cancel", id());
        command(planner, children, none).andExpect(status().isConflict());
        command(
                        planner,
                        "/api/manufacturing",
                        new ManufacturingCommands.Manufacture(
                                preBom, machine, null, bd("1"), "NORMAL", due.toString(), null, parent))
                .andExpect(status().isConflict());
    }
}
