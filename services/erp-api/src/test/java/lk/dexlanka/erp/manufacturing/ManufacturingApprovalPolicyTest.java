package lk.dexlanka.erp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.MultiLevelTestBase;
import lk.dexlanka.erp.admin.AdminCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V9: manufacturing-order approval follows the tenant's {@code MANUFACTURING_ORDER} approval policy. The default
 * reproduces the fixed rule (every order, PLANNER or ADMIN, never the creator); a threshold or a switched-off policy
 * approves new orders on creation ("auto-approved by policy"); approve rights follow the policy's approver roles.
 */
class ManufacturingApprovalPolicyTest extends MultiLevelTestBase {
    String bom;

    @BeforeEach
    void product() {
        String resin = item("RM-P", "RAW_MATERIAL", "kg", "10");
        String bottle = item("FG-P", "FINISHED_GOOD", "pcs", "0");
        bom = approvedBom(bottle, "R01", "1", "1", resin, "2"); // Rs. 20 of resin per bottle
    }

    String create(String quantity) throws Exception {
        var created = json(command(
                        planner,
                        "/api/manufacturing",
                        new ManufacturingCommands.Manufacture(
                                bom,
                                machine,
                                null,
                                bd(quantity),
                                "NORMAL",
                                LocalDate.now().plusDays(5).toString()))
                .andExpect(status().isOk()));
        return str(created, "id");
    }

    void policy(boolean required, String threshold, String... roles) throws Exception {
        command(
                        admin,
                        "/api/admin/approval-policies/MANUFACTURING_ORDER",
                        new AdminCommands.Policy(required, threshold == null ? null : bd(threshold), List.of(roles)))
                .andExpect(status().isOk());
    }

    String approvePath(String order) {
        return "/api/manufacturing/" + order + "/actions/approve";
    }

    List<String> actions(String order) {
        return rows(
                        "SELECT action FROM audit_events WHERE tenant_id=? AND reference=? ORDER BY created_at,id",
                        t,
                        orderRow(order).get("number"))
                .stream()
                .map(r -> str(r, "action"))
                .toList();
    }

    @Test
    void defaultPolicyKeepsIndependentPlannerApproval() throws Exception {
        String order = create("10");
        assertThat(orderRow(order).get("status")).isEqualTo("DRAFT");
        assertThat(dec(orderRow(order).get("estimated_cost"))).isEqualByComparingTo("200");
        assertThat(actions(order)).containsExactly("MO_CREATED");

        command(warehouse, approvePath(order), null).andExpect(status().isForbidden());
        command(operator, approvePath(order), null).andExpect(status().isForbidden());
        command(planner, approvePath(order), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A different authorized person must approve this order"));
        command(otherPlanner, approvePath(order), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // ADMIN approves orders of others, never its own.
        String own = str(
                mo.create(
                        admin,
                        new ManufacturingCommands.Manufacture(
                                bom,
                                machine,
                                null,
                                bd("1"),
                                "NORMAL",
                                LocalDate.now().toString()),
                        id()),
                "id");
        command(admin, approvePath(own), null).andExpect(status().isConflict());
        command(admin, approvePath(create("1")), null).andExpect(status().isOk());
    }

    @Test
    void ordersAtOrBelowTheThresholdAreAutoApprovedOnCreation() throws Exception {
        policy(true, "200", "WAREHOUSE");

        String cheap = create("10"); // Rs. 200: at the threshold
        assertThat(orderRow(cheap).get("status")).isEqualTo("APPROVED");
        assertThat(actions(cheap)).containsExactlyInAnyOrder("MO_CREATED", "MO_AUTO_APPROVED");
        assertThat(row(
                                "SELECT detail FROM audit_events WHERE tenant_id=? AND action='MO_AUTO_APPROVED' AND reference=?",
                                t,
                                orderRow(cheap).get("number"))
                        .get("detail"))
                .isEqualTo("Auto-approved by policy: Rs. 200.00 is within the Rs. 200.00 approval threshold");
        // An auto-approved order continues normally (reservation is the next step).
        command(warehouse, approvePath(cheap), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Only a draft can be approved"));

        String large = create("11"); // Rs. 220
        assertThat(orderRow(large).get("status")).isEqualTo("DRAFT");
        // Approvers follow the policy: a planner is no longer one, the warehouse role is.
        command(otherPlanner, approvePath(large), null).andExpect(status().isForbidden());
        command(warehouse, approvePath(large), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void switchedOffPolicyApprovesEveryNewOrderButNeverStrandsAWaitingDraft() throws Exception {
        String waiting = create("5"); // created under the default policy
        policy(false, null, "PLANNER");

        String direct = create("500");
        assertThat(orderRow(direct).get("status")).isEqualTo("APPROVED");
        assertThat(row(
                                "SELECT detail FROM audit_events WHERE tenant_id=? AND action='MO_AUTO_APPROVED' AND reference=?",
                                t,
                                orderRow(direct).get("number"))
                        .get("detail"))
                .isEqualTo("Auto-approved by policy: no second approval required");

        // The older draft still needs someone other than its creator with an approver role.
        assertThat(orderRow(waiting).get("status")).isEqualTo("DRAFT");
        command(planner, approvePath(waiting), null).andExpect(status().isConflict());
        command(otherPlanner, approvePath(waiting), null).andExpect(status().isOk());
    }

    @Test
    void everyRoleReadsTheTenantsPolicies() throws Exception {
        policy(true, "1000", "PLANNER", "WAREHOUSE");
        var read = json(query(viewer, "/api/approval-policies").andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) read.get("items");
        var mo = find(items, "documentType", "MANUFACTURING_ORDER");
        assertThat(mo).containsEntry("approvalRequired", true).containsKey("amountThreshold");
        assertThat(num(mo.get("amountThreshold"))).isEqualByComparingTo("1000");
        assertThat(mo.get("approverRoles")).isEqualTo(List.of("PLANNER", "WAREHOUSE"));
        assertThat(find(items, "documentType", "PURCHASE_ORDER").get("approverRoles"))
                .isEqualTo(List.of("PLANNER"));

        // Another tenant sees its own (default) policy.
        var other = createTenant("Other policies");
        var stranger = createUser(other.id(), "OPERATOR");
        query(stranger, "/api/approval-policies")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].documentType").value("MANUFACTURING_ORDER"))
                .andExpect(jsonPath("$.items[0].amountThreshold").isEmpty())
                .andExpect(jsonPath("$.items[0].approverRoles[0]").value("PLANNER"));
    }
}
