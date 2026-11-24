package lk.dexlanka.erp.reporting;

import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Multi-level material planning read model (V7). */
@RestController
@RequestMapping("/api")
class PlanningController {
    private final PlanningQuery planning;

    PlanningController(PlanningQuery planning) {
        this.planning = planning;
    }

    @GetMapping("/planning/mrp")
    Map<String, Object> mrp(Authentication a) {
        return planning.mrp(Actor.of(a));
    }
}
