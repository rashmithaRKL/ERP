package lk.dexlanka.erp.admin;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Locale;
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

/** {@code /api/admin/**}: users, approval policies, CSV imports, the audit trail and accounting periods (ADMIN). */
@RestController
@RequestMapping("/api/admin")
class AdminController {
    private final UserAdminService users;
    private final UserQuery userQuery;
    private final ApprovalPolicyService policies;
    private final ImportService imports;
    private final AuditQuery audit;
    private final PeriodService periods;

    AdminController(
            UserAdminService users,
            UserQuery userQuery,
            ApprovalPolicyService policies,
            ImportService imports,
            AuditQuery audit,
            PeriodService periods) {
        this.users = users;
        this.userQuery = userQuery;
        this.policies = policies;
        this.imports = imports;
        this.audit = audit;
        this.periods = periods;
    }

    // ------------------------------------------------------------------ users

    @GetMapping("/users")
    Map<String, Object> users(
            Authentication a,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String before) {
        return userQuery.page(Actor.of(a), q, role, status, limit, before);
    }

    @PostMapping("/users/{id}")
    Map<String, Object> updateUser(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody AdminCommands.UserUpdate c,
            @RequestHeader("Idempotency-Key") String k) {
        return users.update(Actor.of(a), id, c, k);
    }

    @PostMapping("/users/{id}/deactivate")
    Map<String, Object> deactivate(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return users.deactivate(Actor.of(a), id, k);
    }

    @PostMapping("/users/{id}/reactivate")
    Map<String, Object> reactivate(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return users.reactivate(Actor.of(a), id, k);
    }

    @PostMapping("/users/{id}/reset-password")
    Map<String, Object> resetPassword(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return users.resetPassword(Actor.of(a), id, k);
    }

    @PostMapping("/users/{id}/reset-mfa")
    Map<String, Object> resetMfa(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return users.resetMfa(Actor.of(a), id, k);
    }

    // ------------------------------------------------------------------ approval policies

    @GetMapping("/approval-policies")
    Map<String, Object> policies(Authentication a) {
        return Map.of("items", policies.policies(Actor.of(a)));
    }

    @PostMapping("/approval-policies/{documentType}")
    Map<String, Object> updatePolicy(
            Authentication a,
            @PathVariable String documentType,
            @Valid @RequestBody AdminCommands.Policy c,
            @RequestHeader("Idempotency-Key") String k) {
        return policies.update(Actor.of(a), documentType, c, k);
    }

    // ------------------------------------------------------------------ imports

    @GetMapping("/imports")
    Map<String, Object> importHistory(
            Authentication a,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String before) {
        return imports.history(Actor.of(a), limit, before);
    }

    /** Dry run: no Idempotency-Key, nothing is kept. */
    @PostMapping("/imports/{kind}/validate")
    Map<String, Object> validateImport(
            Authentication a, @PathVariable String kind, @Valid @RequestBody AdminCommands.ImportFile c) {
        return imports.preview(Actor.of(a), kind(kind), c);
    }

    @PostMapping("/imports/{kind}/commit")
    Map<String, Object> commitImport(
            Authentication a,
            @PathVariable String kind,
            @Valid @RequestBody AdminCommands.ImportFile c,
            @RequestHeader("Idempotency-Key") String k) {
        return imports.commit(Actor.of(a), kind(kind), c, k);
    }

    @GetMapping("/imports/{kind}/template")
    ResponseEntity<String> template(Authentication a, @PathVariable String kind) {
        String csv = imports.template(Actor.of(a), kind(kind));
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + kind.toLowerCase(Locale.ROOT) + "-template.csv\"")
                .body("﻿" + csv);
    }

    /** {@code opening-stock} → {@code OPENING_STOCK}. */
    private static String kind(String slug) {
        return slug.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    // ------------------------------------------------------------------ audit trail

    @GetMapping("/audit")
    Map<String, Object> audit(
            Authentication a,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String before) {
        return audit.page(Actor.of(a), new AuditQuery.Filter(actor, action, q, from, to), limit, before);
    }

    @GetMapping("/audit/facets")
    Map<String, Object> auditFacets(Authentication a) {
        return audit.facets(Actor.of(a));
    }

    /**
     * Streams the filtered audit trail as CSV (at most {@value AuditQuery#EXPORT_CAP} rows; {@code X-Export-Truncated}
     * says when the filter matched more). The export itself is audited.
     */
    @GetMapping("/audit/export")
    void exportAudit(
            Authentication a,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            HttpServletResponse res)
            throws IOException {
        var actorOf = Actor.of(a);
        var filter = new AuditQuery.Filter(actor, action, q, from, to);
        long matched = audit.count(actorOf, filter);
        audit.recordExport(actorOf, filter, matched);
        res.setContentType("text/csv;charset=UTF-8");
        res.setHeader(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"audit-trail-" + LocalDate.now(java.time.ZoneId.of(AuditQuery.ZONE)) + ".csv\"");
        res.setHeader("X-Export-Truncated", String.valueOf(matched > AuditQuery.EXPORT_CAP));
        audit.export(actorOf, filter, res.getWriter());
    }

    // ------------------------------------------------------------------ accounting periods

    @GetMapping("/periods")
    Map<String, Object> periods(Authentication a) {
        return periods.periods(Actor.of(a));
    }

    @PostMapping("/periods/close")
    Map<String, Object> closePeriod(
            Authentication a,
            @Valid @RequestBody AdminCommands.ClosePeriod c,
            @RequestHeader("Idempotency-Key") String k) {
        return periods.close(Actor.of(a), c, k);
    }

    @PostMapping("/periods/reopen")
    Map<String, Object> reopenPeriod(
            Authentication a,
            @Valid @RequestBody AdminCommands.ReopenPeriod c,
            @RequestHeader("Idempotency-Key") String k) {
        return periods.reopen(Actor.of(a), c, k);
    }
}
