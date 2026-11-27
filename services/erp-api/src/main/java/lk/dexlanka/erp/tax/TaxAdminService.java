package lk.dexlanka.erp.tax;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tax administration commands (V25): tax codes, components and rate versions, tenant settings, country templates and
 * the tax profile of partners and items. Every command follows the write-command pattern (role → tenant lock and
 * idempotency → rules → writes → audit). Administrators only; the general-ledger integration opens them to FINANCE.
 */
@Service
public class TaxAdminService {
    /** Roles besides ADMIN that maintain tax data (none yet; FINANCE arrives with the general ledger). */
    static final String[] MANAGERS = {};

    private final Store s;
    private final Map<String, TaxRegime> regimes = new LinkedHashMap<>();

    TaxAdminService(Store s, List<TaxRegime> regimes) {
        this.s = s;
        for (var r : regimes) this.regimes.put(r.code(), r);
    }

    /** The country templates by regime code. */
    public Map<String, TaxRegime> regimes() {
        return regimes;
    }

    // ------------------------------------------------------------------ codes

    @Transactional
    public Map<String, Object> createCode(Actor a, TaxCommands.Code c, String key) {
        a.allow(MANAGERS);
        boolean standard = c.kind().equals("STANDARD");
        var components = c.components() == null ? List.<TaxCommands.Component>of() : c.components();
        require(!standard || !components.isEmpty(), "A standard tax code needs at least one component with a rate");
        require(standard || components.isEmpty(), "Zero-rated and exempt codes have no components or rates");
        for (int i = 0; i < components.size(); i++) {
            require(i > 0 || !components.get(i).compound(), "The first component cannot be compound");
            date(components.get(i).effectiveFrom());
        }
        return s.once(a, key, "tax-code-create:" + c.code(), c, () -> {
            require(
                    s.list("SELECT 1 FROM tax_codes WHERE tenant_id=? AND code=?", a.tenant(), c.code())
                            .isEmpty(),
                    "Tax code " + c.code() + " already exists");
            String id = Store.id();
            s.db().update(
                            "INSERT INTO tax_codes(id,tenant_id,code,name,applies_to,kind,output_account_code,input_account_code,created_by) VALUES(?,?,?,?,?,?,?,?,?)",
                            id,
                            a.tenant(),
                            c.code(),
                            c.name().strip(),
                            c.appliesTo(),
                            c.kind(),
                            account(c.outputAccountCode(), "OUTPUT_TAX"),
                            account(c.inputAccountCode(), "INPUT_TAX"),
                            a.id());
            for (int i = 0; i < components.size(); i++) {
                var k = components.get(i);
                String component = insertComponent(a, id, i + 1, k.name().strip(), k.compound());
                insertRate(a, component, date(k.effectiveFrom()), k.rate(), null);
            }
            s.audit(a, "TAX_CODE_CREATED", c.code(), c.name().strip() + " · " + describe(c.kind(), components));
            return Map.of("id", id, "code", c.code());
        });
    }

    @Transactional
    public Map<String, Object> updateCode(Actor a, String id, TaxCommands.CodeUpdate c, String key) {
        a.allow(MANAGERS);
        return s.once(a, key, "tax-code-update:" + id, c, () -> {
            var code = s.one("SELECT * FROM tax_codes WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
            if (!c.active() || !c.appliesTo().equals(str(code, "applies_to"))) {
                // a default must stay usable: deactivating or narrowing a code used as a default is refused
                var settings = s.list("SELECT * FROM tax_settings WHERE tenant_id=?", a.tenant());
                if (!settings.isEmpty()) {
                    var st = settings.getFirst();
                    boolean salesOk = c.active() && !c.appliesTo().equals("PURCHASES");
                    boolean purchaseOk = c.active() && !c.appliesTo().equals("SALES");
                    require(
                            !id.equals(st.get("default_sales_code_id")) || salesOk,
                            "This code is the default sales tax code; choose another default first");
                    require(
                            !id.equals(st.get("default_purchase_code_id")) || purchaseOk,
                            "This code is the default purchase tax code; choose another default first");
                    require(
                            !id.equals(st.get("exempt_code_id")) || c.active(),
                            "This code is the exemption code; choose another one first");
                }
            }
            var changed = new ArrayList<String>();
            if (!c.name().strip().equals(str(code, "name"))) changed.add("name");
            if (!c.appliesTo().equals(str(code, "applies_to"))) changed.add("applies to " + label(c.appliesTo()));
            if (!c.outputAccountCode().equals(str(code, "output_account_code")))
                changed.add("output account " + c.outputAccountCode());
            if (!c.inputAccountCode().equals(str(code, "input_account_code")))
                changed.add("input account " + c.inputAccountCode());
            boolean wasActive = Boolean.TRUE.equals(code.get("active"));
            if (wasActive != c.active()) changed.add(c.active() ? "reactivated" : "deactivated");
            s.db().update(
                            "UPDATE tax_codes SET name=?,applies_to=?,output_account_code=?,input_account_code=?,active=?,edited_at=now(),updated_at=now() WHERE tenant_id=? AND id=?",
                            c.name().strip(),
                            c.appliesTo(),
                            c.outputAccountCode(),
                            c.inputAccountCode(),
                            c.active(),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "TAX_CODE_UPDATED",
                    str(code, "code"),
                    changed.isEmpty() ? "No changes" : "Changed " + String.join(", ", changed));
            return Map.of("id", id, "code", code.get("code"));
        });
    }

    @Transactional
    public Map<String, Object> addComponent(Actor a, String codeId, TaxCommands.Component c, String key) {
        a.allow(MANAGERS);
        LocalDate from = date(c.effectiveFrom());
        return s.once(a, key, "tax-component-add:" + codeId, c, () -> {
            var code = s.one("SELECT * FROM tax_codes WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), codeId);
            require(str(code, "kind").equals("STANDARD"), "Only standard tax codes have components");
            int seq = s.db().queryForObject(
                            "SELECT COALESCE(MAX(seq),0)+1 FROM tax_components WHERE tenant_id=? AND tax_code_id=?",
                            Integer.class,
                            a.tenant(),
                            codeId);
            require(seq <= 9, "A tax code has at most nine components");
            require(seq > 1 || !c.compound(), "The first component cannot be compound");
            String id = insertComponent(a, codeId, seq, c.name().strip(), c.compound());
            insertRate(a, id, from, c.rate(), null);
            edited(a, codeId);
            s.audit(
                    a,
                    "TAX_COMPONENT_ADDED",
                    str(code, "code"),
                    c.name().strip() + (c.compound() ? " (compound)" : "") + " · " + pct(c.rate()) + " from " + from);
            return Map.of("id", id, "seq", seq);
        });
    }

    @Transactional
    public Map<String, Object> updateComponent(Actor a, String id, TaxCommands.ComponentUpdate c, String key) {
        a.allow(MANAGERS);
        return s.once(a, key, "tax-component-update:" + id, c, () -> {
            var component = s.one(
                    "SELECT c.*,t.code FROM tax_components c JOIN tax_codes t ON t.tenant_id=c.tenant_id AND t.id=c.tax_code_id WHERE c.tenant_id=? AND c.id=? FOR UPDATE OF c",
                    a.tenant(),
                    id);
            require(
                    ((Number) component.get("seq")).intValue() > 1 || !c.compound(),
                    "The first component cannot be compound");
            if (c.compound() != Boolean.TRUE.equals(component.get("compound")))
                require(
                        !used("component_id", a.tenant(), id),
                        "This component is used by recorded tax lines; its"
                                + " calculation cannot change. Add a new code instead.");
            s.db().update(
                            "UPDATE tax_components SET name=?,compound=? WHERE tenant_id=? AND id=?",
                            c.name().strip(),
                            c.compound(),
                            a.tenant(),
                            id);
            edited(a, str(component, "tax_code_id"));
            s.audit(
                    a,
                    "TAX_COMPONENT_UPDATED",
                    str(component, "code"),
                    c.name().strip() + (c.compound() ? " · compound" : " · on the base only"));
            return Map.of("id", id);
        });
    }

    // ------------------------------------------------------------------ rate versions

    @Transactional
    public Map<String, Object> addRate(Actor a, String componentId, TaxCommands.Rate c, String key) {
        a.allow(MANAGERS);
        LocalDate from = date(c.effectiveFrom());
        return s.once(a, key, "tax-rate-add:" + componentId, c, () -> {
            var component = s.one(
                    "SELECT c.*,t.code FROM tax_components c JOIN tax_codes t ON t.tenant_id=c.tenant_id AND t.id=c.tax_code_id WHERE c.tenant_id=? AND c.id=?",
                    a.tenant(),
                    componentId);
            require(
                    s.list(
                                    "SELECT 1 FROM tax_rate_versions WHERE tenant_id=? AND component_id=? AND effective_from=?",
                                    a.tenant(),
                                    componentId,
                                    from)
                            .isEmpty(),
                    "A rate already starts on " + from + "; remove it first or choose another date");
            LocalDate taxed = lastTaxed(a.tenant(), componentId);
            require(
                    taxed == null || from.isAfter(taxed),
                    "Tax is already recorded on this component up to " + taxed
                            + "; a new rate must start after that date");
            String id = insertRate(a, componentId, from, c.rate(), blank(c.note()));
            edited(a, str(component, "tax_code_id"));
            s.audit(
                    a,
                    "TAX_RATE_ADDED",
                    str(component, "code"),
                    component.get("name") + " · " + pct(c.rate()) + " from " + from);
            return Map.of("id", id);
        });
    }

    @Transactional
    public Map<String, Object> removeRate(Actor a, String id, String key) {
        a.allow(MANAGERS);
        return s.once(a, key, "tax-rate-remove:" + id, Map.of("id", id), () -> {
            var version = s.one("""
                    SELECT v.*,c.name AS component,c.tax_code_id,t.code FROM tax_rate_versions v
                    JOIN tax_components c ON c.tenant_id=v.tenant_id AND c.id=v.component_id
                    JOIN tax_codes t ON t.tenant_id=c.tenant_id AND t.id=c.tax_code_id
                    WHERE v.tenant_id=? AND v.id=?""", a.tenant(), id);
            require(
                    !used("rate_version_id", a.tenant(), id),
                    "This rate version is used by recorded tax lines and cannot be changed");
            require(
                    s.db().queryForObject(
                                            "SELECT COUNT(*) FROM tax_rate_versions WHERE tenant_id=? AND component_id=?",
                                            Long.class,
                                            a.tenant(),
                                            version.get("component_id"))
                            > 1,
                    "A component keeps at least one rate version");
            LocalDate from = TaxService.toDate(version.get("effective_from"));
            LocalDate taxed = lastTaxed(a.tenant(), str(version, "component_id"));
            // removing a version would stretch the previous one over dates that already carry recorded tax
            require(
                    taxed == null || from.isAfter(taxed),
                    "Tax is already recorded on this component up to " + taxed
                            + "; this version can no longer be removed");
            s.db().update("DELETE FROM tax_rate_versions WHERE tenant_id=? AND id=?", a.tenant(), id);
            edited(a, str(version, "tax_code_id"));
            s.audit(
                    a,
                    "TAX_RATE_REMOVED",
                    str(version, "code"),
                    version.get("component") + " · " + pct(dec(version.get("rate"))) + " from " + from);
            return Map.of("id", id, "removed", true);
        });
    }

    // ------------------------------------------------------------------ settings

    @Transactional
    public Map<String, Object> updateSettings(Actor a, TaxCommands.Settings c, String key) {
        a.allow(MANAGERS);
        return s.once(a, key, "tax-settings", c, () -> {
            String sales = blank(c.defaultSalesCodeId());
            String purchase = blank(c.defaultPurchaseCodeId());
            String exempt = blank(c.exemptCodeId());
            checkCode(a, sales, "SALES", "default sales");
            checkCode(a, purchase, "PURCHASES", "default purchase");
            if (exempt != null) {
                var code = s.one("SELECT * FROM tax_codes WHERE tenant_id=? AND id=?", a.tenant(), exempt);
                require(str(code, "kind").equals("EXEMPT"), "The exemption code must be an exempt code");
                require(Boolean.TRUE.equals(code.get("active")), "The exemption code must be active");
            }
            upsertSettings(a, c.pricesIncludeTax(), c.rounding(), null, sales, purchase, exempt, false);
            s.audit(
                    a,
                    "TAX_SETTINGS_UPDATED",
                    "TAX_SETTINGS",
                    (c.pricesIncludeTax() ? "Prices include tax" : "Prices exclude tax") + " · rounding "
                            + (c.rounding().equals("PER_LINE") ? "per line" : "per document") + " · sales default "
                            + codeOf(a, sales) + " · purchase default " + codeOf(a, purchase) + " · exemption "
                            + codeOf(a, exempt));
            return Map.of("saved", true);
        });
    }

    // ------------------------------------------------------------------ templates

    /**
     * Applies a country template: creates missing codes with their rate history, adds missing rate versions to template
     * codes nobody has edited, and keeps every other code untouched. Tenant settings take the template's rounding,
     * price convention and defaults only where none were chosen yet. Re-applying changes nothing.
     */
    @Transactional
    public Map<String, Object> applyTemplate(Actor a, String regimeCode, String key) {
        a.allow(MANAGERS);
        var regime = regimes.get(regimeCode);
        if (regime == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown tax template");
        return s.once(a, key, "tax-template:" + regimeCode, Map.of("regime", regimeCode), () -> {
            var created = new ArrayList<String>();
            var updated = new ArrayList<String>();
            var unchanged = new ArrayList<String>();
            var kept = new ArrayList<String>();
            Map<String, String> ids = new LinkedHashMap<>();
            for (var tc : regime.codes()) {
                var existing = s.list(
                        "SELECT * FROM tax_codes WHERE tenant_id=? AND code=? FOR UPDATE", a.tenant(), tc.code());
                if (existing.isEmpty()) {
                    String id = Store.id();
                    s.db().update(
                                    "INSERT INTO tax_codes(id,tenant_id,code,name,applies_to,kind,template_regime,template_code,created_by) VALUES(?,?,?,?,?,?,?,?,?)",
                                    id,
                                    a.tenant(),
                                    tc.code(),
                                    tc.name(),
                                    tc.appliesTo(),
                                    tc.kind(),
                                    regime.code(),
                                    tc.code(),
                                    a.id());
                    int seq = 1;
                    for (var k : tc.components()) {
                        String component = insertComponent(a, id, seq++, k.name(), k.compound());
                        for (var r : k.rates()) insertRate(a, component, r.effectiveFrom(), r.rate(), r.note());
                    }
                    created.add(tc.code());
                    ids.put(tc.code(), id);
                    continue;
                }
                var code = existing.getFirst();
                ids.put(tc.code(), str(code, "id"));
                boolean ours = regime.code().equals(code.get("template_regime"))
                        && tc.code().equals(code.get("template_code"))
                        && code.get("edited_at") == null
                        && tc.kind().equals(code.get("kind"));
                if (!ours) {
                    kept.add(tc.code());
                    continue;
                }
                int added = 0;
                int seq = 1;
                for (var k : tc.components()) {
                    var components = s.list(
                            "SELECT * FROM tax_components WHERE tenant_id=? AND tax_code_id=? AND seq=?",
                            a.tenant(),
                            code.get("id"),
                            seq);
                    String component = components.isEmpty()
                            ? insertComponent(a, str(code, "id"), seq, k.name(), k.compound())
                            : str(components.getFirst(), "id");
                    if (components.isEmpty()) added++;
                    LocalDate taxed = lastTaxed(a.tenant(), component);
                    for (var r : k.rates()) {
                        boolean present = !s.list(
                                        "SELECT 1 FROM tax_rate_versions WHERE tenant_id=? AND component_id=? AND effective_from=?",
                                        a.tenant(),
                                        component,
                                        r.effectiveFrom())
                                .isEmpty();
                        if (present || (taxed != null && !r.effectiveFrom().isAfter(taxed))) continue;
                        insertRate(a, component, r.effectiveFrom(), r.rate(), r.note());
                        added++;
                    }
                    seq++;
                }
                (added > 0 ? updated : unchanged).add(tc.code());
            }
            var settings = s.list("SELECT * FROM tax_settings WHERE tenant_id=? FOR UPDATE", a.tenant());
            boolean fresh = settings.isEmpty() || settings.getFirst().get("regime") == null;
            var st = settings.isEmpty() ? Map.<String, Object>of() : settings.getFirst();
            String sales = (String) st.get("default_sales_code_id");
            String purchase = (String) st.get("default_purchase_code_id");
            String exempt = (String) st.get("exempt_code_id");
            for (var tc : regime.codes()) {
                String id = ids.get(tc.code());
                if (sales == null && tc.salesDefault() && usableFor(a, id, "SALES")) sales = id;
                if (purchase == null && tc.purchaseDefault() && usableFor(a, id, "PURCHASES")) purchase = id;
                if (exempt == null && tc.exemptionCode() && isActiveExempt(a, id)) exempt = id;
            }
            boolean inclusive = fresh ? regime.pricesIncludeTax() : Boolean.TRUE.equals(st.get("prices_include_tax"));
            String rounding = fresh ? regime.rounding() : str(st, "rounding");
            upsertSettings(a, inclusive, rounding, regime.code(), sales, purchase, exempt, true);
            s.audit(
                    a,
                    "TAX_TEMPLATE_APPLIED",
                    regime.code(),
                    regime.name() + " · created " + list(created) + " · updated " + list(updated) + " · kept "
                            + list(kept));
            var out = new LinkedHashMap<String, Object>();
            out.put("regime", regime.code());
            out.put("created", created);
            out.put("updated", updated);
            out.put("unchanged", unchanged);
            out.put("kept", kept);
            return out;
        });
    }

    // ------------------------------------------------------------------ partners and items

    @Transactional
    public Map<String, Object> updatePartnerTax(Actor a, String partnerId, TaxCommands.PartnerTax c, String key) {
        a.allow(MANAGERS);
        String reference = blank(c.exemptionReference());
        String until = blank(c.exemptionValidUntil());
        require(
                (reference == null) == (until == null),
                "An exemption needs both a certificate reference and a valid-until date");
        LocalDate validUntil = until == null ? null : date(until);
        return s.once(a, key, "partner-tax:" + partnerId, c, () -> {
            var p = s.one("SELECT * FROM partners WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), partnerId);
            String sales = blank(c.salesTaxCodeId());
            String purchase = blank(c.purchaseTaxCodeId());
            checkCode(a, sales, "SALES", "sales");
            checkCode(a, purchase, "PURCHASES", "purchase");
            var changed = new ArrayList<String>();
            if (c.taxRegistered() != Boolean.TRUE.equals(p.get("tax_registered")))
                changed.add(c.taxRegistered() ? "tax-registered" : "not tax-registered");
            if (!Objects.equals(reference, p.get("tax_exemption_reference"))
                    || !Objects.equals(
                            validUntil,
                            p.get("tax_exemption_valid_until") == null
                                    ? null
                                    : TaxService.toDate(p.get("tax_exemption_valid_until"))))
                changed.add(
                        reference == null ? "exemption removed" : "exemption " + reference + " until " + validUntil);
            if (!Objects.equals(sales, p.get("sales_tax_code_id"))) changed.add("sales code " + codeOf(a, sales));
            if (!Objects.equals(purchase, p.get("purchase_tax_code_id")))
                changed.add("purchase code " + codeOf(a, purchase));
            s.db().update(
                            "UPDATE partners SET tax_registered=?,tax_exemption_reference=?,tax_exemption_valid_until=?,sales_tax_code_id=?,purchase_tax_code_id=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            c.taxRegistered(),
                            reference,
                            validUntil,
                            sales,
                            purchase,
                            a.tenant(),
                            partnerId);
            s.audit(
                    a,
                    "PARTNER_TAX_UPDATED",
                    str(p, "name"),
                    "Tax profile" + (changed.isEmpty() ? " unchanged" : " · " + String.join(", ", changed)));
            return Map.of("id", partnerId);
        });
    }

    @Transactional
    public Map<String, Object> updateItemTax(Actor a, String itemId, TaxCommands.ItemTax c, String key) {
        a.allow(MANAGERS);
        return s.once(a, key, "item-tax:" + itemId, c, () -> {
            var item = s.one("SELECT * FROM items WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), itemId);
            String sales = blank(c.salesTaxCodeId());
            String purchase = blank(c.purchaseTaxCodeId());
            checkCode(a, sales, "SALES", "sales");
            checkCode(a, purchase, "PURCHASES", "purchase");
            s.db().update(
                            "UPDATE items SET sales_tax_code_id=?,purchase_tax_code_id=? WHERE tenant_id=? AND id=?",
                            sales,
                            purchase,
                            a.tenant(),
                            itemId);
            s.audit(
                    a,
                    "ITEM_TAX_UPDATED",
                    str(item, "sku"),
                    "Sales tax " + codeOf(a, sales) + " · purchase tax " + codeOf(a, purchase));
            return Map.of("id", itemId);
        });
    }

    // ------------------------------------------------------------------ helpers

    private String insertComponent(Actor a, String codeId, int seq, String name, boolean compound) {
        String id = Store.id();
        s.db().update(
                        "INSERT INTO tax_components(id,tenant_id,tax_code_id,seq,name,compound) VALUES(?,?,?,?,?,?)",
                        id,
                        a.tenant(),
                        codeId,
                        seq,
                        name,
                        compound);
        return id;
    }

    private String insertRate(Actor a, String componentId, LocalDate from, BigDecimal rate, String note) {
        String id = Store.id();
        s.db().update(
                        "INSERT INTO tax_rate_versions(id,tenant_id,component_id,effective_from,rate,note,created_by) VALUES(?,?,?,?,?,?,?)",
                        id,
                        a.tenant(),
                        componentId,
                        from,
                        rate,
                        note,
                        a.id());
        return id;
    }

    private void upsertSettings(
            Actor a,
            boolean inclusive,
            String rounding,
            String regime,
            String sales,
            String purchase,
            String exempt,
            boolean setRegime) {
        s.db().update("""
                        INSERT INTO tax_settings(tenant_id,prices_include_tax,rounding,regime,default_sales_code_id,
                                                 default_purchase_code_id,exempt_code_id,updated_by,updated_at)
                        VALUES(?,?,?,?,?,?,?,?,now())
                        ON CONFLICT (tenant_id) DO UPDATE SET prices_include_tax=EXCLUDED.prices_include_tax,
                            rounding=EXCLUDED.rounding,
                            regime=CASE WHEN ? THEN EXCLUDED.regime ELSE tax_settings.regime END,
                            default_sales_code_id=EXCLUDED.default_sales_code_id,
                            default_purchase_code_id=EXCLUDED.default_purchase_code_id,
                            exempt_code_id=EXCLUDED.exempt_code_id,
                            updated_by=EXCLUDED.updated_by,updated_at=now()""", a.tenant(), inclusive, rounding, regime, sales, purchase, exempt, a.id(), setRegime);
    }

    private void edited(Actor a, String codeId) {
        s.db().update(
                        "UPDATE tax_codes SET edited_at=now(),updated_at=now() WHERE tenant_id=? AND id=?",
                        a.tenant(),
                        codeId);
    }

    /** 404 for a code of another tenant; 409 unless active and applicable to {@code side}. */
    private void checkCode(Actor a, String id, String side, String what) {
        if (id == null) return;
        var code = s.one("SELECT * FROM tax_codes WHERE tenant_id=? AND id=?", a.tenant(), id);
        require(Boolean.TRUE.equals(code.get("active")), "The " + what + " tax code must be active");
        String to = str(code, "applies_to");
        require(
                to.equals("BOTH") || to.equals(side),
                "Tax code " + code.get("code") + " does not apply to "
                        + (side.equals("SALES") ? "sales" : "purchases"));
    }

    private boolean usableFor(Actor a, String id, String side) {
        if (id == null) return false;
        var rows = s.list("SELECT * FROM tax_codes WHERE tenant_id=? AND id=?", a.tenant(), id);
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("active"))) return false;
        String to = str(rows.getFirst(), "applies_to");
        return to.equals("BOTH") || to.equals(side);
    }

    private boolean isActiveExempt(Actor a, String id) {
        if (id == null) return false;
        return !s.list(
                        "SELECT 1 FROM tax_codes WHERE tenant_id=? AND id=? AND kind='EXEMPT' AND active",
                        a.tenant(),
                        id)
                .isEmpty();
    }

    private boolean used(String column, String tenant, String id) {
        return !s.list("SELECT 1 FROM tax_lines WHERE tenant_id=? AND " + column + "=? LIMIT 1", tenant, id)
                .isEmpty();
    }

    private LocalDate lastTaxed(String tenant, String componentId) {
        var rows = s.list(
                "SELECT max(document_date) AS d FROM tax_lines WHERE tenant_id=? AND component_id=?",
                tenant,
                componentId);
        Object d = rows.getFirst().get("d");
        return d == null ? null : TaxService.toDate(d);
    }

    private String codeOf(Actor a, String id) {
        if (id == null) return "none";
        return str(s.one("SELECT code FROM tax_codes WHERE tenant_id=? AND id=?", a.tenant(), id), "code");
    }

    static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a valid date (yyyy-MM-dd)");
        }
    }

    private static String account(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String pct(BigDecimal rate) {
        return rate.stripTrailingZeros().toPlainString() + " %";
    }

    private static String label(String appliesTo) {
        return switch (appliesTo) {
            case "SALES" -> "sales";
            case "PURCHASES" -> "purchases";
            default -> "sales and purchases";
        };
    }

    private static String describe(String kind, List<TaxCommands.Component> components) {
        if (kind.equals("ZERO_RATED")) return "zero-rated";
        if (kind.equals("EXEMPT")) return "exempt";
        var parts = new ArrayList<String>();
        for (var c : components)
            parts.add(c.name().strip() + " " + pct(c.rate()) + (c.compound() ? " compound" : "") + " from "
                    + c.effectiveFrom());
        return String.join(", ", parts);
    }

    private static String list(List<String> codes) {
        return codes.isEmpty() ? "none" : String.join(", ", codes);
    }
}
