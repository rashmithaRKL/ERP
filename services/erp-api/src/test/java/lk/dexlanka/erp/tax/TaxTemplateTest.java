package lk.dexlanka.erp.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Country templates: the Sri Lanka rate history, idempotent and non-destructive application. */
class TaxTemplateTest extends TaxFixture {
    @Test
    void sriLankaTemplateCreatesCodesRateHistoryAndSettings() {
        var result = admin.applyTemplate(adminUser, "LK", id());
        assertThat(result.get("created")).isEqualTo(List.of("VAT-STD", "VAT-ZERO", "VAT-EXEMPT"));
        String std = str(row("SELECT id FROM tax_codes WHERE tenant_id=? AND code='VAT-STD'", t), "id");
        String exempt = str(row("SELECT id FROM tax_codes WHERE tenant_id=? AND code='VAT-EXEMPT'", t), "id");
        assertThat(row(
                        "SELECT kind,applies_to,output_account_code,input_account_code,template_regime FROM tax_codes WHERE tenant_id=? AND id=?",
                        t,
                        std))
                .containsEntry("kind", "STANDARD")
                .containsEntry("applies_to", "BOTH")
                .containsEntry("output_account_code", "OUTPUT_TAX")
                .containsEntry("input_account_code", "INPUT_TAX")
                .containsEntry("template_regime", "LK");
        assertThat(str(row("SELECT kind FROM tax_codes WHERE tenant_id=? AND code='VAT-ZERO'", t), "kind"))
                .isEqualTo("ZERO_RATED");
        var settings = row("SELECT * FROM tax_settings WHERE tenant_id=?", t);
        assertThat(settings)
                .containsEntry("regime", "LK")
                .containsEntry("rounding", "PER_DOCUMENT")
                .containsEntry("prices_include_tax", false)
                .containsEntry("default_sales_code_id", std)
                .containsEntry("default_purchase_code_id", std)
                .containsEntry("exempt_code_id", exempt);

        // the standard rate history, checked on its boundary days
        assertThat(rate("2019-11-30")).isEqualByComparingTo("15");
        assertThat(rate("2019-12-01")).isEqualByComparingTo("8");
        assertThat(rate("2022-05-31")).isEqualByComparingTo("8");
        assertThat(rate("2022-06-01")).isEqualByComparingTo("12");
        assertThat(rate("2022-09-01")).isEqualByComparingTo("15");
        assertThat(rate("2023-12-31")).isEqualByComparingTo("15");
        assertThat(rate("2024-01-01")).isEqualByComparingTo("18");

        // the template list labels the template as a starting point, not a certified product
        @SuppressWarnings("unchecked")
        var templates = (List<Map<String, Object>>) query.settings(adminUser).get("templates");
        assertThat(templates)
                .anySatisfy(tpl -> assertThat(tpl)
                        .containsEntry("code", "LK")
                        .containsEntry("applied", true)
                        .containsEntry("disclaimer", TaxRegime.DISCLAIMER));
        assertThat(TaxRegime.DISCLAIMER)
                .contains("verified by a qualified accountant")
                .contains("no certification");
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='TAX_TEMPLATE_APPLIED'", t))
                .isEqualTo(1);
    }

    @Test
    void reapplyingIsIdempotentAndNeverOverwritesEditedCodes() {
        // a code the tenant created by hand with a template code's name is never touched
        String own = plainCode("VAT-ZERO", "EXEMPT");
        String key = id();
        var first = admin.applyTemplate(adminUser, "LK", key);
        assertThat(first.get("created")).isEqualTo(List.of("VAT-STD", "VAT-EXEMPT"));
        assertThat(first.get("kept")).isEqualTo(List.of("VAT-ZERO"));
        assertThat(str(row("SELECT kind FROM tax_codes WHERE tenant_id=? AND id=?", t, own), "kind"))
                .isEqualTo("EXEMPT");
        // the same request replays the stored result
        assertThat(admin.applyTemplate(adminUser, "LK", key)).isEqualTo(first);

        long versions = count("SELECT COUNT(*) FROM tax_rate_versions WHERE tenant_id=?", t);
        var again = admin.applyTemplate(adminUser, "LK", id());
        assertThat(again.get("created")).isEqualTo(List.of());
        assertThat(again.get("unchanged")).isEqualTo(List.of("VAT-STD", "VAT-EXEMPT"));
        assertThat(count("SELECT COUNT(*) FROM tax_rate_versions WHERE tenant_id=?", t))
                .isEqualTo(versions);
        assertThat(count("SELECT COUNT(*) FROM tax_codes WHERE tenant_id=?", t)).isEqualTo(3);

        // the administrator edits the standard code: rename it and remove a historical rate; settings change too
        String std = str(row("SELECT id FROM tax_codes WHERE tenant_id=? AND code='VAT-STD'", t), "id");
        admin.updateCode(
                adminUser,
                std,
                new TaxCommands.CodeUpdate("VAT (our wording)", "BOTH", "OUTPUT_TAX", "INPUT_TAX", true),
                id());
        String eight = str(
                row(
                        "SELECT v.id FROM tax_rate_versions v JOIN tax_components c ON c.tenant_id=v.tenant_id AND c.id=v.component_id WHERE v.tenant_id=? AND c.tax_code_id=? AND v.effective_from='2019-12-01'",
                        t,
                        std),
                "id");
        admin.removeRate(adminUser, eight, id());
        settings(true, "PER_LINE", std, std, null);

        var third = admin.applyTemplate(adminUser, "LK", id());
        assertThat(third.get("kept")).isEqualTo(List.of("VAT-STD", "VAT-ZERO"));
        assertThat(str(row("SELECT name FROM tax_codes WHERE tenant_id=? AND id=?", t, std), "name"))
                .isEqualTo("VAT (our wording)");
        assertThat(count("SELECT COUNT(*) FROM tax_rate_versions WHERE tenant_id=? AND id=?", t, eight))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM tax_rate_versions WHERE tenant_id=?", t))
                .isEqualTo(versions - 1);
        // chosen settings stay; an unset exemption code is filled in from the template
        var settings = row("SELECT * FROM tax_settings WHERE tenant_id=?", t);
        assertThat(settings).containsEntry("rounding", "PER_LINE").containsEntry("prices_include_tax", true);
        assertThat(settings.get("exempt_code_id")).isNotNull();
    }

    @Test
    void unknownTemplatesAndOtherRolesAreRefused() throws Exception {
        assertThatThrownBy(() -> admin.applyTemplate(adminUser, "XX", id()))
                .hasMessageContaining("Unknown tax template");
        assertThatThrownBy(() -> admin.applyTemplate(planner, "LK", id())).hasMessageContaining("Your role cannot");
        command(salesRep, "/api/tax/templates/LK/apply", null)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isForbidden());
        assertThat(count("SELECT COUNT(*) FROM tax_codes WHERE tenant_id=?", t)).isZero();
    }

    private java.math.BigDecimal rate(String date) {
        return calculate("OUTPUT", date, null, line(1, bottle, "1", "100", null))
                .lines()
                .getFirst()
                .components()
                .getFirst()
                .rate();
    }
}
