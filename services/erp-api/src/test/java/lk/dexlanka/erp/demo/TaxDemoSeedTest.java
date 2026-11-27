package lk.dexlanka.erp.demo;

import static org.assertj.core.api.Assertions.assertThat;

import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.tax.TaxAdminService;
import lk.dexlanka.erp.tax.TaxDemoSeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The V25 tax demo seed against a minimal {@code demo} tenant with the base seed's item, partner and administrator ids:
 * the Sri Lanka template, item and partner defaults, the exempt customer; idempotent on a second run. The tenant is
 * removed afterwards (the other demo seed tests expect none).
 */
class TaxDemoSeedTest extends IntegrationTestBase {
    @Autowired
    TaxAdminService admin;

    @Autowired
    PlatformTransactionManager transactions;

    @AfterEach
    void removeDemoTenant() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            update("SET LOCAL session_replication_role = replica");
            for (var table : rows(
                    "SELECT c.table_name FROM information_schema.columns c JOIN information_schema.tables t ON t.table_schema=c.table_schema AND t.table_name=c.table_name WHERE c.table_schema=current_schema() AND c.column_name='tenant_id' AND t.table_type='BASE TABLE'")) {
                update("DELETE FROM " + table.get("table_name") + " WHERE tenant_id=?", DemoPersonas.TENANT);
            }
            update("DELETE FROM tenants WHERE id=?", DemoPersonas.TENANT);
        });
    }

    @Test
    void appliesTheTemplateSetsDefaultsAndRunsOnce() {
        String demo = DemoPersonas.TENANT;
        update("INSERT INTO tenants(id,name,demo) VALUES(?,'Demo',true)", demo);
        update(
                "INSERT INTO users(id,tenant_id,email,name,password_hash,role) VALUES(?,?,?,?,'unused','ADMIN')",
                DemoPersonas.ADMIN.id(),
                demo,
                DemoPersonas.ADMIN.email(),
                DemoPersonas.ADMIN.name());
        for (String[] i : new String[][] {
            {"bottle", "FG-001", "FINISHED_GOOD", "pcs"},
            {"container", "FG-002", "FINISHED_GOOD", "pcs"},
            {"hdpe", "RM-001", "RAW_MATERIAL", "kg"}
        })
            update(
                    "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,?,?,?,?,1,0)",
                    i[0],
                    demo,
                    i[1],
                    i[1],
                    i[2],
                    i[3]);
        for (String[] p : new String[][] {
            {"ceylon", "CUSTOMER"}, {"lakfood", "CUSTOMER"}, {"island", "CUSTOMER"}, {"resinco", "SUPPLIER"}
        }) update("INSERT INTO partners(id,tenant_id,name,type) VALUES(?,?,?,?)", p[0], demo, p[0], p[1]);

        var seed = new TaxDemoSeed(s, admin, transactions, true);
        seed.run(null);

        assertThat(rows("SELECT code FROM tax_codes WHERE tenant_id=? ORDER BY code", demo))
                .extracting(r -> r.get("code"))
                .containsExactly("VAT-EXEMPT", "VAT-STD", "VAT-ZERO");
        assertThat(row("SELECT regime,rounding FROM tax_settings WHERE tenant_id=?", demo))
                .containsEntry("regime", "LK")
                .containsEntry("rounding", "PER_DOCUMENT");
        assertThat(count("SELECT COUNT(*) FROM items WHERE tenant_id=? AND sales_tax_code_id IS NOT NULL", demo))
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM items WHERE tenant_id=? AND purchase_tax_code_id IS NOT NULL", demo))
                .isEqualTo(1);
        assertThat(row(
                        "SELECT tax_registered,tax_exemption_reference FROM partners WHERE tenant_id=? AND id='lakfood'",
                        demo))
                .containsEntry("tax_registered", true)
                .containsEntry("tax_exemption_reference", TaxDemoSeed.EXEMPTION);
        assertThat(row("SELECT tax_registered FROM partners WHERE tenant_id=? AND id='island'", demo))
                .containsEntry("tax_registered", false);
        assertThat(count("SELECT COUNT(*) FROM tax_lines WHERE tenant_id=?", demo))
                .isZero();

        long keys = count("SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id=?", demo);
        long audits = count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=?", demo);
        seed.run(null);
        assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id=?", demo))
                .isEqualTo(keys);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=?", demo))
                .isEqualTo(audits);
        assertThat(count(
                        "SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id=? AND request_key LIKE 'demo-v25-%'",
                        demo))
                .isEqualTo(keys);
    }
}
