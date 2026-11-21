package lk.dexlanka.erp.manufacturing;

import jakarta.validation.Valid;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Manufacturing-order commands. The read side ({@code GET /api/manufacturing/{id}}) is in the reporting package. */
@RestController
@RequestMapping("/api")
class ManufacturingController {
    private final ManufacturingService mo;

    ManufacturingController(ManufacturingService mo) {
        this.mo = mo;
    }

    @PostMapping("/manufacturing")
    Map<String, Object> manufacture(
            Authentication a,
            @Valid @RequestBody ManufacturingCommands.Manufacture c,
            @RequestHeader("Idempotency-Key") String k) {
        return mo.create(Actor.of(a), c, k);
    }

    @PostMapping("/manufacturing/{id}/actions/{action}")
    Map<String, Object> transition(
            Authentication a,
            @PathVariable String id,
            @PathVariable String action,
            @RequestHeader("Idempotency-Key") String k) {
        return mo.transition(Actor.of(a), id, action, k);
    }

    @PostMapping("/manufacturing/{id}/complete")
    Map<String, Object> complete(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ManufacturingCommands.Complete c,
            @RequestHeader("Idempotency-Key") String k) {
        return mo.complete(Actor.of(a), id, c, k);
    }
}
