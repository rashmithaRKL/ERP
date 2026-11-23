package lk.dexlanka.erp.inventory;

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

/** {@code /api/inventory/**}: warehouses, adjustments, transfers, cycle counts and the inventory read model. */
@RestController
@RequestMapping("/api/inventory")
class InventoryController {
    private final WarehouseService warehouses;
    private final AdjustmentService adjustments;
    private final TransferService transfers;
    private final CountService counts;
    private final InventoryQuery query;

    InventoryController(
            WarehouseService warehouses,
            AdjustmentService adjustments,
            TransferService transfers,
            CountService counts,
            InventoryQuery query) {
        this.warehouses = warehouses;
        this.adjustments = adjustments;
        this.transfers = transfers;
        this.counts = counts;
        this.query = query;
    }

    // ------------------------------------------------------------------ warehouses and settings

    @GetMapping("/warehouses")
    Map<String, Object> warehouses(Authentication a) {
        return query.warehouses(Actor.of(a));
    }

    @PostMapping("/warehouses")
    Map<String, Object> createWarehouse(
            Authentication a,
            @Valid @RequestBody InventoryCommands.Warehouse c,
            @RequestHeader("Idempotency-Key") String k) {
        return warehouses.create(Actor.of(a), c, k);
    }

    @PostMapping("/warehouses/{id}")
    Map<String, Object> updateWarehouse(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody InventoryCommands.WarehouseUpdate c,
            @RequestHeader("Idempotency-Key") String k) {
        return warehouses.update(Actor.of(a), id, c, k);
    }

    @GetMapping("/settings")
    Map<String, Object> settings(Authentication a) {
        return query.settings(Actor.of(a));
    }

    @PostMapping("/settings")
    Map<String, Object> updateSettings(
            Authentication a,
            @Valid @RequestBody InventoryCommands.Settings c,
            @RequestHeader("Idempotency-Key") String k) {
        return adjustments.settings(Actor.of(a), c, k);
    }

    @GetMapping("/valuation")
    Map<String, Object> valuation(Authentication a) {
        return query.valuation(Actor.of(a));
    }

    // ------------------------------------------------------------------ lots and ledger

    @GetMapping("/lots")
    Map<String, Object> lots(
            Authentication a,
            @RequestParam(required = false) String warehouseId,
            @RequestParam(required = false) String itemId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean includeEmpty,
            @RequestParam(defaultValue = "false") boolean movable,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String before) {
        return query.lots(Actor.of(a), warehouseId, itemId, status, q, includeEmpty, movable, limit, before);
    }

    @GetMapping("/lots/{id}")
    Map<String, Object> lot(Authentication a, @PathVariable String id) {
        return query.lot(Actor.of(a), id);
    }

    @GetMapping("/ledger")
    Map<String, Object> ledger(
            Authentication a,
            @RequestParam(required = false) String warehouseId,
            @RequestParam(required = false) String itemId,
            @RequestParam(required = false) String lotId,
            @RequestParam(required = false) String reference,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String before) {
        return query.ledger(Actor.of(a), warehouseId, itemId, lotId, reference, q, from, to, limit, before);
    }

    // ------------------------------------------------------------------ adjustments

    @GetMapping("/adjustments")
    Map<String, Object> adjustments(
            Authentication a,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String before) {
        return query.adjustments(Actor.of(a), status, reason, q, limit, before);
    }

    @GetMapping("/adjustments/{id}")
    Map<String, Object> adjustment(Authentication a, @PathVariable String id) {
        return query.adjustment(Actor.of(a), id);
    }

    @PostMapping("/adjustments")
    Map<String, Object> createAdjustment(
            Authentication a,
            @Valid @RequestBody InventoryCommands.Adjustment c,
            @RequestHeader("Idempotency-Key") String k) {
        return adjustments.create(Actor.of(a), c, k);
    }

    @PostMapping("/adjustments/{id}/post")
    Map<String, Object> postAdjustment(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return adjustments.post(Actor.of(a), id, k);
    }

    @PostMapping("/adjustments/{id}/approve")
    Map<String, Object> approveAdjustment(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return adjustments.approve(Actor.of(a), id, k);
    }

    @PostMapping("/adjustments/{id}/cancel")
    Map<String, Object> cancelAdjustment(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return adjustments.cancel(Actor.of(a), id, k);
    }

    // ------------------------------------------------------------------ transfers

    @GetMapping("/transfers")
    Map<String, Object> transfers(
            Authentication a,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String warehouseId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String before) {
        return query.transfers(Actor.of(a), status, warehouseId, q, limit, before);
    }

    @GetMapping("/transfers/{id}")
    Map<String, Object> transfer(Authentication a, @PathVariable String id) {
        return query.transfer(Actor.of(a), id);
    }

    @PostMapping("/transfers")
    Map<String, Object> createTransfer(
            Authentication a,
            @Valid @RequestBody InventoryCommands.Transfer c,
            @RequestHeader("Idempotency-Key") String k) {
        return transfers.create(Actor.of(a), c, k);
    }

    @PostMapping("/transfers/{id}/post")
    Map<String, Object> postTransfer(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return transfers.post(Actor.of(a), id, k);
    }

    @PostMapping("/transfers/{id}/cancel")
    Map<String, Object> cancelTransfer(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return transfers.cancel(Actor.of(a), id, k);
    }

    // ------------------------------------------------------------------ cycle counts

    @GetMapping("/counts")
    Map<String, Object> counts(
            Authentication a,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String warehouseId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String before) {
        return query.counts(Actor.of(a), status, warehouseId, q, limit, before);
    }

    @GetMapping("/counts/{id}")
    Map<String, Object> count(Authentication a, @PathVariable String id) {
        return query.count(Actor.of(a), id);
    }

    @PostMapping("/counts")
    Map<String, Object> startCount(
            Authentication a,
            @Valid @RequestBody InventoryCommands.Count c,
            @RequestHeader("Idempotency-Key") String k) {
        return counts.start(Actor.of(a), c, k);
    }

    @PostMapping("/counts/{id}/entries")
    Map<String, Object> enterCounts(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody InventoryCommands.CountEntries c,
            @RequestHeader("Idempotency-Key") String k) {
        return counts.enter(Actor.of(a), id, c, k);
    }

    @PostMapping("/counts/{id}/submit")
    Map<String, Object> submitCount(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return counts.submit(Actor.of(a), id, k);
    }

    @PostMapping("/counts/{id}/reopen")
    Map<String, Object> reopenCount(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return counts.reopen(Actor.of(a), id, k);
    }

    @PostMapping("/counts/{id}/post")
    Map<String, Object> postCount(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return counts.post(Actor.of(a), id, k);
    }

    @PostMapping("/counts/{id}/cancel")
    Map<String, Object> cancelCount(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return counts.cancel(Actor.of(a), id, k);
    }
}
