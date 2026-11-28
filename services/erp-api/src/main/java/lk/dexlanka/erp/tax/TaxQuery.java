package lk.dexlanka.erp.tax;

import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tax read model (V25): codes with components and rate history, settings and templates (every role: document screens
 * show tax), the partner/item tax profile, order estimates, and, for administrators, the tax register and VAT return
 * summary with CSV exports.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TaxQuery {
    /** Rows written by one register export at most. */
    static final int EXPORT_CAP = 50_000;

    private final Store s;
    private final TaxService tax;
    private final TaxAdminService admin;

    TaxQuery(Store s, TaxService tax, TaxAdminService admin) {
        this.s = s;
        this.tax = tax;
        this.admin = admin;
    }

    // ------------------------------------------------------------------ codes, settings, templates

    /** Every code of the tenant with its components, their rate versions (with derived {@code effective_to}) and use. */
    public Map<String, Object> codes(Actor a) {
        String t = a.tenant();
        var codes = s.list("""
                SELECT c.*,
                       EXISTS (SELECT 1 FROM tax_lines l WHERE l.tenant_id=c.tenant_id AND l.tax_code_id=c.id) AS used,
                       (SELECT COUNT(*) FROM items i WHERE i.tenant_id=c.tenant_id
                          AND (i.sales_tax_code_id=c.id OR i.purchase_tax_code_id=c.id)) AS item_count,
                       (SELECT COUNT(*) FROM partners p WHERE p.tenant_id=c.tenant_id
                          AND (p.sales_tax_code_id=c.id OR p.purchase_tax_code_id=c.id)) AS partner_count
                FROM tax_codes c WHERE c.tenant_id=? ORDER BY c.active DESC,c.code""", t);
        var components = s.list("""
                SELECT c.*,EXISTS (SELECT 1 FROM tax_lines l WHERE l.tenant_id=c.tenant_id AND l.component_id=c.id) AS used
                FROM tax_components c WHERE c.tenant_id=? ORDER BY c.tax_code_id,c.seq""", t);
        LocalDate today = TaxService.today();
        var versions = s.list("""
                SELECT v.id,v.component_id,v.effective_from,v.effective_to,v.rate,v.note,v.created_at,
                       EXISTS (SELECT 1 FROM tax_lines l WHERE l.tenant_id=v.tenant_id AND l.rate_version_id=v.id) AS used,
                       (v.effective_from<=? AND (v.effective_to IS NULL OR v.effective_to>=?)) AS current
                FROM tax_rate_periods v WHERE v.tenant_id=? ORDER BY v.component_id,v.effective_from DESC""", today, today, t);
        var byComponent = new LinkedHashMap<Object, List<Map<String, Object>>>();
        for (var v : versions)
            byComponent
                    .computeIfAbsent(v.get("component_id"), x -> new ArrayList<>())
                    .add(v);
        var byCode = new LinkedHashMap<Object, List<Map<String, Object>>>();
        for (var c : components) {
            var row = new LinkedHashMap<>(c);
            var rates = byComponent.getOrDefault(c.get("id"), List.of());
            row.put("rates", rates);
            row.put(
                    "current_rate",
                    rates.stream()
                            .filter(v -> Boolean.TRUE.equals(v.get("current")))
                            .map(v -> v.get("rate"))
                            .findFirst()
                            .orElse(null));
            byCode.computeIfAbsent(c.get("tax_code_id"), x -> new ArrayList<>()).add(row);
        }
        var items = new ArrayList<Map<String, Object>>();
        for (var c : codes) {
            var row = new LinkedHashMap<>(c);
            var list = byCode.getOrDefault(c.get("id"), List.of());
            row.put("components", list);
            row.put("current_rate", currentRate(list));
            items.add(row);
        }
        return Map.of("items", items, "today", today.toString());
    }

    /**
     * The effective total rate today: the components combined (a compound component counts on the earlier taxes), or
     * null when a component has no rate today.
     */
    private static BigDecimal currentRate(List<Map<String, Object>> components) {
        if (components.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (var c : components) {
            if (c.get("current_rate") == null) return null;
            BigDecimal r = ((BigDecimal) c.get("current_rate")).divide(BigDecimal.valueOf(100));
            sum = sum.add(Boolean.TRUE.equals(c.get("compound")) ? r.multiply(BigDecimal.ONE.add(sum)) : r);
        }
        return sum.multiply(BigDecimal.valueOf(100)).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    /** Tenant settings, the currency and the available country templates (with the disclaimer). */
    public Map<String, Object> settings(Actor a) {
        var settings = tax.settings(a.tenant());
        var tenant = s.one("SELECT currency FROM tenants WHERE id=?", a.tenant());
        var templates = new ArrayList<Map<String, Object>>();
        for (var r : admin.regimes().values()) {
            var codes = new ArrayList<Map<String, Object>>();
            for (var c : r.codes()) {
                var rates = new ArrayList<Map<String, Object>>();
                for (var k : c.components())
                    for (var v : k.rates())
                        rates.add(Map.of(
                                "component",
                                k.name(),
                                "effective_from",
                                v.effectiveFrom().toString(),
                                "rate",
                                v.rate()));
                var row = new LinkedHashMap<String, Object>();
                row.put("code", c.code());
                row.put("name", c.name());
                row.put("applies_to", c.appliesTo());
                row.put("kind", c.kind());
                row.put("rates", rates);
                codes.add(row);
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("code", r.code());
            row.put("country", r.country());
            row.put("name", r.name());
            row.put("rounding", r.rounding());
            row.put("prices_include_tax", r.pricesIncludeTax());
            row.put("notes", r.notes());
            row.put("disclaimer", TaxRegime.DISCLAIMER);
            row.put("codes", codes);
            row.put("applied", r.code().equals(settings.get("regime")));
            templates.add(row);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("settings", settings);
        out.put("currency", tenant.get("currency"));
        out.put("scale", TaxService.scale(str(tenant, "currency")));
        out.put("templates", templates);
        out.put("today", TaxService.today().toString());
        return out;
    }

    /** A partner's tax profile and the codes it would resolve to (every role; editing is administrators only). */
    public Map<String, Object> partner(Actor a, String id) {
        var p = s.one("""
                SELECT p.id,p.name,p.type,p.tax_id,p.tax_registered,p.tax_exemption_reference,p.tax_exemption_valid_until,
                       p.sales_tax_code_id,p.purchase_tax_code_id,sc.code AS sales_tax_code,pc.code AS purchase_tax_code,
                       (p.tax_exemption_valid_until IS NOT NULL AND p.tax_exemption_valid_until>=?) AS exemption_active
                FROM partners p
                LEFT JOIN tax_codes sc ON sc.tenant_id=p.tenant_id AND sc.id=p.sales_tax_code_id
                LEFT JOIN tax_codes pc ON pc.tenant_id=p.tenant_id AND pc.id=p.purchase_tax_code_id
                WHERE p.tenant_id=? AND p.id=?""", TaxService.today(), a.tenant(), id);
        return Map.of("partner", p);
    }

    public Map<String, Object> item(Actor a, String id) {
        var i = s.one("""
                SELECT i.id,i.sku,i.name,i.sales_tax_code_id,i.purchase_tax_code_id,
                       sc.code AS sales_tax_code,pc.code AS purchase_tax_code
                FROM items i
                LEFT JOIN tax_codes sc ON sc.tenant_id=i.tenant_id AND sc.id=i.sales_tax_code_id
                LEFT JOIN tax_codes pc ON pc.tenant_id=i.tenant_id AND pc.id=i.purchase_tax_code_id
                WHERE i.tenant_id=? AND i.id=?""", a.tenant(), id);
        return Map.of("item", i);
    }

    // ------------------------------------------------------------------ estimates

    /**
     * Estimated tax of a sales order's lines (OUTPUT) as of {@code date} (default today). Read-only: nothing is posted
     * and the order's totals are unchanged. {@code available} is false with a {@code message} when the tenant's codes
     * cannot calculate (for example no rate effective on the date).
     */
    public Map<String, Object> salesEstimate(Actor a, String id, String date) {
        var order = s.one(
                "SELECT id,number,customer_id,total,status FROM sales_orders WHERE tenant_id=? AND id=?",
                a.tenant(),
                id);
        var lines = s.list(
                "SELECT line_no,product_id,quantity,unit_price,discount_pct FROM sales_order_lines WHERE tenant_id=? AND sales_order_id=? ORDER BY line_no",
                a.tenant(),
                id);
        var inputs = new ArrayList<TaxService.LineInput>();
        for (var l : lines)
            inputs.add(new TaxService.LineInput(
                    ((Number) l.get("line_no")).intValue(),
                    str(l, "product_id"),
                    (BigDecimal) l.get("quantity"),
                    (BigDecimal) l.get("unit_price"),
                    (BigDecimal) l.get("discount_pct"),
                    null));
        return estimate(a, "OUTPUT", (String) order.get("customer_id"), date, inputs, order);
    }

    /** Estimated tax of a purchase order's lines (INPUT); see {@link #salesEstimate}. */
    public Map<String, Object> purchaseEstimate(Actor a, String id, String date) {
        var order = s.one(
                "SELECT id,number,supplier_id,total,status FROM purchase_orders WHERE tenant_id=? AND id=?",
                a.tenant(),
                id);
        var lines = s.list(
                "SELECT line_no,item_id,quantity,unit_cost FROM purchase_order_lines WHERE tenant_id=? AND purchase_order_id=? ORDER BY line_no",
                a.tenant(),
                id);
        var inputs = new ArrayList<TaxService.LineInput>();
        for (var l : lines)
            inputs.add(new TaxService.LineInput(
                    ((Number) l.get("line_no")).intValue(),
                    str(l, "item_id"),
                    (BigDecimal) l.get("quantity"),
                    (BigDecimal) l.get("unit_cost"),
                    null,
                    null));
        return estimate(a, "INPUT", (String) order.get("supplier_id"), date, inputs, order);
    }

    private Map<String, Object> estimate(
            Actor a,
            String direction,
            String partner,
            String date,
            List<TaxService.LineInput> lines,
            Map<String, Object> order) {
        LocalDate day = date == null || date.isBlank() ? TaxService.today() : TaxAdminService.date(date);
        var out = new LinkedHashMap<String, Object>();
        out.put("order_id", order.get("id"));
        out.put("number", order.get("number"));
        out.put("order_total", order.get("total"));
        out.put("date", day.toString());
        out.put(
                "configured",
                !s.list("SELECT 1 FROM tax_codes WHERE tenant_id=? LIMIT 1", a.tenant())
                        .isEmpty());
        try {
            out.put("available", true);
            out.put("estimate", tax.calculate(a, new TaxService.Document(direction, day, partner, null, lines)));
        } catch (ResponseStatusException e) {
            // a configuration gap (for example no rate effective on the date) is shown in the drawer, not as an error
            if (e.getStatusCode().value() != 409) throw e;
            out.put("available", false);
            out.put("message", e.getReason());
        }
        return out;
    }

    // ------------------------------------------------------------------ register and return

    /** Register filter: posting dates (inclusive), direction, code id and free text (document number or partner). */
    public record Filter(String from, String to, String direction, String code, String q) {}

    private record Where(String sql, List<Object> args) {}

    private Where where(Actor a, Filter f) {
        var sql = new StringBuilder(" WHERE l.tenant_id=?");
        var args = new ArrayList<Object>();
        args.add(a.tenant());
        if (blank(f.from()) != null) {
            sql.append(" AND l.posting_date>=?");
            args.add(TaxAdminService.date(f.from().strip()));
        }
        if (blank(f.to()) != null) {
            sql.append(" AND l.posting_date<=?");
            args.add(TaxAdminService.date(f.to().strip()));
        }
        if (blank(f.direction()) != null) {
            String d = f.direction().strip().toUpperCase(Locale.ROOT);
            require(d.equals("OUTPUT") || d.equals("INPUT"), "Direction must be OUTPUT or INPUT");
            sql.append(" AND l.direction=?");
            args.add(d);
        }
        if (blank(f.code()) != null) {
            sql.append(" AND l.tax_code_id=?");
            args.add(f.code().strip());
        }
        if (blank(f.q()) != null) {
            String like = "%"
                    + f.q().strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append(" AND (l.source_number ILIKE ? OR p.name ILIKE ? OR l.source_type ILIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        return new Where(sql.toString(), args);
    }

    private static final String REGISTER = """
            SELECT l.*,c.code,c.name AS code_name,k.name AS component,p.name AS partner,p.tax_id AS partner_tax_id
            FROM tax_lines l
            JOIN tax_codes c ON c.tenant_id=l.tenant_id AND c.id=l.tax_code_id
            LEFT JOIN tax_components k ON k.tenant_id=l.tenant_id AND k.id=l.component_id
            LEFT JOIN partners p ON p.tenant_id=l.tenant_id AND p.id=l.partner_id""";

    /** Keyset page of the register, newest posting date first, with the filter's totals. Administrators only. */
    public Map<String, Object> register(Actor a, Filter f, int limit, String before) {
        a.allow(TaxAdminService.MANAGERS);
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
        var w = where(a, f);
        var args = new ArrayList<>(w.args());
        String cursor = blank(before);
        args.add(cursor);
        args.add(cursor);
        args.add(limit + 1);
        var rows = s.list(REGISTER + w.sql() + """
                 AND (?::text IS NULL OR (l.posting_date,l.id) <
                      (SELECT x.posting_date,x.id FROM tax_lines x WHERE x.tenant_id=l.tenant_id AND x.id=?))
                ORDER BY l.posting_date DESC,l.id DESC LIMIT ?""", args.toArray());
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        var summary = s.one("""
                SELECT COUNT(*) AS lines,
                       COALESCE(SUM(l.base) FILTER (WHERE l.component_seq<=1),0) AS base,
                       COALESCE(SUM(l.tax) FILTER (WHERE l.direction='OUTPUT'),0) AS output_tax,
                       COALESCE(SUM(l.tax) FILTER (WHERE l.direction='INPUT'),0) AS input_tax
                FROM tax_lines l LEFT JOIN partners p ON p.tenant_id=l.tenant_id AND p.id=l.partner_id""" + w.sql(), w.args().toArray());
        var out = new LinkedHashMap<String, Object>();
        out.put("items", items);
        out.put("next", more ? items.getLast().get("id") : "");
        out.put("summary", summary);
        return out;
    }

    /**
     * VAT return summary for posting dates {@code from}–{@code to}: output and input base and tax per code (the base of a
     * line counted once, from its first component), standard/zero-rated/exempt bases, and net tax payable (output −
     * input). Administrators only. Empty until receivables/payables record tax (V26/V27).
     */
    public Map<String, Object> taxReturn(Actor a, String from, String to) {
        a.allow(TaxAdminService.MANAGERS);
        LocalDate start = TaxAdminService.date(from);
        LocalDate end = TaxAdminService.date(to);
        require(!end.isBefore(start), "The period ends before it starts");
        var codes = s.list("""
                SELECT c.id,c.code,c.name,c.kind,
                       COALESCE(SUM(l.base) FILTER (WHERE l.direction='OUTPUT' AND l.component_seq<=1),0) AS output_base,
                       COALESCE(SUM(l.tax) FILTER (WHERE l.direction='OUTPUT'),0) AS output_tax,
                       COALESCE(SUM(l.base) FILTER (WHERE l.direction='INPUT' AND l.component_seq<=1),0) AS input_base,
                       COALESCE(SUM(l.tax) FILTER (WHERE l.direction='INPUT'),0) AS input_tax,
                       COUNT(DISTINCT (l.source_type,l.source_id)) AS documents
                FROM tax_lines l
                JOIN tax_codes c ON c.tenant_id=l.tenant_id AND c.id=l.tax_code_id
                WHERE l.tenant_id=? AND l.posting_date BETWEEN ? AND ?
                GROUP BY c.id,c.code,c.name,c.kind
                ORDER BY c.code""", a.tenant(), start, end);
        var components = s.list("""
                SELECT c.code,k.name AS component,l.rate,l.direction,
                       SUM(l.base) AS base,SUM(l.tax) AS tax
                FROM tax_lines l
                JOIN tax_codes c ON c.tenant_id=l.tenant_id AND c.id=l.tax_code_id
                JOIN tax_components k ON k.tenant_id=l.tenant_id AND k.id=l.component_id
                WHERE l.tenant_id=? AND l.posting_date BETWEEN ? AND ?
                GROUP BY c.code,k.seq,k.name,l.rate,l.direction
                ORDER BY c.code,k.seq,l.direction DESC,l.rate""", a.tenant(), start, end);
        var totals = new LinkedHashMap<String, Object>();
        for (String side : List.of("output", "input")) {
            BigDecimal standard = BigDecimal.ZERO,
                    zero = BigDecimal.ZERO,
                    exempt = BigDecimal.ZERO,
                    taxes = BigDecimal.ZERO;
            for (var c : codes) {
                BigDecimal base = (BigDecimal) c.get(side + "_base");
                switch (str(c, "kind")) {
                    case "ZERO_RATED" -> zero = zero.add(base);
                    case "EXEMPT" -> exempt = exempt.add(base);
                    default -> standard = standard.add(base);
                }
                taxes = taxes.add((BigDecimal) c.get(side + "_tax"));
            }
            totals.put(side + "_standard_base", standard);
            totals.put(side + "_zero_rated_base", zero);
            totals.put(side + "_exempt_base", exempt);
            totals.put(side + "_tax", taxes);
        }
        totals.put(
                "net_payable", ((BigDecimal) totals.get("output_tax")).subtract((BigDecimal) totals.get("input_tax")));
        var out = new LinkedHashMap<String, Object>();
        out.put("from", start.toString());
        out.put("to", end.toString());
        out.put("codes", codes);
        out.put("components", components);
        out.put("totals", totals);
        out.put("empty", codes.isEmpty());
        out.put(
                "currency",
                s.one("SELECT currency FROM tenants WHERE id=?", a.tenant()).get("currency"));
        return out;
    }

    /** The register as CSV (newest first, at most {@link #EXPORT_CAP} rows). */
    public String registerCsv(Actor a, Filter f) {
        a.allow(TaxAdminService.MANAGERS);
        var w = where(a, f);
        var args = new ArrayList<>(w.args());
        args.add(EXPORT_CAP);
        var rows = s.list(REGISTER + w.sql() + " ORDER BY l.posting_date DESC,l.id DESC LIMIT ?", args.toArray());
        var out = new StringBuilder("﻿");
        out.append("Posting date,Document date,Direction,Document type,Document,Line,Partner,Partner tax ID,Tax code,"
                + "Kind,Component,Rate %,Base,Tax,Account\r\n");
        for (var r : rows)
            out.append(String.join(
                            ",",
                            cell(r.get("posting_date")),
                            cell(r.get("document_date")),
                            cell(r.get("direction")),
                            cell(r.get("source_type")),
                            cell(r.get("source_number")),
                            cell(r.get("line_no")),
                            cell(r.get("partner")),
                            cell(r.get("partner_tax_id")),
                            cell(r.get("code")),
                            cell(r.get("kind")),
                            cell(r.get("component")),
                            number(r.get("rate"), 4),
                            number(r.get("base"), 2),
                            number(r.get("tax"), 2),
                            cell(r.get("account_code"))))
                    .append("\r\n");
        return out.toString();
    }

    /** The VAT return summary as CSV: one row per code, then the totals. */
    public String returnCsv(Actor a, String from, String to) {
        var r = taxReturn(a, from, to);
        var out = new StringBuilder("﻿");
        out.append("VAT return summary,")
                .append(cell(r.get("from")))
                .append(',')
                .append(cell(r.get("to")));
        out.append("\r\nTax code,Name,Kind,Output base,Output tax,Input base,Input tax,Documents\r\n");
        @SuppressWarnings("unchecked")
        var codes = (List<Map<String, Object>>) r.get("codes");
        for (var c : codes)
            out.append(String.join(
                            ",",
                            cell(c.get("code")),
                            cell(c.get("name")),
                            cell(c.get("kind")),
                            number(c.get("output_base"), 2),
                            number(c.get("output_tax"), 2),
                            number(c.get("input_base"), 2),
                            number(c.get("input_tax"), 2),
                            cell(c.get("documents"))))
                    .append("\r\n");
        @SuppressWarnings("unchecked")
        var totals = (Map<String, Object>) r.get("totals");
        out.append("\r\nTotal,Amount\r\n");
        for (var e : totals.entrySet())
            out.append(cell(e.getKey()))
                    .append(',')
                    .append(number(e.getValue(), 2))
                    .append("\r\n");
        return out.toString();
    }

    static String cell(Object value) {
        String raw = value == null ? "" : value.toString();
        if (!raw.isEmpty() && "=+-@\t\r".indexOf(raw.charAt(0)) >= 0) raw = "'" + raw;
        return "\"" + raw.replace("\"", "\"\"") + "\"";
    }

    private static String number(Object value, int scale) {
        if (value == null) return "";
        return new BigDecimal(value.toString())
                .setScale(scale, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
