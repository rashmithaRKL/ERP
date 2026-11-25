package lk.dexlanka.erp.routing;

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

/** Routing revisions (V10): list, detail, compare, draft authoring, approval, copy and the current revision. */
@RestController
@RequestMapping("/api")
class RoutingController {
    private final RoutingService routings;
    private final RoutingQuery query;

    RoutingController(RoutingService routings, RoutingQuery query) {
        this.routings = routings;
        this.query = query;
    }

    @GetMapping("/routings")
    Map<String, Object> page(
            Authentication a,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.page(Actor.of(a), status, productId, q, limit, before);
    }

    @GetMapping("/routings/compare")
    Map<String, Object> compare(Authentication a, @RequestParam String from, @RequestParam String to) {
        return query.compare(Actor.of(a), from, to);
    }

    @GetMapping("/routings/{id}")
    Map<String, Object> detail(Authentication a, @PathVariable String id) {
        return query.detail(Actor.of(a), id);
    }

    @PostMapping("/routings")
    Map<String, Object> create(
            Authentication a,
            @Valid @RequestBody RoutingCommands.Routing c,
            @RequestHeader("Idempotency-Key") String k) {
        return routings.create(Actor.of(a), c, k);
    }

    @PostMapping("/routings/{id}")
    Map<String, Object> updateDraft(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody RoutingCommands.Draft c,
            @RequestHeader("Idempotency-Key") String k) {
        return routings.updateDraft(Actor.of(a), id, c, k);
    }

    @PostMapping("/routings/{id}/approve")
    Map<String, Object> approve(Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return routings.approve(Actor.of(a), id, k);
    }

    @PostMapping("/routings/{id}/copy")
    Map<String, Object> copy(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody RoutingCommands.Copy c,
            @RequestHeader("Idempotency-Key") String k) {
        return routings.copy(Actor.of(a), id, c, k);
    }

    @PostMapping("/routings/{id}/make-current")
    Map<String, Object> makeCurrent(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return routings.makeCurrent(Actor.of(a), id, k);
    }
}
