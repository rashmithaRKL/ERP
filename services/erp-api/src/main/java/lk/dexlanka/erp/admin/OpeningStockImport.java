package lk.dexlanka.erp.admin;

import static lk.dexlanka.erp.admin.ImportKind.choice;
import static lk.dexlanka.erp.admin.ImportKind.decimal;
import static lk.dexlanka.erp.admin.ImportKind.text;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/**
 * Opening balances, posted exactly like the base opening stock: per row a new lot ({@code source_type OPENING}, source =
 * the import batch number) and one {@code stock_ledger} receipt at the row's unit cost; per inventory account one
 * balanced journal entry, debit the inventory account (FINISHED_GOOD → {@code FG_INVENTORY}, everything else →
 * {@code RAW_INVENTORY}, as for receipts and adjustments) and credit {@value #EQUITY}. Quantities are in the item's base
 * unit. A {@code QUARANTINE} lot gets a pending {@code OPENING} inspection so quality can release or reject it.
 */
@Component
class OpeningStockImport implements ImportKind {
    static final String EQUITY = "OPENING_EQUITY";
    static final String LEDGER_REASON = "OPENING_STOCK_IMPORT";

    private final Store s;

    OpeningStockImport(Store s) {
        this.s = s;
    }

    @Override
    public String kind() {
        return "OPENING_STOCK";
    }

    @Override
    public List<String> required() {
        return List.of("item_sku", "warehouse_code", "quantity", "unit_cost");
    }

    @Override
    public List<String> optional() {
        return List.of("lot_number", "quality_status");
    }

    @Override
    public String template() {
        return """
                item_sku,warehouse_code,lot_number,quantity,unit_cost,quality_status
                RM-101,RM,OPEN-RM101-01,1250.5,410.00,RELEASED
                PK-201,RM,,40,3200.00,
                FG-301,FG,OPEN-FG301-01,12000,21.75,QUARANTINE
                """;
    }

    @Override
    public void begin(Context c) {
        s.openPeriod(c.actor);
    }

    @Override
    public Map<String, Object> row(Context c, CsvFile.Row row) {
        var a = c.actor;
        String sku = text(row, "item_sku", true, 50);
        String code = text(row, "warehouse_code", true, 20).toUpperCase(Locale.ROOT);
        String lotNumber = text(row, "lot_number", false, 60);
        BigDecimal quantity = decimal(row, "quantity", true, BigDecimal.ZERO, true);
        BigDecimal unitCost = decimal(row, "unit_cost", true, BigDecimal.ZERO, false);
        String status = choice(row, "quality_status", List.of("RELEASED", "QUARANTINE"), "RELEASED");

        var items = s.list("SELECT * FROM items WHERE tenant_id=? AND sku=?", a.tenant(), sku);
        if (items.isEmpty()) throw new RowError("item_sku", "No item with SKU " + sku);
        var item = items.getFirst();
        if (!Boolean.TRUE.equals(item.get("active"))) throw new RowError("item_sku", "Item " + sku + " is inactive");
        var warehouses = s.list("SELECT * FROM warehouses WHERE tenant_id=? AND code=?", a.tenant(), code);
        if (warehouses.isEmpty()) throw new RowError("warehouse_code", "No warehouse with code " + code);
        var warehouse = warehouses.getFirst();
        if (!Boolean.TRUE.equals(warehouse.get("active")))
            throw new RowError("warehouse_code", "Warehouse " + code + " is inactive");
        String uom = str(item, "uom");
        var decimals = s.list("SELECT decimals FROM units WHERE tenant_id=? AND code=?", a.tenant(), uom);
        int places = decimals.isEmpty() ? 6 : ((Number) decimals.getFirst().get("decimals")).intValue();
        if (Math.max(0, quantity.stripTrailingZeros().scale()) > places)
            throw new RowError(
                    "quantity",
                    places == 0
                            ? "Quantities in " + uom + " must be whole numbers"
                            : "Quantities in " + uom + " allow at most " + places + " decimal places");
        boolean generated = lotNumber.isEmpty();
        if (generated) lotNumber = generatedLot(a);
        else {
            if (!c.seen.add(lotNumber.toLowerCase(Locale.ROOT)))
                throw new RowError("lot_number", "Lot " + lotNumber + " appears more than once in this file");
            if (!s.list("SELECT 1 FROM lots WHERE tenant_id=? AND lower(number)=lower(?)", a.tenant(), lotNumber)
                    .isEmpty()) throw new RowError("lot_number", "Lot number " + lotNumber + " is already used");
        }

        String lot = id();
        s.db().update(
                        "INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost) VALUES(?,?,?,?,?,?,'OPENING',?,?)",
                        lot,
                        a.tenant(),
                        lotNumber,
                        item.get("id"),
                        warehouse.get("id"),
                        status,
                        c.batchNumber,
                        unitCost);
        BigDecimal value = s.ledger(a, lot, quantity, c.batchNumber, LEDGER_REASON);
        if (status.equals("QUARANTINE"))
            s.db().update(
                            "INSERT INTO inspections(id,tenant_id,lot_id,type) VALUES(?,?,?,'OPENING')",
                            id(),
                            a.tenant(),
                            lot);
        String account = "FINISHED_GOOD".equals(item.get("type")) ? "FG_INVENTORY" : "RAW_INVENTORY";
        c.accounts.merge(account, value, BigDecimal::add);
        c.totalValue = c.totalValue.add(value);

        var preview = new LinkedHashMap<String, Object>();
        preview.put("id", lot);
        preview.put("item_sku", sku);
        preview.put("item", item.get("name"));
        preview.put("warehouse", warehouse.get("name"));
        preview.put("warehouse_code", code);
        preview.put("lot_number", lotNumber);
        preview.put("lot_generated", generated);
        preview.put("quantity", quantity);
        preview.put("uom", uom);
        preview.put("unit_cost", unitCost);
        preview.put("value", value);
        preview.put("quality_status", status);
        preview.put("account", account);
        return preview;
    }

    @Override
    public void finish(Context c) {
        for (var e : c.accounts.entrySet())
            s.journal(c.actor, c.batchNumber, "Opening stock import · " + c.fileName, e.getKey(), EQUITY, e.getValue());
    }

    private String generatedLot(lk.dexlanka.erp.platform.Actor a) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = s.number(a, "OPN");
            if (s.list("SELECT 1 FROM lots WHERE tenant_id=? AND number=?", a.tenant(), candidate)
                    .isEmpty()) return candidate;
        }
        throw new IllegalStateException("Could not allocate a unique lot number");
    }
}
