package lk.dexlanka.erp.catalog;

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
class CatalogController {
    private final CatalogService catalog;

    CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping("/users")
    Map<String, Object> user(
            Authentication a, @Valid @RequestBody CatalogCommands.User c, @RequestHeader("Idempotency-Key") String k) {
        return catalog.user(Actor.of(a), c, k);
    }

    @PostMapping("/boms")
    Map<String, Object> bom(
            Authentication a, @Valid @RequestBody CatalogCommands.Bom c, @RequestHeader("Idempotency-Key") String k) {
        return catalog.bom(Actor.of(a), c, k);
    }

    @PostMapping("/boms/{id}/approve")
    Map<String, Object> approveBom(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return catalog.approveBom(Actor.of(a), id, k);
    }

    @PostMapping("/partners")
    Map<String, Object> partner(
            Authentication a,
            @Valid @RequestBody CatalogCommands.Partner c,
            @RequestHeader("Idempotency-Key") String k) {
        return catalog.partner(Actor.of(a), c, k);
    }

    @PostMapping("/machines")
    Map<String, Object> machine(
            Authentication a,
            @Valid @RequestBody CatalogCommands.Machine c,
            @RequestHeader("Idempotency-Key") String k) {
        return catalog.machine(Actor.of(a), c, k);
    }

    @PostMapping("/organization")
    Map<String, Object> organization(
            Authentication a,
            @Valid @RequestBody CatalogCommands.Organization c,
            @RequestHeader("Idempotency-Key") String k) {
        return catalog.organization(Actor.of(a), c, k);
    }

    @PostMapping("/items")
    Map<String, Object> item(
            Authentication a, @Valid @RequestBody CatalogCommands.Item c, @RequestHeader("Idempotency-Key") String k) {
        return catalog.item(Actor.of(a), c, k);
    }
}
