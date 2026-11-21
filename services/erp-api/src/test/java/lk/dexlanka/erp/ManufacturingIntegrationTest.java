package lk.dexlanka.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lk.dexlanka.erp.catalog.CatalogCommands;
import lk.dexlanka.erp.catalog.CatalogService;
import lk.dexlanka.erp.manufacturing.ManufacturingCommands;
import lk.dexlanka.erp.manufacturing.ManufacturingService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.procurement.ProcurementCommands;
import lk.dexlanka.erp.procurement.ProcurementService;
import lk.dexlanka.erp.quality.QualityCommands;
import lk.dexlanka.erp.quality.QualityService;
import lk.dexlanka.erp.reporting.OrderDetailsQuery;
import lk.dexlanka.erp.sales.SalesCommands;
import lk.dexlanka.erp.sales.SalesService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

class ManufacturingIntegrationTest extends IntegrationTestBase {
    @Autowired
    ManufacturingService mo;

    @Autowired
    ProcurementService procurement;

    @Autowired
    QualityService quality;

    @Autowired
    SalesService sales;

    @Autowired
    OrderDetailsQuery details;

    @Autowired
    CatalogService catalog;

    String t, raw, fg, bom, machine, warehouse, finished, lot, supplier, customer;
    Actor admin, planner, operator;

    @BeforeEach
    void fixture() {
        var tenant = createTenant();
        t = tenant.id();
        warehouse = tenant.rawWarehouse();
        finished = tenant.finishedWarehouse();
        raw = id();
        fg = id();
        bom = id();
        machine = id();
        supplier = id();
        customer = id();
        admin = createUser(t, "ADMIN");
        planner = createUser(t, "PLANNER");
        operator = createUser(t, "OPERATOR");
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,'RM','Resin','RAW_MATERIAL','kg',10,0),(?,?,'FG','Bottle','FINISHED_GOOD','pcs',10,0)",
                raw,
                t,
                fg,
                t);
        update(
                "INSERT INTO partners(id,tenant_id,name,type) VALUES(?,?,'Supplier','SUPPLIER'),(?,?,'Customer','CUSTOMER')",
                supplier,
                t,
                customer,
                t);
        update(
                "INSERT INTO machines(id,tenant_id,factory_id,code,name,kind,status,hourly_rate) VALUES(?,?,?,'M-01','Moulding','Moulding','IDLE',10)",
                machine,
                t,
                tenant.plant());
        update(
                "INSERT INTO boms(id,tenant_id,product_id,revision,status,output_qty) VALUES(?,?,?,'R1','DRAFT',1)",
                bom,
                t,
                fg);
        update("INSERT INTO bom_lines(id,tenant_id,bom_id,item_id,quantity) VALUES(?,?,?,?,1)", id(), t, bom, raw);
        update("UPDATE boms SET status='APPROVED' WHERE tenant_id=? AND id=?", t, bom);
        lot = openingStock(admin, raw, warehouse, "10", "10");
    }

    String draft() {
        return str(
                mo.create(
                        planner,
                        new ManufacturingCommands.Manufacture(
                                bom,
                                machine,
                                null,
                                bd("10"),
                                "NORMAL",
                                LocalDate.now().plusDays(1).toString()),
                        id()),
                "id");
    }

    String started() {
        String o = draft();
        mo.transition(admin, o, "approve", id());
        mo.transition(admin, o, "reserve", id());
        mo.transition(admin, o, "issue", id());
        mo.transition(admin, o, "start", id());
        return o;
    }

    ManufacturingCommands.Complete output(String good, String scrap, String labour, String machineCost) {
        return new ManufacturingCommands.Complete(bd(good), bd(scrap), bd(labour), bd(machineCost), "Test observation");
    }

    Map<String, Object> inspection(String order) {
        return row(
                "SELECT i.*,l.id AS output_lot FROM inspections i JOIN lots l ON l.tenant_id=i.tenant_id AND l.id=i.lot_id WHERE l.tenant_id=? AND l.source_id=? ORDER BY i.created_at DESC LIMIT 1",
                t,
                order);
    }

    @Test
    void partialOutputRetainsAndFinallyReconcilesWip() {
        String o = started();
        mo.complete(operator, o, output("4", "0", "20", "0"), id());
        var partial = mo.order(admin, o);
        assertThat(dec(partial.get("wip_cost"))).isEqualByComparingTo("72");
        assertThat(dec(partial.get("finished_cost"))).isEqualByComparingTo("48");
        assertThat(partial.get("status")).isEqualTo("PARTIALLY_COMPLETED");
        mo.complete(operator, o, output("6", "0", "30", "0"), id());
        var done = mo.order(admin, o);
        assertThat(dec(done.get("wip_cost"))).isEqualByComparingTo("0");
        assertThat(dec(done.get("finished_cost"))).isEqualByComparingTo("150");
        assertThat(decimal(
                        "SELECT SUM(value_amount) FROM stock_balances b JOIN lots l ON l.tenant_id=b.tenant_id AND l.id=b.lot_id WHERE l.tenant_id=? AND l.source_id=?",
                        t,
                        o))
                .isEqualByComparingTo("150");
    }

    @Test
    void repeatedCompletionDoesNotDoubleConsumeOrProduce() {
        String o = started();
        String key = id();
        var a = mo.complete(operator, o, output("10", "0", "20", "10"), key);
        mo.complete(operator, o, output("10", "0", "20", "10"), key);
        assertThat(dec(mo.order(admin, o).get("good_qty"))).isEqualByComparingTo("10");
        assertThat(count("SELECT COUNT(*) FROM production_actuals WHERE tenant_id=? AND order_id=?", t, o))
                .isEqualTo(1);
        assertThat(a.get("status")).isEqualTo("QUALITY_REVIEW");
    }

    @Test
    void idempotencyKeyCannotBeReusedWithDifferentPayload() {
        String o = started();
        String key = id();
        mo.complete(operator, o, output("4", "0", "0", "0"), key);
        assertThatThrownBy(() -> mo.complete(operator, o, output("5", "0", "0", "0"), key))
                .hasMessageContaining("different request");
    }

    @Test
    void concurrentFinalStockReservationsHaveOneWinner() throws Exception {
        String a = draft();
        String b = draft();
        mo.transition(admin, a, "approve", id());
        mo.transition(admin, b, "approve", id());
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (String oid : List.of(a, b)) {
                results.add(pool.submit(() -> {
                    gate.await();
                    try {
                        mo.transition(admin, oid, "reserve", id());
                        return true;
                    } catch (ResponseStatusException e) {
                        return false;
                    }
                }));
            }
            gate.countDown();
            int wins = 0;
            for (var r : results) {
                if (r.get(20, TimeUnit.SECONDS)) wins++;
            }
            assertThat(wins).isEqualTo(1);
            assertThat(dec(row("SELECT reserved FROM stock_balances WHERE tenant_id=? AND lot_id=?", t, lot)
                            .get("reserved")))
                    .isEqualByComparingTo("10");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void orderMaterialSnapshotSurvivesFutureBomChanges() {
        String o = draft();
        assertThatThrownBy(() -> update("UPDATE bom_lines SET quantity=3 WHERE tenant_id=? AND bom_id=?", t, bom))
                .hasMessageContaining("immutable");
        assertThat(dec(row("SELECT required_qty FROM order_materials WHERE tenant_id=? AND order_id=?", t, o)
                        .get("required_qty")))
                .isEqualByComparingTo("10");
    }

    @Test
    void anotherTenantCannotAccessOrMutateAnOrder() {
        String o = draft();
        Actor other = new Actor(id(), id(), "other@example.test", "Other", "ADMIN");
        assertThatThrownBy(() -> details.details(other, o)).hasMessageContaining("not found");
        assertThatThrownBy(() -> mo.transition(other, o, "approve", id())).hasMessageContaining("not found");
    }

    @Test
    void operatorCannotApproveAndCreatorCannotSelfApprove() {
        String o = draft();
        assertThatThrownBy(() -> mo.transition(operator, o, "approve", id())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> mo.transition(planner, o, "approve", id()))
                .hasMessageContaining("different authorized person");
    }

    @Test
    void heldFinishedLotCannotBeDispatched() {
        String o = started();
        mo.complete(operator, o, output("10", "0", "0", "0"), id());
        var sale = sales.sale(
                admin,
                new SalesCommands.Sale(
                        customer, fg, bd("10"), bd("25"), LocalDate.now().toString()),
                id());
        var q = inspection(o);
        assertThatThrownBy(() -> sales.dispatch(
                        admin,
                        str(sale, "id"),
                        new SalesCommands.Dispatch(str(q, "output_lot"), bd("10"), "DN-TEST"),
                        id()))
                .hasMessageContaining("Quality-held");
    }

    @Test
    void failedIncomingInspectionReversesStockAndClearing() {
        var p = procurement.purchase(
                planner,
                new ProcurementCommands.Purchase(
                        supplier, raw, bd("5"), bd("10"), LocalDate.now().toString()),
                id());
        procurement.approvePurchase(admin, str(p, "id"), id());
        var r = procurement.receive(
                admin, str(p, "id"), new ProcurementCommands.Receive(bd("5"), warehouse, "DAMAGED"), id());
        var inspection = row("SELECT id FROM inspections WHERE tenant_id=? AND lot_id=?", t, r.get("lot_id"));
        quality.inspect(
                admin,
                str(inspection, "id"),
                new QualityCommands.Inspect("FAILED", "Damaged resin bag; return to supplier"),
                id());
        assertThat(dec(row("SELECT quantity FROM stock_balances WHERE tenant_id=? AND lot_id=?", t, r.get("lot_id"))
                        .get("quantity")))
                .isZero();
        assertThat(dec(row("SELECT received FROM purchase_orders WHERE tenant_id=? AND id=?", t, p.get("id"))
                        .get("received")))
                .isZero();
    }

    @Test
    void cancellationReleasesReservationsAndPreservesHistory() {
        String o = draft();
        mo.transition(admin, o, "approve", id());
        mo.transition(admin, o, "reserve", id());
        mo.transition(admin, o, "cancel", id());
        assertThat(dec(row("SELECT reserved FROM stock_balances WHERE tenant_id=? AND lot_id=?", t, lot)
                        .get("reserved")))
                .isZero();
        assertThat(count(
                        "SELECT COUNT(*) FROM reservations WHERE tenant_id=? AND order_id=? AND status='RELEASED'",
                        t,
                        o))
                .isEqualTo(1);
    }

    @Test
    void completionCannotOverrunPlannedOutput() {
        String o = started();
        assertThatThrownBy(() -> mo.complete(operator, o, output("11", "0", "0", "0"), id()))
                .hasMessageContaining("remaining planned");
        assertThat(dec(mo.order(admin, o).get("good_qty"))).isZero();
    }

    @Test
    void closedPeriodRollsBackProductionIncludingInventory() {
        String o = started();
        update("INSERT INTO financial_periods(tenant_id,end_date,closed) VALUES(?,CURRENT_DATE,true)", t);
        assertThatThrownBy(() -> mo.complete(operator, o, output("10", "0", "0", "0"), id()))
                .hasMessageContaining("period is closed");
        assertThat(count("SELECT COUNT(*) FROM production_actuals WHERE tenant_id=?", t))
                .isZero();
    }

    @Test
    void databaseRejectsMutationOfPostedStockAndUnbalancedJournals() {
        assertThatThrownBy(() -> update("UPDATE stock_ledger SET delta=500 WHERE tenant_id=?", t))
                .hasMessageContaining("immutable");
        String entry = id();
        update(
                "INSERT INTO journal_entries(id,tenant_id,reference,description,actor) VALUES(?,?,'INVALID','Test','Tester')",
                entry,
                t);
        assertThatThrownBy(() -> update(
                        "INSERT INTO journal_lines(id,tenant_id,entry_id,account,debit) VALUES(?,?,?,'TEST',10)",
                        id(),
                        t,
                        entry))
                .hasMessageContaining("balance");
    }

    @Test
    void wholePiecesAreRequiredAndUnitMismatchRollsBack() {
        assertThatThrownBy(() -> mo.create(
                        planner,
                        new ManufacturingCommands.Manufacture(
                                bom,
                                machine,
                                null,
                                bd("1.5"),
                                "NORMAL",
                                LocalDate.now().toString()),
                        id()))
                .hasMessageContaining("whole numbers");
        assertThatThrownBy(() -> update(
                        "INSERT INTO stock_ledger(id,tenant_id,lot_id,item_id,warehouse_id,uom,delta,value_delta,unit_cost,reference,reason,actor) VALUES(?,?,?,?,?,'pcs',1,10,10,'TEST','TEST','Tester')",
                        id(),
                        t,
                        lot,
                        raw,
                        warehouse))
                .hasMessageContaining("mismatch");
    }

    @Test
    void fullPurchaseToCustomerWorkflowReconciles() {
        var po = procurement.purchase(
                planner,
                new ProcurementCommands.Purchase(
                        supplier, raw, bd("10"), bd("12"), LocalDate.now().toString()),
                id());
        procurement.approvePurchase(admin, str(po, "id"), id());
        var receipt = procurement.receive(
                admin, str(po, "id"), new ProcurementCommands.Receive(bd("10"), warehouse, "SUP-TEST"), id());
        var qi = row("SELECT id FROM inspections WHERE tenant_id=? AND lot_id=?", t, receipt.get("lot_id"));
        quality.inspect(admin, str(qi, "id"), new QualityCommands.Inspect("PASSED", "Incoming resin accepted"), id());
        procurement.invoice(admin, str(receipt, "id"), new ProcurementCommands.Invoice("INV-TEST"), id());
        var sale = sales.sale(
                admin,
                new SalesCommands.Sale(
                        customer, fg, bd("10"), bd("25"), LocalDate.now().toString()),
                id());
        var o = mo.create(
                planner,
                new ManufacturingCommands.Manufacture(
                        bom,
                        machine,
                        str(sale, "id"),
                        bd("10"),
                        "NORMAL",
                        LocalDate.now().toString()),
                id());
        String oid = str(o, "id");
        for (String step : List.of("approve", "reserve", "issue", "start")) mo.transition(admin, oid, step, id());
        mo.complete(operator, oid, output("10", "0", "20", "10"), id());
        var qi2 = inspection(oid);
        quality.inspect(
                admin,
                str(qi2, "id"),
                new QualityCommands.Inspect("PASSED", "Final dimensional checks accepted"),
                id());
        sales.dispatch(
                admin, str(sale, "id"), new SalesCommands.Dispatch(str(qi2, "output_lot"), bd("10"), "DN-E2E"), id());
        mo.transition(admin, oid, "close", id());
        assertThat(mo.order(admin, oid).get("status")).isEqualTo("CLOSED");
        assertThat(row("SELECT status FROM sales_orders WHERE tenant_id=? AND id=?", t, sale.get("id"))
                        .get("status"))
                .isEqualTo("DELIVERED");
        assertThat(decimal("SELECT COALESCE(SUM(debit-credit),0) FROM journal_lines WHERE tenant_id=?", t))
                .isZero();
        assertThat(decimal("SELECT SUM(delta) FROM stock_ledger WHERE tenant_id=? AND item_id=?", t, fg))
                .isZero();
    }

    @Test
    void bomDraftApprovalFreezesComponentsAndCannotReferenceForeignItems() {
        var draft = catalog.bom(
                planner,
                new CatalogCommands.Bom(
                        fg, "R2", bd("1"), bd("0.98"), List.of(new CatalogCommands.BomLine(raw, bd("1.1")))),
                id());
        catalog.approveBom(admin, str(draft, "id"), id());
        assertThatThrownBy(() -> update("UPDATE boms SET output_qty=5 WHERE tenant_id=? AND id=?", t, draft.get("id")))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> catalog.bom(
                        planner,
                        new CatalogCommands.Bom(
                                fg, "R3", bd("1"), bd("1"), List.of(new CatalogCommands.BomLine(id(), bd("1")))),
                        id()))
                .hasMessageContaining("not found");
        assertThat(rows("SELECT id FROM boms WHERE tenant_id=? AND revision='R3'", t))
                .isEmpty();
    }

    @Test
    void createdAccountAuthenticatesWithBcryptAndCsrf() throws Exception {
        String email = id() + "@example.test";
        String password = "isolated-test-password-123";
        catalog.user(admin, new CatalogCommands.User(email, "New planner", password, "PLANNER"), id());
        var hash = str(row("SELECT password_hash FROM users WHERE email=?", email), "password_hash");
        assertThat(hash).startsWith("$2a$").doesNotContain(password);
        mvc.perform(post("/api/auth/login").with(csrf()).param("email", email).param("password", password))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> catalog.user(
                        operator,
                        new CatalogCommands.User(id() + "@example.test", "No access", password, "ADMIN"),
                        id()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void failingMigrationRollsBackItsPartialDdl() throws Exception {
        String schema = "migration_" + id().replace("-", "");
        var folder = Files.createTempDirectory("dexlanka-migration-test-");
        try {
            Files.writeString(folder.resolve("V1__baseline.sql"), "CREATE TABLE prior_success(id integer);");
            Files.writeString(folder.resolve("V2__failing.sql"), "CREATE TABLE must_rollback(id integer); SELECT 1/0;");
            var flyway = Flyway.configure()
                    .dataSource(s.db().getDataSource())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("filesystem:" + folder)
                    .load();
            assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class);
            assertThat(count(
                            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='prior_success'",
                            schema))
                    .isEqualTo(1);
            assertThat(count(
                            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='must_rollback'",
                            schema))
                    .isZero();
        } finally {
            try (var files = Files.list(folder)) {
                for (var file : files.toList()) Files.delete(file);
            }
            Files.delete(folder);
        }
    }

    @Test
    void exactBomRatiosDoNotRoundAnExtraPiece() {
        var line = new HashMap<String, Object>();
        line.put("quantity", bd("3"));
        line.put("uom", "pcs");
        assertThat(ManufacturingService.requirement(line, bd("10"), bd("3"))).isEqualByComparingTo("10");
    }

    @Test
    void snapshotScalingRoundsPiecesUpAndKeepsUnitsExplicit() {
        String packaging = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,'PK','Carton','PACKAGING','pcs',2,0)",
                packaging,
                t);
        var b = catalog.bom(
                planner,
                new CatalogCommands.Bom(
                        fg, "R4", bd("1000"), bd("1"), List.of(new CatalogCommands.BomLine(packaging, bd("20")))),
                id());
        catalog.approveBom(admin, str(b, "id"), id());
        var o = mo.create(
                planner,
                new ManufacturingCommands.Manufacture(
                        str(b, "id"),
                        machine,
                        null,
                        bd("40"),
                        "NORMAL",
                        LocalDate.now().toString()),
                id());
        assertThat(dec(row("SELECT required_qty FROM order_materials WHERE tenant_id=? AND order_id=?", t, o.get("id"))
                        .get("required_qty")))
                .isEqualByComparingTo("1");
    }

    @Test
    void httpRequiresSessionCsrfRoleAndValidInput() throws Exception {
        mvc.perform(get("/api/state")).andExpect(status().isUnauthorized());
        // No CSRF token: rejected by Spring Security before the controller runs.
        mvc.perform(post("/api/manufacturing")
                        .with(as(operator))
                        .contentType("application/json")
                        .header("Idempotency-Key", id())
                        .content("{}"))
                .andExpect(status().isForbidden());
        // Valid request, but OPERATOR may not create manufacturing orders.
        command(
                        operator,
                        "/api/manufacturing",
                        new ManufacturingCommands.Manufacture(
                                bom,
                                machine,
                                null,
                                bd("10"),
                                "NORMAL",
                                LocalDate.now().toString()))
                .andExpect(status().isForbidden());
        // Authorized, but bean validation rejects the empty body.
        command(admin, "/api/manufacturing", Map.of()).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/demo")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
