package lk.dexlanka.erp.procurement;

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
class ProcurementController {
    private final ProcurementService procurement;

    ProcurementController(ProcurementService procurement) {
        this.procurement = procurement;
    }

    @PostMapping("/purchases")
    Map<String, Object> purchase(
            Authentication a,
            @Valid @RequestBody ProcurementCommands.Purchase c,
            @RequestHeader("Idempotency-Key") String k) {
        return procurement.purchase(Actor.of(a), c, k);
    }

    @PostMapping("/purchases/{id}/approve")
    Map<String, Object> approve(Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return procurement.approvePurchase(Actor.of(a), id, k);
    }

    @PostMapping("/purchases/{id}/receive")
    Map<String, Object> receive(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ProcurementCommands.Receive c,
            @RequestHeader("Idempotency-Key") String k) {
        return procurement.receive(Actor.of(a), id, c, k);
    }

    @PostMapping("/receipts/{id}/invoice")
    Map<String, Object> invoice(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ProcurementCommands.Invoice c,
            @RequestHeader("Idempotency-Key") String k) {
        return procurement.invoice(Actor.of(a), id, c, k);
    }
}
