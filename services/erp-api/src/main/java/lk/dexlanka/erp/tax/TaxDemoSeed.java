package lk.dexlanka.erp.tax;

import java.time.LocalDate;
import lk.dexlanka.erp.demo.DemoPersonas;
import lk.dexlanka.erp.demo.DemoSeed;
import lk.dexlanka.erp.platform.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V25 demo data (fictional): the Sri Lanka VAT template applied to the demo tenant, default tax codes on a few items
 * and partners, tax registration of most partners, and one customer (Lanka Fresh Dairy) with a tax exemption
 * certificate. Everything goes through {@link TaxAdminService} as the demo administrator with fixed {@code demo-v25-…}
 * keys, in its own transaction; it runs once (also against an existing demo database).
 */
@Component
@Order(DemoSeed.ORDER + 250)
public class TaxDemoSeed implements ApplicationRunner {
    public static final String EXEMPTION = "VAT-EXM-DEMO-0142";
    private static final Logger log = LoggerFactory.getLogger(TaxDemoSeed.class);
    private static final String T = DemoPersonas.TENANT;

    private final Store s;
    private final TaxAdminService admin;
    private final TransactionTemplate tx;
    private final boolean demo;

    public TaxDemoSeed(
            Store s,
            TaxAdminService admin,
            PlatformTransactionManager transactions,
            @Value("${app.demo:false}") boolean demo) {
        this.s = s;
        this.admin = admin;
        this.tx = new TransactionTemplate(transactions);
        this.demo = demo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demo) return;
        try {
            tx.executeWithoutResult(status -> seed());
        } catch (RuntimeException e) {
            log.warn("Tax demo data (V25) was not added: {}", e.getMessage());
        }
    }

    void seed() {
        if (s.list("SELECT 1 FROM tenants WHERE id=?", T).isEmpty()) return;
        if (!s.list("SELECT 1 FROM idempotency_keys WHERE tenant_id=? AND request_key='demo-v25-exemption'", T)
                .isEmpty()) return;
        var actor = DemoPersonas.ADMIN;
        admin.applyTemplate(actor, SriLankaTaxRegime.CODE, "demo-v25-template-lk");
        String standard = code("VAT-STD");
        for (String item : new String[] {"bottle", "container", "cap"})
            if (exists("items", item))
                admin.updateItemTax(actor, item, new TaxCommands.ItemTax(standard, null), "demo-v25-item-" + item);
        for (String item : new String[] {"hdpe", "pet", "carton"})
            if (exists("items", item))
                admin.updateItemTax(actor, item, new TaxCommands.ItemTax(null, standard), "demo-v25-item-" + item);
        for (String partner : new String[] {"ceylon", "serendib", "resinco", "colorco", "packco"})
            if (exists("partners", partner)) {
                boolean supplier = !partner.equals("ceylon") && !partner.equals("serendib");
                admin.updatePartnerTax(
                        actor,
                        partner,
                        new TaxCommands.PartnerTax(
                                true, null, null, supplier ? null : standard, supplier ? standard : null),
                        "demo-v25-partner-" + partner);
            }
        if (exists("partners", "lakfood"))
            admin.updatePartnerTax(
                    actor,
                    "lakfood",
                    new TaxCommands.PartnerTax(
                            true,
                            EXEMPTION,
                            LocalDate.of(TaxService.today().getYear() + 1, 12, 31)
                                    .toString(),
                            standard,
                            null),
                    "demo-v25-exemption");
    }

    private String code(String code) {
        return (String) s.one("SELECT id FROM tax_codes WHERE tenant_id=? AND code=?", T, code)
                .get("id");
    }

    private boolean exists(String table, String id) {
        return !s.list("SELECT 1 FROM " + table + " WHERE tenant_id=? AND id=?", T, id)
                .isEmpty();
    }
}
