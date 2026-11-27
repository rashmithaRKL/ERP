package lk.dexlanka.erp.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Effective-dated rate versions: boundary days, derived end dates, and the immutability of used versions. */
class TaxRatesTest extends TaxFixture {
    @Test
    void theRateEffectiveOnTheDocumentDateApplies() {
        String vat = standardCode("VAT", "BOTH", "VAT:15:2022-09-01");
        String component = component(vat, 1);
        admin.addRate(adminUser, component, new TaxCommands.Rate("2024-01-01", bd("18.0000"), "Budget 2024"), id());

        assertThat(calculate("OUTPUT", "2023-12-31", null, line(1, bottle, "1", "100", vat))
                        .tax())
                .isEqualByComparingTo("15.00");
        var first = calculate("OUTPUT", "2024-01-01", null, line(1, bottle, "1", "100", vat));
        assertThat(first.tax()).isEqualByComparingTo("18.00");
        assertThat(first.lines().getFirst().components().getFirst().rate()).isEqualByComparingTo("18");
        assertThat(first.lines().getFirst().components().getFirst().effectiveFrom())
                .hasToString("2024-01-01");
        assertThat(calculate("OUTPUT", "2022-09-01", null, line(1, bottle, "1", "100", vat))
                        .tax())
                .isEqualByComparingTo("15.00");
        // the day before the first version: no rate, the calculation is refused with a readable reason
        assertThatThrownBy(() -> calculate("OUTPUT", "2022-08-31", null, line(1, bottle, "1", "100", vat)))
                .hasMessageContaining("has no rate effective on 2022-08-31");

        // the read model derives each version's end from the next version's start
        @SuppressWarnings("unchecked")
        var code = ((List<Map<String, Object>>) query.codes(adminUser).get("items"))
                .stream().filter(c -> c.get("id").equals(vat)).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var rates = (List<Map<String, Object>>)
                ((List<Map<String, Object>>) code.get("components")).getFirst().get("rates");
        assertThat(rates).extracting(r -> String.valueOf(r.get("effective_to"))).containsExactly("null", "2023-12-31");
        assertThat(code.get("current_rate").toString()).isEqualTo("18.0000");

        // rates keep four decimals
        admin.addRate(adminUser, component, new TaxCommands.Rate("2030-01-01", bd("18.1234"), null), id());
        assertThat(calculate("OUTPUT", "2030-01-01", null, line(1, bottle, "1", "10000", vat))
                        .tax())
                .isEqualByComparingTo("1812.34");
        // a second version on the same day is refused
        assertThatThrownBy(() ->
                        admin.addRate(adminUser, component, new TaxCommands.Rate("2030-01-01", bd("19"), null), id()))
                .hasMessageContaining("already starts on 2030-01-01");
    }

    @Test
    void aRateVersionUsedByRecordedTaxIsImmutable() {
        String vat = standardCode("VAT", "BOTH", "VAT:15:2022-09-01");
        String component = component(vat, 1);
        String eighteen = str(
                admin.addRate(adminUser, component, new TaxCommands.Rate("2024-01-01", bd("18"), null), id()), "id");
        String later = str(
                admin.addRate(adminUser, component, new TaxCommands.Rate("2027-01-01", bd("20"), null), id()), "id");

        var ids =
                record("CUSTOMER_INVOICE", "INV-1", "OUTPUT", "2026-03-15", customer, line(1, bottle, "2", "50", vat));
        assertThat(ids).hasSize(1);
        var row = row("SELECT * FROM tax_lines WHERE tenant_id=? AND id=?", t, ids.getFirst());
        assertThat(row.get("rate_version_id")).isEqualTo(eighteen);
        assertThat(dec(row.get("base"))).isEqualByComparingTo("100");
        assertThat(dec(row.get("tax"))).isEqualByComparingTo("18");
        assertThat(row.get("account_code")).isEqualTo("OUTPUT_TAX");
        assertThat(row.get("partner_id")).isEqualTo(customer);

        // the used version cannot be removed or edited, not even directly in the database
        assertThatThrownBy(() -> admin.removeRate(adminUser, eighteen, id()))
                .hasMessageContaining("used by recorded tax");
        assertThatThrownBy(() -> update("UPDATE tax_rate_versions SET rate=19 WHERE tenant_id=? AND id=?", t, eighteen))
                .hasMessageContaining("used by recorded tax lines");
        assertThatThrownBy(() -> update("DELETE FROM tax_rate_versions WHERE tenant_id=? AND id=?", t, eighteen))
                .hasMessageContaining("used by recorded tax lines");
        // no new version may start on or before the last taxed document date (it would change the recorded rate)
        assertThatThrownBy(() ->
                        admin.addRate(adminUser, component, new TaxCommands.Rate("2026-03-15", bd("17"), null), id()))
                .hasMessageContaining("up to 2026-03-15");
        assertThatThrownBy(() -> update(
                        "INSERT INTO tax_rate_versions(id,tenant_id,component_id,effective_from,rate) VALUES(?,?,?,'2025-01-01',16)",
                        id(),
                        t,
                        component))
                .hasMessageContaining("a new rate must start after that date");
        // a later version that no recorded line uses can still be added and removed
        assertThat(str(
                        admin.addRate(adminUser, component, new TaxCommands.Rate("2026-03-16", bd("18.5"), null), id()),
                        "id"))
                .isNotBlank();
        assertThat(admin.removeRate(adminUser, later, id())).containsEntry("removed", true);

        // the component keeps its calculation and the code its kind; the register itself is append-only
        assertThatThrownBy(() ->
                        admin.updateComponent(adminUser, component, new TaxCommands.ComponentUpdate("VAT", true), id()))
                .hasMessageContaining("first component cannot be compound");
        assertThatThrownBy(() -> update("UPDATE tax_codes SET kind='EXEMPT' WHERE tenant_id=? AND id=?", t, vat))
                .hasMessageContaining("code and kind cannot change");
        assertThatThrownBy(() -> update("UPDATE tax_lines SET tax=0 WHERE tenant_id=? AND id=?", t, ids.getFirst()))
                .hasMessageContaining("Posted history is immutable");
        // renaming is fine
        admin.updateComponent(adminUser, component, new TaxCommands.ComponentUpdate("Value added tax", false), id());

        // one document records once
        String source = id();
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                        .executeWithoutResult(status -> {
                            var r = calculate("OUTPUT", "2026-04-01", customer, line(1, bottle, "1", "10", vat));
                            tax.record(
                                    adminUser,
                                    new TaxService.Source(
                                            "CUSTOMER_INVOICE",
                                            source,
                                            "INV-2",
                                            java.time.LocalDate.parse("2026-04-01"),
                                            r));
                            tax.record(
                                    adminUser,
                                    new TaxService.Source(
                                            "CUSTOMER_INVOICE",
                                            source,
                                            "INV-2",
                                            java.time.LocalDate.parse("2026-04-01"),
                                            r));
                        }))
                .hasMessageContaining("already recorded");
        // record() refuses to run outside a transaction
        var outside = calculate("OUTPUT", "2026-04-01", customer, line(1, bottle, "1", "10", vat));
        assertThatThrownBy(() -> tax.record(
                        adminUser,
                        new TaxService.Source(
                                "CUSTOMER_INVOICE", id(), "INV-3", java.time.LocalDate.parse("2026-04-01"), outside)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void compoundComponentsAreRecordedOneRowEach() {
        String stacked = standardCode("STACK", "SALES", "Levy:10:2020-01-01", "Surcharge:5:2020-01-01:compound");
        var r = calculate("OUTPUT", "2026-01-10", customer, line(1, bottle, "4", "250", stacked));
        assertThat(r.tax()).isEqualByComparingTo("155.00");
        var ids = record(
                "CUSTOMER_INVOICE", "INV-9", "OUTPUT", "2026-01-10", customer, line(1, bottle, "4", "250", stacked));
        assertThat(ids).hasSize(2);
        var rows = rows(
                "SELECT component_seq,base,tax FROM tax_lines WHERE tenant_id=? AND source_number='INV-9' ORDER BY component_seq",
                t);
        assertThat(dec(rows.get(0).get("base"))).isEqualByComparingTo("1000");
        assertThat(dec(rows.get(1).get("base"))).isEqualByComparingTo("1100");
        assertThat(dec(rows.get(1).get("tax"))).isEqualByComparingTo("55");
        // a component with recorded tax keeps its compound flag
        assertThatThrownBy(() -> admin.updateComponent(
                        adminUser, component(stacked, 2), new TaxCommands.ComponentUpdate("Surcharge", false), id()))
                .hasMessageContaining("used by recorded tax lines");
    }
}
