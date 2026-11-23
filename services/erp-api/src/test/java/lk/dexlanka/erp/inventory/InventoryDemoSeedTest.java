package lk.dexlanka.erp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.demo.DemoSeed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;

/** Runs the base demo seed and the inventory demo seed (twice) against the shared test database. */
class InventoryDemoSeedTest extends IntegrationTestBase {
    @Autowired
    DemoSeed base;

    @Autowired
    InventoryRules rules;

    @Autowired
    WarehouseService warehouses;

    @Autowired
    TransferService transfers;

    @Autowired
    AdjustmentService adjustments;

    @Autowired
    CountService counts;

    @Test
    void demoSeedCreatesEveryInventoryScenarioOnce() throws Exception {
        var seedDemo = DemoSeed.class.getDeclaredMethod("seedDemo");
        seedDemo.setAccessible(true);
        seedDemo.invoke(
                AopTestUtils.getUltimateTargetObject(base)); // idempotent: skipped when the demo tenant already exists
        var seed = new InventoryDemoSeed(s, rules, warehouses, transfers, adjustments, counts, true);
        seed.run(null);
        seed.run(null);

        assertThat(count("SELECT COUNT(*) FROM warehouses WHERE tenant_id='demo' AND code='PKG' AND kind='RAW'"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM stock_transfers WHERE tenant_id='demo' AND status='POSTED'"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM stock_transfer_lines WHERE tenant_id='demo'"))
                .isEqualTo(2);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM stock_adjustments WHERE tenant_id='demo' AND status='POSTED' AND reason='DAMAGE'"))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT COUNT(*) FROM stock_adjustments WHERE tenant_id='demo' AND status='APPROVAL_REQUIRED'"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM stock_counts WHERE tenant_id='demo' AND status='COUNTING'"))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM stock_count_lines l JOIN stock_counts c ON c.tenant_id=l.tenant_id AND c.id=l.count_id WHERE c.tenant_id='demo' AND l.counted_qty IS NOT NULL"))
                .isEqualTo(1);
        assertThat(row("SELECT created_by FROM stock_adjustments WHERE tenant_id='demo' AND status='APPROVAL_REQUIRED'")
                        .get("created_by"))
                .isEqualTo("demo-warehouse");
    }
}
