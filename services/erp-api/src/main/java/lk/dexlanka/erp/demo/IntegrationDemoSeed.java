package lk.dexlanka.erp.demo;

import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.sales.SalesCommands;
import lk.dexlanka.erp.sales.SalesService;
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
 * V9 demo data (fictional): a confirmed sales order whose bottle line is entered in the sales unit "box" (the V4 item
 * master conversion 1 box = 50 pcs) next to a line in the base unit, so the sales editor, drawer and dispatch show
 * entered and base quantities side by side. Runs once after the earlier feature seeds; skipped when any {@code
 * demo-v9-} key exists. Approval policies keep their built-in defaults in the demo.
 */
@Component
@Order(DemoSeed.ORDER + 90)
class IntegrationDemoSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(IntegrationDemoSeed.class);

    private final Store s;
    private final SalesService sales;
    private final TransactionTemplate tx;
    private final boolean demo;

    IntegrationDemoSeed(
            Store s,
            SalesService sales,
            PlatformTransactionManager transactions,
            @Value("${app.demo:false}") boolean demo) {
        this.s = s;
        this.sales = sales;
        this.tx = new TransactionTemplate(transactions);
        this.demo = demo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demo
                || s.list("SELECT 1 FROM tenants WHERE id=?", DemoPersonas.TENANT)
                        .isEmpty()) return;
        // An existing demo database may have drifted (a conversion removed, a product deactivated): never block
        // startup.
        try {
            tx.executeWithoutResult(status -> seed());
        } catch (RuntimeException e) {
            log.warn("Integration demo data (V9) was not added: {}", e.getMessage());
        }
    }

    /** Idempotent: does nothing once a {@code demo-v9-} command has run. */
    void seed() {
        if (!s.list(
                        "SELECT 1 FROM idempotency_keys WHERE tenant_id=? AND request_key LIKE 'demo-v9-%' LIMIT 1",
                        DemoPersonas.TENANT)
                .isEmpty()) return;
        var order = sales.create(
                DemoPersonas.PLANNER,
                new SalesCommands.Order(
                        "island",
                        "PO-ICP-7790",
                        LocalDate.now().plusDays(9).toString(),
                        "Bottles are ordered and shipped in cartons of 50.",
                        List.of(
                                new SalesCommands.Line(
                                        null, "bottle", new BigDecimal("40"), new BigDecimal("1375"), null, "box"),
                                new SalesCommands.Line(
                                        null, "cap", new BigDecimal("2000"), new BigDecimal("7.80"), null, null))),
                "demo-v9-order-units");
        sales.confirm(DemoPersonas.PLANNER, str(order, "id"), "demo-v9-confirm-units");
    }
}
