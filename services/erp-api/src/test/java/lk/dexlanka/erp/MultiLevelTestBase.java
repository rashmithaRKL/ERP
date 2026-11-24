package lk.dexlanka.erp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.catalog.CatalogCommands;
import lk.dexlanka.erp.catalog.CatalogService;
import lk.dexlanka.erp.manufacturing.ManufacturingCommands;
import lk.dexlanka.erp.manufacturing.ManufacturingService;
import lk.dexlanka.erp.platform.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Fixture for the multi-level BOM and planning tests (V7): a fresh tenant with every role, one work center and helpers
 * for items, revisions, stock and orders. Adds no Spring configuration, so the shared test context is reused.
 */
public abstract class MultiLevelTestBase extends IntegrationTestBase {
    @Autowired
    protected CatalogService catalog;

    @Autowired
    protected ManufacturingService mo;

    protected TestTenant tenant;
    protected String t;
    protected String machine;
    protected Actor admin, planner, otherPlanner, warehouse, operator, quality, viewer;

    @BeforeEach
    void multiLevelFixture() {
        tenant = createTenant("Multi-level fixture");
        t = tenant.id();
        admin = createUser(t, "ADMIN");
        planner = createUser(t, "PLANNER");
        otherPlanner = createUser(t, "PLANNER");
        warehouse = createUser(t, "WAREHOUSE");
        operator = createUser(t, "OPERATOR");
        quality = createUser(t, "QUALITY");
        viewer = createUser(t, "VIEWER");
        machine = id();
        update(
                "INSERT INTO machines(id,tenant_id,factory_id,code,name,kind,status,hourly_rate) VALUES(?,?,?,'M-01','Line 1','Moulding','IDLE',10)",
                machine,
                t,
                tenant.plant());
    }

    /** A new item with a standard cost, safety stock (reorder level) and lead time. */
    protected String item(String sku, String type, String uom, String cost, String safety, int leadDays) {
        String item = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level,lead_time_days) VALUES(?,?,?,?,?,?,?,?,?)",
                item,
                t,
                sku,
                sku + " name",
                type,
                uom,
                bd(cost),
                bd(safety),
                leadDays);
        return item;
    }

    protected String item(String sku, String type, String uom, String cost) {
        return item(sku, type, uom, cost, "0", 0);
    }

    /** A draft revision; {@code lines} alternates item id and quantity. */
    protected String draftBom(String product, String revision, String output, String yield, String... lines) {
        var bomLines = new ArrayList<CatalogCommands.BomLine>();
        for (int i = 0; i < lines.length; i += 2) bomLines.add(new CatalogCommands.BomLine(lines[i], bd(lines[i + 1])));
        return str(
                catalog.bom(planner, new CatalogCommands.Bom(product, revision, bd(output), bd(yield), bomLines), id()),
                "id");
    }

    protected String approvedBom(String product, String revision, String output, String yield, String... lines) {
        String bom = draftBom(product, revision, output, yield, lines);
        catalog.approveBom(admin, bom, id());
        return bom;
    }

    /** A released lot in the raw warehouse (semi-finished stock lives there too). */
    protected String stock(String item, String quantity, String unitCost) {
        return openingStock(admin, item, tenant.rawWarehouse(), quantity, unitCost);
    }

    /** A lot awaiting quality inspection (counted as supply by planning, not usable for reservation). */
    protected String quarantine(String item, String quantity, String unitCost) {
        String lot = stock(item, quantity, unitCost);
        update("UPDATE lots SET status='QUARANTINE' WHERE tenant_id=? AND id=?", t, lot);
        return lot;
    }

    protected String order(String bom, String quantity, LocalDate due) {
        return str(
                mo.create(
                        planner,
                        new ManufacturingCommands.Manufacture(
                                bom, machine, null, bd(quantity), "NORMAL", due.toString()),
                        id()),
                "id");
    }

    protected Map<String, Object> orderRow(String order) {
        return row("SELECT * FROM manufacturing_orders WHERE tenant_id=? AND id=?", t, order);
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    protected static Map<String, Object> find(List<Map<String, Object>> rows, String key, Object value) {
        return rows.stream()
                .filter(r -> value.equals(r.get(key)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row with " + key + "=" + value + " in " + rows));
    }

    protected static BigDecimal num(Object value) {
        return new BigDecimal(String.valueOf(value));
    }
}
