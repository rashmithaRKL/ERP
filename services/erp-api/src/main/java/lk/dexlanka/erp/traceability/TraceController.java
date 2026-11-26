package lk.dexlanka.erp.traceability;

import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** V14 genealogy explorer: entry-point search and backward/forward traces (every role). */
@RestController
@RequestMapping("/api")
class TraceController {
    private final TraceService trace;

    TraceController(TraceService trace) {
        this.trace = trace;
    }

    @GetMapping("/traceability/search")
    Map<String, Object> search(Authentication a, @RequestParam(required = false) String q) {
        return trace.search(Actor.of(a), q);
    }

    @GetMapping("/traceability/trace")
    Map<String, Object> trace(
            Authentication a,
            @RequestParam(defaultValue = "LOT") String type,
            @RequestParam String id,
            @RequestParam(defaultValue = "BOTH") String direction,
            @RequestParam(required = false) Integer depth,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return trace.trace(Actor.of(a), type, id, direction, depth, from, to);
    }
}
