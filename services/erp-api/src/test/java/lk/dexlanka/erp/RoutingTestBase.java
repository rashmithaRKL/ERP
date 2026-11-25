package lk.dexlanka.erp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.catalog.CatalogCommands;
import lk.dexlanka.erp.catalog.CatalogService;
import lk.dexlanka.erp.manufacturing.ManufacturingCommands;
import lk.dexlanka.erp.manufacturing.ManufacturingService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.routing.RoutingCommands;
import lk.dexlanka.erp.routing.RoutingService;
import lk.dexlanka.erp.routing.SkillCommands;
import lk.dexlanka.erp.routing.SkillService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Fixture for the V10 routing, calendar, skill and operation tests: a fresh tenant with every role (two planners and
 * two operators for the independent-approval and skill rules), three work centers (two blow moulders, one packing
 * line), a finished good with an approved BOM and resin stock. Adds no Spring configuration, so the shared test context
 * is reused.
 */
public abstract class RoutingTestBase extends IntegrationTestBase {
    @Autowired
    protected CatalogService catalog;

    @Autowired
    protected ManufacturingService mo;

    @Autowired
    protected RoutingService routings;

    @Autowired
    protected SkillService skills;

    protected TestTenant tenant;
    protected String t;
    protected Actor admin, planner, otherPlanner, warehouse, operator, operator2, quality, viewer;
    protected String bm1, bm2, pk1, resin, bottle, bom;

    @BeforeEach
    void routingFixture() {
        tenant = createTenant("Routing fixture");
        t = tenant.id();
        admin = createUser(t, "ADMIN");
        planner = createUser(t, "PLANNER");
        otherPlanner = createUser(t, "PLANNER");
        warehouse = createUser(t, "WAREHOUSE");
        operator = createUser(t, "OPERATOR");
        operator2 = createUser(t, "OPERATOR");
        quality = createUser(t, "QUALITY");
        viewer = createUser(t, "VIEWER");
        bm1 = machine("BM-1", "Blow moulding");
        bm2 = machine("BM-2", "Blow moulding");
        pk1 = machine("PK-1", "Packaging");
        resin = item("RES", "RAW_MATERIAL", "kg", "100");
        bottle = item("BOT", "FINISHED_GOOD", "pcs", "5");
        bom = str(
                catalog.bom(
                        planner,
                        new CatalogCommands.Bom(
                                bottle,
                                "B1",
                                bd("1000"),
                                bd("1"),
                                List.of(new CatalogCommands.BomLine(resin, bd("20")))),
                        id()),
                "id");
        catalog.approveBom(admin, bom, id());
        openingStock(admin, resin, tenant.rawWarehouse(), "500", "100");
    }

    protected String machine(String code, String kind) {
        String id = id();
        update(
                "INSERT INTO machines(id,tenant_id,factory_id,code,name,kind,status,hourly_rate) VALUES(?,?,?,?,?,?,'IDLE',60)",
                id,
                t,
                tenant.plant(),
                code,
                code + " line",
                kind);
        return id;
    }

    protected String item(String sku, String type, String uom, String cost) {
        String id = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,?,?,?,?,?,0)",
                id,
                t,
                sku,
                sku + " name",
                type,
                uom,
                bd(cost));
        return id;
    }

    protected String skill(String code) {
        return str(skills.create(planner, new SkillCommands.Skill(code, code + " skill", ""), id()), "id");
    }

    protected void grant(Actor user, String skill, int level, LocalDate until) {
        skills.assign(
                planner,
                new SkillCommands.UserSkill(user.id(), skill, level, until == null ? null : until.toString(), ""),
                id());
    }

    protected static RoutingCommands.Operation op(
            int seq,
            String name,
            String machine,
            List<String> alternates,
            String setup,
            String runPerUnit,
            String crew,
            String skill,
            Integer level,
            boolean inspection) {
        return new RoutingCommands.Operation(
                seq,
                name,
                machine,
                alternates,
                new BigDecimal(setup),
                new BigDecimal(runPerUnit),
                new BigDecimal(crew),
                skill,
                level,
                inspection,
                name + " instructions");
    }

    protected static RoutingCommands.Operation op(int seq, String name, String machine) {
        return op(seq, name, machine, List.of(), "0", "0.01", "1", null, null, false);
    }

    protected String draftRouting(String product, String revision, RoutingCommands.Operation... ops) {
        return str(
                routings.create(
                        planner,
                        new RoutingCommands.Routing(product, revision, "", new ArrayList<>(List.of(ops))),
                        id()),
                "id");
    }

    protected String approvedRouting(String product, String revision, RoutingCommands.Operation... ops) {
        String id = draftRouting(product, revision, ops);
        routings.approve(otherPlanner, id, id());
        return id;
    }

    /** A draft manufacturing order for the fixture bottle on {@code machine}. */
    protected String order(String quantity, String machine, String priority) {
        return str(
                mo.create(
                        planner,
                        new ManufacturingCommands.Manufacture(
                                bom,
                                machine,
                                null,
                                bd(quantity),
                                priority,
                                LocalDate.now().plusDays(5).toString()),
                        id()),
                "id");
    }

    /** An order approved by a second planner, reserved and issued: RELEASED. */
    protected String releasedOrder(String quantity, String machine, String priority) {
        String id = order(quantity, machine, priority);
        mo.transition(otherPlanner, id, "approve", id());
        mo.transition(planner, id, "reserve", id());
        mo.transition(warehouse, id, "issue", id());
        return id;
    }

    protected Map<String, Object> orderRow(String id) {
        return row("SELECT * FROM manufacturing_orders WHERE tenant_id=? AND id=?", t, id);
    }

    protected String machineStatus(String machine) {
        return str(row("SELECT status FROM machines WHERE tenant_id=? AND id=?", t, machine), "status");
    }
}
