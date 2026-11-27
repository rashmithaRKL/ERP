package lk.dexlanka.erp.demo;

import static org.assertj.core.api.Assertions.assertThat;

import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.catalog.CatalogService;
import lk.dexlanka.erp.catalog.PartnerService;
import lk.dexlanka.erp.manufacturing.ManufacturingService;
import lk.dexlanka.erp.procurement.ProcurementService;
import lk.dexlanka.erp.quality.QualityService;
import lk.dexlanka.erp.sales.ReturnsService;
import lk.dexlanka.erp.sales.SalesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The base demo seed (sales orders now created as drafts and confirmed, dispatched per line) followed by the V3 sales
 * demo seed, run against the shared test database's {@code demo} tenant. The seeds commit their own transactions (the
 * drifted-database check needs that), so the tenant is removed afterwards: the other demo seed tests expect none.
 */
class SalesDemoSeedTest extends IntegrationTestBase {
    @Autowired
    ManufacturingService mo;

    @Autowired
    ProcurementService procurement;

    @Autowired
    QualityService quality;

    @Autowired
    SalesService sales;

    @Autowired
    ReturnsService returns;

    @Autowired
    CatalogService catalog;

    @Autowired
    PartnerService partners;

    @Autowired
    PlatformTransactionManager transactions;

    @AfterEach
    void removeDemoTenant() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            // Append-only triggers and foreign keys stay off for this transaction only (the embedded test server runs
            // as a superuser); production code never deletes ledger rows.
            update("SET LOCAL session_replication_role = replica");
            for (var table : rows(
                    "SELECT c.table_name FROM information_schema.columns c JOIN information_schema.tables t ON t.table_schema=c.table_schema AND t.table_name=c.table_name WHERE c.table_schema=current_schema() AND c.column_name='tenant_id' AND t.table_type='BASE TABLE'")) {
                update("DELETE FROM " + table.get("table_name") + " WHERE tenant_id=?", DemoPersonas.TENANT);
            }
            update("DELETE FROM tenants WHERE id=?", DemoPersonas.TENANT);
        });
        assertThat(count("SELECT COUNT(*) FROM manufacturing_orders WHERE tenant_id=?", DemoPersonas.TENANT))
                .isZero();
    }

    @Test
    void demoSeedsBuildSalesDocumentsInEveryStateAndRunOnce() {
        new DemoSeed(s, passwords, mo, procurement, quality, sales, true, "", "").run(null);
        var seed = new SalesDemoSeed(s, sales, returns, mo, quality, catalog, partners, transactions, true);
        String demo = DemoPersonas.TENANT;

        // A drifted demo database (here: a work center under maintenance) must not block startup or leave half a seed.
        update("UPDATE machines SET status='MAINTENANCE' WHERE tenant_id=? AND id='bm02'", demo);
        seed.run(null);
        assertThat(count(
                        "SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id=? AND request_key LIKE 'demo-v3-%'",
                        demo))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM partners WHERE tenant_id=? AND credit_limit IS NOT NULL", demo))
                .isZero();
        update("UPDATE machines SET status='RUNNING' WHERE tenant_id=? AND id='bm02'", demo);
        seed.run(null);

        // Base seed: 13 delivered history orders and 6 confirmed orders behind the active manufacturing orders.
        assertThat(count(
                        "SELECT COUNT(*) FROM manufacturing_orders WHERE tenant_id=? AND sales_order_line_id IS NOT NULL",
                        demo))
                .isGreaterThanOrEqualTo(19);
        assertThat(count(
                        "SELECT COUNT(*) FROM deliveries WHERE tenant_id=? AND reference LIKE 'DN-DEMO-%' AND reference NOT LIKE 'DN-DEMO-S%'",
                        demo))
                .isEqualTo(13);

        // Sales seed: one order per state, a customer return awaiting QC and an inactive customer.
        assertThat(rows(
                        "SELECT so.status,COUNT(*) AS n FROM sales_orders so WHERE so.tenant_id=? AND so.customer_reference IS NOT NULL GROUP BY so.status ORDER BY so.status",
                        demo))
                .extracting(r -> r.get("status") + "=" + r.get("n"))
                .containsExactly("CANCELLED=1", "CONFIRMED=1", "DELIVERED=1", "DRAFT=1", "PARTIAL=1");
        assertThat(count(
                        "SELECT COUNT(*) FROM sales_orders so WHERE so.tenant_id=? AND so.customer_reference IS NOT NULL AND (SELECT COUNT(*) FROM sales_order_lines l WHERE l.tenant_id=so.tenant_id AND l.sales_order_id=so.id)>1",
                        demo))
                .isEqualTo(4);
        assertThat(rows(
                        "SELECT q.status FROM customer_returns r JOIN inspections q ON q.tenant_id=r.tenant_id AND q.id=r.inspection_id WHERE r.tenant_id=?",
                        demo))
                .extracting(r -> r.get("status"))
                .containsExactly("PENDING");
        assertThat(count("SELECT COUNT(*) FROM partners WHERE tenant_id=? AND active=false", demo))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM partners WHERE tenant_id=? AND credit_limit IS NOT NULL", demo))
                .isEqualTo(5);
        assertThat(count(
                        "SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='SALE_CONFIRMED' AND detail LIKE '%Credit limit exceeded%'",
                        demo))
                .isEqualTo(1);
        assertThat(decimal("SELECT COALESCE(SUM(debit-credit),0) FROM journal_lines WHERE tenant_id=?", demo))
                .isZero();

        long keys = count("SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id=?", demo);
        seed.run(null);
        assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id=?", demo))
                .isEqualTo(keys);
    }
}
