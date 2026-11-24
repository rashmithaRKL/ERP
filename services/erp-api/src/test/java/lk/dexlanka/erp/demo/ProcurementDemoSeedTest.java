package lk.dexlanka.erp.demo;

import static org.assertj.core.api.Assertions.assertThat;

import lk.dexlanka.erp.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the base, item-master and procurement demo seeds inside one transaction that is rolled back, so the shared test
 * database keeps no procurement demo data.
 */
class ProcurementDemoSeedTest extends IntegrationTestBase {
    @Autowired
    DemoSeed base;

    @Autowired
    ItemMasterDemoSeed itemMaster;

    @Autowired
    ProcurementDemoSeed procurement;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void seedsOnePurchaseOrderInEveryStateAndASupplierReturnOnce() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            base.seedDemo();
            itemMaster.seed();
            procurement.seed();

            assertThat(status("RFQ-LPT-2611")).isEqualTo("DRAFT");
            assertThat(status("EP-QT-0915")).isEqualTo("SUBMITTED");
            assertThat(status("LPT-SO-55821")).isEqualTo("PARTIAL");
            assertThat(status("LPT-SO-55790")).isEqualTo("RECEIVED");
            // Multi-line, in purchase units where the item master defines them.
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM purchase_order_lines l JOIN purchase_orders po ON po.tenant_id=l.tenant_id AND po.id=l.purchase_order_id WHERE po.tenant_id='demo' AND po.supplier_reference='RFQ-LPT-2611' AND l.unit='bag'"))
                    .isEqualTo(2);
            // Order C: one line released, one still in incoming QC.
            assertThat(rows(
                                    "SELECT lt.status FROM receipts r JOIN purchase_orders po ON po.tenant_id=r.tenant_id AND po.id=r.purchase_order_id JOIN lots lt ON lt.tenant_id=r.tenant_id AND lt.id=r.lot_id WHERE po.tenant_id='demo' AND po.supplier_reference='LPT-SO-55821'")
                            .stream()
                            .map(r -> r.get("status"))
                            .toList())
                    .containsExactlyInAnyOrder("RELEASED", "QUARANTINE");
            // Order D: invoiced with a small price variance (1.20 per kg on 1,000 kg of PET).
            var invoice = row(
                    "SELECT si.* FROM supplier_invoices si JOIN purchase_orders po ON po.tenant_id=si.tenant_id AND po.id=si.purchase_order_id WHERE po.tenant_id='demo' AND po.supplier_reference='LPT-SO-55790'");
            assertThat(invoice).containsEntry("status", "VARIANCE");
            assertThat(dec(invoice.get("price_variance"))).isEqualByComparingTo("1200");
            var supplierReturn =
                    row("SELECT * FROM supplier_returns WHERE tenant_id='demo' AND debit_note='DBN-2611-014'");
            assertThat(dec(supplierReturn.get("quantity"))).isEqualByComparingTo("50");
            assertThat(decimal("SELECT COALESCE(SUM(debit-credit),0) FROM journal_lines WHERE tenant_id='demo'"))
                    .isZero();

            // Idempotent: a second start changes nothing.
            long audits = count("SELECT COUNT(*) FROM audit_events WHERE tenant_id='demo'");
            procurement.seed();
            procurement.run(null);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id='demo'"))
                    .isEqualTo(audits);
            status.setRollbackOnly();
        });
        assertThat(count("SELECT COUNT(*) FROM supplier_returns WHERE tenant_id='demo' AND debit_note='DBN-2611-014'"))
                .isZero();
    }

    private String status(String reference) {
        return str(
                row("SELECT status FROM purchase_orders WHERE tenant_id='demo' AND supplier_reference=?", reference),
                "status");
    }
}
