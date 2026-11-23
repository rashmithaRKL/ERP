package lk.dexlanka.erp.sales;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.money;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer returns against a delivery. The returned quantity enters a new {@code QUARANTINE} lot in the finished-goods
 * warehouse at the delivery's unit cost, a compensating journal reverses the delivery's revenue/receivable and cost of
 * goods sold for that quantity, and a {@code RETURN} inspection lets Quality control release (pass) or reject (fail)
 * the lot through the existing inspection command. Posted delivery, ledger and journal rows are never changed.
 */
@Service
public class ReturnsService {
    private final Store s;

    ReturnsService(Store s) {
        this.s = s;
    }

    @Transactional
    public Map<String, Object> customerReturn(Actor a, String deliveryId, SalesCommands.Return c, String key) {
        a.allow("SALES", "PLANNER", "WAREHOUSE");
        return s.once(a, key, "customer-return:" + deliveryId, c, () -> {
            s.openPeriod(a);
            var d = s.one("""
                    SELECT d.*,so.number AS order_number,sol.line_no,l.item_id,l.warehouse_id,w.kind AS warehouse_kind,
                           i.uom,i.sku
                    FROM deliveries d
                    JOIN sales_orders so ON so.tenant_id=d.tenant_id AND so.id=d.sales_order_id
                    JOIN sales_order_lines sol ON sol.tenant_id=d.tenant_id AND sol.id=d.sales_order_line_id
                    JOIN lots l ON l.tenant_id=d.tenant_id AND l.id=d.lot_id
                    JOIN warehouses w ON w.tenant_id=l.tenant_id AND w.id=l.warehouse_id
                    JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                    WHERE d.tenant_id=? AND d.id=?
                    FOR UPDATE OF d""", a.tenant(), deliveryId);
            s.quantity(str(d, "uom"), c.quantity());
            BigDecimal shipped = dec(d.get("quantity"));
            BigDecimal returnable = shipped.subtract(dec(d.get("returned")));
            require(
                    c.quantity().compareTo(returnable) <= 0,
                    "Return exceeds the delivered quantity not yet returned ("
                            + returnable.stripTrailingZeros().toPlainString() + " " + d.get("uom") + ")");
            var prior = s.one(
                    "SELECT COALESCE(SUM(cost_amount),0) AS cost,COALESCE(SUM(credit_amount),0) AS credit FROM customer_returns WHERE tenant_id=? AND delivery_id=?",
                    a.tenant(),
                    deliveryId);
            // Returning the rest of a delivery reverses exactly what remains, so rounding never leaves a residual.
            boolean rest = c.quantity().compareTo(returnable) == 0;
            BigDecimal cost = rest
                    ? dec(d.get("cost_amount")).subtract(dec(prior.get("cost")))
                    : share(dec(d.get("cost_amount")), c.quantity(), shipped);
            BigDecimal credit = rest
                    ? dec(d.get("revenue_amount")).subtract(dec(prior.get("credit")))
                    : share(dec(d.get("revenue_amount")), c.quantity(), shipped);
            cost = money(cost.max(BigDecimal.ZERO));
            credit = money(credit.max(BigDecimal.ZERO));
            String warehouse = d.get("warehouse_kind").equals("FINISHED")
                    ? str(d, "warehouse_id")
                    : str(
                            s.one(
                                    "SELECT id FROM warehouses WHERE tenant_id=? AND kind='FINISHED' ORDER BY id LIMIT 1",
                                    a.tenant()),
                            "id");
            String returnId = id();
            String number = s.number(a, "RT");
            String lot = id();
            String inspection = id();
            s.db().update(
                            "INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost) VALUES(?,?,?,?,?,'QUARANTINE','CUSTOMER_RETURN',?,?)",
                            lot,
                            a.tenant(),
                            number,
                            d.get("item_id"),
                            warehouse,
                            returnId,
                            cost.divide(c.quantity(), 6, RoundingMode.HALF_UP));
            s.ledger(a, lot, c.quantity(), number, "CUSTOMER_RETURN", cost);
            s.db().update(
                            "INSERT INTO inspections(id,tenant_id,lot_id,type) VALUES(?,?,?,'RETURN')",
                            inspection,
                            a.tenant(),
                            lot);
            s.db().update(
                            "INSERT INTO customer_returns(id,tenant_id,number,delivery_id,sales_order_id,sales_order_line_id,lot_id,inspection_id,quantity,unit_cost,cost_amount,credit_amount,reason,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            returnId,
                            a.tenant(),
                            number,
                            deliveryId,
                            d.get("sales_order_id"),
                            d.get("sales_order_line_id"),
                            lot,
                            inspection,
                            c.quantity(),
                            cost.divide(c.quantity(), 6, RoundingMode.HALF_UP),
                            cost,
                            credit,
                            c.reason().strip(),
                            a.id());
            s.db().update(
                            "UPDATE deliveries SET returned=returned+? WHERE tenant_id=? AND id=?",
                            c.quantity(),
                            a.tenant(),
                            deliveryId);
            s.db().update(
                            "UPDATE sales_order_lines SET returned=returned+? WHERE tenant_id=? AND id=?",
                            c.quantity(),
                            a.tenant(),
                            d.get("sales_order_line_id"));
            s.journal(
                    a,
                    number,
                    "Customer return: sales revenue and receivable reversed",
                    "SALES_REVENUE",
                    "ACCOUNTS_RECEIVABLE",
                    credit);
            s.journal(
                    a,
                    number,
                    "Customer return: cost of goods sold reversed into held stock",
                    "FG_INVENTORY",
                    "COST_OF_GOODS_SOLD",
                    cost);
            s.audit(
                    a,
                    "CUSTOMER_RETURN_POSTED",
                    str(d, "order_number"),
                    number + ": " + c.quantity().stripTrailingZeros().toPlainString() + " " + d.get("uom") + " of "
                            + d.get("sku") + " (line " + d.get("line_no") + ") from " + d.get("reference")
                            + " into quality quarantine · " + c.reason().strip());
            var out = new LinkedHashMap<String, Object>();
            out.put("id", returnId);
            out.put("number", number);
            out.put("lotId", lot);
            out.put("inspectionId", inspection);
            out.put("status", "AWAITING_QC");
            return out;
        });
    }

    private static BigDecimal share(BigDecimal amount, BigDecimal quantity, BigDecimal of) {
        return amount.multiply(quantity).divide(of, 6, RoundingMode.HALF_UP);
    }
}
