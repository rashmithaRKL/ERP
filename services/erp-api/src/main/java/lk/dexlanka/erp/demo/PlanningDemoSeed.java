package lk.dexlanka.erp.demo;

import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lk.dexlanka.erp.catalog.BomCommands;
import lk.dexlanka.erp.catalog.BomService;
import lk.dexlanka.erp.catalog.CatalogCommands;
import lk.dexlanka.erp.catalog.CatalogService;
import lk.dexlanka.erp.manufacturing.ManufacturingCommands;
import lk.dexlanka.erp.manufacturing.ManufacturingService;
import lk.dexlanka.erp.manufacturing.OrderTreeService;
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
 * Multi-level BOM and planning demo data (V7), seeded once after the item master seed (fictional):
 *
 * <ul>
 *   <li>the {@code SF-001} PET preform keeps (or gets) an approved BOM, and the draft {@code FG-101} bottle revision left
 *       by the item master seed is approved: a two-level product (bottle → preform → PET resin);
 *   <li>a revision {@code R02} copied from it and edited, to compare against {@code R01};
 *   <li>an approved parent order for 10,000 bottles with one child order for the preform shortage;
 *   <li>a second, draft parent order without a child, so material planning shows planned preform production.
 * </ul>
 *
 * Posted through the public commands as {@link DemoPersonas} with fixed {@code demo-v7-} keys; skipped once any parent
 * order exists. A failure is logged and rolled back without stopping the application.
 */
@Component
@Order(DemoSeed.ORDER + 70)
class PlanningDemoSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PlanningDemoSeed.class);
    private static final String T = DemoPersonas.TENANT;

    private final Store s;
    private final CatalogService catalog;
    private final BomService boms;
    private final ManufacturingService mo;
    private final OrderTreeService tree;
    private final TransactionTemplate tx;
    private final boolean demo;

    PlanningDemoSeed(
            Store s,
            CatalogService catalog,
            BomService boms,
            ManufacturingService mo,
            OrderTreeService tree,
            PlatformTransactionManager transactions,
            @Value("${app.demo:false}") boolean demo) {
        this.s = s;
        this.catalog = catalog;
        this.boms = boms;
        this.mo = mo;
        this.tree = tree;
        this.tx = new TransactionTemplate(transactions);
        this.demo = demo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demo) return;
        try {
            tx.executeWithoutResult(status -> seed());
        } catch (RuntimeException e) {
            log.warn("Multi-level BOM and planning demo data (V7) was not seeded: {}", e.getMessage());
        }
    }

    void seed() {
        if (s.list("SELECT 1 FROM tenants WHERE id=?", T).isEmpty()) return;
        if (!s.list("SELECT 1 FROM manufacturing_orders WHERE tenant_id=? AND parent_order_id IS NOT NULL LIMIT 1", T)
                .isEmpty()) return; // seeded
        var preform = s.list("SELECT id FROM items WHERE tenant_id=? AND lower(sku)='sf-001'", T);
        var bottle = s.list("SELECT id FROM items WHERE tenant_id=? AND sku='FG-101'", T);
        if (preform.isEmpty()
                || bottle.isEmpty()
                || s.list("SELECT 1 FROM items WHERE tenant_id=? AND id='pet'", T)
                        .isEmpty()) return; // the item master demo data is missing
        String preformId = str(preform.getFirst(), "id");
        String bottleId = str(bottle.getFirst(), "id");

        // Level 2 first (revisions are approved bottom-up): the preform blows from PET resin.
        approved(
                preformId,
                "demo-v7-preform",
                List.of(new CatalogCommands.BomLine("pet", new BigDecimal("24.5"))),
                new BigDecimal("1"));
        // Level 1: the item master seed left FG-101 R01 as a draft (preform, carton, label).
        String bottleBom = approved(
                bottleId,
                "demo-v7-bottle",
                List.of(
                        new CatalogCommands.BomLine(preformId, new BigDecimal("1000")),
                        new CatalogCommands.BomLine("carton", new BigDecimal("20")),
                        new CatalogCommands.BomLine("label", new BigDecimal("1000"))),
                new BigDecimal("0.99"));

        // Revision tools: R02 copied from the approved revision and edited (lighter carton spec, tighter yield).
        if (s.list("SELECT 1 FROM boms WHERE tenant_id=? AND product_id=? AND revision='R02'", T, bottleId)
                .isEmpty()) {
            String draft = str(
                    boms.copy(DemoPersonas.PLANNER, bottleBom, new BomCommands.Copy("R02"), "demo-v7-bottle-copy"),
                    "id");
            boms.updateDraft(
                    DemoPersonas.PLANNER,
                    draft,
                    new BomCommands.Draft(
                            new BigDecimal("1000"),
                            new BigDecimal("0.985"),
                            List.of(
                                    new CatalogCommands.BomLine(preformId, new BigDecimal("1000")),
                                    new CatalogCommands.BomLine("carton", new BigDecimal("25")),
                                    new CatalogCommands.BomLine("label", new BigDecimal("1000")))),
                    "demo-v7-bottle-r02");
        }

        // Parent A: approved; its preform shortage becomes one child order on the injection moulder.
        String parentA = str(
                mo.create(
                        DemoPersonas.PLANNER,
                        new ManufacturingCommands.Manufacture(
                                bottleBom,
                                "bm02",
                                null,
                                new BigDecimal("10000"),
                                "HIGH",
                                LocalDate.now().plusDays(7).toString()),
                        "demo-v7-parent-a"),
                "id");
        mo.transition(DemoPersonas.ADMIN, parentA, "approve", "demo-v7-parent-a-approve");
        tree.createChildren(
                DemoPersonas.PLANNER,
                parentA,
                new ManufacturingCommands.ChildOrders("im01", null, null),
                "demo-v7-parent-a-children");

        // Parent B: a draft without children, so planning shows planned preform production pegged to it.
        mo.create(
                DemoPersonas.PLANNER,
                new ManufacturingCommands.Manufacture(
                        bottleBom,
                        "bm01",
                        null,
                        new BigDecimal("6000"),
                        "NORMAL",
                        LocalDate.now().plusDays(14).toString()),
                "demo-v7-parent-b");
        log.info("Multi-level BOM and planning demo data seeded");
    }

    /** The product's latest approved revision; approves its newest draft (or creates R01) when it has none. */
    private String approved(String product, String key, List<CatalogCommands.BomLine> lines, BigDecimal yield) {
        var existing = s.list(
                "SELECT id,status FROM boms WHERE tenant_id=? AND product_id=? ORDER BY (status='APPROVED') DESC,created_at DESC,revision DESC",
                T,
                product);
        if (!existing.isEmpty() && "APPROVED".equals(existing.getFirst().get("status")))
            return str(existing.getFirst(), "id");
        String bom = existing.isEmpty()
                ? str(
                        catalog.bom(
                                DemoPersonas.PLANNER,
                                new CatalogCommands.Bom(product, "R01", new BigDecimal("1000"), yield, lines),
                                key + "-bom"),
                        "id")
                : str(existing.getFirst(), "id");
        catalog.approveBom(DemoPersonas.ADMIN, bom, key + "-approve");
        return bom;
    }
}
