package lk.dexlanka.erp.maintenance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.platform.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Shared fixture for the maintenance tests: a fresh tenant with users per role (including MAINTENANCE), two machines
 * (M1 idle, M2 running) and a spare part SP (pcs, Rs. 100) with two released lots: 5 pcs at Rs. 100 (older) and 10 pcs
 * at Rs. 120.
 */
abstract class MaintenanceTestSupport extends IntegrationTestBase {
    @Autowired
    DowntimeService downtime;

    @Autowired
    WorkOrderService orders;

    @Autowired
    SparePartService parts;

    @Autowired
    PlanService plans;

    @Autowired
    MachineStatusService machines;

    @Autowired
    MaintenanceQuery query;

    @Autowired
    MaintenanceMetrics metrics;

    TestTenant tenant;
    String t, m1, m2, spare, lotA, lotB;
    Actor admin, planner, planner2, operator, technician, warehouse, viewer;

    @BeforeEach
    void maintenanceFixture() {
        tenant = createTenant();
        t = tenant.id();
        admin = createUser(t, "ADMIN");
        planner = createUser(t, "PLANNER");
        planner2 = createUser(t, "PLANNER");
        operator = createUser(t, "OPERATOR");
        technician = createUser(t, "MAINTENANCE");
        warehouse = createUser(t, "WAREHOUSE");
        viewer = createUser(t, "VIEWER");
        m1 = machine("M1", "IDLE");
        m2 = machine("M2", "RUNNING");
        spare = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,'SP','Bearing','SPARE_PART','pcs',100,0)",
                spare,
                t);
        lotA = openingStock(admin, spare, tenant.rawWarehouse(), "5", "100");
        update("UPDATE lots SET created_at=now()-interval '2 days' WHERE tenant_id=? AND id=?", t, lotA);
        lotB = openingStock(admin, spare, tenant.rawWarehouse(), "10", "120");
    }

    // ------------------------------------------------------------------ builders

    String machine(String code, String status) {
        String id = id();
        update(
                "INSERT INTO machines(id,tenant_id,factory_id,code,name,kind,status,hourly_rate) VALUES(?,?,?,?,?,'Test',?,1000)",
                id,
                t,
                tenant.plant(),
                code,
                "Machine " + code,
                status);
        return id;
    }

    /** A manufacturing order on {@code machine} in {@code status} (direct insert; only machine and status matter). */
    String productionOrder(String machine, String status) {
        String product = id();
        String bom = id();
        String order = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,?,'Bottle','FINISHED_GOOD','pcs',5,0)",
                product,
                t,
                "FG-" + product.substring(0, 8));
        update(
                "INSERT INTO boms(id,tenant_id,product_id,revision,status,output_qty) VALUES(?,?,?,'R1','DRAFT',100)",
                bom,
                t,
                product);
        update(
                "INSERT INTO manufacturing_orders(id,tenant_id,number,product_id,bom_id,bom_revision,machine_id,factory_id,quantity,status,priority,due_date,created_by) VALUES(?,?,?,?,?,'R1',?,?,100,?,'NORMAL',CURRENT_DATE,?)",
                order,
                t,
                "MO-" + order.substring(0, 8),
                product,
                bom,
                machine,
                tenant.plant(),
                status,
                planner.id());
        return order;
    }

    MaintenanceCommands.ReportDowntime report(String machine, String reason, String start, String end) {
        return new MaintenanceCommands.ReportDowntime(machine, reason, null, start, end, null, "");
    }

    Map<String, Object> closedDowntime(Actor actor, String machine, String reason, String start, String end) {
        return downtime.report(actor, report(machine, reason, start, end), id());
    }

    MaintenanceCommands.RequestWorkOrder corrective(String machine, boolean breakdown) {
        return new MaintenanceCommands.RequestWorkOrder(
                machine, "Hydraulic leak", "Oil under the clamp unit", null, breakdown, null, null, null);
    }

    /** A corrective order requested by the operator and approved by the planner. */
    String approvedOrder(String machine, boolean breakdown) {
        String id = str(orders.request(operator, corrective(machine, breakdown), id()), "id");
        orders.approve(planner, id, new MaintenanceCommands.Approve(null, null, null), id());
        return id;
    }

    /** An approved corrective order started by the technician. */
    String startedOrder(String machine) {
        String id = approvedOrder(machine, false);
        orders.start(technician, id, id());
        return id;
    }

    MaintenanceCommands.Plan plan(String machine, int days, String due) {
        return new MaintenanceCommands.Plan(
                machine,
                "Monthly service",
                "",
                List.of("Check oil", "Grease guides"),
                days,
                null,
                due,
                new BigDecimal("2"),
                null);
    }

    Map<String, Object> workOrderRow(String id) {
        return row("SELECT * FROM maintenance_work_orders WHERE tenant_id=? AND id=?", t, id);
    }

    String machineStatus(String machine) {
        return str(row("SELECT status FROM machines WHERE tenant_id=? AND id=?", t, machine), "status");
    }

    BigDecimal quantity(String lot) {
        return decimal("SELECT quantity FROM stock_balances WHERE tenant_id=? AND lot_id=?", t, lot);
    }

    /** Debit minus credit of {@code account} on journal entries with {@code reference}. */
    BigDecimal journal(String reference, String account) {
        return decimal(
                "SELECT COALESCE(SUM(l.debit-l.credit),0) FROM journal_lines l JOIN journal_entries e ON e.tenant_id=l.tenant_id AND e.id=l.entry_id WHERE e.tenant_id=? AND e.reference=? AND l.account=?",
                t,
                reference,
                account);
    }

    long unbalancedEntries() {
        return count(
                "SELECT COUNT(*) FROM (SELECT entry_id FROM journal_lines WHERE tenant_id=? GROUP BY entry_id HAVING SUM(debit)<>SUM(credit)) x",
                t);
    }

    List<Object> actions(String reference) {
        return rows(
                        "SELECT action FROM audit_events WHERE tenant_id=? AND reference=? ORDER BY created_at,id",
                        t,
                        reference)
                .stream()
                .map(r -> r.get("action"))
                .toList();
    }
}
