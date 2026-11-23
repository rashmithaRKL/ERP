package lk.dexlanka.erp.sales;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.platform.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A tenant with sales, warehouse, planning, operator and quality users, two finished goods (bottle, cap), a raw
 * material with an approved bottle BOM, a work center, a customer and a supplier. Released finished stock comes from
 * {@link #finishedLot} (an opening lot posted through the real ledger).
 */
abstract class SalesFixture extends IntegrationTestBase {
    @Autowired
    SalesService sales;

    @Autowired
    ReturnsService returns;

    @Autowired
    SalesQuery query;

    String t, bottle, cap, resin, bom, machine, customer, supplier, finished, raw;
    Actor admin, planner, salesRep, warehouse, operator, quality, viewer;

    @BeforeEach
    void salesFixture() {
        var tenant = createTenant("Sales fixture");
        t = tenant.id();
        finished = tenant.finishedWarehouse();
        raw = tenant.rawWarehouse();
        admin = createUser(t, "ADMIN");
        planner = createUser(t, "PLANNER");
        salesRep = createUser(t, "SALES");
        warehouse = createUser(t, "WAREHOUSE");
        operator = createUser(t, "OPERATOR");
        quality = createUser(t, "QUALITY");
        viewer = createUser(t, "VIEWER");
        bottle = id();
        cap = id();
        resin = id();
        bom = id();
        machine = id();
        customer = id();
        supplier = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,'FG-B','Bottle','FINISHED_GOOD','pcs',10,0),(?,?,'FG-C','Cap','FINISHED_GOOD','pcs',2,0),(?,?,'RM','Resin','RAW_MATERIAL','kg',10,0)",
                bottle,
                t,
                cap,
                t,
                resin,
                t);
        update(
                "INSERT INTO partners(id,tenant_id,name,type) VALUES(?,?,'Customer Co','CUSTOMER'),(?,?,'Supplier Co','SUPPLIER')",
                customer,
                t,
                supplier,
                t);
        update(
                "INSERT INTO machines(id,tenant_id,factory_id,code,name,kind,status,hourly_rate) VALUES(?,?,?,'M-01','Moulding','Moulding','IDLE',10)",
                machine,
                t,
                tenant.plant());
        update(
                "INSERT INTO boms(id,tenant_id,product_id,revision,status,output_qty) VALUES(?,?,?,'R1','DRAFT',1)",
                bom,
                t,
                bottle);
        update("INSERT INTO bom_lines(id,tenant_id,bom_id,item_id,quantity) VALUES(?,?,?,?,1)", id(), t, bom, resin);
        update("UPDATE boms SET status='APPROVED' WHERE tenant_id=? AND id=?", t, bom);
    }

    /** A quality-released opening lot of a finished good in the finished-goods warehouse. */
    String finishedLot(String item, String quantity, String unitCost) {
        return openingStock(admin, item, finished, quantity, unitCost);
    }

    static SalesCommands.Line line(String product, String quantity, String price, String discount) {
        return new SalesCommands.Line(null, product, bd(quantity), bd(price), discount == null ? null : bd(discount));
    }

    SalesCommands.Order order(SalesCommands.Line... lines) {
        return new SalesCommands.Order(
                customer, "PO-123", LocalDate.now().plusDays(5).toString(), "", List.of(lines));
    }

    String draft(SalesCommands.Line... lines) {
        return str(sales.create(salesRep, order(lines), id()), "id");
    }

    String confirmed(SalesCommands.Line... lines) {
        String so = draft(lines);
        sales.confirm(salesRep, so, id());
        return so;
    }

    String lineId(String so, int number) {
        return str(
                row(
                        "SELECT id FROM sales_order_lines WHERE tenant_id=? AND sales_order_id=? AND line_no=?",
                        t,
                        so,
                        number),
                "id");
    }

    Map<String, Object> dispatch(String so, int line, String reference, Object... lotQuantities) {
        var lots = new java.util.ArrayList<SalesCommands.Allocation>();
        for (int i = 0; i < lotQuantities.length; i += 2)
            lots.add(new SalesCommands.Allocation((String) lotQuantities[i], bd((String) lotQuantities[i + 1])));
        return sales.dispatchLine(
                warehouse, so, lineId(so, line), new SalesCommands.DispatchLine(reference, lots), id());
    }

    Map<String, Object> header(String so) {
        return row("SELECT * FROM sales_orders WHERE tenant_id=? AND id=?", t, so);
    }

    Map<String, Object> salesLine(String so, int number) {
        return row(
                "SELECT * FROM sales_order_lines WHERE tenant_id=? AND sales_order_id=? AND line_no=?", t, so, number);
    }

    /** Net movement of one account for this tenant (debit positive), optionally limited to a journal reference. */
    BigDecimal account(String account, String reference) {
        return decimal(
                "SELECT COALESCE(SUM(l.debit-l.credit),0) FROM journal_lines l JOIN journal_entries e ON e.tenant_id=l.tenant_id AND e.id=l.entry_id WHERE l.tenant_id=? AND l.account=? AND (?::text IS NULL OR e.reference=?)",
                t,
                account,
                reference,
                reference);
    }

    BigDecimal journalImbalance() {
        return decimal("SELECT COALESCE(SUM(debit-credit),0) FROM journal_lines WHERE tenant_id=?", t);
    }

    /** {@code key} of every row of a JSON array (a decoded response list). */
    static List<Object> values(Object rows, String key) {
        return ((List<?>) rows)
                .stream().<Object>map(r -> ((Map<?, ?>) r).get(key)).toList();
    }

    BigDecimal stock(String lot) {
        return decimal("SELECT quantity FROM stock_balances WHERE tenant_id=? AND lot_id=?", t, lot);
    }
}
