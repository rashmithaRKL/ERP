package lk.dexlanka.erp.tax;

import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The public tax API (V25) for the receivables and payables features (V26/V27): {@link #calculate} turns document lines
 * into per-line taxable bases and component taxes with document totals per code, and {@link #record} writes the result
 * to the tax register ({@code tax_lines}) inside the posting document's transaction. Nothing in V25 posts tax; the
 * sales and purchase order drawers only show {@link #calculate} estimates.
 *
 * <p>Code resolution per line, first match wins: the line's explicit code → the partner's exemption valid on the
 * document date (→ the tenant's exemption code) → the item's default → the partner's default → the tenant's default.
 * Inactive codes and codes for the other direction are skipped (an explicit one is rejected). Rounding and the price
 * convention come from the tenant's settings ({@code pricesIncludeTax} may be overridden per document); maths in
 * {@link TaxCalculator}.
 */
@Service
public class TaxService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

    /**
     * A document to calculate. {@code direction} is {@code OUTPUT} (sales: tax charged) or {@code INPUT} (purchases:
     * tax paid); {@code partnerId} may be null; {@code pricesIncludeTax} null = the tenant setting.
     */
    public record Document(
            String direction, LocalDate date, String partnerId, Boolean pricesIncludeTax, List<LineInput> lines) {}

    /**
     * One line: {@code quantity × unitPrice × (100 − discountPct) / 100} is the line amount (tax-exclusive or inclusive
     * per the document). {@code discountPct} and {@code taxCodeId} may be null; {@code itemId} may be null for a line
     * without an item.
     */
    public record LineInput(
            int lineNo,
            String itemId,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discountPct,
            String taxCodeId) {}

    /**
     * The calculation. Amounts are rounded to the currency's {@code scale} decimals; {@code net + tax = gross}.
     * {@code warnings} lists lines without a tax code and partner notes (an unregistered supplier).
     */
    public record Result(
            String currency,
            int scale,
            LocalDate date,
            String direction,
            String partnerId,
            boolean pricesIncludeTax,
            String rounding,
            List<LineTax> lines,
            List<CodeTotal> codes,
            BigDecimal net,
            BigDecimal tax,
            BigDecimal gross,
            List<String> warnings) {}

    /**
     * One line's tax. {@code amount} is the entered line amount (the gross when prices include tax); {@code source} is
     * {@code LINE}, {@code EXEMPTION}, {@code ITEM}, {@code PARTNER}, {@code TENANT} or {@code NONE} (untaxed: code
     * fields null). {@code kind} is {@code STANDARD}, {@code ZERO_RATED} or {@code EXEMPT}; {@code accountCode} the
     * code's account for the direction.
     */
    public record LineTax(
            int lineNo,
            String itemId,
            BigDecimal amount,
            String taxCodeId,
            String code,
            String codeName,
            String kind,
            String source,
            String accountCode,
            BigDecimal base,
            BigDecimal tax,
            BigDecimal gross,
            List<ComponentTax> components) {}

    /** One component of a line: its rate version as of the document date, the base it applied to and the tax. */
    public record ComponentTax(
            String componentId,
            int seq,
            String name,
            boolean compound,
            String rateVersionId,
            BigDecimal rate,
            LocalDate effectiveFrom,
            BigDecimal base,
            BigDecimal tax) {}

    /** Document total of one code (zero-rated and exempt codes have no components and zero tax). */
    public record CodeTotal(
            String taxCodeId,
            String code,
            String name,
            String kind,
            String accountCode,
            BigDecimal base,
            BigDecimal tax,
            List<ComponentTotal> components) {}

    public record ComponentTotal(
            String componentId,
            int seq,
            String name,
            boolean compound,
            String rateVersionId,
            BigDecimal rate,
            BigDecimal base,
            BigDecimal tax) {}

    /**
     * What {@link #record} writes: the source document ({@code sourceType} upper-case such as {@code CUSTOMER_INVOICE},
     * its id and number), the posting date, and a {@link Result} from {@link #calculate}.
     */
    public record Source(
            String sourceType, String sourceId, String sourceNumber, LocalDate postingDate, Result result) {}

    private final Store s;

    TaxService(Store s) {
        this.s = s;
    }

    /** Today's business date (Asia/Colombo). */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /**
     * Calculates the tax of a document for the actor's tenant (read-only; joins the caller's transaction when there is
     * one). 404 for a partner, item or code of another tenant; 409 for an explicit code that is inactive, for the other
     * direction, or a standard code without a rate effective on the document date.
     */
    @Transactional(readOnly = true, noRollbackFor = ResponseStatusException.class)
    public Result calculate(Actor a, Document d) {
        require(d.direction() != null && d.direction().matches("OUTPUT|INPUT"), "Direction must be OUTPUT or INPUT");
        require(d.date() != null, "A document date is required");
        require(d.lines() != null, "Lines are required");
        String t = a.tenant();
        var settings = settings(t);
        var tenant = s.one("SELECT currency FROM tenants WHERE id=?", t);
        String currency = str(tenant, "currency");
        int scale = scale(currency);
        boolean inclusive = d.pricesIncludeTax() != null
                ? d.pricesIncludeTax()
                : Boolean.TRUE.equals(settings.get("prices_include_tax"));
        String rounding = str(settings, "rounding").isEmpty() ? "PER_LINE" : str(settings, "rounding");
        boolean sales = d.direction().equals("OUTPUT");
        var warnings = new LinkedHashSet<String>();

        Map<String, Object> partner = null;
        if (d.partnerId() != null && !d.partnerId().isBlank()) {
            partner = s.one("SELECT * FROM partners WHERE tenant_id=? AND id=?", t, d.partnerId());
            if (!sales && !Boolean.TRUE.equals(partner.get("tax_registered")))
                warnings.add(
                        partner.get("name") + " is not recorded as tax-registered; input tax may not be claimable");
        }
        boolean exempt = partner != null
                && partner.get("tax_exemption_valid_until") != null
                && !d.date().isAfter(toDate(partner.get("tax_exemption_valid_until")));

        var defs = new HashMap<String, TaxCalculator.CodeDef>();
        var resolved = new ArrayList<TaxCalculator.Resolved>();
        for (var line : d.lines()) {
            require(line.quantity() != null && line.unitPrice() != null, "Each line needs a quantity and a price");
            require(
                    line.discountPct() == null
                            || (line.discountPct().signum() >= 0
                                    && line.discountPct().compareTo(BigDecimal.valueOf(100)) <= 0),
                    "Discounts are between 0 and 100 %");
            String codeId = null;
            String source = "NONE";
            if (line.taxCodeId() != null && !line.taxCodeId().isBlank()) {
                var code = s.one("SELECT * FROM tax_codes WHERE tenant_id=? AND id=?", t, line.taxCodeId());
                require(Boolean.TRUE.equals(code.get("active")), "Tax code " + code.get("code") + " is inactive");
                require(
                        applies(code, sales),
                        "Tax code " + code.get("code") + " does not apply to " + (sales ? "sales" : "purchases"));
                codeId = line.taxCodeId();
                source = "LINE";
            }
            if (codeId == null && exempt) {
                codeId = usable(t, exemptionCode(t, settings, sales), sales);
                if (codeId != null) source = "EXEMPTION";
                else warnings.add("The partner's exemption applies but no active exempt tax code is set up");
            }
            if (codeId == null && line.itemId() != null && !line.itemId().isBlank()) {
                var item = s.one(
                        "SELECT sales_tax_code_id,purchase_tax_code_id FROM items WHERE tenant_id=? AND id=?",
                        t,
                        line.itemId());
                codeId = usable(t, (String) item.get(sales ? "sales_tax_code_id" : "purchase_tax_code_id"), sales);
                if (codeId != null) source = "ITEM";
            }
            if (codeId == null && partner != null) {
                codeId = usable(t, (String) partner.get(sales ? "sales_tax_code_id" : "purchase_tax_code_id"), sales);
                if (codeId != null) source = "PARTNER";
            }
            if (codeId == null) {
                codeId = usable(
                        t, (String) settings.get(sales ? "default_sales_code_id" : "default_purchase_code_id"), sales);
                if (codeId != null) source = "TENANT";
            }
            if (codeId == null) warnings.add("Line " + line.lineNo() + " has no tax code and is treated as untaxed");
            String id = codeId;
            var def = id == null ? null : defs.computeIfAbsent(id, x -> definition(t, x, d.date()));
            resolved.add(new TaxCalculator.Resolved(line, def, source));
        }
        return TaxCalculator.calculate(
                currency,
                scale,
                d.date(),
                d.direction(),
                partner == null ? null : d.partnerId(),
                inclusive,
                rounding,
                resolved,
                List.copyOf(warnings));
    }

    /**
     * Writes a calculated document to the tax register: one {@code tax_lines} row per line and component (one row
     * without a component for zero-rated and exempt lines). Must run inside the posting command's transaction (the
     * caller owns permission, idempotency, audit and the period check). 409 when a line has no tax code or tax was
     * already recorded for the source document. Returns the new row ids.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> record(Actor a, Source source) {
        require(
                source.sourceType() != null && source.sourceType().matches("[A-Z][A-Z_]{1,39}"),
                "Use an upper-case source document type");
        require(source.sourceId() != null && !source.sourceId().isBlank(), "A source document id is required");
        require(source.postingDate() != null, "A posting date is required");
        var r = source.result();
        require(r != null && !r.lines().isEmpty(), "Nothing to record");
        require(
                s.list(
                                "SELECT 1 FROM tax_lines WHERE tenant_id=? AND source_type=? AND source_id=? LIMIT 1",
                                a.tenant(),
                                source.sourceType(),
                                source.sourceId())
                        .isEmpty(),
                "Tax was already recorded for this document");
        var ids = new ArrayList<String>();
        var lineNos = new LinkedHashSet<Integer>();
        for (var line : r.lines()) {
            require(lineNos.add(line.lineNo()), "Line numbers must be unique");
            require(line.taxCodeId() != null, "Line " + line.lineNo() + " has no tax code");
            if (line.components().isEmpty()) {
                ids.add(insert(a, source, r, line, null));
            } else {
                for (var c : line.components()) ids.add(insert(a, source, r, line, c));
            }
        }
        return ids;
    }

    private String insert(Actor a, Source source, Result r, LineTax line, ComponentTax c) {
        String id = Store.id();
        s.db().update(
                        """
                        INSERT INTO tax_lines(id,tenant_id,source_type,source_id,source_number,line_no,tax_code_id,kind,
                                              component_id,component_seq,rate_version_id,rate,account_code,base,tax,
                                              direction,posting_date,document_date,partner_id,created_by)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                        id,
                        a.tenant(),
                        source.sourceType(),
                        source.sourceId(),
                        source.sourceNumber(),
                        line.lineNo(),
                        line.taxCodeId(),
                        line.kind(),
                        c == null ? null : c.componentId(),
                        c == null ? 0 : c.seq(),
                        c == null ? null : c.rateVersionId(),
                        c == null ? BigDecimal.ZERO : c.rate(),
                        line.accountCode(),
                        c == null ? line.base() : c.base(),
                        c == null ? BigDecimal.ZERO : c.tax(),
                        r.direction(),
                        source.postingDate(),
                        r.date(),
                        r.partnerId(),
                        a.id());
        return id;
    }

    // ------------------------------------------------------------------ helpers shared with the read model

    /** The tenant's settings row, or the defaults when none was saved yet. */
    public Map<String, Object> settings(String tenant) {
        var rows = s.list("SELECT * FROM tax_settings WHERE tenant_id=?", tenant);
        if (!rows.isEmpty()) return rows.getFirst();
        var defaults = new HashMap<String, Object>();
        defaults.put("tenant_id", tenant);
        defaults.put("prices_include_tax", false);
        defaults.put("rounding", "PER_LINE");
        return defaults;
    }

    /** The currency's minor-unit decimals (LKR: 2); 2 for an unknown code. */
    static int scale(String currency) {
        try {
            int digits = Currency.getInstance(currency).getDefaultFractionDigits();
            return digits < 0 ? 2 : digits;
        } catch (IllegalArgumentException e) {
            return 2;
        }
    }

    private static boolean applies(Map<String, Object> code, boolean sales) {
        String to = str(code, "applies_to");
        return to.equals("BOTH") || to.equals(sales ? "SALES" : "PURCHASES");
    }

    /** {@code id} when it names an active code for the direction, else null (resolution falls through). */
    private String usable(String tenant, String id, boolean sales) {
        if (id == null) return null;
        var rows = s.list("SELECT * FROM tax_codes WHERE tenant_id=? AND id=?", tenant, id);
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.getFirst().get("active")) && applies(rows.getFirst(), sales)
                ? id
                : null;
    }

    /** The tenant's exemption code, or the first active EXEMPT code for the direction. */
    private String exemptionCode(String tenant, Map<String, Object> settings, boolean sales) {
        String configured = (String) settings.get("exempt_code_id");
        if (usable(tenant, configured, sales) != null) return configured;
        var rows = s.list(
                "SELECT id FROM tax_codes WHERE tenant_id=? AND kind='EXEMPT' AND active AND applies_to IN ('BOTH',?) ORDER BY code LIMIT 1",
                tenant,
                sales ? "SALES" : "PURCHASES");
        return rows.isEmpty() ? null : (String) rows.getFirst().get("id");
    }

    /** A code with the rate version of each component effective on {@code date}. */
    TaxCalculator.CodeDef definition(String tenant, String codeId, LocalDate date) {
        var code = s.one("SELECT * FROM tax_codes WHERE tenant_id=? AND id=?", tenant, codeId);
        var components = new ArrayList<TaxCalculator.ComponentDef>();
        if (str(code, "kind").equals("STANDARD")) {
            var rows = s.list("""
                    SELECT c.id,c.seq,c.name,c.compound,v.id AS version_id,v.rate,v.effective_from
                    FROM tax_components c
                    LEFT JOIN LATERAL (SELECT * FROM tax_rate_versions v
                                       WHERE v.tenant_id=c.tenant_id AND v.component_id=c.id AND v.effective_from<=?
                                       ORDER BY v.effective_from DESC LIMIT 1) v ON true
                    WHERE c.tenant_id=? AND c.tax_code_id=?
                    ORDER BY c.seq""", date, tenant, codeId);
            require(!rows.isEmpty(), "Tax code " + code.get("code") + " has no components");
            for (var c : rows) {
                require(
                        c.get("version_id") != null,
                        "Tax code " + code.get("code") + " (" + c.get("name") + ") has no rate effective on " + date);
                components.add(new TaxCalculator.ComponentDef(
                        str(c, "id"),
                        ((Number) c.get("seq")).intValue(),
                        str(c, "name"),
                        Boolean.TRUE.equals(c.get("compound")),
                        str(c, "version_id"),
                        (BigDecimal) c.get("rate"),
                        toDate(c.get("effective_from"))));
            }
        }
        return new TaxCalculator.CodeDef(
                codeId,
                str(code, "code"),
                str(code, "name"),
                str(code, "kind"),
                str(code, "output_account_code"),
                str(code, "input_account_code"),
                components);
    }

    static LocalDate toDate(Object value) {
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof LocalDate d) return d;
        return LocalDate.parse(Objects.toString(value).substring(0, 10));
    }
}
