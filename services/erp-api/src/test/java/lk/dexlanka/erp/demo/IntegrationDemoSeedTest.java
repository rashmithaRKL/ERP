package lk.dexlanka.erp.demo;

import static org.assertj.core.api.Assertions.assertThat;

import lk.dexlanka.erp.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs the base, item-master and V9 demo seeds in one rolled-back transaction. */
class IntegrationDemoSeedTest extends IntegrationTestBase {
    @Autowired
    DemoSeed base;

    @Autowired
    ItemMasterDemoSeed itemMaster;

    @Autowired
    IntegrationDemoSeed integration;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void seedsASalesOrderInSalesUnitsOnce() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            base.seedDemo();
            itemMaster.seed();
            integration.seed();

            var order = row("SELECT * FROM sales_orders WHERE tenant_id='demo' AND customer_reference='PO-ICP-7790'");
            assertThat(order.get("status")).isEqualTo("CONFIRMED");
            assertThat(dec(order.get("total"))).isEqualByComparingTo("70600"); // 40 x 1,375 + 2,000 x 7.80
            var boxes = row(
                    "SELECT * FROM sales_order_lines WHERE tenant_id='demo' AND sales_order_id=? AND line_no=1",
                    order.get("id"));
            assertThat(boxes.get("unit")).isEqualTo("box");
            assertThat(dec(boxes.get("base_quantity"))).isEqualByComparingTo("2000");
            assertThat(dec(boxes.get("base_unit_price"))).isEqualByComparingTo("27.5");

            long keys = count("SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id='demo'");
            integration.seed();
            integration.run(null);
            assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id='demo'"))
                    .isEqualTo(keys);
            status.setRollbackOnly();
        });
        assertThat(count("SELECT COUNT(*) FROM sales_orders WHERE tenant_id='demo' AND customer_reference='PO-ICP-7790'"))
                .isZero();
    }
}
