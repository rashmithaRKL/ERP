package lk.dexlanka.erp.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import lk.dexlanka.erp.admin.AdminCommands;
import org.junit.jupiter.api.Test;

/**
 * V9: purchase-order approval follows the tenant's {@code PURCHASE_ORDER} approval policy, evaluated on submission with
 * the order total. The default keeps the fixed rule (every order; PLANNER or ADMIN; neither the creator nor the
 * submitter); a threshold or a switched-off policy approves on submission ("auto-approved by policy").
 */
class PurchaseApprovalPolicyTest extends ProcurementFixture {
    void policy(boolean required, String threshold, String... roles) throws Exception {
        command(
                        admin,
                        "/api/admin/approval-policies/PURCHASE_ORDER",
                        new AdminCommands.Policy(required, threshold == null ? null : bd(threshold), List.of(roles)))
                .andExpect(status().isOk());
    }

    String approvePath(String po) {
        return "/api/purchases/orders/" + po + "/approve";
    }

    List<String> actions(String po) {
        return rows(
                        "SELECT action FROM audit_events WHERE tenant_id=? AND reference=? ORDER BY created_at,id",
                        t,
                        header(po).get("number"))
                .stream()
                .map(r -> str(r, "action"))
                .toList();
    }

    @Test
    void defaultPolicyKeepsIndependentApprovalByAPlanner() throws Exception {
        String po = draft(line(resin, "2", "bag", "12000")); // Rs. 24,000
        command(planner2, "/api/purchases/orders/" + po + "/submit", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        // Creator (planner) and submitter (planner2) are both excluded; other roles are refused.
        command(planner, approvePath(po), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A different authorized person must approve this purchase"));
        command(planner2, approvePath(po), null).andExpect(status().isConflict());
        command(warehouse, approvePath(po), null).andExpect(status().isForbidden());
        command(viewer, approvePath(po), null).andExpect(status().isForbidden());
        command(admin, approvePath(po), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(header(po).get("approved_by")).isEqualTo(admin.id());
        assertThat(actions(po)).containsExactly("PO_DRAFT_CREATED", "PO_SUBMITTED", "PO_APPROVED");
    }

    @Test
    void ordersAtOrBelowTheThresholdAreApprovedOnSubmission() throws Exception {
        policy(true, "24000", "WAREHOUSE");

        String small = draft(line(resin, "2", "bag", "12000")); // exactly Rs. 24,000
        command(planner, "/api/purchases/orders/" + small + "/submit", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        var h = header(small);
        assertThat(h.get("status")).isEqualTo("APPROVED");
        assertThat(h.get("approved_by")).isNull();
        assertThat(h.get("approved_at")).isNotNull();
        assertThat(h.get("submitted_by")).isEqualTo(planner.id());
        assertThat(actions(small)).containsExactlyInAnyOrder("PO_DRAFT_CREATED", "PO_SUBMITTED", "PO_AUTO_APPROVED");
        assertThat(row(
                                "SELECT detail FROM audit_events WHERE tenant_id=? AND action='PO_AUTO_APPROVED' AND reference=?",
                                t,
                                h.get("number"))
                        .get("detail"))
                .isEqualTo("Auto-approved by policy: Rs. 24000.00 is within the Rs. 24000.00 approval threshold");
        // Goods can be received straight away.
        receive(small, receiptLine(lineId(small, 1), "25", "kg", null));
        assertThat(header(small).get("status")).isEqualTo("PARTIAL");

        String large = submitted(line(resin, "3", "bag", "12000")); // Rs. 36,000
        assertThat(header(large).get("status")).isEqualTo("SUBMITTED");
        command(planner2, approvePath(large), null).andExpect(status().isForbidden());
        command(warehouse, approvePath(large), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void switchedOffPolicyApprovesOnSubmissionAndTheOriginalContract() throws Exception {
        String waiting = submitted(line(carton, "10", null, "30"));
        policy(false, null, "PLANNER");

        String po = draft(line(resin, "40", "bag", "12000"));
        command(planner, "/api/purchases/orders/" + po + "/submit", null)
                .andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(row(
                                "SELECT detail FROM audit_events WHERE tenant_id=? AND action='PO_AUTO_APPROVED' AND reference=?",
                                t,
                                header(po).get("number"))
                        .get("detail"))
                .isEqualTo("Auto-approved by policy: no second approval required");

        // The original single-item contract (created and submitted in one step) follows the same policy.
        var legacy = json(command(
                        warehouse,
                        "/api/purchases",
                        new ProcurementCommands.Purchase(
                                supplier,
                                resin,
                                bd("25"),
                                bd("480"),
                                LocalDate.now().plusDays(3).toString()))
                .andExpect(status().isOk()));
        assertThat(legacy.get("status")).isEqualTo("APPROVED");

        // A draft submitted under the old policy still waits for an independent approver.
        assertThat(header(waiting).get("status")).isEqualTo("SUBMITTED");
        command(planner, approvePath(waiting), null).andExpect(status().isConflict());
        command(planner2, approvePath(waiting), null).andExpect(status().isOk());
    }
}
