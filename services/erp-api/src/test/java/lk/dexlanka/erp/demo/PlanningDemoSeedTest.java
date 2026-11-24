package lk.dexlanka.erp.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.reporting.PlanningQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the base, item master and planning demo seeds inside one transaction that is rolled back, so the shared test
 * database keeps no demo tenant.
 */
class PlanningDemoSeedTest extends IntegrationTestBase {
    @Autowired
    DemoSeed base;

    @Autowired
    ItemMasterDemoSeed itemMaster;

    @Autowired
    PlanningDemoSeed planning;

    @Autowired
    PlanningQuery mrp;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    @SuppressWarnings("unchecked")
    void seedsATwoLevelProductParentAndChildOrdersAndPlannedProductionOnce() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            base.seedDemo();
            itemMaster.seed();
            planning.seed();

            String bottle = str(row("SELECT id FROM items WHERE tenant_id='demo' AND sku='FG-101'"), "id");
            String preform = str(row("SELECT id FROM items WHERE tenant_id='demo' AND sku='SF-001'"), "id");
            assertThat(row("SELECT status FROM boms WHERE tenant_id='demo' AND product_id=? AND revision='R01'", bottle)
                            .get("status"))
                    .isEqualTo("APPROVED");
            var r02 = row("SELECT * FROM boms WHERE tenant_id='demo' AND product_id=? AND revision='R02'", bottle);
            assertThat(r02.get("status")).isEqualTo("DRAFT");
            assertThat(r02.get("source_bom_id")).isNotNull();

            // Parent A (approved) with one child for the preform shortage: 10,102 needed - 4,960 released.
            var parent = row(
                    "SELECT * FROM manufacturing_orders WHERE tenant_id='demo' AND product_id=? AND status='APPROVED'",
                    bottle);
            var child = row(
                    "SELECT * FROM manufacturing_orders WHERE tenant_id='demo' AND parent_order_id=?",
                    parent.get("id"));
            assertThat(child.get("product_id")).isEqualTo(preform);
            assertThat(dec(child.get("quantity"))).isEqualByComparingTo("5142");
            assertThat(child.get("status")).isEqualTo("DRAFT");
            assertThat(str(child, "due_date"))
                    .isEqualTo(LocalDate.now().plusDays(7).minusDays(2).toString());

            // Parent B (draft, no child) drives planned preform production.
            var draft = row(
                    "SELECT * FROM manufacturing_orders WHERE tenant_id='demo' AND product_id=? AND status='DRAFT'",
                    bottle);
            var plan = mrp.mrp(DemoPersonas.PLANNER);
            var planned = ((List<Map<String, Object>>) plan.get("plannedProduction"))
                    .stream()
                            .filter(p -> preform.equals(p.get("id")))
                            .findFirst()
                            .orElseThrow();
            assertThat(dec(planned.get("quantity")).signum()).isPositive();
            assertThat((List<Map<String, Object>>) planned.get("parent_orders"))
                    .extracting(p -> p.get("order_id"))
                    .contains(draft.get("id"));

            // Idempotent: a second start changes nothing.
            long audits = count("SELECT COUNT(*) FROM audit_events WHERE tenant_id='demo'");
            planning.seed();
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id='demo'"))
                    .isEqualTo(audits);
            status.setRollbackOnly();
        });
        assertThat(count("SELECT COUNT(*) FROM manufacturing_orders WHERE tenant_id='demo'"))
                .isZero();
    }
}
