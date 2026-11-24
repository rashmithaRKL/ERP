package lk.dexlanka.erp.admin;

import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/approval-policies}: the approval rules every signed-in user may read (V9), so manufacturing,
 * purchasing and inventory screens offer approve buttons to the policy's approver roles. Editing stays under {@code
 * /api/admin/approval-policies} (ADMIN).
 */
@RestController
@RequestMapping("/api")
class ApprovalPolicyController {
    private final ApprovalPolicyService policies;

    ApprovalPolicyController(ApprovalPolicyService policies) {
        this.policies = policies;
    }

    @GetMapping("/approval-policies")
    Map<String, Object> policies(Authentication a) {
        return Map.of("items", policies.summaries(Actor.of(a)));
    }
}
