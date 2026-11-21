package lk.dexlanka.erp.reporting;

import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read models: the workspace snapshot and manufacturing-order details. */
@RestController
@RequestMapping("/api")
class ReportingController {
    private final StateQuery state;
    private final OrderDetailsQuery details;

    ReportingController(StateQuery state, OrderDetailsQuery details) {
        this.state = state;
        this.details = details;
    }

    @GetMapping("/state")
    Map<String, Object> state(Authentication a, @RequestParam(defaultValue = "14") int days) {
        return state.state(Actor.of(a), days);
    }

    @GetMapping("/manufacturing/{id}")
    Map<String, Object> details(Authentication a, @PathVariable String id) {
        return details.details(Actor.of(a), id);
    }
}
