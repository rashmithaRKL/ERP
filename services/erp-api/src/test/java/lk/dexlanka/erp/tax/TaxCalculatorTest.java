package lk.dexlanka.erp.tax;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The tax arithmetic of {@link TaxCalculator} without a database: compound components, prices and rounding. */
class TaxCalculatorTest {
    private static final LocalDate DAY = LocalDate.of(2024, 1, 1);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static TaxCalculator.ComponentDef component(String id, int seq, String rate, boolean compound) {
        return new TaxCalculator.ComponentDef(id, seq, id, compound, "v-" + id, bd(rate), DAY);
    }

    private static TaxCalculator.CodeDef standard(String code, TaxCalculator.ComponentDef... components) {
        return new TaxCalculator.CodeDef(code, code, code, "STANDARD", "OUTPUT_TAX", "INPUT_TAX", List.of(components));
    }

    private static TaxCalculator.CodeDef other(String code, String kind) {
        return new TaxCalculator.CodeDef(code, code, code, kind, "OUTPUT_TAX", "INPUT_TAX", List.of());
    }

    private static TaxCalculator.Resolved line(
            int no, String qty, String price, String discount, TaxCalculator.CodeDef code) {
        return new TaxCalculator.Resolved(
                new TaxService.LineInput(no, null, bd(qty), bd(price), discount == null ? null : bd(discount), null),
                code,
                code == null ? "NONE" : "LINE");
    }

    private static TaxService.Result calc(boolean inclusive, String rounding, TaxCalculator.Resolved... lines) {
        return TaxCalculator.calculate(
                "LKR", 2, DAY, "OUTPUT", null, inclusive, rounding, List.of(lines), new ArrayList<>());
    }

    @Test
    void exclusivePricesAddTaxOnTheDiscountedAmount() {
        var vat = standard("VAT", component("vat", 1, "18.0000", false));
        var r = calc(false, "PER_LINE", line(1, "10", "100", null, vat), line(2, "3", "33.333", "10", vat));
        // 3 x 33.333 x 90 % = 89.9991 -> 90.00; 18 % of 90.00 = 16.20
        assertThat(r.lines().get(0).base()).isEqualByComparingTo("1000.00");
        assertThat(r.lines().get(0).tax()).isEqualByComparingTo("180.00");
        assertThat(r.lines().get(1).amount()).isEqualByComparingTo("90.00");
        assertThat(r.lines().get(1).tax()).isEqualByComparingTo("16.20");
        assertThat(r.net()).isEqualByComparingTo("1090.00");
        assertThat(r.tax()).isEqualByComparingTo("196.20");
        assertThat(r.gross()).isEqualByComparingTo("1286.20");
        assertThat(r.codes()).singleElement().satisfies(c -> {
            assertThat(c.base()).isEqualByComparingTo("1090.00");
            assertThat(c.tax()).isEqualByComparingTo("196.20");
            assertThat(c.accountCode()).isEqualTo("OUTPUT_TAX");
        });
    }

    @Test
    void compoundComponentIsCalculatedOnBasePlusEarlierTaxes() {
        var compound = standard("CMP", component("a", 1, "10", false), component("b", 2, "5", true));
        var simple = standard("SIM", component("c", 1, "10", false), component("d", 2, "5", false));
        var r = calc(false, "PER_LINE", line(1, "1", "1000", null, compound), line(2, "1", "1000", null, simple));
        var stacked = r.lines().get(0).components();
        assertThat(stacked.get(0).tax()).isEqualByComparingTo("100.00");
        assertThat(stacked.get(1).base()).isEqualByComparingTo("1100.00");
        assertThat(stacked.get(1).tax()).isEqualByComparingTo("55.00");
        assertThat(r.lines().get(0).tax()).isEqualByComparingTo("155.00");
        assertThat(r.lines().get(1).components().get(1).base()).isEqualByComparingTo("1000.00");
        assertThat(r.lines().get(1).tax()).isEqualByComparingTo("150.00");

        // the same in a tax-inclusive document: 1155.00 gross is 1000.00 base + 100.00 + 55.00
        var inclusive = calc(true, "PER_LINE", line(1, "1", "1155", null, compound));
        assertThat(inclusive.lines().get(0).base()).isEqualByComparingTo("1000.00");
        assertThat(inclusive.lines().get(0).components().get(1).tax()).isEqualByComparingTo("55.00");
        assertThat(inclusive.gross()).isEqualByComparingTo("1155.00");
    }

    @Test
    void inclusivePricesSplitTheGrossAndTheBaseAbsorbsRounding() {
        var vat = standard("VAT", component("vat", 1, "18", false));
        var r = calc(true, "PER_LINE", line(1, "1", "100", null, vat), line(2, "2", "590", null, vat));
        // 100 / 1.18 = 84.7457..., tax 15.2542... -> 15.25 and the base is 100 - 15.25
        assertThat(r.lines().get(0).tax()).isEqualByComparingTo("15.25");
        assertThat(r.lines().get(0).base()).isEqualByComparingTo("84.75");
        assertThat(r.lines().get(0).gross()).isEqualByComparingTo("100.00");
        assertThat(r.lines().get(1).base()).isEqualByComparingTo("1000.00");
        assertThat(r.lines().get(1).tax()).isEqualByComparingTo("180.00");
        assertThat(r.gross()).isEqualByComparingTo("1280.00");
        assertThat(r.net().add(r.tax())).isEqualByComparingTo(r.gross());

        var exclusive = calc(false, "PER_LINE", line(1, "1", "100", null, vat));
        assertThat(exclusive.gross()).isEqualByComparingTo("118.00");
    }

    @Test
    void perLineAndPerDocumentRoundingDifferByACent() {
        var vat = standard("VAT", component("vat", 1, "18", false));
        var lines = new TaxCalculator.Resolved[] {
            line(1, "1", "0.10", null, vat), line(2, "1", "0.10", null, vat), line(3, "1", "0.10", null, vat)
        };
        var perLine = calc(false, "PER_LINE", lines);
        var perDocument = calc(false, "PER_DOCUMENT", lines);
        // 0.018 per line: rounded on each line 0.02 x 3 = 0.06; on the document 0.054 -> 0.05
        assertThat(perLine.tax()).isEqualByComparingTo("0.06");
        assertThat(perDocument.tax()).isEqualByComparingTo("0.05");
        // per-document line shares are allocated cumulatively and add up to the document total
        assertThat(perDocument.lines().stream().map(TaxService.LineTax::tax))
                .containsExactly(bd("0.02"), bd("0.02"), bd("0.01"));
        assertThat(perDocument.codes().getFirst().tax()).isEqualByComparingTo("0.05");

        // tax-inclusive per document: the base is what remains of each gross
        var inclusive = calc(true, "PER_DOCUMENT", line(1, "1", "0.12", null, vat), line(2, "1", "0.12", null, vat));
        assertThat(inclusive.gross()).isEqualByComparingTo("0.24");
        assertThat(inclusive.net().add(inclusive.tax())).isEqualByComparingTo("0.24");
        assertThat(inclusive.tax()).isEqualByComparingTo("0.04");
    }

    @Test
    void zeroRatedExemptAndUntaxedLinesCarryNoTax() {
        var r = calc(
                false,
                "PER_LINE",
                line(1, "2", "50", null, other("ZERO", "ZERO_RATED")),
                line(2, "1", "40", null, other("EXEMPT", "EXEMPT")),
                line(3, "1", "10", null, null));
        assertThat(r.tax()).isEqualByComparingTo("0");
        assertThat(r.net()).isEqualByComparingTo("150.00");
        assertThat(r.codes()).extracting(TaxService.CodeTotal::kind).containsExactly("ZERO_RATED", "EXEMPT");
        assertThat(r.codes()).extracting(TaxService.CodeTotal::base).containsExactly(bd("100.00"), bd("40.00"));
        assertThat(r.lines().get(2).source()).isEqualTo("NONE");
        assertThat(r.lines().get(2).taxCodeId()).isNull();
    }

    @Test
    void negativeQuantitiesMirrorPositiveOnes() {
        var vat = standard("VAT", component("vat", 1, "18", false));
        var credit = calc(false, "PER_LINE", line(1, "-1", "0.25", null, vat));
        var debit = calc(false, "PER_LINE", line(1, "1", "0.25", null, vat));
        assertThat(credit.tax()).isEqualByComparingTo(debit.tax().negate());
        assertThat(debit.tax()).isEqualByComparingTo("0.05");
    }
}
