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

/** Inspection decisions and the paged inspection list (V9). */
@RestController
@RequestMapping("/api")
class QualityController {
    private final QualityService quality;
    private final InspectionQuery inspections;

    QualityController(QualityService quality, InspectionQuery inspections) {
        this.quality = quality;
        this.inspections = inspections;
    }

    @GetMapping("/quality/inspections")
    Map<String, Object> inspections(
            Authentication a,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return inspections.page(Actor.of(a), status, type, q, limit, before);
    }

    @PostMapping("/quality/{id}/inspect")
    Map<String, Object> inspect(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody QualityCommands.Inspect c,
            @RequestHeader("Idempotency-Key") String k) {
        return quality.inspect(Actor.of(a), id, c, k);
    }
}
