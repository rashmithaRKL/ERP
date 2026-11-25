package lk.dexlanka.erp.quality;

import jakarta.validation.Valid;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Inspection plans, inspection measurements and the defect-code catalogue (V12). */
@RestController
@RequestMapping("/api")
class InspectionPlanController {
    private final InspectionPlanService plans;
    private final InspectionPlanQuery query;

    InspectionPlanController(InspectionPlanService plans, InspectionPlanQuery query) {
        this.plans = plans;
        this.query = query;
    }

    @GetMapping("/quality/plans")
    Map<String, Object> plans(
            Authentication a,
            @RequestParam(defaultValue = "active") String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.page(Actor.of(a), status, type, q, limit, before);
    }

    @GetMapping("/quality/plans/{id}")
    Map<String, Object> plan(Authentication a, @PathVariable String id) {
        return query.plan(Actor.of(a), id);
    }

    @PostMapping("/quality/plans")
    Map<String, Object> create(
            Authentication a,
            @Valid @RequestBody InspectionPlanCommands.Plan c,
            @RequestHeader("Idempotency-Key") String k) {
        return plans.create(Actor.of(a), c, k);
    }

    @PostMapping("/quality/plans/{id}")
    Map<String, Object> update(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody InspectionPlanCommands.PlanUpdate c,
            @RequestHeader("Idempotency-Key") String k) {
        return plans.update(Actor.of(a), id, c, k);
    }

    @GetMapping("/quality/inspections/{id}/plan")
    Map<String, Object> inspectionPlan(Authentication a, @PathVariable String id) {
        return query.forInspection(Actor.of(a), id);
    }

    @GetMapping("/quality/inspections/{id}/measurements")
    Map<String, Object> measurements(Authentication a, @PathVariable String id) {
        return query.measurements(Actor.of(a), id);
    }

    @GetMapping("/quality/defect-codes")
    Map<String, Object> defectCodes(Authentication a) {
        return query.defectCodes(Actor.of(a));
    }

    @PostMapping("/quality/defect-codes")
    Map<String, Object> createDefectCode(
            Authentication a,
            @Valid @RequestBody InspectionPlanCommands.DefectCode c,
            @RequestHeader("Idempotency-Key") String k) {
        return plans.createDefectCode(Actor.of(a), c, k);
    }

    @PostMapping("/quality/defect-codes/{code}")
    Map<String, Object> updateDefectCode(
            Authentication a,
            @PathVariable String code,
            @Valid @RequestBody InspectionPlanCommands.DefectCode c,
            @RequestHeader("Idempotency-Key") String k) {
        return plans.updateDefectCode(Actor.of(a), code, c, k);
    }
}
