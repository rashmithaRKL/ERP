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
import org.springframework.web.bind.annotation.RestController;

/** Parent/child manufacturing orders (V7). */
@RestController
@RequestMapping("/api")
class OrderTreeController {
    private final OrderTreeService service;
    private final OrderTreeQuery query;

    OrderTreeController(OrderTreeService service, OrderTreeQuery query) {
        this.service = service;
        this.query = query;
    }

    @PostMapping("/manufacturing/{id}/children")
    Map<String, Object> children(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ManufacturingCommands.ChildOrders c,
            @RequestHeader("Idempotency-Key") String k) {
        return service.createChildren(Actor.of(a), id, c, k);
    }

    @GetMapping("/manufacturing/{id}/tree")
    Map<String, Object> tree(Authentication a, @PathVariable String id) {
        return query.tree(Actor.of(a), id);
    }
}
