package lk.dexlanka.erp;

import static lk.dexlanka.erp.Store.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"app.bootstrap-email=test@example.test","app.bootstrap-password=only-for-isolated-tests-123","server.servlet.session.cookie.secure=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ManufacturingIntegrationTest {
    @Autowired Store s;@Autowired ManufacturingService mo;@Autowired OperationsService ops;@Autowired Queries q;@Autowired MockMvc mvc;@Autowired CatalogService catalog;
    String t,raw,fg,bom,machine,warehouse,finished,lot,supplier,customer;Actor admin,planner,operator;
    @BeforeEach void fixture() {
        t=id();raw=id();fg=id();bom=id();machine=id();warehouse=id();finished=id();lot=id();supplier=id();customer=id();
        s.db.update("INSERT INTO tenants(id,name) VALUES(?,'Integration fixture')",t);
        String plant=id();s.db.update("INSERT INTO factories(id,tenant_id,name,location) VALUES(?,?,'Test plant','Test')",plant,t);
        s.db.update("INSERT INTO warehouses(id,tenant_id,factory_id,name,kind) VALUES(?,?,?,'Raw','RAW'),(?,?,?,'Finished','FINISHED')",warehouse,t,plant,finished,t,plant);
        admin=fixtureUser("ADMIN");planner=fixtureUser("PLANNER");operator=fixtureUser("OPERATOR");
        s.db.update("INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,'RM','Resin','RAW_MATERIAL','kg',10,0),(?,?,'FG','Bottle','FINISHED_GOOD','pcs',10,0)",raw,t,fg,t);
        s.db.update("INSERT INTO partners(id,tenant_id,name,type) VALUES(?,?,'Supplier','SUPPLIER'),(?,?,'Customer','CUSTOMER')",supplier,t,customer,t);
        s.db.update("INSERT INTO machines(id,tenant_id,factory_id,code,name,kind,status,hourly_rate) VALUES(?,?,?,'M-01','Moulding','Moulding','IDLE',10)",machine,t,plant);
        s.db.update("INSERT INTO boms(id,tenant_id,product_id,revision,status,output_qty) VALUES(?,?,?,'R1','DRAFT',1)",bom,t,fg);
        s.db.update("INSERT INTO bom_lines(id,tenant_id,bom_id,item_id,quantity) VALUES(?,?,?,?,1)",id(),t,bom,raw);
        s.db.update("UPDATE boms SET status='APPROVED' WHERE tenant_id=? AND id=?",t,bom);
        s.db.update("INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost) VALUES(?,?,'OPENING',?,?,'RELEASED','OPENING','TEST',10)",lot,t,raw,warehouse);
        s.ledger(admin,lot,bd("10"),"OPENING","TEST_OPENING");s.journal(admin,"OPENING","Test opening","RAW_INVENTORY","OPENING_EQUITY",bd("100"));
    }
    Actor fixtureUser(String role) {String uid=id(),email=uid+"@example.test";s.db.update("INSERT INTO users(id,tenant_id,email,name,password_hash,role) VALUES(?,?,?,?,'unused',?)",uid,t,email,role,role);return new Actor(uid,t,email,role,role);}
    static BigDecimal bd(String v){return new BigDecimal(v);}
    String draft(){return str(mo.create(planner,new Commands.Manufacture(bom,machine,null,bd("10"),"NORMAL",LocalDate.now().plusDays(1).toString()),id()),"id");}
    String started(){String o=draft();mo.transition(admin,o,"approve",id());mo.transition(admin,o,"reserve",id());mo.transition(admin,o,"issue",id());mo.transition(admin,o,"start",id());return o;}
    Commands.Complete output(String good,String scrap,String labour,String machineCost){return new Commands.Complete(bd(good),bd(scrap),bd(labour),bd(machineCost),"Test observation");}
    Map<String,Object> inspection(String order){return s.one("SELECT i.*,l.id AS output_lot FROM inspections i JOIN lots l ON l.tenant_id=i.tenant_id AND l.id=i.lot_id WHERE l.tenant_id=? AND l.source_id=? ORDER BY i.created_at DESC LIMIT 1",t,order);}
    @Test void partialOutputRetainsAndFinallyReconcilesWip() {
        String o=started();mo.complete(operator,o,output("4","0","20","0"),id());var partial=mo.order(admin,o);
        assertThat(dec(partial.get("wip_cost"))).isEqualByComparingTo("72");assertThat(dec(partial.get("finished_cost"))).isEqualByComparingTo("48");assertThat(partial.get("status")).isEqualTo("PARTIALLY_COMPLETED");
        mo.complete(operator,o,output("6","0","30","0"),id());var done=mo.order(admin,o);
        assertThat(dec(done.get("wip_cost"))).isEqualByComparingTo("0");assertThat(dec(done.get("finished_cost"))).isEqualByComparingTo("150");
        assertThat(s.db.queryForObject("SELECT SUM(value_amount) FROM stock_balances b JOIN lots l ON l.tenant_id=b.tenant_id AND l.id=b.lot_id WHERE l.tenant_id=? AND l.source_id=?",BigDecimal.class,t,o)).isEqualByComparingTo("150");
    }
    @Test void repeatedCompletionDoesNotDoubleConsumeOrProduce() {
        String o=started(),key=id();var a=mo.complete(operator,o,output("10","0","20","10"),key);mo.complete(operator,o,output("10","0","20","10"),key);
        assertThat(dec(mo.order(admin,o).get("good_qty"))).isEqualByComparingTo("10");assertThat(s.db.queryForObject("SELECT COUNT(*) FROM production_actuals WHERE tenant_id=? AND order_id=?",Integer.class,t,o)).isEqualTo(1);assertThat(a.get("status")).isEqualTo("QUALITY_REVIEW");
    }
    @Test void idempotencyKeyCannotBeReusedWithDifferentPayload(){String o=started(),key=id();mo.complete(operator,o,output("4","0","0","0"),key);assertThatThrownBy(()->mo.complete(operator,o,output("5","0","0","0"),key)).hasMessageContaining("different request");}
    @Test void concurrentFinalStockReservationsHaveOneWinner() throws Exception {
        String a=draft(),b=draft();mo.transition(admin,a,"approve",id());mo.transition(admin,b,"approve",id());
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(String oid:List.of(a,b))results.add(pool.submit(()->{gate.await();try{mo.transition(admin,oid,"reserve",id());return true;}catch(org.springframework.web.server.ResponseStatusException e){return false;}}));
            gate.countDown();int wins=0;for(var r:results)if(r.get(20,TimeUnit.SECONDS))wins++;
            assertThat(wins).isEqualTo(1);assertThat(dec(s.one("SELECT reserved FROM stock_balances WHERE tenant_id=? AND lot_id=?",t,lot).get("reserved"))).isEqualByComparingTo("10");
        } finally {pool.shutdownNow();}
    }
    @Test void orderMaterialSnapshotSurvivesFutureBomChanges(){String o=draft();assertThatThrownBy(()->s.db.update("UPDATE bom_lines SET quantity=3 WHERE tenant_id=? AND bom_id=?",t,bom)).hasMessageContaining("immutable");assertThat(dec(s.one("SELECT required_qty FROM order_materials WHERE tenant_id=? AND order_id=?",t,o).get("required_qty"))).isEqualByComparingTo("10");}
    @Test void anotherTenantCannotAccessOrMutateAnOrder(){String o=draft();Actor other=new Actor(id(),id(),"other@example.test","Other","ADMIN");assertThatThrownBy(()->q.details(other,o)).hasMessageContaining("not found");assertThatThrownBy(()->mo.transition(other,o,"approve",id())).hasMessageContaining("not found");}
    @Test void operatorCannotApproveAndCreatorCannotSelfApprove(){String o=draft();assertThatThrownBy(()->mo.transition(operator,o,"approve",id())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);assertThatThrownBy(()->mo.transition(planner,o,"approve",id())).hasMessageContaining("different authorized person");}
    @Test void heldFinishedLotCannotBeDispatched(){String o=started();mo.complete(operator,o,output("10","0","0","0"),id());var sale=ops.sale(admin,new Commands.Sale(customer,fg,bd("10"),bd("25"),LocalDate.now().toString()),id());var q=inspection(o);assertThatThrownBy(()->ops.dispatch(admin,str(sale,"id"),new Commands.Dispatch(str(q,"output_lot"),bd("10"),"DN-TEST"),id())).hasMessageContaining("Quality-held");}
    @Test void failedIncomingInspectionReversesStockAndClearing(){var p=ops.purchase(planner,new Commands.Purchase(supplier,raw,bd("5"),bd("10"),LocalDate.now().toString()),id());ops.approvePurchase(admin,str(p,"id"),id());var r=ops.receive(admin,str(p,"id"),new Commands.Receive(bd("5"),warehouse,"DAMAGED"),id());var inspection=s.one("SELECT id FROM inspections WHERE tenant_id=? AND lot_id=?",t,r.get("lot_id"));ops.inspect(admin,str(inspection,"id"),new Commands.Inspect("FAILED","Damaged resin bag; return to supplier"),id());assertThat(dec(s.one("SELECT quantity FROM stock_balances WHERE tenant_id=? AND lot_id=?",t,r.get("lot_id")).get("quantity"))).isZero();assertThat(dec(s.one("SELECT received FROM purchase_orders WHERE tenant_id=? AND id=?",t,p.get("id")).get("received"))).isZero();}
    @Test void cancellationReleasesReservationsAndPreservesHistory(){String o=draft();mo.transition(admin,o,"approve",id());mo.transition(admin,o,"reserve",id());mo.transition(admin,o,"cancel",id());assertThat(dec(s.one("SELECT reserved FROM stock_balances WHERE tenant_id=? AND lot_id=?",t,lot).get("reserved"))).isZero();assertThat(s.db.queryForObject("SELECT COUNT(*) FROM reservations WHERE tenant_id=? AND order_id=? AND status='RELEASED'",Integer.class,t,o)).isEqualTo(1);}
    @Test void completionCannotOverrunPlannedOutput(){String o=started();assertThatThrownBy(()->mo.complete(operator,o,output("11","0","0","0"),id())).hasMessageContaining("remaining planned");assertThat(dec(mo.order(admin,o).get("good_qty"))).isZero();}
    @Test void closedPeriodRollsBackProductionIncludingInventory(){String o=started();s.db.update("INSERT INTO financial_periods(tenant_id,end_date,closed) VALUES(?,CURRENT_DATE,true)",t);assertThatThrownBy(()->mo.complete(operator,o,output("10","0","0","0"),id())).hasMessageContaining("period is closed");assertThat(s.db.queryForObject("SELECT COUNT(*) FROM production_actuals WHERE tenant_id=?",Integer.class,t)).isZero();}
    @Test void databaseRejectsMutationOfPostedStockAndUnbalancedJournals(){assertThatThrownBy(()->s.db.update("UPDATE stock_ledger SET delta=500 WHERE tenant_id=?",t)).hasMessageContaining("immutable");String entry=id();s.db.update("INSERT INTO journal_entries(id,tenant_id,reference,description,actor) VALUES(?,?,'INVALID','Test','Tester')",entry,t);assertThatThrownBy(()->s.db.update("INSERT INTO journal_lines(id,tenant_id,entry_id,account,debit) VALUES(?,?,?,'TEST',10)",id(),t,entry)).hasMessageContaining("balance");}
    @Test void wholePiecesAreRequiredAndUnitMismatchRollsBack(){assertThatThrownBy(()->mo.create(planner,new Commands.Manufacture(bom,machine,null,bd("1.5"),"NORMAL",LocalDate.now().toString()),id())).hasMessageContaining("whole numbers");assertThatThrownBy(()->s.db.update("INSERT INTO stock_ledger(id,tenant_id,lot_id,item_id,warehouse_id,uom,delta,value_delta,unit_cost,reference,reason,actor) VALUES(?,?,?,?,?,'pcs',1,10,10,'TEST','TEST','Tester')",id(),t,lot,raw,warehouse)).hasMessageContaining("mismatch");}
    @Test void fullPurchaseToCustomerWorkflowReconciles(){
        var po=ops.purchase(planner,new Commands.Purchase(supplier,raw,bd("10"),bd("12"),LocalDate.now().toString()),id());ops.approvePurchase(admin,str(po,"id"),id());var receipt=ops.receive(admin,str(po,"id"),new Commands.Receive(bd("10"),warehouse,"SUP-TEST"),id());var qi=s.one("SELECT id FROM inspections WHERE tenant_id=? AND lot_id=?",t,receipt.get("lot_id"));ops.inspect(admin,str(qi,"id"),new Commands.Inspect("PASSED","Incoming resin accepted"),id());ops.invoice(admin,str(receipt,"id"),new Commands.Invoice("INV-TEST"),id());
        var sale=ops.sale(admin,new Commands.Sale(customer,fg,bd("10"),bd("25"),LocalDate.now().toString()),id());var o=mo.create(planner,new Commands.Manufacture(bom,machine,str(sale,"id"),bd("10"),"NORMAL",LocalDate.now().toString()),id());String oid=str(o,"id");for(String step:List.of("approve","reserve","issue","start"))mo.transition(admin,oid,step,id());mo.complete(operator,oid,output("10","0","20","10"),id());var qi2=inspection(oid);ops.inspect(admin,str(qi2,"id"),new Commands.Inspect("PASSED","Final dimensional checks accepted"),id());ops.dispatch(admin,str(sale,"id"),new Commands.Dispatch(str(qi2,"output_lot"),bd("10"),"DN-E2E"),id());mo.transition(admin,oid,"close",id());
        assertThat(mo.order(admin,oid).get("status")).isEqualTo("CLOSED");assertThat(s.one("SELECT status FROM sales_orders WHERE tenant_id=? AND id=?",t,sale.get("id")).get("status")).isEqualTo("DELIVERED");assertThat(s.db.queryForObject("SELECT COALESCE(SUM(debit-credit),0) FROM journal_lines WHERE tenant_id=?",BigDecimal.class,t)).isZero();assertThat(s.db.queryForObject("SELECT SUM(delta) FROM stock_ledger WHERE tenant_id=? AND item_id=?",BigDecimal.class,t,fg)).isZero();
    }

    @Test void bomDraftApprovalFreezesComponentsAndCannotReferenceForeignItems(){
        var draft=catalog.bom(planner,new Commands.Bom(fg,"R2",bd("1"),bd("0.98"),List.of(new Commands.BomLine(raw,bd("1.1")))),id());
        catalog.approveBom(admin,str(draft,"id"),id());
        assertThatThrownBy(()->s.db.update("UPDATE boms SET output_qty=5 WHERE tenant_id=? AND id=?",t,draft.get("id"))).hasMessageContaining("immutable");
        assertThatThrownBy(()->catalog.bom(planner,new Commands.Bom(fg,"R3",bd("1"),bd("1"),List.of(new Commands.BomLine(id(),bd("1")))),id())).hasMessageContaining("not found");
        assertThat(s.list("SELECT id FROM boms WHERE tenant_id=? AND revision='R3'",t)).isEmpty();
    }
    @Test void createdAccountAuthenticatesWithBcryptAndCsrf() throws Exception {
        String email=id()+"@example.test",password="isolated-test-password-123";
        catalog.user(admin,new Commands.User(email,"New planner",password,"PLANNER"),id());
        var hash=str(s.one("SELECT password_hash FROM users WHERE email=?",email),"password_hash");
        assertThat(hash).startsWith("$2a$").doesNotContain(password);
        mvc.perform(post("/api/auth/login").with(csrf()).param("email",email).param("password",password)).andExpect(status().isOk());
        assertThatThrownBy(()->catalog.user(operator,new Commands.User(id()+"@example.test","No access",password,"ADMIN"),id())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void failingMigrationRollsBackItsPartialDdl() throws Exception {
        String schema="migration_"+id().replace("-","");
        var folder=java.nio.file.Files.createTempDirectory("dexlanka-migration-test-");
        try {
            java.nio.file.Files.writeString(folder.resolve("V1__baseline.sql"),"CREATE TABLE prior_success(id integer);");
            java.nio.file.Files.writeString(folder.resolve("V2__failing.sql"),"CREATE TABLE must_rollback(id integer); SELECT 1/0;");
            var flyway=org.flywaydb.core.Flyway.configure().dataSource(s.db.getDataSource()).schemas(schema).defaultSchema(schema).locations("filesystem:"+folder).load();
            assertThatThrownBy(flyway::migrate).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
            assertThat(s.db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='prior_success'",Integer.class,schema)).isEqualTo(1);
            assertThat(s.db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='must_rollback'",Integer.class,schema)).isZero();
        } finally { try(var files=java.nio.file.Files.list(folder)){for(var file:files.toList())java.nio.file.Files.delete(file);}java.nio.file.Files.delete(folder); }
    }
    @Test void exactBomRatiosDoNotRoundAnExtraPiece(){
        var line=new HashMap<String,Object>();line.put("quantity",bd("3"));line.put("uom","pcs");
        assertThat(ManufacturingService.requirement(line,bd("10"),bd("3"))).isEqualByComparingTo("10");
    }
    @Test void snapshotScalingRoundsPiecesUpAndKeepsUnitsExplicit(){
        String packaging=id();s.db.update("INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,'PK','Carton','PACKAGING','pcs',2,0)",packaging,t);
        var b=catalog.bom(planner,new Commands.Bom(fg,"R4",bd("1000"),bd("1"),List.of(new Commands.BomLine(packaging,bd("20")))),id());catalog.approveBom(admin,str(b,"id"),id());
        var o=mo.create(planner,new Commands.Manufacture(str(b,"id"),machine,null,bd("40"),"NORMAL",LocalDate.now().toString()),id());
        assertThat(dec(s.one("SELECT required_qty FROM order_materials WHERE tenant_id=? AND order_id=?",t,o.get("id")).get("required_qty"))).isEqualByComparingTo("1");
    }

    @Test void httpRequiresSessionCsrfRoleAndValidInput() throws Exception {
        mvc.perform(get("/api/state")).andExpect(status().isUnauthorized());
        var principal=new Security.ErpUser(operator,"unused");
        mvc.perform(post("/api/manufacturing").with(user(principal)).contentType("application/json").header("Idempotency-Key",id()).content("{}")) .andExpect(status().isForbidden());
        mvc.perform(post("/api/manufacturing").with(user(principal)).with(csrf()).contentType("application/json").header("Idempotency-Key",id()).content(s.encode(new Commands.Manufacture(bom,machine,null,bd("10"),"NORMAL",LocalDate.now().toString())))).andExpect(status().isForbidden());
        mvc.perform(post("/api/manufacturing").with(user(new Security.ErpUser(admin,"unused"))).with(csrf()).contentType("application/json").header("Idempotency-Key",id()).content("{}")) .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/demo").with(csrf()).contentType("application/json").content("{}")) .andExpect(status().isNotFound());
    }
}
