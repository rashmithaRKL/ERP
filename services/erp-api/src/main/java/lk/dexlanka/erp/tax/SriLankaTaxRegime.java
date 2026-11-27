package lk.dexlanka.erp.tax;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Sri Lanka VAT template: the standard rate with its recent history, a zero-rated code (for example exports) and an
 * exempt code. Rounding per document, prices exclusive of tax. <b>A starting point to be verified by a qualified
 * accountant; no certification is claimed.</b> Other levies (for example SSCL) and special schemes are not included.
 */
@Component
class SriLankaTaxRegime implements TaxRegime {
    static final String CODE = "LK";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String country() {
        return "Sri Lanka";
    }

    @Override
    public String name() {
        return "Sri Lanka VAT";
    }

    @Override
    public String rounding() {
        return "PER_DOCUMENT";
    }

    @Override
    public boolean pricesIncludeTax() {
        return false;
    }

    @Override
    public List<String> notes() {
        return List.of(
                "VAT standard rate history: 15 % from 1 Nov 2016, 8 % from 1 Dec 2019, 12 % from 1 Jun 2022,"
                        + " 15 % from 1 Sep 2022 and 18 % from 1 Jan 2024.",
                "VAT-ZERO is for zero-rated supplies such as exports (the base is reported as zero-rated);"
                        + " VAT-EXEMPT for exempt supplies (the base is reported separately).",
                "Not included: the Social Security Contribution Levy (SSCL), suspended VAT and other special schemes.",
                DISCLAIMER);
    }

    @Override
    public List<TemplateCode> codes() {
        return List.of(
                new TemplateCode(
                        "VAT-STD",
                        "VAT standard rate",
                        "BOTH",
                        "STANDARD",
                        List.of(new TemplateComponent(
                                "VAT",
                                false,
                                List.of(
                                        rate("2016-11-01", "15", "VAT standard rate 15 %"),
                                        rate("2019-12-01", "8", "VAT standard rate 8 %"),
                                        rate("2022-06-01", "12", "VAT standard rate 12 %"),
                                        rate("2022-09-01", "15", "VAT standard rate 15 %"),
                                        rate("2024-01-01", "18", "VAT standard rate 18 %")))),
                        true,
                        true,
                        false),
                new TemplateCode("VAT-ZERO", "VAT zero-rated", "BOTH", "ZERO_RATED", List.of(), false, false, false),
                new TemplateCode("VAT-EXEMPT", "VAT exempt", "BOTH", "EXEMPT", List.of(), false, false, true));
    }

    private static TemplateRate rate(String from, String rate, String note) {
        return new TemplateRate(LocalDate.parse(from), new BigDecimal(rate).setScale(4), note);
    }
}
