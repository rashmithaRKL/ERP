package lk.dexlanka.erp.procurement;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;
import static lk.dexlanka.erp.procurement.PurchaseRules.blankToNull;
import static lk.dexlanka.erp.procurement.PurchaseRules.plain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.catalog.UnitService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goods receipt: one or more lines of an approved order delivered under one delivery note into an active raw-material
 * warehouse. Each received line becomes its own quarantined lot (valued at the line's base unit cost) with an incoming
 * inspection, a {@code GOODS_RECEIPT} ledger entry and a RAW_INVENTORY / GRNI journal, exactly as single-item
 * receipts were posted before V6.
 */
@Service
public class ReceivingService {
    private final Store s;
    private final PurchaseRules rules;
    private final UnitService units;

    ReceivingService(Store s, PurchaseRules rules, UnitService units) {
        this.s = s;
        this.rules = rules;
        this.units = units;
    }

    @Transactional
    public Map<String, Object> receive(Actor a, String id, ProcurementCommands.GoodsReceipt c, String key) {
        a.allow("WAREHOUSE");
        return s.once(
                a,
                key,
                "po-receive:" + id,
                c,
                () -> post(a, id, c.warehouseId(), c.deliveryNote().strip(), c.lines()));
    }

    /** Single-lot receipt of the order's only open line (the original {@code POST /api/purchases/{id}/receive}). */
    @Transactional
    public Map<String, Object> receiveSingle(Actor a, String id, ProcurementCommands.Receive c, String key) {
        a.allow("WAREHOUSE");
        return s.once(a, key, "receive:" + id, c, () -> {
            rules.header(a, id);
            var open = s.list(
                    "SELECT id FROM purchase_order_lines WHERE tenant_id=? AND purchase_order_id=? AND received<base_quantity ORDER BY line_no",
                    a.tenant(),
                    id);
            require(!open.isEmpty(), "Receipt exceeds outstanding purchase quantity");
            require(open.size() == 1, "This purchase order has several open lines; receive it line by line");
            var lineId = str(open.getFirst(), "id");
            var line = s.one("SELECT item_id FROM purchase_order_lines WHERE tenant_id=? AND id=?", a.tenant(), lineId);
            String base = str(
                    s.one("SELECT uom FROM items WHERE tenant_id=? AND id=?", a.tenant(), line.get("item_id")), "uom");
            var posted = post(
                    a,
                    id,
                    c.warehouseId(),
                    null,
                    List.of(new ProcurementCommands.ReceiptLine(lineId, c.quantity(), base, c.supplierLot())));
            @SuppressWarnings("unchecked")
            var receipt = ((List<Map<String, Object>>) posted.get("receipts")).getFirst();
            return Map.of("id", receipt.get("id"), "lot_id", receipt.get("lotId"), "status", "QUARANTINE");
        });
    }

    private Map<String, Object> post(
            Actor a, String id, String warehouseId, String deliveryNote, List<ProcurementCommands.ReceiptLine> lines) {
        s.openPeriod(a);
        var po = rules.locked(a, id);
        require(
                PurchaseRules.RECEIVABLE.contains(str(po, "status")),
                "Only approved, outstanding purchases can be received");
        var warehouse = s.one("SELECT * FROM warehouses WHERE tenant_id=? AND id=?", a.tenant(), warehouseId);
        require(
                warehouse.get("kind").equals("RAW"),
                "Purchased goods are received into a raw-material warehouse; " + warehouse.get("name") + " is a "
                        + str(warehouse, "kind").toLowerCase(java.util.Locale.ROOT) + " warehouse");
        require(
                Boolean.TRUE.equals(warehouse.get("active")),
                "Warehouse " + warehouse.get("code") + " (" + warehouse.get("name")
                        + ") is inactive; choose an active raw-material warehouse");
        String grn = rules.documentNumber(a, "receipts", "GRN");
        var seen = new HashSet<String>();
        var lots = new HashSet<String>();
        var receipts = new ArrayList<Map<String, Object>>();
        var details = new ArrayList<String>();
        for (var r : lines) {
            require(seen.add(r.lineId()), "Each order line can appear only once per goods receipt");
            var line = s.one("""
                    SELECT l.*,i.uom,i.sku,i.name AS item FROM purchase_order_lines l
                    JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                    WHERE l.tenant_id=? AND l.purchase_order_id=? AND l.id=?
                    FOR UPDATE OF l""", a.tenant(), id, r.lineId());
            String unit = r.unit() == null || r.unit().isBlank()
                    ? str(line, "unit")
                    : r.unit().strip();
            BigDecimal quantity = units.toBaseExact(a, str(line, "item_id"), r.quantity(), unit);
            s.quantity(str(line, "uom"), quantity);
            require(quantity.signum() > 0, "Line " + line.get("line_no") + ": the quantity converts to zero");
            BigDecimal open = dec(line.get("base_quantity")).subtract(dec(line.get("received")));
            require(
                    quantity.compareTo(open) <= 0,
                    open.signum() == 0
                            ? "Line " + line.get("line_no") + " (" + line.get("sku") + ") is already fully received"
                            : "Receipt exceeds the outstanding quantity of line " + line.get("line_no") + " ("
                                    + plain(open) + " " + line.get("uom") + " of " + line.get("sku") + " open)");
            String lotNumber = blankToNull(r.supplierLot()) == null
                    ? grn + "-" + line.get("line_no")
                    : r.supplierLot().strip();
            require(
                    lots.add(lotNumber) && !rules.lotNumberTaken(a, lotNumber),
                    "Lot number " + lotNumber + " already exists; use a unique supplier lot reference");
            String lot = id();
            String receipt = id();
            s.db().update(
                            "INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost) VALUES(?,?,?,?,?,'QUARANTINE','PURCHASE',?,?)",
                            lot,
                            a.tenant(),
                            lotNumber,
                            line.get("item_id"),
                            warehouseId,
                            id,
                            line.get("base_unit_cost"));
            BigDecimal value = s.ledger(a, lot, quantity, str(po, "number"), "GOODS_RECEIPT");
            s.db().update(
                            "INSERT INTO receipts(id,tenant_id,purchase_order_id,purchase_order_line_id,lot_id,quantity,number,delivery_note,entered_quantity,entered_unit,unit_cost,value_amount,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            receipt,
                            a.tenant(),
                            id,
                            r.lineId(),
                            lot,
                            quantity,
                            grn,
                            deliveryNote,
                            r.quantity(),
                            unit,
                            line.get("base_unit_cost"),
                            value,
                            a.id());
            s.db().update(
                            "INSERT INTO inspections(id,tenant_id,lot_id,type) VALUES(?,?,?,'INCOMING')",
                            id(),
                            a.tenant(),
                            lot);
            s.db().update(
                            "UPDATE purchase_order_lines SET received=received+? WHERE tenant_id=? AND id=?",
                            quantity,
                            a.tenant(),
                            r.lineId());
            s.journal(a, str(po, "number"), "Goods received into quality quarantine", "RAW_INVENTORY", "GRNI", value);
            var out = new LinkedHashMap<String, Object>();
            out.put("id", receipt);
            out.put("lineId", r.lineId());
            out.put("lotId", lot);
            out.put("lot", lotNumber);
            out.put("quantity", quantity);
            out.put("value", value);
            receipts.add(out);
            details.add(plain(quantity) + " " + line.get("uom") + " of " + line.get("sku") + " (line "
                    + line.get("line_no") + ") in lot " + lotNumber);
        }
        String status = rules.refreshStatus(a, id);
        s.audit(
                a,
                "GOODS_RECEIVED",
                str(po, "number"),
                grn + (deliveryNote == null ? "" : " · delivery note " + deliveryNote) + ": "
                        + String.join(", ", details) + " into quality quarantine at " + warehouse.get("name"));
        var result = new LinkedHashMap<String, Object>();
        result.put("number", grn);
        result.put("status", "QUARANTINE");
        result.put("orderStatus", status);
        result.put("receipts", receipts);
        return result;
    }
}
