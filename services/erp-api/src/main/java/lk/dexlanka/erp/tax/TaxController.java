package lk.dexlanka.erp.tax;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** V25 taxes: codes, rates, settings, templates, partner/item tax profiles, estimates, register and VAT return. */
@RestController
@RequestMapping("/api/tax")
class TaxController {
    private final TaxAdminService admin;
    private final TaxQuery query;
    private final TaxService tax;

    TaxController(TaxAdminService admin, TaxQuery query, TaxService tax) {
        this.admin = admin;
        this.query = query;
        this.tax = tax;
    }

    // ------------------------------------------------------------------ reads (every role)

    @GetMapping("/codes")
    Map<String, Object> codes(Authentication a) {
        return query.codes(Actor.of(a));
    }

    @GetMapping("/settings")
    Map<String, Object> settings(Authentication a) {
        return query.settings(Actor.of(a));
    }

    @GetMapping("/partners/{id}")
    Map<String, Object> partner(Authentication a, @PathVariable String id) {
        return query.partner(Actor.of(a), id);
    }

    @GetMapping("/items/{id}")
    Map<String, Object> item(Authentication a, @PathVariable String id) {
        return query.item(Actor.of(a), id);
    }

    @GetMapping("/estimates/sales-orders/{id}")
    Map<String, Object> salesEstimate(
            Authentication a, @PathVariable String id, @RequestParam(required = false) String date) {
        return query.salesEstimate(Actor.of(a), id, date);
    }

    @GetMapping("/estimates/purchase-orders/{id}")
    Map<String, Object> purchaseEstimate(
            Authentication a, @PathVariable String id, @RequestParam(required = false) String date) {
        return query.purchaseEstimate(Actor.of(a), id, date);
    }

    /** Read-only preview: nothing is stored (the Idempotency-Key header is accepted and ignored). */
    @PostMapping("/calculate")
    TaxService.Result calculate(Authentication a, @Valid @RequestBody TaxCommands.Calculate c) {
        var lines = c.lines().stream()
                .map(l -> new TaxService.LineInput(
                        l.lineNo(), l.itemId(), l.quantity(), l.unitPrice(), l.discountPct(), l.taxCodeId()))
                .toList();
        return tax.calculate(
                Actor.of(a),
                new TaxService.Document(
                        c.direction(),
                        TaxAdminService.date(c.date()),
                        c.partnerId() == null || c.partnerId().isBlank() ? null : c.partnerId(),
                        c.pricesIncludeTax(),
                        lines));
    }

    // ------------------------------------------------------------------ register and return (administrators)

    @GetMapping("/register")
    Map<String, Object> register(
            Authentication a,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String before) {
        return query.register(Actor.of(a), new TaxQuery.Filter(from, to, direction, code, q), limit, before);
    }

    @GetMapping("/register/export")
    ResponseEntity<String> exportRegister(
            Authentication a,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String q) {
        String csv = query.registerCsv(Actor.of(a), new TaxQuery.Filter(from, to, direction, code, q));
        return csv(csv, "tax-register-" + LocalDate.now(TaxService.ZONE) + ".csv");
    }

    @GetMapping("/return")
    Map<String, Object> taxReturn(Authentication a, @RequestParam String from, @RequestParam String to) {
        return query.taxReturn(Actor.of(a), from, to);
    }

    @GetMapping("/return/export")
    ResponseEntity<String> exportReturn(Authentication a, @RequestParam String from, @RequestParam String to) {
        return csv(query.returnCsv(Actor.of(a), from, to), "vat-return-" + from + "-to-" + to + ".csv");
    }

    private static ResponseEntity<String> csv(String body, String name) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(body);
    }

    // ------------------------------------------------------------------ commands (administrators)

    @PostMapping("/codes")
    Map<String, Object> createCode(
            Authentication a, @Valid @RequestBody TaxCommands.Code c, @RequestHeader("Idempotency-Key") String k) {
        return admin.createCode(Actor.of(a), c, k);
    }

    @PostMapping("/codes/{id}")
    Map<String, Object> updateCode(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody TaxCommands.CodeUpdate c,
            @RequestHeader("Idempotency-Key") String k) {
        return admin.updateCode(Actor.of(a), id, c, k);
    }

    @PostMapping("/codes/{id}/components")
    Map<String, Object> addComponent(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody TaxCommands.Component c,
            @RequestHeader("Idempotency-Key") String k) {
        return admin.addComponent(Actor.of(a), id, c, k);
    }

    @PostMapping("/components/{id}")
    Map<String, Object> updateComponent(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody TaxCommands.ComponentUpdate c,
            @RequestHeader("Idempotency-Key") String k) {
        return admin.updateComponent(Actor.of(a), id, c, k);
    }

    @PostMapping("/components/{id}/rates")
    Map<String, Object> addRate(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody TaxCommands.Rate c,
            @RequestHeader("Idempotency-Key") String k) {
        return admin.addRate(Actor.of(a), id, c, k);
    }

    @PostMapping("/rates/{id}/remove")
    Map<String, Object> removeRate(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return admin.removeRate(Actor.of(a), id, k);
    }

    @PostMapping("/settings")
    Map<String, Object> updateSettings(
            Authentication a, @Valid @RequestBody TaxCommands.Settings c, @RequestHeader("Idempotency-Key") String k) {
        return admin.updateSettings(Actor.of(a), c, k);
    }

    @PostMapping("/templates/{regime}/apply")
    Map<String, Object> applyTemplate(
            Authentication a, @PathVariable String regime, @RequestHeader("Idempotency-Key") String k) {
        return admin.applyTemplate(Actor.of(a), regime, k);
    }

    @PostMapping("/partners/{id}")
    Map<String, Object> updatePartner(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody TaxCommands.PartnerTax c,
            @RequestHeader("Idempotency-Key") String k) {
        return admin.updatePartnerTax(Actor.of(a), id, c, k);
    }

    @PostMapping("/items/{id}")
    Map<String, Object> updateItem(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody TaxCommands.ItemTax c,
            @RequestHeader("Idempotency-Key") String k) {
        return admin.updateItemTax(Actor.of(a), id, c, k);
    }
}
