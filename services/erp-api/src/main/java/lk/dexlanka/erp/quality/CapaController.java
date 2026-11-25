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

/** Corrective and preventive actions (V12). */
@RestController
@RequestMapping("/api")
class CapaController {
    private final CapaService capas;
    private final CapaQuery query;

    CapaController(CapaService capas, CapaQuery query) {
        this.capas = capas;
        this.query = query;
    }

    @GetMapping("/quality/capas")
    Map<String, Object> list(
            Authentication a,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.page(Actor.of(a), status, q, overdue, limit, before);
    }

    @GetMapping("/quality/capas/{id}")
    Map<String, Object> capa(Authentication a, @PathVariable String id) {
        return query.capa(Actor.of(a), id);
    }

    @GetMapping("/quality/capa-actions")
    Map<String, Object> actions(
            Authentication a,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.actions(Actor.of(a), status, overdue, mine, limit, before);
    }

    @PostMapping("/quality/capas")
    Map<String, Object> create(
            Authentication a, @Valid @RequestBody CapaCommands.Capa c, @RequestHeader("Idempotency-Key") String k) {
        return capas.create(Actor.of(a), c, k);
    }

    @PostMapping("/quality/capas/{id}")
    Map<String, Object> update(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody CapaCommands.Update c,
            @RequestHeader("Idempotency-Key") String k) {
        return capas.update(Actor.of(a), id, c, k);
    }

    @PostMapping("/quality/capas/{id}/actions")
    Map<String, Object> addAction(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody CapaCommands.Action c,
            @RequestHeader("Idempotency-Key") String k) {
        return capas.addAction(Actor.of(a), id, c, k);
    }

    @PostMapping("/quality/capas/{id}/start")
    Map<String, Object> start(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody CapaCommands.Note c,
            @RequestHeader("Idempotency-Key") String k) {
        return capas.start(Actor.of(a), id, c, k);
    }

    @PostMapping("/quality/capas/{id}/submit")
    Map<String, Object> submit(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody CapaCommands.Note c,
            @RequestHeader("Idempotency-Key") String k) {
        return capas.submit(Actor.of(a), id, c, k);
    }

    @PostMapping("/quality/capas/{id}/verify")
    Map<String, Object> verify(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody CapaCommands.Verify c,
            @RequestHeader("Idempotency-Key") String k) {
        return capas.verify(Actor.of(a), id, c, k);
    }

    @PostMapping("/quality/capas/{id}/cancel")
    Map<String, Object> cancel(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody CapaCommands.Note c,
            @RequestHeader("Idempotency-Key") String k) {
        return capas.cancel(Actor.of(a), id, c, k);
    }

    @PostMapping("/quality/capa-actions/{id}/complete")
    Map<String, Object> completeAction(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody CapaCommands.Note c,
            @RequestHeader("Idempotency-Key") String k) {
        return capas.completeAction(Actor.of(a), id, c, k);
    }

    @PostMapping("/quality/capa-actions/{id}/cancel")
    Map<String, Object> cancelAction(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody CapaCommands.Note c,
            @RequestHeader("Idempotency-Key") String k) {
        return capas.cancelAction(Actor.of(a), id, c, k);
    }
}
