package lk.dexlanka.erp.traceability;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** V14 recalls: list/detail/notification export (every role), commands (QUALITY/ADMIN). */
@RestController
@RequestMapping("/api")
class RecallController {
    private final RecallService recalls;
    private final RecallQuery query;

    RecallController(RecallService recalls, RecallQuery query) {
        this.recalls = recalls;
        this.query = query;
    }

    @GetMapping("/recalls")
    Map<String, Object> page(
            Authentication a,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.page(Actor.of(a), status, severity, q, limit, before);
    }

    @GetMapping("/recalls/{id}")
    Map<String, Object> recall(Authentication a, @PathVariable String id) {
        return query.recall(Actor.of(a), id);
    }

    /** The customer notification list as CSV (one row per affected delivery); the download is audited. */
    @GetMapping("/recalls/{id}/notifications/export")
    void export(Authentication a, @PathVariable String id, HttpServletResponse res) throws IOException {
        var actor = Actor.of(a);
        var recall = query.recall(actor, id);
        recalls.recordExport(actor, id);
        res.setContentType("text/csv;charset=UTF-8");
        res.setHeader(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + recall.get("number") + "-notifications.csv\"");
        query.export(actor, id, res.getWriter());
    }

    @PostMapping("/recalls")
    Map<String, Object> open(
            Authentication a, @Valid @RequestBody RecallCommands.Open c, @RequestHeader("Idempotency-Key") String k) {
        return recalls.open(Actor.of(a), c, k);
    }

    @PostMapping("/recalls/{id}/refresh")
    Map<String, Object> refresh(Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return recalls.refresh(Actor.of(a), id, k);
    }

    @PostMapping("/recalls/{id}/contain")
    Map<String, Object> contain(Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return recalls.contain(Actor.of(a), id, k);
    }

    @PostMapping("/recalls/{id}/customers/{customerId}/notify")
    Map<String, Object> notify(
            Authentication a,
            @PathVariable String id,
            @PathVariable String customerId,
            @Valid @RequestBody RecallCommands.Notify c,
            @RequestHeader("Idempotency-Key") String k) {
        return recalls.notify(Actor.of(a), id, customerId, c, k);
    }

    @PostMapping("/recalls/{id}/close")
    Map<String, Object> close(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody RecallCommands.Close c,
            @RequestHeader("Idempotency-Key") String k) {
        return recalls.close(Actor.of(a), id, c, k);
    }

    @PostMapping("/recalls/{id}/cancel")
    Map<String, Object> cancel(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody RecallCommands.Cancel c,
            @RequestHeader("Idempotency-Key") String k) {
        return recalls.cancel(Actor.of(a), id, c, k);
    }
}
