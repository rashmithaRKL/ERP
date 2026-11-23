package lk.dexlanka.erp.demo;

import static org.assertj.core.api.Assertions.assertThat;

import lk.dexlanka.erp.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the base demo seed and the item master demo seed inside one transaction that is rolled back, so the shared test
 * database keeps no demo tenant.
 */
class ItemMasterDemoSeedTest extends IntegrationTestBase {
    @Autowired
    DemoSeed base;

    @Autowired
    ItemMasterDemoSeed itemMaster;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void seedsCategoriesUnitsBarcodesVariantsAndAProducedPreformOnce() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            base.seedDemo();
            itemMaster.seed();

            assertThat(count("SELECT COUNT(*) FROM item_categories WHERE tenant_id='demo'"))
                    .isEqualTo(13);
            assertThat(count("SELECT COUNT(*) FROM items WHERE tenant_id='demo' AND category_id IS NULL"))
                    .isZero();
            assertThat(count("SELECT COUNT(*) FROM item_unit_conversions WHERE tenant_id='demo'"))
                    .isEqualTo(4);
            assertThat(
                            decimal(
                                    "SELECT factor FROM item_unit_conversions WHERE tenant_id='demo' AND item_id='hdpe' AND unit_code='bag'"))
                    .isEqualByComparingTo("25");
            assertThat(count("SELECT COUNT(*) FROM item_barcodes WHERE tenant_id='demo'"))
                    .isEqualTo(4);
            assertThat(count("SELECT COUNT(*) FROM items i JOIN item_templates t ON t.tenant_id=i.tenant_id AND"
                            + " t.id=i.template_id WHERE i.tenant_id='demo' AND t.code='BTL-PET'"))
                    .isEqualTo(2);

            // The preform was produced through a closed order into the raw (production input) store and released.
            var lot = row(
                    "SELECT l.*,b.quantity FROM lots l JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id WHERE l.tenant_id='demo' AND i.sku='SF-001'");
            assertThat(lot).containsEntry("warehouse_id", "demo-raw").containsEntry("status", "RELEASED");
            assertThat(dec(lot.get("quantity"))).isEqualByComparingTo("4960");
            assertThat(row(
                                    "SELECT status FROM manufacturing_orders WHERE tenant_id='demo' AND id=?",
                                    lot.get("source_id"))
                            .get("status"))
                    .isEqualTo("CLOSED");
            // Where-used: the draft bottle revision consumes the preform.
            assertThat(count("SELECT COUNT(*) FROM bom_lines l JOIN items i ON i.tenant_id=l.tenant_id AND"
                            + " i.id=l.item_id WHERE l.tenant_id='demo' AND i.sku='SF-001'"))
                    .isEqualTo(1);

            // Idempotent: a second start changes nothing.
            long audits = count("SELECT COUNT(*) FROM audit_events WHERE tenant_id='demo'");
            itemMaster.seed();
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id='demo'"))
                    .isEqualTo(audits);
            status.setRollbackOnly();
        });
        assertThat(count("SELECT COUNT(*) FROM item_templates WHERE tenant_id='demo'"))
                .isZero();
    }
}
