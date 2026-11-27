package lk.dexlanka.erp.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.procurement.ProcurementCommands;
import lk.dexlanka.erp.procurement.PurchaseOrderService;
import lk.dexlanka.erp.sales.SalesCommands;
import lk.dexlanka.erp.sales.SalesService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Read-only estimates on sales and purchase orders, permissions, cross-tenant denial and idempotency. */
class TaxEstimateAndAccessTest extends TaxFixture {
    @Autowired
    SalesService sales;

    @Autowired
    PurchaseOrderService purchases;

    @Test
    void salesAndPurchaseOrdersShowAnEstimateWithoutChangingTheirTotals() throws Exception {
        String so = str(
                sales.create(
                        salesRep,
                        new SalesCommands.Order(
                                customer,
                                null,
                                LocalDate.now().plusDays(5).toString(),
                                "",
                                List.of(
                                        new SalesCommands.Line(null, bottle, bd("100"), bd("25"), bd("10")),
                                        new SalesCommands.Line(null, bottle, bd("3"), bd("0.10"), null))),
                        id()),
                "id");
        var before = row("SELECT total,subtotal,updated_at FROM sales_orders WHERE tenant_id=? AND id=?", t, so);

        // no tax codes yet: the estimate says so instead of failing
        var unconfigured =
                json(query(salesRep, "/api/tax/estimates/sales-orders/" + so).andExpect(status().isOk()));
        assertThat(unconfigured.get("configured")).isEqualTo(false);

        admin.applyTemplate(adminUser, "LK", id());
        var estimate = json(query(salesRep, "/api/tax/estimates/sales-orders/" + so + "?date=2026-02-01")
                .andExpect(status().isOk()));
        assertThat(estimate.get("available")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        var e = (Map<String, Object>) estimate.get("estimate");
        // 100 x 25 x 90 % = 2250.00 and 0.30: 18 % per document = 405.054 -> 405.05
        assertThat(dec(e.get("net"))).isEqualByComparingTo("2250.30");
        assertThat(dec(e.get("tax"))).isEqualByComparingTo("405.05");
        assertThat(dec(e.get("gross"))).isEqualByComparingTo("2655.35");
        assertThat(e.get("direction")).isEqualTo("OUTPUT");
        // before 2016-11-01 the template has no rate: shown as unavailable with the reason
        var old = json(query(salesRep, "/api/tax/estimates/sales-orders/" + so + "?date=2015-01-01")
                .andExpect(status().isOk()));
        assertThat(old.get("available")).isEqualTo(false);
        assertThat(String.valueOf(old.get("message"))).contains("no rate effective on 2015-01-01");

        // the order and its journals are untouched; nothing is recorded
        assertThat(row("SELECT total,subtotal,updated_at FROM sales_orders WHERE tenant_id=? AND id=?", t, so))
                .isEqualTo(before);
        assertThat(count("SELECT COUNT(*) FROM tax_lines WHERE tenant_id=?", t)).isZero();
        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE tenant_id=?", t))
                .isZero();

        // purchase order: an unregistered supplier is flagged
        String po = str(
                purchases.create(
                        planner,
                        new ProcurementCommands.Order(
                                supplier,
                                null,
                                LocalDate.now().plusDays(7).toString(),
                                "",
                                List.of(new ProcurementCommands.OrderLine(
                                        null, resin, bd("200"), null, bd("4.5"), null))),
                        id()),
                "id");
        var purchase = json(query(viewer, "/api/tax/estimates/purchase-orders/" + po + "?date=2026-02-01")
                .andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var p = (Map<String, Object>) purchase.get("estimate");
        assertThat(p.get("direction")).isEqualTo("INPUT");
        assertThat(dec(p.get("tax"))).isEqualByComparingTo("162.00");
        assertThat(p.get("warnings").toString()).contains("not recorded as tax-registered");
        assertThat(count("SELECT COUNT(*) FROM tax_lines WHERE tenant_id=?", t)).isZero();
    }

    @Test
    void onlyAdministratorsChangeTaxDataAndTenantsAreIsolated() throws Exception {
        var body = new TaxCommands.Code(
                "VAT",
                "VAT",
                "BOTH",
                "STANDARD",
                null,
                null,
                List.of(new TaxCommands.Component("VAT", false, "2024-01-01", bd("18"))));
        for (var actor : List.of(salesRep, planner, viewer))
            command(actor, "/api/tax/codes", body).andExpect(status().isForbidden());
        command(salesRep, "/api/tax/partners/" + customer, new TaxCommands.PartnerTax(true, null, null, null, null))
                .andExpect(status().isForbidden());
        command(planner, "/api/tax/items/" + bottle, new TaxCommands.ItemTax(null, null))
                .andExpect(status().isForbidden());
        command(viewer, "/api/tax/settings", new TaxCommands.Settings(false, "PER_LINE", null, null, null))
                .andExpect(status().isForbidden());

        // every role can read codes and settings (document screens show tax)
        query(viewer, "/api/tax/codes").andExpect(status().isOk());
        query(salesRep, "/api/tax/settings").andExpect(status().isOk());
        query(planner, "/api/tax/partners/" + customer).andExpect(status().isOk());
        query(viewer, "/api/tax/items/" + bottle).andExpect(status().isOk());

        // idempotent create: the same key and body replay, another body with that key is refused
        String key = id();
        var created = json(command(adminUser, "/api/tax/codes", body, key).andExpect(status().isOk()));
        var replay = json(command(adminUser, "/api/tax/codes", body, key).andExpect(status().isOk()));
        assertThat(replay.get("id")).isEqualTo(created.get("id"));
        var other = new TaxCommands.Code("VAT2", "VAT", "BOTH", "EXEMPT", null, null, null);
        command(adminUser, "/api/tax/codes", other, key).andExpect(status().isConflict());
        command(adminUser, "/api/tax/codes", body).andExpect(status().isConflict()); // duplicate code
        command(adminUser, "/api/tax/codes", new TaxCommands.Code("lower", "x", "BOTH", "EXEMPT", null, null, null))
                .andExpect(status().isBadRequest());
        command(
                        adminUser,
                        "/api/tax/codes",
                        new TaxCommands.Code("NOCOMP", "x", "BOTH", "STANDARD", null, null, List.of()))
                .andExpect(status().isConflict());
        command(
                        adminUser,
                        "/api/tax/codes",
                        new TaxCommands.Code(
                                "ZR",
                                "x",
                                "BOTH",
                                "ZERO_RATED",
                                null,
                                null,
                                List.of(new TaxCommands.Component("VAT", false, "2024-01-01", bd("0")))))
                .andExpect(status().isConflict());
        String vat = str(created, "id");
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='TAX_CODE_CREATED'", t))
                .isEqualTo(1);

        // another tenant's administrator cannot see or touch this tenant's codes, partners, items or orders
        var foreign = createTenant("Other tax tenant");
        var otherAdmin = createUser(foreign.id(), "ADMIN");
        command(
                        otherAdmin,
                        "/api/tax/codes/" + vat,
                        new TaxCommands.CodeUpdate("x", "BOTH", "OUTPUT_TAX", "INPUT_TAX", true))
                .andExpect(status().isNotFound());
        command(
                        otherAdmin,
                        "/api/tax/components/" + component(vat, 1) + "/rates",
                        new TaxCommands.Rate("2030-01-01", bd("20"), null))
                .andExpect(status().isNotFound());
        command(otherAdmin, "/api/tax/partners/" + customer, new TaxCommands.PartnerTax(true, null, null, null, null))
                .andExpect(status().isNotFound());
        command(otherAdmin, "/api/tax/items/" + bottle, new TaxCommands.ItemTax(null, null))
                .andExpect(status().isNotFound());
        query(otherAdmin, "/api/tax/partners/" + customer).andExpect(status().isNotFound());
        query(otherAdmin, "/api/tax/estimates/sales-orders/" + id()).andExpect(status().isNotFound());
        // another tenant's code cannot be set as a default or used on a line
        var foreignCode = str(
                admin.createCode(
                        otherAdmin, new TaxCommands.Code("THEIRS", "x", "BOTH", "EXEMPT", null, null, null), id()),
                "id");
        assertThatThrownBy(
                        () -> admin.updateItemTax(adminUser, bottle, new TaxCommands.ItemTax(foreignCode, null), id()))
                .hasMessageContaining("Record not found");
        assertThatThrownBy(() -> calculate("OUTPUT", "2026-01-01", null, line(1, bottle, "1", "1", foreignCode)))
                .hasMessageContaining("Record not found");
        assertThatThrownBy(() -> tax.calculate(
                        otherAdmin,
                        new TaxService.Document("OUTPUT", LocalDate.parse("2026-01-01"), customer, null, List.of())))
                .hasMessageContaining("Record not found");
        @SuppressWarnings("unchecked")
        var theirs = (List<Map<String, Object>>) query.codes(otherAdmin).get("items");
        assertThat(theirs).extracting(c -> c.get("code")).containsExactly("THEIRS");

        // the preview endpoint calculates without storing anything
        var preview = json(command(
                        salesRep,
                        "/api/tax/calculate",
                        new TaxCommands.Calculate(
                                "OUTPUT",
                                "2026-01-01",
                                customer,
                                null,
                                List.of(new TaxCommands.CalculateLine(1, bottle, bd("1"), bd("100"), null, vat))))
                .andExpect(status().isOk()));
        assertThat(dec(preview.get("tax"))).isEqualByComparingTo("18.00");
    }
}
