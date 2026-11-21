package lk.dexlanka.erp.sales;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.money;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer demand and lot-specific dispatch, which recognizes revenue/receivables and cost of goods sold. */
@Service
public class SalesService {
    private final Store s;

    SalesService(Store s) {
        this.s = s;
    }

    @Transactional
    public Map<String, Object> sale(Actor a, SalesCommands.Sale c, String key) {
        a.allow("SALES", "PLANNER");
        s.validDate(c.dueDate());
        return s.once(a, key, "sale", c, () -> {
            s.one("SELECT id FROM partners WHERE tenant_id=? AND id=? AND type='CUSTOMER'", a.tenant(), c.customerId());
            var item = s.one(
                    "SELECT * FROM items WHERE tenant_id=? AND id=? AND type='FINISHED_GOOD' AND active=true",
                    a.tenant(),
                    c.productId());
            s.quantity(str(item, "uom"), c.quantity());
            String id = id();
            String num = s.number(a, "SO");
            s.db().update(
                            "INSERT INTO sales_orders(id,tenant_id,number,customer_id,product_id,quantity,unit_price,due_date) VALUES(?,?,?,?,?,?,?,?::date)",
                            id,
                            a.tenant(),
                            num,
                            c.customerId(),
                            c.productId(),
                            c.quantity(),
                            c.unitPrice(),
                            c.dueDate());
            s.audit(a, "SALE_CONFIRMED", num, c.quantity() + " " + item.get("uom") + " confirmed");
            return s.one("SELECT * FROM sales_orders WHERE tenant_id=? AND id=?", a.tenant(), id);
        });
    }

    @Transactional
    public Map<String, Object> dispatch(Actor a, String id, SalesCommands.Dispatch c, String key) {
        a.allow("WAREHOUSE");
        return s.once(a, key, "dispatch:" + id, c, () -> {
            s.openPeriod(a);
            var so = s.one("SELECT * FROM sales_orders WHERE tenant_id=? AND id=?", a.tenant(), id);
            var lot = s.one(
                    "SELECT l.*,b.quantity-b.reserved AS available FROM lots l JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id WHERE l.tenant_id=? AND l.id=?",
                    a.tenant(),
                    c.lotId());
            require(lot.get("status").equals("RELEASED"), "Quality-held or rejected lots cannot be dispatched");
            require(lot.get("item_id").equals(so.get("product_id")), "Lot does not match the sales product");
            require(c.quantity().compareTo(dec(lot.get("available"))) <= 0, "Insufficient unreserved finished stock");
            require(
                    dec(so.get("delivered")).add(c.quantity()).compareTo(dec(so.get("quantity"))) <= 0,
                    "Dispatch exceeds the outstanding customer quantity");
            BigDecimal cost = s.ledger(a, c.lotId(), c.quantity().negate(), c.reference(), "CUSTOMER_DISPATCH")
                    .abs();
            s.db().update(
                            "INSERT INTO deliveries(id,tenant_id,sales_order_id,lot_id,quantity,reference) VALUES(?,?,?,?,?,?)",
                            id(),
                            a.tenant(),
                            id,
                            c.lotId(),
                            c.quantity(),
                            c.reference());
            s.db().update(
                            "UPDATE sales_orders SET delivered=delivered+?,status=CASE WHEN delivered+?=quantity THEN 'DELIVERED' ELSE 'PARTIAL' END WHERE tenant_id=? AND id=?",
                            c.quantity(),
                            c.quantity(),
                            a.tenant(),
                            id);
            s.journal(a, c.reference(), "Finished goods dispatched", "COST_OF_GOODS_SOLD", "FG_INVENTORY", cost);
            s.journal(
                    a,
                    c.reference(),
                    "Sales invoice recognized on dispatch",
                    "ACCOUNTS_RECEIVABLE",
                    "SALES_REVENUE",
                    money(c.quantity().multiply(dec(so.get("unit_price")))));
            s.audit(a, "DISPATCH_POSTED", c.reference(), c.quantity() + " units to " + so.get("number"));
            return Map.of("id", id, "status", "DISPATCHED");
        });
    }
}
