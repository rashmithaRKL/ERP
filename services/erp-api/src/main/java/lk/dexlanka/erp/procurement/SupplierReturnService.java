package lk.dexlanka.erp.procurement;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.money;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;
import static lk.dexlanka.erp.procurement.PurchaseRules.amount;
import static lk.dexlanka.erp.procurement.PurchaseRules.plain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier returns of quality-released stock against a receipt. The stock leaves the receipt lot at its balance value
 * ({@code Store.ledger}, reason {@code SUPPLIER_RETURN}) and the receipt posting is compensated like an incoming QC
 * rejection: debit GRNI / credit RAW_INVENTORY for the part not yet invoiced. The part already invoiced is settled by
 * the debit note against the supplier: debit ACCOUNTS_PAYABLE / credit RAW_INVENTORY. The order line's returned figure
 * grows; the received quantity (and so the open order remainder) is unchanged.
 */
@Service
public class SupplierReturnService {
    private final Store s;
    private final PurchaseRules rules;

    SupplierReturnService(Store s, PurchaseRules rules) {
        this.s = s;
        this.rules = rules;
    }

    @Transactional
    public Map<String, Object> returnToSupplier(
            Actor a, String receiptId, ProcurementCommands.SupplierReturn c, String key) {
        a.allow("WAREHOUSE");
        return s.once(a, key, "supplier-return:" + receiptId, c, () -> {
            s.openPeriod(a);
            var r = s.one("""
                    SELECT r.*,po.number AS order_number,po.status AS order_status,po.supplier_id,pl.line_no,pl.item_id,
                           lt.number AS lot,lt.status AS lot_status,i.uom,i.sku
                    FROM receipts r
                    JOIN purchase_orders po ON po.tenant_id=r.tenant_id AND po.id=r.purchase_order_id
                    JOIN purchase_order_lines pl ON pl.tenant_id=r.tenant_id AND pl.id=r.purchase_order_line_id
                    JOIN lots lt ON lt.tenant_id=r.tenant_id AND lt.id=r.lot_id
                    JOIN items i ON i.tenant_id=pl.tenant_id AND i.id=pl.item_id
                    WHERE r.tenant_id=? AND r.id=?
                    FOR UPDATE OF r""", a.tenant(), receiptId);
            rules.locked(a, str(r, "purchase_order_id"));
            require(
                    r.get("lot_status").equals("RELEASED"),
                    r.get("lot_status").equals("QUARANTINE")
                            ? "Lot " + r.get("lot")
                                    + " is awaiting its incoming inspection; a failed inspection returns it to the supplier"
                            : "Lot " + r.get("lot") + " was rejected at incoming inspection and already returned");
            s.quantity(str(r, "uom"), c.quantity());
            BigDecimal returnable = dec(r.get("quantity")).subtract(dec(r.get("returned")));
            require(
                    c.quantity().compareTo(returnable) <= 0,
                    "Return exceeds the received quantity not yet returned (" + plain(returnable) + " " + r.get("uom")
                            + ")");
            var balance = s.one(
                    "SELECT * FROM stock_balances WHERE tenant_id=? AND lot_id=? FOR UPDATE",
                    a.tenant(),
                    r.get("lot_id"));
            BigDecimal available = dec(balance.get("quantity")).subtract(dec(balance.get("reserved")));
            require(
                    c.quantity().compareTo(available) <= 0,
                    "Only " + plain(available) + " " + r.get("uom") + " of lot " + r.get("lot")
                            + " is available (unreserved, not yet consumed or moved); return less or release reservations");
            String number = rules.documentNumber(a, "supplier_returns", "SR");
            BigDecimal value = s.ledger(a, str(r, "lot_id"), c.quantity().negate(), number, "SUPPLIER_RETURN")
                    .abs();
            // Received but not yet invoiced is reversed from goods-received clearing first; the rest was invoiced.
            BigDecimal open = SupplierInvoiceService.open(r).max(BigDecimal.ZERO);
            BigDecimal clearingQuantity = c.quantity().min(open);
            BigDecimal payableQuantity = c.quantity().subtract(clearingQuantity);
            BigDecimal clearingAmount = payableQuantity.signum() == 0
                    ? value
                    : clearingQuantity.signum() == 0
                            ? BigDecimal.ZERO
                            : money(value.multiply(clearingQuantity).divide(c.quantity(), 12, RoundingMode.HALF_UP));
            BigDecimal payableAmount = value.subtract(clearingAmount);
            String id = id();
            s.db().update(
                            "INSERT INTO supplier_returns(id,tenant_id,number,purchase_order_id,purchase_order_line_id,receipt_id,lot_id,supplier_id,item_id,quantity,value_amount,clearing_quantity,clearing_amount,payable_quantity,payable_amount,debit_note,reason,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            id,
                            a.tenant(),
                            number,
                            r.get("purchase_order_id"),
                            r.get("purchase_order_line_id"),
                            receiptId,
                            r.get("lot_id"),
                            r.get("supplier_id"),
                            r.get("item_id"),
                            c.quantity(),
                            value,
                            clearingQuantity,
                            clearingAmount,
                            payableQuantity,
                            payableAmount,
                            c.debitNote().strip(),
                            c.reason().strip(),
                            a.id());
            s.db().update(
                            "UPDATE receipts SET returned=returned+?,credited=credited+? WHERE tenant_id=? AND id=?",
                            c.quantity(),
                            payableQuantity,
                            a.tenant(),
                            receiptId);
            s.db().update(
                            "UPDATE purchase_order_lines SET returned=returned+? WHERE tenant_id=? AND id=?",
                            c.quantity(),
                            a.tenant(),
                            r.get("purchase_order_line_id"));
            s.journal(
                    a,
                    number,
                    "Supplier return: goods received clearing reversed for " + r.get("order_number"),
                    "GRNI",
                    "RAW_INVENTORY",
                    clearingAmount);
            s.journal(
                    a,
                    number,
                    "Supplier return debit note " + c.debitNote().strip() + " for " + r.get("order_number"),
                    "ACCOUNTS_PAYABLE",
                    "RAW_INVENTORY",
                    payableAmount);
            s.audit(
                    a,
                    "SUPPLIER_RETURN_POSTED",
                    str(r, "order_number"),
                    number + ": " + plain(c.quantity()) + " " + r.get("uom") + " of " + r.get("sku") + " (line "
                            + r.get("line_no") + ") from lot " + r.get("lot") + " returned to the supplier at "
                            + amount(value) + " · debit note " + c.debitNote().strip() + " · "
                            + c.reason().strip());
            var out = new LinkedHashMap<String, Object>();
            out.put("id", id);
            out.put("number", number);
            out.put("status", "POSTED");
            out.put("value", value);
            out.put("clearingAmount", clearingAmount);
            out.put("payableAmount", payableAmount);
            return out;
        });
    }
}
