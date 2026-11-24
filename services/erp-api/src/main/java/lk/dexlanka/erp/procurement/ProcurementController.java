package lk.dexlanka.erp.procurement;

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
class ProcurementController {
    private final ProcurementService legacy;
    private final PurchaseOrderService orders;
    private final ReceivingService receiving;
    private final SupplierInvoiceService invoices;
    private final SupplierReturnService returns;
    private final ProcurementQuery query;

    ProcurementController(
            ProcurementService legacy,
            PurchaseOrderService orders,
            ReceivingService receiving,
            SupplierInvoiceService invoices,
            SupplierReturnService returns,
            ProcurementQuery query) {
        this.legacy = legacy;
        this.orders = orders;
        this.receiving = receiving;
        this.invoices = invoices;
        this.returns = returns;
        this.query = query;
    }

    // ------------------------------------------------------------------ read model

    @GetMapping("/purchases/orders")
    Map<String, Object> orders(
            Authentication a,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.page(Actor.of(a), status, q, limit, before);
    }

    @GetMapping("/purchases/orders/{id}")
    Map<String, Object> order(Authentication a, @PathVariable String id) {
        return query.detail(Actor.of(a), id);
    }

    @GetMapping("/purchases/receipts")
    Map<String, Object> receipts(
            Authentication a,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.receipts(Actor.of(a), status, q, limit, before);
    }

    @GetMapping("/purchases/invoices")
    Map<String, Object> invoices(
            Authentication a,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.invoices(Actor.of(a), status, q, limit, before);
    }

    @GetMapping("/purchases/returns")
    Map<String, Object> supplierReturns(
            Authentication a,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.returns(Actor.of(a), q, limit, before);
    }

    @GetMapping("/purchases/sources")
    Map<String, Object> sources(Authentication a, @RequestParam String items) {
        return query.sources(Actor.of(a), items);
    }

    // ------------------------------------------------------------------ purchase orders

    @PostMapping("/purchases/orders")
    Map<String, Object> create(
            Authentication a,
            @Valid @RequestBody ProcurementCommands.Order c,
            @RequestHeader("Idempotency-Key") String k) {
        return orders.create(Actor.of(a), c, k);
    }

    @PostMapping("/purchases/orders/{id}")
    Map<String, Object> update(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ProcurementCommands.Order c,
            @RequestHeader("Idempotency-Key") String k) {
        return orders.update(Actor.of(a), id, c, k);
    }

    @PostMapping("/purchases/orders/{id}/submit")
    Map<String, Object> submit(Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return orders.submit(Actor.of(a), id, k);
    }

    @PostMapping("/purchases/orders/{id}/approve")
    Map<String, Object> approveOrder(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return orders.approve(Actor.of(a), id, k);
    }

    @PostMapping("/purchases/orders/{id}/cancel")
    Map<String, Object> cancel(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ProcurementCommands.Reason c,
            @RequestHeader("Idempotency-Key") String k) {
        return orders.cancel(Actor.of(a), id, c, k);
    }

    @PostMapping("/purchases/orders/{id}/close")
    Map<String, Object> close(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ProcurementCommands.Reason c,
            @RequestHeader("Idempotency-Key") String k) {
        return orders.close(Actor.of(a), id, c, k);
    }

    @PostMapping("/purchases/orders/{id}/receipts")
    Map<String, Object> receiveLines(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ProcurementCommands.GoodsReceipt c,
            @RequestHeader("Idempotency-Key") String k) {
        return receiving.receive(Actor.of(a), id, c, k);
    }

    @PostMapping("/purchases/orders/{id}/invoices")
    Map<String, Object> matchInvoice(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ProcurementCommands.SupplierInvoice c,
            @RequestHeader("Idempotency-Key") String k) {
        return invoices.match(Actor.of(a), id, c, k);
    }

    @PostMapping("/purchases/receipts/{id}/returns")
    Map<String, Object> supplierReturn(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ProcurementCommands.SupplierReturn c,
            @RequestHeader("Idempotency-Key") String k) {
        return returns.returnToSupplier(Actor.of(a), id, c, k);
    }

    @PostMapping("/purchases/recommendations")
    Map<String, Object> fromRecommendations(
            Authentication a,
            @Valid @RequestBody ProcurementCommands.Recommendations c,
            @RequestHeader("Idempotency-Key") String k) {
        return orders.fromRecommendations(Actor.of(a), c, k);
    }

    // ------------------------------------------------------------------ original single-item contract

    @PostMapping("/purchases")
    Map<String, Object> purchase(
            Authentication a,
            @Valid @RequestBody ProcurementCommands.Purchase c,
            @RequestHeader("Idempotency-Key") String k) {
        return legacy.purchase(Actor.of(a), c, k);
    }

    @PostMapping("/purchases/{id}/approve")
    Map<String, Object> approve(Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return legacy.approvePurchase(Actor.of(a), id, k);
    }

    @PostMapping("/purchases/{id}/receive")
    Map<String, Object> receive(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ProcurementCommands.Receive c,
            @RequestHeader("Idempotency-Key") String k) {
        return legacy.receive(Actor.of(a), id, c, k);
    }

    @PostMapping("/receipts/{id}/invoice")
    Map<String, Object> invoice(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ProcurementCommands.Invoice c,
            @RequestHeader("Idempotency-Key") String k) {
        return legacy.invoice(Actor.of(a), id, c, k);
    }
}
