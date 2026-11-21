package lk.dexlanka.erp.procurement;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.money;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Purchase orders, independent approval, goods receipt into quarantine and three-way supplier invoice matching. */
@Service
public class ProcurementService {
    private final Store s;

    ProcurementService(Store s) {
        this.s = s;
    }

    @Transactional
    public Map<String, Object> purchase(Actor a, ProcurementCommands.Purchase c, String key) {
        a.allow("PLANNER", "WAREHOUSE");
        s.validDate(c.dueDate());
        return s.once(a, key, "purchase", c, () -> {
            s.one("SELECT id FROM partners WHERE tenant_id=? AND id=? AND type='SUPPLIER'", a.tenant(), c.supplierId());
            var item = s.one(
                    "SELECT * FROM items WHERE tenant_id=? AND id=? AND type<>'FINISHED_GOOD' AND active=true",
                    a.tenant(),
                    c.itemId());
            s.quantity(str(item, "uom"), c.quantity());
            String id = id();
            String num = s.number(a, "PO");
            s.db().update(
                            "INSERT INTO purchase_orders(id,tenant_id,number,supplier_id,item_id,quantity,unit_cost,due_date,created_by) VALUES(?,?,?,?,?,?,?,?::date,?)",
                            id,
                            a.tenant(),
                            num,
                            c.supplierId(),
                            c.itemId(),
                            c.quantity(),
                            c.unitCost(),
                            c.dueDate(),
                            a.id());
            s.audit(a, "PO_CREATED", num, "Purchase awaiting independent approval");
            return s.one("SELECT * FROM purchase_orders WHERE tenant_id=? AND id=?", a.tenant(), id);
        });
    }

    @Transactional
    public Map<String, Object> approvePurchase(Actor a, String id, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "approve-po:" + id, Map.of(), () -> {
            var p = s.one("SELECT * FROM purchase_orders WHERE tenant_id=? AND id=?", a.tenant(), id);
            require(p.get("status").equals("DRAFT"), "Only draft purchases can be approved");
            require(!p.get("created_by").equals(a.id()), "A different authorized person must approve this purchase");
            s.db().update("UPDATE purchase_orders SET status='APPROVED' WHERE tenant_id=? AND id=?", a.tenant(), id);
            s.audit(a, "PO_APPROVED", str(p, "number"), "Approved for receipt");
            return Map.of("id", id, "status", "APPROVED");
        });
    }

    @Transactional
    public Map<String, Object> receive(Actor a, String id, ProcurementCommands.Receive c, String key) {
        a.allow("WAREHOUSE");
        return s.once(a, key, "receive:" + id, c, () -> {
            s.openPeriod(a);
            var p = s.one("SELECT * FROM purchase_orders WHERE tenant_id=? AND id=?", a.tenant(), id);
            require(
                    Set.of("APPROVED", "PARTIAL").contains(p.get("status")),
                    "Only approved, outstanding purchases can be received");
            require(
                    dec(p.get("received")).add(c.quantity()).compareTo(dec(p.get("quantity"))) <= 0,
                    "Receipt exceeds outstanding purchase quantity");
            s.one("SELECT id FROM warehouses WHERE tenant_id=? AND id=? AND kind='RAW'", a.tenant(), c.warehouseId());
            String lot = id();
            String receipt = id();
            s.db().update(
                            "INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost) VALUES(?,?,?,?,?,'QUARANTINE','PURCHASE',?,?)",
                            lot,
                            a.tenant(),
                            c.supplierLot(),
                            p.get("item_id"),
                            c.warehouseId(),
                            id,
                            p.get("unit_cost"));
            BigDecimal cost = s.ledger(a, lot, c.quantity(), str(p, "number"), "GOODS_RECEIPT");
            s.db().update(
                            "INSERT INTO receipts(id,tenant_id,purchase_order_id,lot_id,quantity) VALUES(?,?,?,?,?)",
                            receipt,
                            a.tenant(),
                            id,
                            lot,
                            c.quantity());
            s.db().update(
                            "INSERT INTO inspections(id,tenant_id,lot_id,type) VALUES(?,?,?,'INCOMING')",
                            id(),
                            a.tenant(),
                            lot);
            s.db().update(
                            "UPDATE purchase_orders SET received=received+?,status=CASE WHEN received+?=quantity THEN 'RECEIVED' ELSE 'PARTIAL' END WHERE tenant_id=? AND id=?",
                            c.quantity(),
                            c.quantity(),
                            a.tenant(),
                            id);
            s.journal(a, str(p, "number"), "Goods received into quality quarantine", "RAW_INVENTORY", "GRNI", cost);
            s.audit(a, "GOODS_RECEIVED", str(p, "number"), c.quantity() + " in quarantined lot " + c.supplierLot());
            return Map.of("id", receipt, "lot_id", lot, "status", "QUARANTINE");
        });
    }

    @Transactional
    public Map<String, Object> invoice(Actor a, String id, ProcurementCommands.Invoice c, String key) {
        a.allow();
        return s.once(a, key, "invoice:" + id, c, () -> {
            var r = s.one(
                    "SELECT r.*,l.status,p.unit_cost,p.number FROM receipts r JOIN lots l ON l.tenant_id=r.tenant_id AND l.id=r.lot_id JOIN purchase_orders p ON p.tenant_id=r.tenant_id AND p.id=r.purchase_order_id WHERE r.tenant_id=? AND r.id=?",
                    a.tenant(),
                    id);
            require(r.get("status").equals("RELEASED"), "Only quality-released receipts can be matched");
            require(r.get("invoice_reference") == null, "This receipt is already invoiced");
            s.journal(
                    a,
                    c.reference(),
                    "Three-way matched supplier invoice for " + r.get("number"),
                    "GRNI",
                    "ACCOUNTS_PAYABLE",
                    money(dec(r.get("quantity")).multiply(dec(r.get("unit_cost")))));
            s.db().update(
                            "UPDATE receipts SET invoice_reference=? WHERE tenant_id=? AND id=?",
                            c.reference(),
                            a.tenant(),
                            id);
            s.audit(a, "INVOICE_MATCHED", c.reference(), "PO price and accepted receipt quantity matched");
            return Map.of("id", id, "invoice_reference", c.reference());
        });
    }
}
