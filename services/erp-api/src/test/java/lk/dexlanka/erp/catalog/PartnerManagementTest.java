package lk.dexlanka.erp.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.procurement.ProcurementCommands;
import lk.dexlanka.erp.procurement.ProcurementService;
import lk.dexlanka.erp.sales.SalesCommands;
import lk.dexlanka.erp.sales.SalesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

class PartnerManagementTest extends IntegrationTestBase {
    @Autowired
    PartnerService partners;

    @Autowired
    SalesService sales;

    @Autowired
    ProcurementService procurement;

    String t, customer, supplier, product, material;
    Actor admin, planner, salesRep, warehouse, viewer;

    @BeforeEach
    void fixture() {
        t = createTenant("Partner fixture").id();
        admin = createUser(t, "ADMIN");
        planner = createUser(t, "PLANNER");
        salesRep = createUser(t, "SALES");
        warehouse = createUser(t, "WAREHOUSE");
        viewer = createUser(t, "VIEWER");
        customer = id();
        supplier = id();
        product = id();
        material = id();
        update(
                "INSERT INTO partners(id,tenant_id,name,type,email) VALUES(?,?,'Acme Foods','CUSTOMER','buy@acme.test'),(?,?,'Resin Traders','SUPPLIER',NULL)",
                customer,
                t,
                supplier,
                t);
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,'FG','Bottle','FINISHED_GOOD','pcs',10,0),(?,?,'RM','Resin','RAW_MATERIAL','kg',10,0)",
                product,
                t,
                material,
                t);
    }

    static PartnerCommands.Update edit(String name, BigDecimal creditLimit, boolean active) {
        return new PartnerCommands.Update(
                name,
                "orders@acme.test",
                "+94 70 000 0000",
                "1 Billing Street",
                "2 Shipping Road",
                "TIN-1",
                45,
                creditLimit,
                active);
    }

    static List<Object> values(Object rows, String key) {
        return ((List<?>) rows)
                .stream().<Object>map(r -> ((Map<?, ?>) r).get(key)).toList();
    }

    Map<String, Object> partner(String id) {
        return row("SELECT * FROM partners WHERE tenant_id=? AND id=?", t, id);
    }

    SalesCommands.Order order() {
        return new SalesCommands.Order(
                customer,
                null,
                LocalDate.now().toString(),
                null,
                List.of(new SalesCommands.Line(null, product, bd("1"), bd("10"), null)));
    }

    @Test
    void editingAPartnerUpdatesEveryFieldAndIsAudited() throws Exception {
        command(salesRep, "/api/partners/" + customer, edit("Acme Foods (Pvt) Ltd", bd("250000"), true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Acme Foods (Pvt) Ltd"));
        var p = partner(customer);
        assertThat(p.get("email")).isEqualTo("orders@acme.test");
        assertThat(p.get("phone")).isEqualTo("+94 70 000 0000");
        assertThat(p.get("billing_address")).isEqualTo("1 Billing Street");
        assertThat(p.get("shipping_address")).isEqualTo("2 Shipping Road");
        assertThat(p.get("tax_id")).isEqualTo("TIN-1");
        assertThat(p.get("payment_days")).isEqualTo(45);
        assertThat(dec(p.get("credit_limit"))).isEqualByComparingTo("250000");
        var audit = row("SELECT * FROM audit_events WHERE tenant_id=? AND action='PARTNER_UPDATED'", t);
        assertThat(audit.get("reference")).isEqualTo("Acme Foods (Pvt) Ltd");
        assertThat(audit.get("detail"))
                .asString()
                .contains("Customer", "name", "credit limit", "payment terms", "(was Acme Foods)");
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE tenant_id=? AND event_type='PARTNER_UPDATED'", t))
                .isEqualTo(1);

        var detail = json(query(viewer, "/api/partners/" + customer).andExpect(status().isOk()));
        assertThat(((Map<?, ?>) detail.get("partner")).get("open_documents")).isEqualTo(0);
        assertThat((List<?>) detail.get("activity")).hasSize(1);
    }

    @Test
    void rolesTypesAndInputAreValidated() throws Exception {
        command(salesRep, "/api/partners/" + supplier, edit("Resin Traders", null, true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Sales role can edit customers only"));
        command(warehouse, "/api/partners/" + customer, edit("Acme", null, true))
                .andExpect(status().isConflict());
        command(warehouse, "/api/partners/" + supplier, edit("Resin Traders Ltd", null, true))
                .andExpect(status().isOk());
        command(planner, "/api/partners/" + supplier, edit("Resin Traders Ltd", bd("10"), true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Credit limits apply to customers only"));
        command(viewer, "/api/partners/" + customer, edit("Acme", null, true)).andExpect(status().isForbidden());
        var invalid = new HashMap<String, Object>(Map.of("name", "Acme", "email", "not-an-email", "paymentDays", 30));
        invalid.put("active", true);
        command(planner, "/api/partners/" + customer, invalid).andExpect(status().isBadRequest());
        command(planner, "/api/partners/" + customer, Map.of("name", "Acme", "paymentDays", 400, "active", true))
                .andExpect(status().isBadRequest());
        assertThat(partner(customer).get("name")).isEqualTo("Acme Foods");
        // Creation accepts the new optional fields too.
        var created = json(command(
                        salesRep,
                        "/api/partners",
                        new CatalogCommands.Partner(
                                "Beta Stores",
                                "CUSTOMER",
                                "",
                                30,
                                "+94 70 000 0009",
                                "Billing",
                                "Shipping",
                                "TIN-9",
                                bd("1000")))
                .andExpect(status().isOk()));
        var beta = partner(str(created, "id"));
        assertThat(beta.get("email")).isNull();
        assertThat(beta.get("tax_id")).isEqualTo("TIN-9");
        assertThat(beta.get("active")).isEqualTo(true);
        command(
                        warehouse,
                        "/api/partners",
                        new CatalogCommands.Partner("Gamma", "SUPPLIER", null, 30, null, null, null, null, bd("5")))
                .andExpect(status().isConflict());
    }

    @Test
    void inactivePartnersCannotBeUsedOnNewDocuments() throws Exception {
        String draft = str(sales.create(salesRep, order(), id()), "id");
        partners.update(salesRep, customer, edit("Acme Foods", null, false), id());
        assertThat(partner(customer).get("active")).isEqualTo(false);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='PARTNER_DEACTIVATED'", t))
                .isEqualTo(1);
        command(salesRep, "/api/sales/orders", order())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Inactive customers cannot be used on new documents"));
        command(
                        salesRep,
                        "/api/sales",
                        new SalesCommands.Sale(
                                customer,
                                product,
                                bd("1"),
                                bd("1"),
                                LocalDate.now().toString()))
                .andExpect(status().isConflict());
        assertThatThrownBy(() -> sales.confirm(salesRep, draft, id())).hasMessageContaining("Inactive customers");
        // The database refuses an inactive partner even if a code path forgot the check.
        assertThatThrownBy(() -> update(
                        "INSERT INTO sales_orders(id,tenant_id,number,customer_id,due_date) VALUES(?,?,'SO-X',?,CURRENT_DATE)",
                        id(),
                        t,
                        customer))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Inactive partners");

        partners.update(
                warehouse,
                supplier,
                new PartnerCommands.Update("Resin Traders", null, null, null, null, null, 30, null, false),
                id());
        assertThatThrownBy(() -> procurement.purchase(
                        planner,
                        new ProcurementCommands.Purchase(
                                supplier,
                                material,
                                bd("5"),
                                bd("10"),
                                LocalDate.now().toString()),
                        id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        command(
                        planner,
                        "/api/purchases",
                        new ProcurementCommands.Purchase(
                                supplier,
                                material,
                                bd("5"),
                                bd("10"),
                                LocalDate.now().toString()))
                .andExpect(status().isConflict());

        // Reactivation restores normal use.
        partners.update(planner, customer, edit("Acme Foods", null, true), id());
        assertThat(sales.confirm(salesRep, draft, id()).get("status")).isEqualTo("CONFIRMED");
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='PARTNER_REACTIVATED'", t))
                .isEqualTo(1);
    }

    @Test
    void directoryIsPagedSearchableAndFiltered() throws Exception {
        for (String name : List.of("Zeta Retail", "Delta Mart", "Kappa Foods"))
            update("INSERT INTO partners(id,tenant_id,name,type) VALUES(?,?,?,'CUSTOMER')", id(), t, name);
        update("UPDATE partners SET active=false WHERE tenant_id=? AND name='Kappa Foods'", t);
        var page1 = json(query(viewer, "/api/partners?type=CUSTOMER&limit=2").andExpect(status().isOk()));
        assertThat(values(page1.get("items"), "name")).containsExactly("Acme Foods", "Delta Mart");
        assertThat(page1.get("total")).isEqualTo(4);
        var page2 = json(query(viewer, "/api/partners?type=CUSTOMER&limit=2&before=" + page1.get("next")));
        assertThat(values(page2.get("items"), "name")).containsExactly("Kappa Foods", "Zeta Retail");
        var active = json(query(viewer, "/api/partners?type=CUSTOMER&status=active"));
        assertThat(active.get("total")).isEqualTo(3);
        var inactive = json(query(viewer, "/api/partners?status=inactive"));
        assertThat((List<?>) inactive.get("items")).hasSize(1);
        var search = json(query(viewer, "/api/partners?q=acme.test"));
        assertThat((List<?>) search.get("items")).hasSize(1);
        var suppliers = json(query(viewer, "/api/partners?type=supplier"));
        assertThat(values(suppliers.get("items"), "name")).containsExactly("Resin Traders");
        query(viewer, "/api/partners?type=EMPLOYEE").andExpect(status().isConflict());
    }

    @Test
    void editsAreIdempotentAndTenantScoped() throws Exception {
        String key = id();
        var body = edit("Acme Foods", bd("100"), true);
        partners.update(planner, customer, body, key);
        partners.update(planner, customer, body, key);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='PARTNER_UPDATED'", t))
                .isEqualTo(1);
        assertThatThrownBy(() -> partners.update(planner, customer, edit("Acme Foods", bd("200"), true), key))
                .hasMessageContaining("different request");
        assertThatThrownBy(() -> partners.update(viewer, customer, body, id()))
                .isInstanceOf(AccessDeniedException.class);
        var stranger = createUser(createTenant("Other").id(), "ADMIN");
        command(stranger, "/api/partners/" + customer, body).andExpect(status().isNotFound());
        query(stranger, "/api/partners/" + customer).andExpect(status().isNotFound());
        assertThat(json(query(stranger, "/api/partners")).get("total")).isEqualTo(0);
        assertThat(dec(partner(customer).get("credit_limit"))).isEqualByComparingTo("100");
    }
}
