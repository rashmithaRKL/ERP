package lk.dexlanka.erp.quality;

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

/** Nonconformance reports, dispositions, rework orders, the quality summary and a lot's quality side (V12). */
@RestController
@RequestMapping("/api")
class NcrController {
    private final NcrService ncrs;
    private final ReworkService rework;
    private final NcrQuery query;

    NcrController(NcrService ncrs, ReworkService rework, NcrQuery query) {
        this.ncrs = ncrs;
        this.rework = rework;
        this.query = query;
    }

    @GetMapping("/quality/ncrs")
    Map<String, Object> list(
            Authentication a,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String defect,
            @RequestParam(required = false) String item,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.page(Actor.of(a), status, severity, defect, item, source, q, limit, before);
    }

    @GetMapping("/quality/ncrs/{id}")
    Map<String, Object> ncr(Authentication a, @PathVariable String id) {
        return query.ncr(Actor.of(a), id);
    }

    @PostMapping("/quality/ncrs")
    Map<String, Object> raise(
            Authentication a, @Valid @RequestBody NcrCommands.Raise c, @RequestHeader("Idempotency-Key") String k) {
        return ncrs.raise(Actor.of(a), c, k);
    }

    @PostMapping("/quality/ncrs/{id}")
    Map<String, Object> update(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody NcrCommands.Update c,
            @RequestHeader("Idempotency-Key") String k) {
        return ncrs.update(Actor.of(a), id, c, k);
    }

    @PostMapping("/quality/ncrs/{id}/dispositions")
    Map<String, Object> disposition(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody NcrCommands.Disposition c,
            @RequestHeader("Idempotency-Key") String k) {
        return ncrs.disposition(Actor.of(a), id, c, k);
    }

    @PostMapping("/quality/ncrs/{id}/close")
    Map<String, Object> close(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody NcrCommands.Close c,
            @RequestHeader("Idempotency-Key") String k) {
        return ncrs.close(Actor.of(a), id, c, k);
    }

    @PostMapping("/quality/ncrs/{id}/cancel")
    Map<String, Object> cancel(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody NcrCommands.Cancel c,
            @RequestHeader("Idempotency-Key") String k) {
        return ncrs.cancel(Actor.of(a), id, c, k);
    }

    @PostMapping("/quality/dispositions/{id}/decision")
    Map<String, Object> decide(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody NcrCommands.Decision c,
            @RequestHeader("Idempotency-Key") String k) {
        return ncrs.decide(Actor.of(a), id, c, k);
    }

    @PostMapping("/quality/dispositions/{id}/return")
    Map<String, Object> shipReturn(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody NcrCommands.ShipReturn c,
            @RequestHeader("Idempotency-Key") String k) {
        return ncrs.shipReturn(Actor.of(a), id, c, k);
    }

    @GetMapping("/quality/rework")
    Map<String, Object> reworkList(
            Authentication a,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.reworkPage(Actor.of(a), status, q, limit, before);
    }

    @GetMapping("/quality/rework/{id}")
    Map<String, Object> reworkOrder(Authentication a, @PathVariable String id) {
        return query.rework(Actor.of(a), id);
    }

    @PostMapping("/quality/rework/{id}/complete")
    Map<String, Object> completeRework(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody NcrCommands.ReworkComplete c,
            @RequestHeader("Idempotency-Key") String k) {
        return rework.complete(Actor.of(a), id, c, k);
    }

    @GetMapping("/quality/summary")
    Map<String, Object> summary(
            Authentication a,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return query.summary(Actor.of(a), from, to);
    }

    @GetMapping("/quality/lots/{id}")
    Map<String, Object> lot(Authentication a, @PathVariable String id) {
        return query.lot(Actor.of(a), id);
    }

    @GetMapping("/quality/people")
    Map<String, Object> people(Authentication a) {
        return query.people(Actor.of(a));
    }
}
