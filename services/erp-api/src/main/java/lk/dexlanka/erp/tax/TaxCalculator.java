package lk.dexlanka.erp.tax;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tax arithmetic, free of the database (ADR-009). Exact {@link BigDecimal} maths only: the single division (the
 * base of a tax-inclusive price) is carried at 20 decimals before rounding; every rounding is HALF_UP to the currency's
 * decimals.
 *
 * <ul>
 *   <li>Line amount = quantity × unit price × (100 − discount %) / 100, rounded to the currency.
 *   <li>Component tax = rate × (base, plus the tax of every earlier component when the component is compound).
 *   <li>Tax-inclusive prices: the line amount is the gross; base = gross / (1 + Σ effective rates), and base + tax
 *       equals the gross exactly (the base absorbs the rounding).
 *   <li>{@code PER_LINE}: each component's tax is rounded on each line; document totals are sums of rounded line
 *       taxes. {@code PER_DOCUMENT}: each code component's exact tax is summed over the document and rounded once;
 *       line shares are allocated cumulatively (round(running exact total) − previous rounded running total), so they
 *       add up to the document total exactly.
 *   <li>Zero-rated and exempt codes carry no tax; a line without a resolved code is reported untaxed.
 * </ul>
 */
final class TaxCalculator {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final MathContext DIVISION = new MathContext(34, RoundingMode.HALF_UP);
    private static final int DIVISION_SCALE = 20;

    private TaxCalculator() {}

    /** A tax code as of the document date: one rate version per component. */
    record CodeDef(
            String id,
            String code,
            String name,
            String kind,
            String outputAccount,
            String inputAccount,
            List<ComponentDef> components) {}

    record ComponentDef(
            String id, int seq, String name, boolean compound, String rateVersionId, BigDecimal rate, LocalDate from) {}

    /** A document line with its resolved code ({@code code} null = untaxed) and where the code came from. */
    record Resolved(TaxService.LineInput line, CodeDef code, String source) {}

    static TaxService.Result calculate(
            String currency,
            int scale,
            LocalDate date,
            String direction,
            String partnerId,
            boolean inclusive,
            String rounding,
            List<Resolved> lines,
            List<String> warnings) {
        boolean perDocument = rounding.equals("PER_DOCUMENT");
        // exact component taxes per line, and per-document running totals per code component
        int n = lines.size();
        var amounts = new BigDecimal[n];
        var exact = new BigDecimal[n][];
        for (int i = 0; i < n; i++) {
            var r = lines.get(i);
            var l = r.line();
            BigDecimal discount = l.discountPct() == null ? BigDecimal.ZERO : l.discountPct();
            amounts[i] = l.quantity()
                    .multiply(l.unitPrice())
                    .multiply(HUNDRED.subtract(discount))
                    .divide(HUNDRED)
                    .setScale(scale, RoundingMode.HALF_UP);
            exact[i] = standard(r.code()) ? exactTaxes(r.code(), amounts[i], inclusive) : new BigDecimal[0];
        }

        var taxes = new BigDecimal[n][];
        if (perDocument) {
            Map<String, BigDecimal> running = new HashMap<>();
            for (int i = 0; i < n; i++) {
                var code = lines.get(i).code();
                taxes[i] = new BigDecimal[exact[i].length];
                for (int k = 0; k < exact[i].length; k++) {
                    String key = code.components().get(k).id();
                    BigDecimal before = running.getOrDefault(key, BigDecimal.ZERO);
                    BigDecimal after = before.add(exact[i][k]);
                    running.put(key, after);
                    taxes[i][k] = round(after, scale).subtract(round(before, scale));
                }
            }
        } else {
            for (int i = 0; i < n; i++) {
                var code = lines.get(i).code();
                if (!standard(code)) {
                    taxes[i] = new BigDecimal[0];
                } else if (inclusive) {
                    taxes[i] = new BigDecimal[exact[i].length];
                    for (int k = 0; k < exact[i].length; k++) taxes[i][k] = round(exact[i][k], scale);
                } else {
                    // exclusive, per line: compound components build on the rounded earlier taxes of the line
                    taxes[i] = new BigDecimal[code.components().size()];
                    BigDecimal earlier = BigDecimal.ZERO;
                    for (int k = 0; k < taxes[i].length; k++) {
                        var c = code.components().get(k);
                        BigDecimal base = c.compound() ? amounts[i].add(earlier) : amounts[i];
                        taxes[i][k] = round(base.multiply(c.rate()).divide(HUNDRED), scale);
                        earlier = earlier.add(taxes[i][k]);
                    }
                }
            }
        }

        var out = new ArrayList<TaxService.LineTax>();
        Map<String, CodeAccumulator> totals = new LinkedHashMap<>();
        BigDecimal net = BigDecimal.ZERO.setScale(scale);
        BigDecimal taxTotal = BigDecimal.ZERO.setScale(scale);
        for (int i = 0; i < n; i++) {
            var r = lines.get(i);
            var code = r.code();
            BigDecimal lineTax = BigDecimal.ZERO.setScale(scale);
            for (BigDecimal t : taxes[i]) lineTax = lineTax.add(t);
            BigDecimal base = inclusive ? amounts[i].subtract(lineTax) : amounts[i];
            var components = new ArrayList<TaxService.ComponentTax>();
            BigDecimal earlier = BigDecimal.ZERO.setScale(scale);
            for (int k = 0; k < taxes[i].length; k++) {
                var c = code.components().get(k);
                BigDecimal componentBase = c.compound() ? base.add(earlier) : base;
                components.add(new TaxService.ComponentTax(
                        c.id(),
                        c.seq(),
                        c.name(),
                        c.compound(),
                        c.rateVersionId(),
                        c.rate(),
                        c.from(),
                        componentBase,
                        taxes[i][k]));
                earlier = earlier.add(taxes[i][k]);
            }
            String account =
                    code == null ? null : direction.equals("OUTPUT") ? code.outputAccount() : code.inputAccount();
            out.add(new TaxService.LineTax(
                    r.line().lineNo(),
                    r.line().itemId(),
                    amounts[i],
                    code == null ? null : code.id(),
                    code == null ? null : code.code(),
                    code == null ? null : code.name(),
                    code == null ? null : code.kind(),
                    r.source(),
                    account,
                    base,
                    lineTax,
                    base.add(lineTax),
                    components));
            net = net.add(base);
            taxTotal = taxTotal.add(lineTax);
            if (code != null)
                totals.computeIfAbsent(code.id(), x -> new CodeAccumulator(code, account, scale))
                        .add(base, components);
        }
        return new TaxService.Result(
                currency,
                scale,
                date,
                direction,
                partnerId,
                inclusive,
                rounding,
                out,
                totals.values().stream().map(CodeAccumulator::total).toList(),
                net,
                taxTotal,
                net.add(taxTotal),
                warnings);
    }

    private static boolean standard(CodeDef code) {
        return code != null && code.kind().equals("STANDARD");
    }

    /**
     * Exact (unrounded) component taxes of one line. Each component's effective factor on the base is c_k = rate_k ×
     * (1 + Σ c_j of earlier components when compound, else 1); an inclusive amount is first divided by 1 + Σ c_k.
     */
    static BigDecimal[] exactTaxes(CodeDef code, BigDecimal amount, boolean inclusive) {
        int m = code.components().size();
        var factors = new BigDecimal[m];
        BigDecimal sum = BigDecimal.ZERO;
        for (int k = 0; k < m; k++) {
            var c = code.components().get(k);
            BigDecimal r = c.rate().divide(HUNDRED);
            factors[k] = c.compound() ? r.multiply(BigDecimal.ONE.add(sum)) : r;
            sum = sum.add(factors[k]);
        }
        BigDecimal base = inclusive
                ? amount.divide(BigDecimal.ONE.add(sum), DIVISION).setScale(DIVISION_SCALE, RoundingMode.HALF_UP)
                : amount;
        var out = new BigDecimal[m];
        for (int k = 0; k < m; k++) out[k] = base.multiply(factors[k]);
        return out;
    }

    static BigDecimal round(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    /** Document total of one code: base and tax per component. */
    private static final class CodeAccumulator {
        private final CodeDef code;
        private final String account;
        private BigDecimal base;
        private BigDecimal tax;
        private final Map<String, BigDecimal[]> components = new LinkedHashMap<>();

        CodeAccumulator(CodeDef code, String account, int scale) {
            this.code = code;
            this.account = account;
            this.base = BigDecimal.ZERO.setScale(scale);
            this.tax = BigDecimal.ZERO.setScale(scale);
            for (var c : code.components())
                components.put(
                        c.id(), new BigDecimal[] {BigDecimal.ZERO.setScale(scale), BigDecimal.ZERO.setScale(scale)});
        }

        void add(BigDecimal lineBase, List<TaxService.ComponentTax> lineComponents) {
            base = base.add(lineBase);
            for (var c : lineComponents) {
                var sums = components.get(c.componentId());
                sums[0] = sums[0].add(c.base());
                sums[1] = sums[1].add(c.tax());
                tax = tax.add(c.tax());
            }
        }

        TaxService.CodeTotal total() {
            var list = new ArrayList<TaxService.ComponentTotal>();
            for (var c : code.components()) {
                var sums = components.get(c.id());
                list.add(new TaxService.ComponentTotal(
                        c.id(), c.seq(), c.name(), c.compound(), c.rateVersionId(), c.rate(), sums[0], sums[1]));
            }
            return new TaxService.CodeTotal(code.id(), code.code(), code.name(), code.kind(), account, base, tax, list);
        }
    }
}
