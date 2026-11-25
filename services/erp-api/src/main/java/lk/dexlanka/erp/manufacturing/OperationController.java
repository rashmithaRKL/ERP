package lk.dexlanka.erp.manufacturing;

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

/** Manufacturing-order operations (V10): shop-floor commands, the queue per work center and time logs. */
@RestController
@RequestMapping("/api")
class OperationController {
    private final OperationService operations;
    private final OperationQuery query;

    OperationController(OperationService operations, OperationQuery query) {
        this.operations = operations;
        this.query = query;
    }

    @GetMapping("/manufacturing/{id}/operations")
    Map<String, Object> forOrder(Authentication a, @PathVariable String id) {
        return query.forOrder(Actor.of(a), id);
    }

    @GetMapping("/operations/work-centers")
    Map<String, Object> workCenters(Authentication a) {
        return query.workCenters(Actor.of(a));
    }

    @GetMapping("/operations/queue")
    Map<String, Object> queue(
            Authentication a,
            @RequestParam String machineId,
            @RequestParam(defaultValue = "24") int limit,
            @RequestParam(required = false) String before) {
        return query.queue(Actor.of(a), machineId, limit, before);
    }

    @GetMapping("/operations/time-logs")
    Map<String, Object> timeLogs(
            Authentication a,
            @RequestParam(required = false) String machineId,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String operationId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String before) {
        return query.timeLogs(Actor.of(a), machineId, orderId, operationId, limit, before);
    }

    @GetMapping("/operations/{id}")
    Map<String, Object> detail(Authentication a, @PathVariable String id) {
        return query.detail(Actor.of(a), id);
    }

    @PostMapping("/operations/{id}/start")
    Map<String, Object> start(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody OperationCommands.Start c,
            @RequestHeader("Idempotency-Key") String k) {
        return operations.start(Actor.of(a), id, c, k);
    }

    @PostMapping("/operations/{id}/pause")
    Map<String, Object> pause(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody OperationCommands.Pause c,
            @RequestHeader("Idempotency-Key") String k) {
        return operations.pause(Actor.of(a), id, c, k);
    }

    @PostMapping("/operations/{id}/resume")
    Map<String, Object> resume(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody OperationCommands.Resume c,
            @RequestHeader("Idempotency-Key") String k) {
        return operations.resume(Actor.of(a), id, c, k);
    }

    @PostMapping("/operations/{id}/setup-done")
    Map<String, Object> setupDone(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return operations.setupDone(Actor.of(a), id, k);
    }

    @PostMapping("/operations/{id}/report")
    Map<String, Object> report(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody OperationCommands.Quantities c,
            @RequestHeader("Idempotency-Key") String k) {
        return operations.report(Actor.of(a), id, c, k);
    }

    @PostMapping("/operations/{id}/finish")
    Map<String, Object> finish(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody OperationCommands.Quantities c,
            @RequestHeader("Idempotency-Key") String k) {
        return operations.finish(Actor.of(a), id, c, k);
    }
}
