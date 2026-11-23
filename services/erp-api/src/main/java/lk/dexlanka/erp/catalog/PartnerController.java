package lk.dexlanka.erp.catalog;

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

/** Partner directory and maintenance. Creation ({@code POST /api/partners}) stays in {@link CatalogController}. */
@RestController
@RequestMapping("/api")
class PartnerController {
    private final PartnerService partners;
    private final PartnerQuery query;

    PartnerController(PartnerService partners, PartnerQuery query) {
        this.partners = partners;
        this.query = query;
    }

    @GetMapping("/partners")
    Map<String, Object> page(
            Authentication a,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.page(Actor.of(a), type, q, status, limit, before);
    }

    @GetMapping("/partners/{id}")
    Map<String, Object> detail(Authentication a, @PathVariable String id) {
        return query.detail(Actor.of(a), id);
    }

    @PostMapping("/partners/{id}")
    Map<String, Object> update(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody PartnerCommands.Update c,
            @RequestHeader("Idempotency-Key") String k) {
        return partners.update(Actor.of(a), id, c, k);
    }
}
