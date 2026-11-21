package lk.dexlanka.erp.sales;

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

@RestController
@RequestMapping("/api")
class SalesController {
    private final SalesService sales;

    SalesController(SalesService sales) {
        this.sales = sales;
    }

    @PostMapping("/sales")
    Map<String, Object> sale(
            Authentication a, @Valid @RequestBody SalesCommands.Sale c, @RequestHeader("Idempotency-Key") String k) {
        return sales.sale(Actor.of(a), c, k);
    }

    @PostMapping("/sales/{id}/dispatch")
    Map<String, Object> dispatch(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody SalesCommands.Dispatch c,
            @RequestHeader("Idempotency-Key") String k) {
        return sales.dispatch(Actor.of(a), id, c, k);
    }
}
