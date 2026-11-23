package lk.dexlanka.erp.sales;

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

@RestController
@RequestMapping("/api")
class SalesController {
    private final SalesService sales;
    private final ReturnsService returns;
    private final SalesQuery query;

    SalesController(SalesService sales, ReturnsService returns, SalesQuery query) {
        this.sales = sales;
        this.returns = returns;
        this.query = query;
    }

    // ------------------------------------------------------------------ read model

    @GetMapping("/sales/orders")
    Map<String, Object> orders(
            Authentication a,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.page(Actor.of(a), status, q, limit, before);
    }

    @GetMapping("/sales/orders/{id}")
    Map<String, Object> order(Authentication a, @PathVariable String id) {
        return query.detail(Actor.of(a), id);
    }

    // ------------------------------------------------------------------ documents

    @PostMapping("/sales/orders")
    Map<String, Object> create(
            Authentication a, @Valid @RequestBody SalesCommands.Order c, @RequestHeader("Idempotency-Key") String k) {
        return sales.create(Actor.of(a), c, k);
    }

    @PostMapping("/sales/orders/{id}")
    Map<String, Object> update(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody SalesCommands.Order c,
            @RequestHeader("Idempotency-Key") String k) {
        return sales.update(Actor.of(a), id, c, k);
    }

    @PostMapping("/sales/orders/{id}/confirm")
    Map<String, Object> confirm(Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return sales.confirm(Actor.of(a), id, k);
    }

    @PostMapping("/sales/orders/{id}/cancel")
    Map<String, Object> cancel(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody SalesCommands.Reason c,
            @RequestHeader("Idempotency-Key") String k) {
        return sales.cancel(Actor.of(a), id, c, k);
    }

    @PostMapping("/sales/orders/{id}/close")
    Map<String, Object> close(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody SalesCommands.Reason c,
            @RequestHeader("Idempotency-Key") String k) {
        return sales.close(Actor.of(a), id, c, k);
    }

    @PostMapping("/sales/orders/{id}/lines/{lineId}/dispatch")
    Map<String, Object> dispatchLine(
            Authentication a,
            @PathVariable String id,
            @PathVariable String lineId,
            @Valid @RequestBody SalesCommands.DispatchLine c,
            @RequestHeader("Idempotency-Key") String k) {
        return sales.dispatchLine(Actor.of(a), id, lineId, c, k);
    }

    @PostMapping("/sales/deliveries/{id}/returns")
    Map<String, Object> customerReturn(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody SalesCommands.Return c,
            @RequestHeader("Idempotency-Key") String k) {
        return returns.customerReturn(Actor.of(a), id, c, k);
    }

    // ------------------------------------------------------------------ original single-item contract

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
