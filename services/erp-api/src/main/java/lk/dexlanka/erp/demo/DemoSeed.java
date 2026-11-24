package lk.dexlanka.erp.demo;

import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lk.dexlanka.erp.manufacturing.ManufacturingCommands;
import lk.dexlanka.erp.manufacturing.ManufacturingService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.procurement.ProcurementCommands;
import lk.dexlanka.erp.procurement.ProcurementService;
import lk.dexlanka.erp.quality.QualityCommands;
import lk.dexlanka.erp.quality.QualityService;
import lk.dexlanka.erp.sales.SalesCommands;
import lk.dexlanka.erp.sales.SalesService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Startup data. With {@code app.demo=true} it seeds the fictional "demo" tenant once (skipped when it already exists).
 * Otherwise, on an empty database, it provisions the first tenant and administrator from {@code BOOTSTRAP_EMAIL} /
 * {@code BOOTSTRAP_PASSWORD}. Everything runs in one transaction.
 *
 * <p>Feature demo data belongs in its own idempotent {@code ApplicationRunner} ordered after {@link #ORDER}; see the
 * backend README.
 */
@Component
@Order(DemoSeed.ORDER)
public class DemoSeed implements ApplicationRunner {
    /** Runner order of the base seed. Feature seeds use {@code @Order(DemoSeed.ORDER + n)} with n > 0. */
    public static final int ORDER = 0;

    private final Store s;
    private final PasswordEncoder passwords;
    private final ManufacturingService mo;
    private final ProcurementService procurement;
    private final QualityService quality;
    private final SalesService sales;
    private final boolean demo;
    private final String bootstrapEmail;
    private final String bootstrapPassword;

    DemoSeed(
            Store s,
            PasswordEncoder p,
            ManufacturingService mo,
            ProcurementService procurement,
            QualityService quality,
            SalesService sales,
            @Value("${app.demo:false}") boolean demo,
            @Value("${app.bootstrap-email:}") String email,
            @Value("${app.bootstrap-password:}") String password) {
        this.s = s;
        this.passwords = p;
        this.mo = mo;
        this.procurement = procurement;
        this.quality = quality;
        this.sales = sales;
        this.demo = demo;
        this.bootstrapEmail = email;
        this.bootstrapPassword = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (demo) seedDemo();
        else if (s.list("SELECT 1 FROM tenants LIMIT 1").isEmpty()) {
            if (bootstrapEmail.isBlank() || bootstrapPassword.length() < 16)
                throw new IllegalStateException(
                        "First startup requires BOOTSTRAP_EMAIL and a BOOTSTRAP_PASSWORD of at least 16 characters");
            base("company", "Your manufacturing company", false, bootstrapEmail, bootstrapPassword);
        }
    }

    Actor base(String tenant, String name, boolean isDemo, String email, String password) {
        s.db().update("INSERT INTO tenants(id,name,demo) VALUES(?,?,?)", tenant, name, isDemo);
        s.db().update(
                        "INSERT INTO factories(id,tenant_id,name,location) VALUES(?,?,?,?)",
                        tenant + "-plant",
                        tenant,
                        "Homagama factory",
                        "Homagama, Sri Lanka");
        s.db().update(
                        "INSERT INTO warehouses(id,tenant_id,factory_id,name,kind) VALUES(?,?,?,?,?)",
                        tenant + "-raw",
                        tenant,
                        tenant + "-plant",
                        "Raw material warehouse",
                        "RAW");
        s.db().update(
                        "INSERT INTO warehouses(id,tenant_id,factory_id,name,kind) VALUES(?,?,?,?,?)",
                        tenant + "-finished",
                        tenant,
                        tenant + "-plant",
                        "Finished goods warehouse",
                        "FINISHED");
        s.db().update(
                        "INSERT INTO users(id,tenant_id,email,name,password_hash,role) VALUES(?,?,?,?,?,'ADMIN')",
                        tenant + "-admin",
                        tenant,
                        email,
                        "Nimal Perera",
                        passwords.encode(password));
        return new Actor(tenant + "-admin", tenant, email, "Nimal Perera", "ADMIN");
    }

    void seedDemo() {
        if (!s.list("SELECT 1 FROM tenants WHERE id='demo'").isEmpty()) return;
        Actor admin = base("demo", "Lanka Precision Packaging", true, "demo.admin@dexlanka.test", id() + id());
        String randomHash = passwords.encode(id() + id());
        String[][] users = {
            {"planner", "Dilini Fernando", "PLANNER"},
            {"operator", "Kasun Silva", "OPERATOR"},
            {"warehouse", "Amal Jayasinghe", "WAREHOUSE"},
            {"quality", "Tharushi Senanayake", "QUALITY"}
        };
        for (var u : users)
            s.db().update(
                            "INSERT INTO users(id,tenant_id,email,name,password_hash,role) VALUES(?,'demo',?,?,?,?)",
                            "demo-" + u[0],
                            "demo." + u[0] + "@dexlanka.test",
                            u[1],
                            randomHash,
                            u[2]);
        Actor planner = DemoPersonas.PLANNER;
        String[][] items = {
            {"hdpe", "RM-001", "HDPE resin · blow moulding", "RAW_MATERIAL", "kg", "485", "1500"},
            {"pet", "RM-002", "PET resin · food grade", "RAW_MATERIAL", "kg", "520", "1200"},
            {"color", "RM-003", "Blue colour masterbatch", "RAW_MATERIAL", "kg", "1850", "150"},
            {"carton", "PK-001", "Corrugated carton · 50 pcs", "PACKAGING", "pcs", "125", "1000"},
            {"label", "PK-002", "Printed bottle label", "PACKAGING", "pcs", "2.5", "10000"},
            {"bottle", "FG-001", "500ml plastic bottle", "FINISHED_GOOD", "pcs", "17.5", "10000"},
            {"container", "FG-002", "1L food-grade container", "FINISHED_GOOD", "pcs", "31.8", "5000"},
            {"cap", "FG-003", "28mm bottle cap", "FINISHED_GOOD", "pcs", "4.2", "10000"},
            {"food", "FG-004", "750ml food packaging box", "FINISHED_GOOD", "pcs", "24.6", "5000"}
        };
        for (var i : items)
            s.db().update(
                            "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,'demo',?,?,?,?,?,?)",
                            i[0],
                            i[1],
                            i[2],
                            i[3],
                            i[4],
                            new BigDecimal(i[5]),
                            new BigDecimal(i[6]));
        String[][] partners = {
            {"ceylon", "Ceylon Beverage Industries", "CUSTOMER"},
            {"serendib", "Serendib Foods (Pvt) Ltd", "CUSTOMER"},
            {"island", "Island Consumer Products", "CUSTOMER"},
            {"lakfood", "Lanka Fresh Dairy", "CUSTOMER"},
            {"resinco", "Lanka Polymer Trading", "SUPPLIER"},
            {"colorco", "ColourTech Lanka", "SUPPLIER"},
            {"packco", "EcoPack Corrugators", "SUPPLIER"}
        };
        for (var p : partners)
            s.db().update(
                            "INSERT INTO partners(id,tenant_id,name,type,email) VALUES(?,'demo',?,?,?)",
                            p[0],
                            p[1],
                            p[2],
                            p[0] + "@example.test");
        String[][] machines = {
            {"bm01", "BM-01", "Blow moulding line 01", "Blow moulding", "RUNNING", "2400"},
            {"bm02", "BM-02", "Blow moulding line 02", "Blow moulding", "RUNNING", "2400"},
            {"im01", "IM-01", "Injection moulding 01", "Injection moulding", "RUNNING", "1850"},
            {"im02", "IM-02", "Injection moulding 02", "Injection moulding", "IDLE", "1850"},
            {"im03", "IM-03", "Injection moulding 03", "Injection moulding", "MAINTENANCE", "2100"},
            {"pk01", "PK-01", "Packaging & labelling", "Packaging", "RUNNING", "950"}
        };
        for (var m : machines)
            s.db().update(
                            "INSERT INTO machines(id,tenant_id,factory_id,code,name,kind,status,hourly_rate) VALUES(?,'demo','demo-plant',?,?,?,?,?)",
                            m[0],
                            m[1],
                            m[2],
                            m[3],
                            m[4],
                            new BigDecimal(m[5]));
        for (String product : List.of("bottle", "container", "cap", "food")) {
            s.db().update(
                            "INSERT INTO boms(id,tenant_id,product_id,revision,status,output_qty) VALUES(?,'demo',?,'R03','DRAFT',1000)",
                            "bom-" + product,
                            product);
            addBomLine(
                    product,
                    product.equals("food") ? "pet" : "hdpe",
                    switch (product) {
                        case "bottle" -> "22";
                        case "container" -> "48";
                        case "cap" -> "3";
                        default -> "35";
                    });
            addBomLine(product, "color", "0.4");
            addBomLine(product, "carton", "20");
            if (product.equals("bottle")) addBomLine(product, "label", "1000");
            s.db().update("UPDATE boms SET status='APPROVED' WHERE tenant_id='demo' AND id=?", "bom-" + product);
        }
        String[][] opening = {
            {"hdpe", "9000"}, {"pet", "980"}, {"color", "150"}, {"carton", "9000"}, {"label", "280000"}
        };
        for (var o : opening) {
            var item = s.one("SELECT * FROM items WHERE id=? AND tenant_id='demo'", o[0]);
            String lot = "opening-" + o[0];
            s.db().update(
                            "INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost) VALUES(?,'demo',?,?,'demo-raw','RELEASED','OPENING','OPENING-2026',?)",
                            lot,
                            "RM-261001-" + o[0].toUpperCase(Locale.ROOT),
                            o[0],
                            item.get("standard_cost"));
            BigDecimal value = s.ledger(admin, lot, new BigDecimal(o[1]), "OPENING-2026", "DEMO_OPENING_STOCK");
            s.journal(
                    admin, "OPENING-2026", "Demonstration opening inventory", "RAW_INVENTORY", "OPENING_EQUITY", value);
        }
        int[] daily = {7800, 9200, 8400, 10600, 9800, 6400, 7200, 8800, 10200, 9400, 11200, 10800, 10400};
        for (int d = 0; d < daily.length; d++) {
            var sale = salesOrder(
                    planner,
                    d % 2 == 0 ? "ceylon" : "serendib",
                    "bottle",
                    new BigDecimal(daily[d]),
                    new BigDecimal("26.5"),
                    LocalDate.now().minusDays(13 - d).toString());
            var order = mo.create(
                    planner,
                    new ManufacturingCommands.Manufacture(
                            "bom-bottle",
                            "bm01",
                            sale[0],
                            new BigDecimal(daily[d]),
                            "NORMAL",
                            LocalDate.now().minusDays(13 - d).toString(),
                            sale[1]),
                    id());
            String oid = str(order, "id");
            mo.transition(admin, oid, "approve", id());
            mo.transition(admin, oid, "reserve", id());
            mo.transition(admin, oid, "issue", id());
            mo.transition(admin, oid, "start", id());
            mo.complete(
                    admin,
                    oid,
                    new ManufacturingCommands.Complete(
                            new BigDecimal(daily[d] - 40),
                            new BigDecimal("40"),
                            new BigDecimal("6500"),
                            new BigDecimal("4800"),
                            "Demonstration shift: startup rejects"),
                    id());
            s.db().update(
                            "UPDATE production_actuals SET created_at=(CURRENT_DATE-?)+interval '14 hours' WHERE tenant_id='demo' AND order_id=?",
                            13 - d,
                            oid);
            var inspection = s.one(
                    "SELECT i.id,l.id AS lot_id FROM inspections i JOIN lots l ON l.tenant_id=i.tenant_id AND l.id=i.lot_id WHERE i.tenant_id='demo' AND l.source_id=?",
                    oid);
            quality.inspect(
                    admin,
                    str(inspection, "id"),
                    new QualityCommands.Inspect(
                            "PASSED", "Weight, seal integrity and visual checks passed · demonstration"),
                    id());
            sales.dispatchLine(
                    admin,
                    sale[0],
                    sale[1],
                    new SalesCommands.DispatchLine(
                            "DN-DEMO-" + (d + 1),
                            List.of(new SalesCommands.Allocation(
                                    str(inspection, "lot_id"), new BigDecimal(daily[d] - 40)))),
                    id());
            mo.transition(admin, oid, "close", id());
        }
        String[][] active = {
            {"bottle", "ceylon", "25000", "bm01", "URGENT", "-1", "IN_PROGRESS"},
            {"container", "serendib", "18000", "bm02", "HIGH", "1", "PARTIAL"},
            {"cap", "island", "50000", "im01", "NORMAL", "2", "IN_PROGRESS"},
            {"food", "lakfood", "12000", "im02", "HIGH", "3", "DRAFT"},
            {"bottle", "island", "30000", "bm02", "NORMAL", "4", "APPROVED"},
            {"container", "ceylon", "10000", "im02", "NORMAL", "5", "DRAFT"}
        };
        for (var o : active) {
            var sale = salesOrder(
                    planner,
                    o[1],
                    o[0],
                    new BigDecimal(o[2]),
                    new BigDecimal(o[0].equals("cap") ? "7.5" : "38.5"),
                    LocalDate.now().plusDays(Integer.parseInt(o[5])).toString());
            var order = mo.create(
                    planner,
                    new ManufacturingCommands.Manufacture(
                            "bom-" + o[0],
                            o[3],
                            sale[0],
                            new BigDecimal(o[2]),
                            o[4],
                            LocalDate.now().plusDays(Integer.parseInt(o[5])).toString(),
                            sale[1]),
                    id());
            String oid = str(order, "id");
            if (!o[6].equals("DRAFT")) mo.transition(admin, oid, "approve", id());
            if (Set.of("IN_PROGRESS", "PARTIAL").contains(o[6])) {
                mo.transition(admin, oid, "reserve", id());
                mo.transition(admin, oid, "issue", id());
                mo.transition(admin, oid, "start", id());
                mo.complete(
                        admin,
                        oid,
                        new ManufacturingCommands.Complete(
                                new BigDecimal(o[6].equals("PARTIAL") ? "7200" : "4000"),
                                new BigDecimal("20"),
                                new BigDecimal("3500"),
                                new BigDecimal("2400"),
                                "Demonstration shift: initial moulding rejects"),
                        id());
            }
        }
        // Purchasing (V6 documents): drafted and submitted by the planner, approved by the administrator.
        approvedPurchase(planner, admin, "resinco", "pet", "2000", "515", 2);
        String p2 = approvedPurchase(planner, admin, "colorco", "color", "100", "1850", 1);
        var colourLine =
                s.one("SELECT id FROM purchase_order_lines WHERE tenant_id='demo' AND purchase_order_id=?", p2);
        procurement.receiveLines(
                admin,
                p2,
                new ProcurementCommands.GoodsReceipt(
                        "demo-raw",
                        "DN-CT-261029",
                        List.of(new ProcurementCommands.ReceiptLine(
                                str(colourLine, "id"), new BigDecimal("25"), null, "CB-261029-008"))),
                id());
        s.audit(
                admin,
                "DEMO_INITIALIZED",
                "DEMO",
                "Fictional demonstration data. Historical output observations are backfilled; financial postings use entry date.");
    }

    /** A confirmed one-line customer order (created as a draft, then confirmed); returns {order id, line id}. */
    String[] salesOrder(Actor actor, String customer, String product, BigDecimal qty, BigDecimal price, String due) {
        var order = sales.create(
                actor,
                new SalesCommands.Order(
                        customer, null, due, "", List.of(new SalesCommands.Line(null, product, qty, price, null))),
                id());
        sales.confirm(actor, str(order, "id"), id());
        var line =
                s.one("SELECT id FROM sales_order_lines WHERE tenant_id='demo' AND sales_order_id=?", order.get("id"));
        return new String[] {str(order, "id"), str(line, "id")};
    }

    /** A one-line purchase order (base unit) drafted and submitted by {@code buyer}, approved by {@code approver}. */
    String approvedPurchase(
            Actor buyer, Actor approver, String supplier, String item, String qty, String cost, int days) {
        String due = LocalDate.now().plusDays(days).toString();
        var order = procurement.createOrder(
                buyer,
                new ProcurementCommands.Order(
                        supplier,
                        null,
                        due,
                        "",
                        List.of(new ProcurementCommands.OrderLine(
                                null, item, new BigDecimal(qty), null, new BigDecimal(cost), null))),
                id());
        String id = str(order, "id");
        procurement.submit(buyer, id, id());
        procurement.approvePurchase(approver, id, id());
        return id;
    }

    void addBomLine(String product, String material, String qty) {
        s.db().update(
                        "INSERT INTO bom_lines(id,tenant_id,bom_id,item_id,quantity) VALUES(?,'demo',?,?,?)",
                        id(),
                        "bom-" + product,
                        material,
                        new BigDecimal(qty));
    }
}
