package lk.dexlanka.erp.performance;

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

/** V15 OEE and utilization: read model for every role, closing days for planners. */
@RestController
@RequestMapping("/api/performance")
class PerformanceController {
    private final PerformanceQuery query;
    private final PerformanceService service;

    PerformanceController(PerformanceQuery query, PerformanceService service) {
        this.query = query;
        this.service = service;
    }

    @GetMapping("/oee")
    Map<String, Object> oee(
            Authentication a,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String machine,
            @RequestParam(required = false) String bucket) {
        return query.oee(Actor.of(a), from, to, machine, bucket);
    }

    @GetMapping("/losses")
    Map<String, Object> losses(
            Authentication a,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String machine) {
        return query.losses(Actor.of(a), from, to, machine);
    }

    @GetMapping("/days/{machineId}/{date}")
    Map<String, Object> day(Authentication a, @PathVariable String machineId, @PathVariable String date) {
        return query.day(Actor.of(a), machineId, date);
    }

    @PostMapping("/close")
    Map<String, Object> close(
            Authentication a,
            @Valid @RequestBody PerformanceCommands.Close c,
            @RequestHeader("Idempotency-Key") String k) {
        return service.close(Actor.of(a), c, k);
    }
}
