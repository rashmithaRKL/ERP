package lk.dexlanka.erp;

import static lk.dexlanka.erp.Store.*;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OperationsService {
    final Store s;
    OperationsService(Store s) {this.s=s;}
    @Transactional Map<String,Object> purchase(Actor a,Commands.Purchase c,String key) {
        a.allow("PLANNER","WAREHOUSE");s.validDate(c.dueDate());return s.once(a,key,"purchase",c,() -> {
            s.one("SELECT id FROM partners WHERE tenant_id=? AND id=? AND type='SUPPLIER'",a.tenant(),c.supplierId());
            var item=s.one("SELECT * FROM items WHERE tenant_id=? AND id=? AND type<>'FINISHED_GOOD' AND active=true",a.tenant(),c.itemId());s.quantity(str(item,"uom"),c.quantity());
            String id=id(),num=s.number(a,"PO");s.db.update("INSERT INTO purchase_orders(id,tenant_id,number,supplier_id,item_id,quantity,unit_cost,due_date,created_by) VALUES(?,?,?,?,?,?,?,?::date,?)",id,a.tenant(),num,c.supplierId(),c.itemId(),c.quantity(),c.unitCost(),c.dueDate(),a.id());s.audit(a,"PO_CREATED",num,"Purchase awaiting independent approval");return s.one("SELECT * FROM purchase_orders WHERE tenant_id=? AND id=?",a.tenant(),id);
        });
    }
    @Transactional Map<String,Object> approvePurchase(Actor a,String id,String key) {
        a.allow("PLANNER");return s.once(a,key,"approve-po:"+id,Map.of(),() -> {
            var p=s.one("SELECT * FROM purchase_orders WHERE tenant_id=? AND id=?",a.tenant(),id);require(p.get("status").equals("DRAFT"),"Only draft purchases can be approved");require(!p.get("created_by").equals(a.id()),"A different authorized person must approve this purchase");
            s.db.update("UPDATE purchase_orders SET status='APPROVED' WHERE tenant_id=? AND id=?",a.tenant(),id);s.audit(a,"PO_APPROVED",str(p,"number"),"Approved for receipt");return Map.of("id",id,"status","APPROVED");
        });
    }
    @Transactional Map<String,Object> receive(Actor a,String id,Commands.Receive c,String key) {
        a.allow("WAREHOUSE");return s.once(a,key,"receive:"+id,c,() -> {
            s.openPeriod(a);var p=s.one("SELECT * FROM purchase_orders WHERE tenant_id=? AND id=?",a.tenant(),id);require(Set.of("APPROVED","PARTIAL").contains(p.get("status")),"Only approved, outstanding purchases can be received");
            require(dec(p.get("received")).add(c.quantity()).compareTo(dec(p.get("quantity")))<=0,"Receipt exceeds outstanding purchase quantity");
            s.one("SELECT id FROM warehouses WHERE tenant_id=? AND id=? AND kind='RAW'",a.tenant(),c.warehouseId());
            String lot=id(),receipt=id();s.db.update("INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost) VALUES(?,?,?,?,?,'QUARANTINE','PURCHASE',?,?)",lot,a.tenant(),c.supplierLot(),p.get("item_id"),c.warehouseId(),id,p.get("unit_cost"));
            BigDecimal cost=s.ledger(a,lot,c.quantity(),str(p,"number"),"GOODS_RECEIPT");
            s.db.update("INSERT INTO receipts(id,tenant_id,purchase_order_id,lot_id,quantity) VALUES(?,?,?,?,?)",receipt,a.tenant(),id,lot,c.quantity());
            s.db.update("INSERT INTO inspections(id,tenant_id,lot_id,type) VALUES(?,?,?,'INCOMING')",id(),a.tenant(),lot);
            s.db.update("UPDATE purchase_orders SET received=received+?,status=CASE WHEN received+?=quantity THEN 'RECEIVED' ELSE 'PARTIAL' END WHERE tenant_id=? AND id=?",c.quantity(),c.quantity(),a.tenant(),id);
            s.journal(a,str(p,"number"),"Goods received into quality quarantine","RAW_INVENTORY","GRNI",cost);s.audit(a,"GOODS_RECEIVED",str(p,"number"),c.quantity()+" in quarantined lot "+c.supplierLot());return Map.of("id",receipt,"lot_id",lot,"status","QUARANTINE");
        });
    }
    @Transactional Map<String,Object> invoice(Actor a,String id,Commands.Invoice c,String key) {
        a.allow();return s.once(a,key,"invoice:"+id,c,() -> {
            var r=s.one("SELECT r.*,l.status,p.unit_cost,p.number FROM receipts r JOIN lots l ON l.tenant_id=r.tenant_id AND l.id=r.lot_id JOIN purchase_orders p ON p.tenant_id=r.tenant_id AND p.id=r.purchase_order_id WHERE r.tenant_id=? AND r.id=?",a.tenant(),id);
            require(r.get("status").equals("RELEASED"),"Only quality-released receipts can be matched");require(r.get("invoice_reference")==null,"This receipt is already invoiced");
            s.journal(a,c.reference(),"Three-way matched supplier invoice for "+r.get("number"),"GRNI","ACCOUNTS_PAYABLE",money(dec(r.get("quantity")).multiply(dec(r.get("unit_cost")))));
            s.db.update("UPDATE receipts SET invoice_reference=? WHERE tenant_id=? AND id=?",c.reference(),a.tenant(),id);s.audit(a,"INVOICE_MATCHED",c.reference(),"PO price and accepted receipt quantity matched");return Map.of("id",id,"invoice_reference",c.reference());
        });
    }
    @Transactional Map<String,Object> inspect(Actor a,String id,Commands.Inspect c,String key) {
        a.allow("QUALITY");return s.once(a,key,"inspect:"+id,c,() -> {
            var q=s.one("SELECT i.*,l.source_id,l.source_type,l.item_id FROM inspections i JOIN lots l ON l.tenant_id=i.tenant_id AND l.id=i.lot_id WHERE i.tenant_id=? AND i.id=?",a.tenant(),id);require(q.get("status").equals("PENDING"),"Inspection has already been finalized");
            s.db.update("UPDATE inspections SET status=?,notes=?,inspected_by=?,inspected_at=now() WHERE tenant_id=? AND id=?",c.result(),c.notes(),a.name(),a.tenant(),id);
            s.db.update("UPDATE lots SET status=? WHERE tenant_id=? AND id=?",c.result().equals("PASSED")?"RELEASED":"REJECTED",a.tenant(),q.get("lot_id"));
            if(c.result().equals("FAILED") && q.get("source_type").equals("PURCHASE")) {
                var b=s.one("SELECT * FROM stock_balances WHERE tenant_id=? AND lot_id=?",a.tenant(),q.get("lot_id"));
                BigDecimal cost=s.ledger(a,str(q,"lot_id"),dec(b.get("quantity")).negate(),str(q,"source_id"),"SUPPLIER_RETURN_QC").abs();
                s.journal(a,str(q,"source_id"),"Rejected incoming lot returned to supplier","GRNI","RAW_INVENTORY",cost);
                s.db.update("UPDATE purchase_orders SET received=received-?,status='PARTIAL' WHERE tenant_id=? AND id=?",b.get("quantity"),a.tenant(),q.get("source_id"));
            }
            if(q.get("source_type").equals("MANUFACTURING") && c.result().equals("PASSED")) {
                var o=s.one("SELECT * FROM manufacturing_orders WHERE tenant_id=? AND id=?",a.tenant(),q.get("source_id"));
                var pending=s.list("SELECT 1 FROM inspections i JOIN lots l ON l.tenant_id=i.tenant_id AND l.id=i.lot_id WHERE i.tenant_id=? AND l.source_type='MANUFACTURING' AND l.source_id=? AND i.status<>'PASSED'",a.tenant(),q.get("source_id"));
                if(pending.isEmpty() && dec(o.get("good_qty")).add(dec(o.get("scrap_qty"))).compareTo(dec(o.get("quantity")))==0) s.db.update("UPDATE manufacturing_orders SET status='COMPLETED' WHERE tenant_id=? AND id=?",a.tenant(),q.get("source_id"));
            }
            s.audit(a,"QC_"+c.result(),str(q,"lot_id"),c.notes());return Map.of("id",id,"status",c.result());
        });
    }
    @Transactional Map<String,Object> sale(Actor a,Commands.Sale c,String key) {
        a.allow("SALES","PLANNER");s.validDate(c.dueDate());return s.once(a,key,"sale",c,() -> {
            s.one("SELECT id FROM partners WHERE tenant_id=? AND id=? AND type='CUSTOMER'",a.tenant(),c.customerId());
            var item=s.one("SELECT * FROM items WHERE tenant_id=? AND id=? AND type='FINISHED_GOOD' AND active=true",a.tenant(),c.productId());s.quantity(str(item,"uom"),c.quantity());
            String id=id(),num=s.number(a,"SO");s.db.update("INSERT INTO sales_orders(id,tenant_id,number,customer_id,product_id,quantity,unit_price,due_date) VALUES(?,?,?,?,?,?,?,?::date)",id,a.tenant(),num,c.customerId(),c.productId(),c.quantity(),c.unitPrice(),c.dueDate());s.audit(a,"SALE_CONFIRMED",num,c.quantity()+" "+item.get("uom")+" confirmed");return s.one("SELECT * FROM sales_orders WHERE tenant_id=? AND id=?",a.tenant(),id);
        });
    }
    @Transactional Map<String,Object> dispatch(Actor a,String id,Commands.Dispatch c,String key) {
        a.allow("WAREHOUSE");return s.once(a,key,"dispatch:"+id,c,() -> {
            s.openPeriod(a);var so=s.one("SELECT * FROM sales_orders WHERE tenant_id=? AND id=?",a.tenant(),id);
            var lot=s.one("SELECT l.*,b.quantity-b.reserved AS available FROM lots l JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id WHERE l.tenant_id=? AND l.id=?",a.tenant(),c.lotId());
            require(lot.get("status").equals("RELEASED"),"Quality-held or rejected lots cannot be dispatched");require(lot.get("item_id").equals(so.get("product_id")),"Lot does not match the sales product");
            require(c.quantity().compareTo(dec(lot.get("available")))<=0,"Insufficient unreserved finished stock");require(dec(so.get("delivered")).add(c.quantity()).compareTo(dec(so.get("quantity")))<=0,"Dispatch exceeds the outstanding customer quantity");
            BigDecimal cost=s.ledger(a,c.lotId(),c.quantity().negate(),c.reference(),"CUSTOMER_DISPATCH").abs();
            s.db.update("INSERT INTO deliveries(id,tenant_id,sales_order_id,lot_id,quantity,reference) VALUES(?,?,?,?,?,?)",id(),a.tenant(),id,c.lotId(),c.quantity(),c.reference());
            s.db.update("UPDATE sales_orders SET delivered=delivered+?,status=CASE WHEN delivered+?=quantity THEN 'DELIVERED' ELSE 'PARTIAL' END WHERE tenant_id=? AND id=?",c.quantity(),c.quantity(),a.tenant(),id);
            s.journal(a,c.reference(),"Finished goods dispatched","COST_OF_GOODS_SOLD","FG_INVENTORY",cost);
            s.journal(a,c.reference(),"Sales invoice recognized on dispatch","ACCOUNTS_RECEIVABLE","SALES_REVENUE",money(c.quantity().multiply(dec(so.get("unit_price")))));
            s.audit(a,"DISPATCH_POSTED",c.reference(),c.quantity()+" units to "+so.get("number"));return Map.of("id",id,"status","DISPATCHED");
        });
    }
    @Transactional Map<String,Object> item(Actor a,Commands.Item c,String key) {
        a.allow("PLANNER");return s.once(a,key,"item",c,() -> {s.quantity(c.uom(),c.reorderLevel());String id=id();s.db.update("INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,?,?,?,?,?,?)",id,a.tenant(),c.sku(),c.name(),c.type(),c.uom(),c.standardCost(),c.reorderLevel());s.audit(a,"ITEM_CREATED",c.sku(),c.name());return Map.of("id",id);});
    }
}
