package lk.dexlanka.erp.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Code resolution (line → partner exemption → item → partner → tenant), tenant price/rounding settings with a
 * per-document override, and zero-rated vs exempt bases in the register and VAT return.
 */
class TaxResolutionTest extends TaxFixture {
    @Test
    void resolutionOrderWithPartnerExemptionAndItsExpiry() {
        String tenantDefault = standardCode("STD", "BOTH", "VAT:18:2020-01-01");
        String partnerDefault = standardCode("PARTNER", "SALES", "VAT:10:2020-01-01");
        String itemDefault = standardCode("ITEM", "SALES", "VAT:5:2020-01-01");
        String explicit = standardCode("LINE", "SALES", "VAT:2:2020-01-01");
        String exempt = plainCode("EXEMPT", "EXEMPT");
        String purchaseOnly = standardCode("BUY", "PURCHASES", "VAT:7:2020-01-01");

        // nothing configured: untaxed with a warning
        var none = calculate("OUTPUT", "2026-06-30", customer, line(1, bottle, "1", "100", null));
        assertThat(none.lines().getFirst().source()).isEqualTo("NONE");
        assertThat(none.tax()).isEqualByComparingTo("0");
        assertThat(none.warnings()).anyMatch(w -> w.contains("no tax code"));

        settings(false, "PER_LINE", tenantDefault, tenantDefault, exempt);
        assertThat(source("2026-06-30", null)).isEqualTo("TENANT:STD");
        admin.updatePartnerTax(
                adminUser, customer, new TaxCommands.PartnerTax(true, null, null, partnerDefault, null), id());
        assertThat(source("2026-06-30", null)).isEqualTo("PARTNER:PARTNER");
        admin.updateItemTax(adminUser, bottle, new TaxCommands.ItemTax(itemDefault, null), id());
        assertThat(source("2026-06-30", null)).isEqualTo("ITEM:ITEM");
        assertThat(source("2026-06-30", explicit)).isEqualTo("LINE:LINE");

        // an exemption valid until 30 June applies up to and including that day, then lapses
        admin.updatePartnerTax(
                adminUser,
                customer,
                new TaxCommands.PartnerTax(true, "EX-2026-001", "2026-06-30", partnerDefault, null),
                id());
        assertThat(source("2026-06-30", null)).isEqualTo("EXEMPTION:EXEMPT");
        assertThat(source("2026-07-01", null)).isEqualTo("ITEM:ITEM");
        // an explicit line code still wins over the exemption
        assertThat(source("2026-06-30", explicit)).isEqualTo("LINE:LINE");
        var exemptLine = calculate("OUTPUT", "2026-06-30", customer, line(1, bottle, "3", "100", null));
        assertThat(exemptLine.tax()).isEqualByComparingTo("0");
        assertThat(exemptLine.codes().getFirst().kind()).isEqualTo("EXEMPT");
        assertThat(exemptLine.codes().getFirst().base()).isEqualByComparingTo("300.00");

        // an inactive or other-direction default is skipped; an explicit one is refused
        admin.updateCode(
                adminUser,
                itemDefault,
                new TaxCommands.CodeUpdate("ITEM tax", "SALES", "OUTPUT_TAX", "INPUT_TAX", false),
                id());
        assertThat(source("2026-07-01", null)).isEqualTo("PARTNER:PARTNER");
        assertThatThrownBy(() -> calculate("OUTPUT", "2026-07-01", customer, line(1, bottle, "1", "1", itemDefault)))
                .hasMessageContaining("is inactive");
        assertThatThrownBy(() -> calculate("OUTPUT", "2026-07-01", customer, line(1, bottle, "1", "1", purchaseOnly)))
                .hasMessageContaining("does not apply to sales");
        // setting an item's sales default to a purchase-only code is refused
        assertThatThrownBy(
                        () -> admin.updateItemTax(adminUser, bottle, new TaxCommands.ItemTax(purchaseOnly, null), id()))
                .hasMessageContaining("does not apply to sales");
        // the partner profile is readable by every role
        var profile = query.partner(viewer, customer);
        @SuppressWarnings("unchecked")
        var p = (Map<String, Object>) profile.get("partner");
        assertThat(p)
                .containsEntry("tax_exemption_reference", "EX-2026-001")
                .containsEntry("sales_tax_code", "PARTNER");
        assertThat(count(
                        "SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='PARTNER_TAX_UPDATED' AND reference='Customer Co'",
                        t))
                .isEqualTo(2);
        assertThatThrownBy(() -> admin.updatePartnerTax(
                        adminUser, customer, new TaxCommands.PartnerTax(true, "EX-1", null, null, null), id()))
                .hasMessageContaining("both a certificate reference and a valid-until date");
    }

    @Test
    void pricesInclusiveOrExclusiveFromSettingsWithAPerDocumentOverride() {
        String vat = standardCode("VAT", "BOTH", "VAT:18:2020-01-01");
        settings(false, "PER_LINE", vat, vat, null);
        assertThat(calculate("OUTPUT", "2026-01-01", null, line(1, bottle, "1", "118", null))
                        .gross())
                .isEqualByComparingTo("139.24");
        settings(true, "PER_LINE", vat, vat, null);
        var inclusive = calculate("OUTPUT", "2026-01-01", null, line(1, bottle, "1", "118", null));
        assertThat(inclusive.pricesIncludeTax()).isTrue();
        assertThat(inclusive.net()).isEqualByComparingTo("100.00");
        assertThat(inclusive.gross()).isEqualByComparingTo("118.00");
        var overridden = tax.calculate(
                adminUser,
                new TaxService.Document(
                        "OUTPUT",
                        LocalDate.parse("2026-01-01"),
                        null,
                        false,
                        List.of(line(1, bottle, "1", "118", null))));
        assertThat(overridden.gross()).isEqualByComparingTo("139.24");

        // the tenant's rounding applies to every document: three 0.10 lines at 18 %
        var lines = new TaxService.LineInput[] {
            line(1, bottle, "1", "0.10", null), line(2, bottle, "1", "0.10", null), line(3, bottle, "1", "0.10", null)
        };
        settings(false, "PER_LINE", vat, vat, null);
        assertThat(calculate("OUTPUT", "2026-01-01", null, lines).tax()).isEqualByComparingTo("0.06");
        settings(false, "PER_DOCUMENT", vat, vat, null);
        var perDocument = calculate("OUTPUT", "2026-01-01", null, lines);
        assertThat(perDocument.tax()).isEqualByComparingTo("0.05");
        assertThat(perDocument.rounding()).isEqualTo("PER_DOCUMENT");
        assertThat(perDocument.currency()).isEqualTo("LKR");
        assertThat(perDocument.scale()).isEqualTo(2);
    }

    @Test
    void zeroRatedAndExemptBasesAreReportedSeparatelyInTheReturn() throws Exception {
        String vat = standardCode("VAT", "BOTH", "VAT:18:2020-01-01");
        String zero = plainCode("ZERO", "ZERO_RATED");
        String exempt = plainCode("EXEMPT", "EXEMPT");

        // the return is empty (and says so) until tax is recorded
        var empty = query.taxReturn(adminUser, "2026-05-01", "2026-05-31");
        assertThat(empty.get("empty")).isEqualTo(true);

        record(
                "CUSTOMER_INVOICE",
                "INV-1",
                "OUTPUT",
                "2026-05-03",
                customer,
                line(1, bottle, "10", "100", vat),
                line(2, bottle, "5", "100", zero),
                line(3, bottle, "2", "100", exempt));
        record("SUPPLIER_INVOICE", "SINV-7", "INPUT", "2026-05-20", supplier, line(1, resin, "100", "5", vat));
        record("CUSTOMER_INVOICE", "INV-JUNE", "OUTPUT", "2026-06-01", customer, line(1, bottle, "1", "100", vat));

        var r = query.taxReturn(adminUser, "2026-05-01", "2026-05-31");
        assertThat(r.get("empty")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        var totals = (Map<String, Object>) r.get("totals");
        assertThat(dec(totals.get("output_standard_base"))).isEqualByComparingTo("1000");
        assertThat(dec(totals.get("output_zero_rated_base"))).isEqualByComparingTo("500");
        assertThat(dec(totals.get("output_exempt_base"))).isEqualByComparingTo("200");
        assertThat(dec(totals.get("output_tax"))).isEqualByComparingTo("180");
        assertThat(dec(totals.get("input_standard_base"))).isEqualByComparingTo("500");
        assertThat(dec(totals.get("input_tax"))).isEqualByComparingTo("90");
        assertThat(dec(totals.get("net_payable"))).isEqualByComparingTo("90");
        @SuppressWarnings("unchecked")
        var codes = (List<Map<String, Object>>) r.get("codes");
        assertThat(codes).extracting(c -> c.get("code")).containsExactly("EXEMPT", "VAT", "ZERO");

        // the register: paged, filtered, totalled, exported
        var page =
                query.register(adminUser, new TaxQuery.Filter("2026-05-01", "2026-05-31", null, null, null), 2, null);
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) page.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.getFirst().get("source_number")).isEqualTo("SINV-7");
        var next = query.register(
                adminUser, new TaxQuery.Filter("2026-05-01", "2026-05-31", null, null, null), 2, str(page, "next"));
        @SuppressWarnings("unchecked")
        var rest = (List<Map<String, Object>>) next.get("items");
        assertThat(rest).hasSize(2);
        assertThat(next.get("next")).isEqualTo("");
        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) page.get("summary");
        assertThat(dec(summary.get("output_tax"))).isEqualByComparingTo("180");
        var output = query.register(adminUser, new TaxQuery.Filter(null, null, "OUTPUT", zero, null), 50, null);
        assertThat((List<?>) output.get("items")).hasSize(1);
        var byText = query.register(adminUser, new TaxQuery.Filter(null, null, null, null, "supplier co"), 50, null);
        assertThat((List<?>) byText.get("items")).hasSize(1);

        String csv = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/tax/register/export?from=2026-05-01&to=2026-05-31")
                        .with(as(adminUser)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(csv)
                .contains("Posting date,Document date")
                .contains("\"INV-1\"")
                .contains("180.00");
        assertThat(csv.lines().count()).isEqualTo(5);
        String returnCsv = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/tax/return/export?from=2026-05-01&to=2026-05-31")
                        .with(as(adminUser)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(returnCsv).contains("\"net_payable\",90.00").contains("\"ZERO\"");

        // only administrators read the register and the return (FINANCE joins with the general ledger)
        query(viewer, "/api/tax/register").andExpect(status().isForbidden());
        query(salesRep, "/api/tax/return?from=2026-05-01&to=2026-05-31").andExpect(status().isForbidden());
        query(planner, "/api/tax/register/export").andExpect(status().isForbidden());
    }

    private String source(String date, String explicit) {
        var r = calculate("OUTPUT", date, customer, line(1, bottle, "1", "100", explicit));
        var l = r.lines().getFirst();
        return l.source() + ":" + l.code();
    }
}
