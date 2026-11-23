package lk.dexlanka.erp.catalog;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Units of measure and the single quantity-conversion service of the item master.
 *
 * <p>Stock, BOMs and documents store quantities in the item's base unit. A feature that accepts a quantity in another
 * unit (a bag of resin, a box of bottles) converts it with {@link #toBase} (or {@link #toBaseExact} when rounding must
 * be refused) inside its own transaction and posts the base quantity. Conversion order: the base unit itself (factor 1),
 * the item's own conversion ({@code item_unit_conversions}), then the fixed ratio between two units of the same kind
 * (g to kg). The result is rounded HALF_UP to the base unit's {@code decimals}.
 */
@Service
public class UnitService {
    /** Rounding applied to converted quantities at the target unit's precision. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final Store s;

    UnitService(Store s) {
        this.s = s;
    }

    /**
     * A converted quantity: {@code quantity unit = baseQuantity baseUnit} with {@code factor} base units per unit.
     * {@code exact} is false when rounding to {@code baseDecimals} changed the value.
     */
    public record Converted(
            String itemId,
            BigDecimal quantity,
            String unit,
            BigDecimal factor,
            BigDecimal baseQuantity,
            String baseUnit,
            int baseDecimals,
            boolean exact) {
        public Map<String, Object> toMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("itemId", itemId);
            m.put("quantity", quantity);
            m.put("unit", unit);
            m.put("factor", factor);
            m.put("baseQuantity", baseQuantity);
            m.put("baseUnit", baseUnit);
            m.put("baseDecimals", baseDecimals);
            m.put("exact", exact);
            return m;
        }
    }

    // ------------------------------------------------------------------ conversion (public API for other features)

    /** Converts {@code quantity} of {@code unit} to the item's base unit, rounding at the base unit's precision. */
    @Transactional(readOnly = true)
    public Converted toBase(Actor a, String itemId, BigDecimal quantity, String unit) {
        require(quantity != null, "A quantity is required");
        var item = s.one("SELECT id,sku,uom FROM items WHERE tenant_id=? AND id=?", a.tenant(), itemId);
        var from = unit(a, unit);
        var base = unit(a, str(item, "uom"));
        precision(from, quantity, "Quantity");
        BigDecimal factor = factor(a, item, from, base);
        int decimals = ((Number) base.get("decimals")).intValue();
        BigDecimal raw = quantity.multiply(factor);
        BigDecimal rounded = raw.setScale(decimals, ROUNDING);
        return new Converted(
                itemId,
                quantity,
                str(from, "code"),
                factor,
                rounded,
                str(base, "code"),
                decimals,
                rounded.compareTo(raw) == 0);
    }

    /** {@link #toBase} that refuses (409) a quantity that cannot be expressed exactly in the base unit. */
    @Transactional(readOnly = true)
    public BigDecimal toBaseExact(Actor a, String itemId, BigDecimal quantity, String unit) {
        var c = toBase(a, itemId, quantity, unit);
        require(
                c.exact(),
                quantity.stripTrailingZeros().toPlainString() + " " + c.unit() + " is not a whole multiple of "
                        + c.baseUnit() + " at " + c.baseDecimals() + " decimal places");
        return c.baseQuantity();
    }

    /** Converts a base-unit quantity of the item into {@code unit}, rounded at that unit's precision. */
    @Transactional(readOnly = true)
    public BigDecimal fromBase(Actor a, String itemId, BigDecimal baseQuantity, String unit) {
        var item = s.one("SELECT id,sku,uom FROM items WHERE tenant_id=? AND id=?", a.tenant(), itemId);
        var to = unit(a, unit);
        BigDecimal factor = factor(a, item, to, unit(a, str(item, "uom")));
        return baseQuantity.divide(factor, ((Number) to.get("decimals")).intValue(), ROUNDING);
    }

    /** Base units in one {@code unit} of the item (409 when the item has no conversion for that unit). */
    @Transactional(readOnly = true)
    public BigDecimal factor(Actor a, String itemId, String unit) {
        var item = s.one("SELECT id,sku,uom FROM items WHERE tenant_id=? AND id=?", a.tenant(), itemId);
        return factor(a, item, unit(a, unit), unit(a, str(item, "uom")));
    }

    BigDecimal factor(Actor a, Map<String, Object> item, Map<String, Object> from, Map<String, Object> base) {
        if (str(from, "code").equals(str(base, "code"))) return BigDecimal.ONE;
        var own = s.list(
                "SELECT factor FROM item_unit_conversions WHERE tenant_id=? AND item_id=? AND unit_code=?",
                a.tenant(),
                item.get("id"),
                from.get("code"));
        if (!own.isEmpty()) return dec(own.getFirst().get("factor")).stripTrailingZeros();
        BigDecimal standard = standardFactor(from, base);
        require(
                standard != null,
                "No conversion from " + str(from, "code") + " to " + str(base, "code") + " is defined for SKU "
                        + str(item, "sku"));
        return standard;
    }

    /** Fixed ratio between two units of the same kind (1 g = 0.001 kg), or null. */
    static BigDecimal standardFactor(Map<String, Object> from, Map<String, Object> to) {
        if (from.get("ratio") == null || to.get("ratio") == null) return null;
        if (!str(from, "kind").equals(str(to, "kind"))) return null;
        return dec(from.get("ratio"))
                .divide(dec(to.get("ratio")), 18, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    // ------------------------------------------------------------------ lookups and guards

    /** The tenant's unit (404 with the code when it does not exist). */
    Map<String, Object> unit(Actor a, String code) {
        var rows = s.list("SELECT * FROM units WHERE tenant_id=? AND code=?", a.tenant(), code);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown unit of measure: " + code);
        return rows.getFirst();
    }

    /** A unit that may be chosen for new master data. */
    Map<String, Object> activeUnit(Actor a, String code) {
        var u = unit(a, code);
        require(Boolean.TRUE.equals(u.get("active")), "Unit " + code + " is inactive");
        return u;
    }

    /** 409 when {@code quantity} carries more decimal places than the unit allows. */
    static void precision(Map<String, Object> unit, BigDecimal quantity, String label) {
        if (quantity == null) return;
        int decimals = ((Number) unit.get("decimals")).intValue();
        require(
                Math.max(0, quantity.stripTrailingZeros().scale()) <= decimals,
                label + " in " + str(unit, "code") + " allows at most " + decimals + " decimal place"
                        + (decimals == 1 ? "" : "s"));
    }

    /** Units a quantity of the item can be entered in: base unit, item conversions, then same-kind standard units. */
    List<Map<String, Object>> itemUnits(Actor a, Map<String, Object> item) {
        var base = unit(a, str(item, "uom"));
        var result = new ArrayList<Map<String, Object>>();
        result.add(choice(base, BigDecimal.ONE, "BASE", true, true));
        var seen = new java.util.HashSet<String>();
        seen.add(str(base, "code"));
        for (var c : s.list("""
                SELECT c.*,u.name,u.kind,u.decimals,u.ratio,u.active,u.code
                FROM item_unit_conversions c
                JOIN units u ON u.tenant_id=c.tenant_id AND u.code=c.unit_code
                WHERE c.tenant_id=? AND c.item_id=?
                ORDER BY c.factor,u.code""", a.tenant(), item.get("id"))) {
            seen.add(str(c, "code"));
            result.add(choice(
                    c,
                    dec(c.get("factor")).stripTrailingZeros(),
                    "ITEM",
                    Boolean.TRUE.equals(c.get("purchase")),
                    Boolean.TRUE.equals(c.get("sales"))));
        }
        for (var u : s.list(
                "SELECT * FROM units WHERE tenant_id=? AND kind=? AND active=true ORDER BY ratio,code",
                a.tenant(),
                base.get("kind"))) {
            if (seen.contains(str(u, "code"))) continue;
            BigDecimal f = standardFactor(u, base);
            if (f != null) result.add(choice(u, f, "STANDARD", true, true));
        }
        return result;
    }

    private static Map<String, Object> choice(
            Map<String, Object> unit, BigDecimal factor, String source, boolean purchase, boolean sales) {
        var m = new LinkedHashMap<String, Object>();
        m.put("code", unit.get("code"));
        m.put("name", unit.get("name"));
        m.put("kind", unit.get("kind"));
        m.put("decimals", unit.get("decimals"));
        m.put("factor", factor);
        m.put("source", source);
        m.put("purchase", purchase);
        m.put("sales", sales);
        return m;
    }

    // ------------------------------------------------------------------ unit commands

    @Transactional
    public Map<String, Object> create(Actor a, ItemCommands.Unit c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "unit", c, () -> {
            require(
                    s.list("SELECT 1 FROM units WHERE tenant_id=? AND lower(code)=lower(?)", a.tenant(), c.code())
                            .isEmpty(),
                    "Unit code " + c.code() + " already exists");
            require(
                    !c.kind().equals("PACKAGE") || c.ratio() == null,
                    "Package units (box, bag...) have no fixed size; define their size per item as a conversion");
            s.db().update(
                            "INSERT INTO units(tenant_id,code,name,kind,decimals,ratio) VALUES(?,?,?,?,?,?)",
                            a.tenant(),
                            c.code(),
                            c.name().trim(),
                            c.kind(),
                            c.decimals(),
                            c.ratio());
            s.audit(
                    a,
                    "UNIT_CREATED",
                    c.code(),
                    c.name().trim() + " · " + c.kind().toLowerCase(Locale.ROOT) + " · " + c.decimals() + " decimals");
            return Map.of("code", c.code());
        });
    }

    @Transactional
    public Map<String, Object> update(Actor a, String code, ItemCommands.EditUnit c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "edit-unit:" + code, c, () -> {
            var u = unit(a, code);
            int decimals = ((Number) u.get("decimals")).intValue();
            if (decimals != c.decimals())
                require(!used(a, code), "The precision of " + code + " is fixed once items use this unit");
            s.db().update(
                            "UPDATE units SET name=?,decimals=?,active=? WHERE tenant_id=? AND code=?",
                            c.name().trim(),
                            c.decimals(),
                            c.active(),
                            a.tenant(),
                            code);
            var changes = new ArrayList<String>();
            if (!str(u, "name").equals(c.name().trim()))
                changes.add("name " + u.get("name") + " → " + c.name().trim());
            if (decimals != c.decimals()) changes.add("decimals " + decimals + " → " + c.decimals());
            if (!c.active().equals(u.get("active"))) changes.add(c.active() ? "reactivated" : "deactivated");
            s.audit(a, "UNIT_UPDATED", code, changes.isEmpty() ? "Saved without changes" : String.join("; ", changes));
            return Map.of("code", code, "active", c.active());
        });
    }

    boolean used(Actor a, String code) {
        return !s.list("""
                SELECT 1 FROM items WHERE tenant_id=? AND uom=?
                UNION ALL SELECT 1 FROM item_unit_conversions WHERE tenant_id=? AND unit_code=?
                UNION ALL SELECT 1 FROM item_barcodes WHERE tenant_id=? AND unit_code=?
                UNION ALL SELECT 1 FROM item_templates WHERE tenant_id=? AND uom=?
                LIMIT 1""", a.tenant(), code, a.tenant(), code, a.tenant(), code, a.tenant(), code)
                .isEmpty();
    }
}
