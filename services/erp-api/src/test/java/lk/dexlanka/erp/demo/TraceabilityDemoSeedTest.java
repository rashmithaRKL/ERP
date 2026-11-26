package lk.dexlanka.erp.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.traceability.TraceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the base seed, the earlier feature seeds of the demo package and the V14 traceability seed inside one
 * transaction that is rolled back, so the shared test database keeps no demo data.
 */
class TraceabilityDemoSeedTest extends IntegrationTestBase {
    @Autowired
    DemoSeed base;

    @Autowired
    ItemMasterDemoSeed itemMaster;

    @Autowired
    PlanningDemoSeed planning;

    @Autowired
    IntegrationDemoSeed integration;

    @Autowired
    RoutingDemoSeed routing;

    @Autowired
    MaintenanceDemoSeed maintenance;

    @Autowired
    QualityDemoSeed quality;

    @Autowired
    TraceabilityDemoSeed traceability;

    @Autowired
    TraceService traces;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void seedsAContainedRecallWithANotifiedAndAPendingCustomerOnce() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            base.seedDemo();
            itemMaster.seed();
            planning.seed();
            integration.seed();
            routing.seed();
            maintenance.persona();
            maintenance.seed();
            quality.seed();
            assertThat(traceability.seeded()).isFalse();
            traceability.seed();
            assertThat(traceability.seeded()).isTrue();

            var recall = row("SELECT * FROM recalls WHERE tenant_id='demo'");
            assertThat(recall)
                    .containsEntry("status", "CONTAINED")
                    .containsEntry("severity", "CRITICAL")
                    .containsEntry("scope", "FORWARD")
                    .containsEntry("customers", 2)
                    .containsEntry("held_lots", 3);
            assertThat(str(recall, "number")).startsWith("RCL-");
            // The resin, preform and bottle lots still in stock are held by RECALL NCRs.
            var held = rows(
                    "SELECT l.number,l.status,n.source,n.status AS ncr_status FROM recall_lots x JOIN lots l ON l.tenant_id=x.tenant_id AND l.id=x.lot_id JOIN ncrs n ON n.tenant_id=x.tenant_id AND n.id=x.ncr_id WHERE x.tenant_id='demo' AND x.recall_id=? AND x.containment='HELD'",
                    recall.get("id"));
            assertThat(held).hasSize(3).allSatisfy(r -> {
                assertThat(r).containsEntry("status", "QUARANTINE").containsEntry("source", "RECALL");
                assertThat(r).containsEntry("ncr_status", "OPEN");
            });
            assertThat(held).anySatisfy(r -> assertThat(r).containsEntry("number", "LPT-R2611-31"));
            assertThat(count(
                            "SELECT COUNT(*) FROM recall_lots x JOIN items i ON i.tenant_id=x.tenant_id AND i.id=x.item_id WHERE x.tenant_id='demo' AND x.recall_id=? AND x.containment='HELD' AND i.type='FINISHED_GOOD'",
                            recall.get("id")))
                    .isEqualTo(1);
            // One customer notified, one pending.
            assertThat(rows(
                                    "SELECT customer_id,status FROM recall_customers WHERE tenant_id='demo' AND recall_id=? ORDER BY customer_id",
                                    recall.get("id"))
                            .stream()
                            .map(r -> r.get("customer_id") + ":" + r.get("status"))
                            .toList())
                    .containsExactly("ceylon:NOTIFIED", "island:PENDING");
            assertThat(count(
                            "SELECT COUNT(*) FROM recall_shipments WHERE tenant_id='demo' AND recall_id=?",
                            recall.get("id")))
                    .isEqualTo(2);

            // The genealogy from the resin lot reaches both customers.
            String resinLot = str(row("SELECT id FROM lots WHERE tenant_id='demo' AND number='LPT-R2611-31'"), "id");
            var forward = traces.forward("demo", List.of(resinLot), TraceService.DEFAULT_DEPTH);
            assertThat(forward.lots()).hasSize(3);

            // Idempotent: a second run adds nothing.
            traceability.seed();
            assertThat(count("SELECT COUNT(*) FROM recalls WHERE tenant_id='demo'"))
                    .isEqualTo(1);
            status.setRollbackOnly();
        });
    }
}
