package lk.dexlanka.erp.admin;

import static lk.dexlanka.erp.admin.ApprovalPolicyService.MANUFACTURING_ORDER;
import static lk.dexlanka.erp.admin.ApprovalPolicyService.PURCHASE_ORDER;
import static lk.dexlanka.erp.admin.ApprovalPolicyService.STOCK_ADJUSTMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.inventory.InventoryCommands;
import lk.dexlanka.erp.platform.Actor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Approval policies: built-in defaults identical to the pre-V8 rules, {@link ApprovalPolicyService#evaluate}, the
 * administration endpoints, the stock-adjustment wiring (threshold, approver roles, no threshold, not required, the
 * absolute self-approval ban) and the migration of the V5 {@code inventory_settings} threshold.
 */
class ApprovalPolicyTest extends AdminTestSupport {
    @Autowired
    ApprovalPolicyService policies;

    String resin, resinLot;
    Actor quality;

    @BeforeEach
    void stock() {
        quality = createUser(t, "QUALITY");
        resin = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,'RM','Resin','RAW_MATERIAL','kg',10,0)",
                resin,
                t);
        resinLot = openingStock(admin, resin, tenant.rawWarehouse(), "10000", "10"); // Rs. 100,000
    }

    @Test
    void defaultsReproduceTheRulesBeforePolicies() throws Exception {
        var listed = json(query(admin, "/api/admin/approval-policies").andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) listed.get("items");
        assertThat(items)
                .extracting(p -> p.get("documentType"))
                .containsExactly(MANUFACTURING_ORDER, PURCHASE_ORDER, STOCK_ADJUSTMENT);
        assertThat(items.get(0))
                .containsEntry("approvalRequired", true)
                .containsEntry("amountThreshold", null)
                .containsEntry("approverRoles", List.of("PLANNER"))
                .containsEntry("customized", false)
                .containsEntry("wired", false);
        assertThat(items.get(1))
                .containsEntry("approverRoles", List.of("PLANNER"))
                .containsEntry("wired", false);
        assertThat(items.get(2))
                .containsEntry("approverRoles", List.of("PLANNER", "WAREHOUSE"))
                .containsEntry("wired", true);
        assertThat(dec(items.get(2).get("amountThreshold"))).isEqualByComparingTo("50000");

        // evaluate(): the adjustment threshold is strict, orders always need a second person.
        assertThat(policies.evaluate(t, STOCK_ADJUSTMENT, bd("50000"), planner.id())
                        .approvalRequired())
                .isFalse();
        assertThat(policies.evaluate(t, STOCK_ADJUSTMENT, bd("50000.01"), planner.id())
                        .approvalRequired())
                .isTrue();
        var order = policies.evaluate(t, MANUFACTURING_ORDER, BigDecimal.ZERO, planner.id());
        assertThat(order.approvalRequired()).isTrue();
        assertThat(order.threshold()).isNull();
        assertThat(policies.evaluate(t, PURCHASE_ORDER, bd("1"), planner.id()).approvalRequired())
                .isTrue();

        // The self-approval ban is absolute (ADMIN included); roles outside the policy are refused.
        assertThatThrownBy(() -> order.requireApprover(planner))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("different authorized person");
        var byAdmin = policies.evaluate(t, MANUFACTURING_ORDER, BigDecimal.ONE, admin.id());
        assertThatThrownBy(() -> byAdmin.requireApprover(admin)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> order.requireApprover(warehouse)).isInstanceOf(AccessDeniedException.class);
        order.requireApprover(admin);
        assertThat(order.mayApprove(createUser(t, "PLANNER"))).isTrue();

        // The inventory settings endpoint keeps its V5 shape.
        query(warehouse, "/api/inventory/settings")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalThreshold").value(50000))
                .andExpect(jsonPath("$.approvalRequired").value(true));
    }

    @Test
    void stockAdjustmentsKeepTheirDefaultBehaviour() throws Exception {
        var small = submit(warehouse, "100"); // Rs. 1,000
        assertThat(small.get("status")).isEqualTo("POSTED");
        assertThat(dec(small.get("approval_threshold"))).isEqualByComparingTo("50000");

        var large = submit(warehouse, "6000"); // Rs. 60,000
        assertThat(large.get("status")).isEqualTo("APPROVAL_REQUIRED");
        assertThat(dec(large.get("approval_threshold"))).isEqualByComparingTo("50000");
        String id = large.get("id").toString();
        command(warehouse, "/api/inventory/adjustments/" + id + "/approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A different authorized person must approve this adjustment"));
        command(quality, "/api/inventory/adjustments/" + id + "/approve", null).andExpect(status().isForbidden());
        command(planner, "/api/inventory/adjustments/" + id + "/approve", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));
    }

    @Test
    void administratorsConfigureThresholdApproversAndRequirement() throws Exception {
        String key = id();
        var policy = new AdminCommands.Policy(true, bd("500"), List.of("QUALITY", "QUALITY"));
        command(admin, "/api/admin/approval-policies/STOCK_ADJUSTMENT", policy, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approverRoles[0]").value("QUALITY"))
                .andExpect(jsonPath("$.customized").value(true));
        command(admin, "/api/admin/approval-policies/STOCK_ADJUSTMENT", policy, key)
                .andExpect(status().isOk());
        assertThat(rows("SELECT detail FROM audit_events WHERE tenant_id=? AND action='APPROVAL_POLICY_UPDATED'", t))
                .extracting(r -> r.get("detail"))
                .containsExactly("Stock adjustments: above Rs. 500.00 · approvers Admin, Quality");

        var parked = submit(warehouse, "60"); // Rs. 600 > 500
        assertThat(parked.get("status")).isEqualTo("APPROVAL_REQUIRED");
        assertThat(dec(parked.get("approval_threshold"))).isEqualByComparingTo("500");
        String id = parked.get("id").toString();
        command(planner, "/api/inventory/adjustments/" + id + "/approve", null).andExpect(status().isForbidden());
        command(quality, "/api/inventory/adjustments/" + id + "/approve", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));

        // The Inventory page's threshold editor changes only the threshold of the same policy.
        command(admin, "/api/inventory/settings", new InventoryCommands.Settings(bd("700")))
                .andExpect(status().isOk());
        var stored = policies.policy(t, STOCK_ADJUSTMENT);
        assertThat(stored.amountThreshold()).isEqualByComparingTo("700");
        assertThat(stored.approverRoles()).containsExactly("QUALITY");
        assertThat(submit(warehouse, "60").get("status")).isEqualTo("POSTED");

        // No threshold: every adjustment needs a second person.
        command(
                        admin,
                        "/api/admin/approval-policies/STOCK_ADJUSTMENT",
                        Map.of("approvalRequired", true, "approverRoles", List.of("WAREHOUSE")))
                .andExpect(status().isOk());
        var tiny = submit(warehouse, "1");
        assertThat(tiny.get("status")).isEqualTo("APPROVAL_REQUIRED");
        assertThat(tiny.get("approval_threshold")).isNull();
        query(warehouse, "/api/inventory/settings")
                .andExpect(jsonPath("$.approvalThreshold").value(0));
        // Admin is always an approver, but never for their own adjustment.
        var own = submit(admin, "1");
        command(admin, "/api/inventory/adjustments/" + own.get("id") + "/approve", null)
                .andExpect(status().isConflict());
        command(admin, "/api/inventory/adjustments/" + tiny.get("id") + "/approve", null)
                .andExpect(status().isOk());

        // Not required: everything posts directly.
        command(
                        admin,
                        "/api/admin/approval-policies/STOCK_ADJUSTMENT",
                        new AdminCommands.Policy(false, bd("10"), List.of()))
                .andExpect(status().isOk());
        var direct = submit(warehouse, "7000");
        assertThat(direct.get("status")).isEqualTo("POSTED");
        assertThat(direct.get("approval_threshold")).isNull();
        query(warehouse, "/api/inventory/settings")
                .andExpect(jsonPath("$.approvalRequired").value(false));
    }

    @Test
    void policyCommandsAreValidatedAndAdminOnly() throws Exception {
        command(
                        admin,
                        "/api/admin/approval-policies/STOCK_ADJUSTMENT",
                        new AdminCommands.Policy(true, bd("1"), List.of("VIEWER")))
                .andExpect(status().isBadRequest());
        command(
                        admin,
                        "/api/admin/approval-policies/STOCK_ADJUSTMENT",
                        new AdminCommands.Policy(true, bd("1"), List.of("ADMIN")))
                .andExpect(status().isBadRequest());
        command(
                        admin,
                        "/api/admin/approval-policies/STOCK_ADJUSTMENT",
                        new AdminCommands.Policy(true, bd("-1"), List.of()))
                .andExpect(status().isBadRequest());
        command(admin, "/api/admin/approval-policies/SALES_ORDER", new AdminCommands.Policy(true, null, List.of()))
                .andExpect(status().isNotFound());
        command(
                        planner,
                        "/api/admin/approval-policies/PURCHASE_ORDER",
                        new AdminCommands.Policy(false, null, List.of()))
                .andExpect(status().isForbidden());
        query(planner, "/api/admin/approval-policies").andExpect(status().isForbidden());

        // Policies are per tenant.
        command(admin, "/api/admin/approval-policies/PURCHASE_ORDER", new AdminCommands.Policy(false, null, List.of()))
                .andExpect(status().isOk());
        var other = createTenant("Other");
        assertThat(policies.policy(other.id(), PURCHASE_ORDER).approvalRequired())
                .isTrue();
        assertThat(policies.policy(t, PURCHASE_ORDER).approvalRequired()).isFalse();
    }

    @Test
    void migrationCarriesEveryTenantsV5ThresholdIntoThePolicy() throws Exception {
        String schema = "admin_v8_" + id().replace("-", "");
        var folder = Files.createTempDirectory("dexlanka-v8-migration-");
        try {
            Files.writeString(folder.resolve("V4_1__tenants.sql"), """
                    INSERT INTO tenants(id,name) VALUES('t1','Custom threshold'),('t2','Default threshold');
                    INSERT INTO users(id,tenant_id,email,name,password_hash,role)
                        VALUES('u1','t1','v8-%s@example.test','Owner','x','ADMIN');
                    """.formatted(schema));
            Files.writeString(
                    folder.resolve("V5_1__threshold.sql"),
                    "UPDATE inventory_settings SET approval_threshold=1234.5,updated_by='u1' WHERE tenant_id='t1';");
            Flyway.configure()
                    .dataSource(s.db().getDataSource())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration", "filesystem:" + folder)
                    .target("8")
                    .load()
                    .migrate();
            String q = schema + ".";
            var custom = row("SELECT * FROM " + q + "approval_policies WHERE tenant_id='t1'");
            assertThat(custom.get("document_type")).isEqualTo("STOCK_ADJUSTMENT");
            assertThat(custom.get("approval_required")).isEqualTo(true);
            assertThat(dec(custom.get("amount_threshold"))).isEqualByComparingTo("1234.5");
            assertThat(custom.get("approver_roles")).isEqualTo("PLANNER,WAREHOUSE");
            assertThat(custom.get("updated_by")).isEqualTo("u1");
            var standard = row("SELECT * FROM " + q + "approval_policies WHERE tenant_id='t2'");
            assertThat(dec(standard.get("amount_threshold"))).isEqualByComparingTo("50000");
            assertThat(standard.get("updated_by")).isNull();
            assertThat(count(
                            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='inventory_settings'",
                            schema))
                    .isZero();
            var user = row("SELECT * FROM " + q + "users WHERE id='u1'");
            assertThat(user.get("session_version")).isEqualTo(0);
            assertThat(user.get("must_change_password")).isEqualTo(false);
        } finally {
            update("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            try (var files = Files.list(folder)) {
                for (var file : files.toList()) Files.delete(file);
            }
            Files.delete(folder);
        }
    }

    /** Creates and submits a one-line damage adjustment of {@code quantity} kg of resin (Rs. 10/kg). */
    private Map<String, Object> submit(Actor actor, String quantity) throws Exception {
        var line = new InventoryCommands.AdjustmentLine("DECREASE", resinLot, null, null, bd(quantity), null, null);
        var draft = json(command(
                        actor,
                        "/api/inventory/adjustments",
                        new InventoryCommands.Adjustment("DAMAGE", "Policy test", List.of(line)))
                .andExpect(status().isOk()));
        return json(command(actor, "/api/inventory/adjustments/" + draft.get("id") + "/post", null)
                .andExpect(status().isOk()));
    }
}
