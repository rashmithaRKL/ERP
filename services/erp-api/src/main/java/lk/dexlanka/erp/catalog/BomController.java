package lk.dexlanka.erp.catalog;

import jakarta.validation.Valid;
import java.math.BigDecimal;
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

/** Multi-level BOM tools (V7). Create and approve stay in {@link CatalogController}. */
@RestController
@RequestMapping("/api")
class BomController {
    private final BomService boms;
    private final BomQuery query;

    BomController(BomService boms, BomQuery query) {
        this.boms = boms;
        this.query = query;
    }

    @PostMapping("/boms/{id}/copy")
    Map<String, Object> copy(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody BomCommands.Copy c,
            @RequestHeader("Idempotency-Key") String k) {
        return boms.copy(Actor.of(a), id, c, k);
    }

    @PostMapping("/boms/{id}")
    Map<String, Object> updateDraft(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody BomCommands.Draft c,
            @RequestHeader("Idempotency-Key") String k) {
        return boms.updateDraft(Actor.of(a), id, c, k);
    }

    @GetMapping("/boms/{id}/explosion")
    Map<String, Object> explosion(
            Authentication a, @PathVariable String id, @RequestParam(required = false) BigDecimal quantity) {
        return query.explode(Actor.of(a), id, quantity);
    }

    @GetMapping("/boms/explosion")
    Map<String, Object> productExplosion(
            Authentication a, @RequestParam String productId, @RequestParam(required = false) BigDecimal quantity) {
        return query.explodeProduct(Actor.of(a), productId, quantity);
    }

    @GetMapping("/boms/where-used")
    Map<String, Object> whereUsed(Authentication a, @RequestParam String itemId) {
        return query.whereUsed(Actor.of(a), itemId);
    }

    @GetMapping("/boms/compare")
    Map<String, Object> compare(Authentication a, @RequestParam String from, @RequestParam String to) {
        return query.compare(Actor.of(a), from, to);
    }

    @GetMapping("/boms/{id}/cost")
    Map<String, Object> cost(Authentication a, @PathVariable String id) {
        return query.cost(Actor.of(a), id);
    }
}
