package lk.dexlanka.erp.scheduling;

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

/** {@code /api/schedule/**}: finite-capacity schedule runs, pins, estimates and the published schedule (V13). */
@RestController
@RequestMapping("/api/schedule")
class ScheduleController {
    private final ScheduleService service;
    private final ScheduleQuery query;

    ScheduleController(ScheduleService service, ScheduleQuery query) {
        this.service = service;
        this.query = query;
    }

    @GetMapping
    Map<String, Object> overview(Authentication a) {
        return query.overview(Actor.of(a));
    }

    @GetMapping("/runs")
    Map<String, Object> runs(
            Authentication a,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.runs(Actor.of(a), status, limit, before);
    }

    @GetMapping("/runs/{id}")
    Map<String, Object> run(Authentication a, @PathVariable String id) {
        return query.detail(Actor.of(a), id);
    }

    @GetMapping("/runs/{id}/gantt")
    Map<String, Object> gantt(
            Authentication a,
            @PathVariable String id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return query.gantt(Actor.of(a), id, from, to);
    }

    @GetMapping("/runs/{id}/late")
    Map<String, Object> late(Authentication a, @PathVariable String id) {
        return query.late(Actor.of(a), id);
    }

    @GetMapping("/runs/{id}/capacity")
    Map<String, Object> capacity(
            Authentication a,
            @PathVariable String id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return query.capacity(Actor.of(a), id, from, to);
    }

    @GetMapping("/runs/{id}/compare")
    Map<String, Object> compare(
            Authentication a, @PathVariable String id, @RequestParam(required = false) String with) {
        return query.compare(Actor.of(a), id, with);
    }

    @GetMapping("/next")
    Map<String, Object> next(
            Authentication a, @RequestParam String machineId, @RequestParam(defaultValue = "10") int limit) {
        return query.next(Actor.of(a), machineId, limit);
    }

    @GetMapping("/orders/{id}")
    Map<String, Object> order(Authentication a, @PathVariable String id) {
        return query.order(Actor.of(a), id);
    }

    @GetMapping("/pins")
    Map<String, Object> pins(Authentication a) {
        return query.pins(Actor.of(a));
    }

    @PostMapping("/runs")
    Map<String, Object> createRun(
            Authentication a, @Valid @RequestBody ScheduleCommands.Run c, @RequestHeader("Idempotency-Key") String k) {
        return service.run(Actor.of(a), c, k);
    }

    @PostMapping("/runs/{id}/publish")
    Map<String, Object> publish(Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return service.publish(Actor.of(a), id, k);
    }

    @PostMapping("/runs/{id}/discard")
    Map<String, Object> discard(Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return service.discard(Actor.of(a), id, k);
    }

    @PostMapping("/pins")
    Map<String, Object> pin(
            Authentication a, @Valid @RequestBody ScheduleCommands.Pin c, @RequestHeader("Idempotency-Key") String k) {
        return service.pin(Actor.of(a), c, k);
    }

    @PostMapping("/pins/{id}/remove")
    Map<String, Object> unpin(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ScheduleCommands.Unpin c,
            @RequestHeader("Idempotency-Key") String k) {
        return service.unpin(Actor.of(a), id, c, k);
    }

    @PostMapping("/estimates")
    Map<String, Object> estimate(
            Authentication a,
            @Valid @RequestBody ScheduleCommands.Estimate c,
            @RequestHeader("Idempotency-Key") String k) {
        return service.estimate(Actor.of(a), c, k);
    }

    @PostMapping("/estimates/{orderId}/remove")
    Map<String, Object> removeEstimate(
            Authentication a, @PathVariable String orderId, @RequestHeader("Idempotency-Key") String k) {
        return service.removeEstimate(Actor.of(a), orderId, k);
    }
}
