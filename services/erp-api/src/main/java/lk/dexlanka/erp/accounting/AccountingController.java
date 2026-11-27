package lk.dexlanka.erp.accounting;

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

/**
 * {@code /api/accounting/**} (V24, FINANCE and ADMIN): chart of accounts, account ledger, trial balance, manual journals,
 * posted entries and their reversal.
 */
@RestController
@RequestMapping("/api/accounting")
class AccountingController {
    private final AccountingQuery query;
    private final ChartService chart;
    private final ManualJournalService journals;
    private final ReversalService reversals;

    AccountingController(
            AccountingQuery query, ChartService chart, ManualJournalService journals, ReversalService reversals) {
        this.query = query;
        this.chart = chart;
        this.journals = journals;
        this.reversals = reversals;
    }

    // ------------------------------------------------------------------ chart of accounts

    @GetMapping("/accounts")
    Map<String, Object> accounts(Authentication a, @RequestParam(required = false) String asOf) {
        return query.accounts(Actor.of(a), asOf);
    }

    @PostMapping("/accounts")
    Map<String, Object> createAccount(
            Authentication a,
            @Valid @RequestBody AccountingCommands.Account c,
            @RequestHeader("Idempotency-Key") String k) {
        return chart.create(Actor.of(a), c, k);
    }

    @PostMapping("/accounts/{id}")
    Map<String, Object> updateAccount(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody AccountingCommands.Account c,
            @RequestHeader("Idempotency-Key") String k) {
        return chart.update(Actor.of(a), id, c, k);
    }

    @PostMapping("/accounts/{id}/deactivate")
    Map<String, Object> deactivate(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return chart.deactivate(Actor.of(a), id, k);
    }

    @PostMapping("/accounts/{id}/reactivate")
    Map<String, Object> reactivate(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return chart.reactivate(Actor.of(a), id, k);
    }

    @GetMapping("/accounts/{id}/ledger")
    Map<String, Object> ledger(
            Authentication a,
            @PathVariable String id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String before) {
        return query.ledger(Actor.of(a), id, from, to, limit, before);
    }

    // ------------------------------------------------------------------ trial balance

    @GetMapping("/trial-balance")
    Map<String, Object> trialBalance(
            Authentication a,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean zero) {
        return query.trialBalance(Actor.of(a), from, to, zero);
    }

    // ------------------------------------------------------------------ manual journals

    @GetMapping("/journals")
    Map<String, Object> journals(
            Authentication a,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.journals(Actor.of(a), status, q, limit, before);
    }

    @GetMapping("/journals/{id}")
    Map<String, Object> journal(Authentication a, @PathVariable String id) {
        return query.journal(Actor.of(a), id);
    }

    @GetMapping("/policy")
    Map<String, Object> policy(Authentication a) {
        return query.policy(Actor.of(a));
    }

    @PostMapping("/journals")
    Map<String, Object> createJournal(
            Authentication a,
            @Valid @RequestBody AccountingCommands.Journal c,
            @RequestHeader("Idempotency-Key") String k) {
        return journals.create(Actor.of(a), c, k);
    }

    @PostMapping("/journals/{id}")
    Map<String, Object> updateJournal(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody AccountingCommands.Journal c,
            @RequestHeader("Idempotency-Key") String k) {
        return journals.update(Actor.of(a), id, c, k);
    }

    @PostMapping("/journals/{id}/submit")
    Map<String, Object> submit(Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return journals.submit(Actor.of(a), id, k);
    }

    @PostMapping("/journals/{id}/approve")
    Map<String, Object> approve(Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return journals.approve(Actor.of(a), id, k);
    }

    @PostMapping("/journals/{id}/return")
    Map<String, Object> returnToDraft(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody AccountingCommands.Reason c,
            @RequestHeader("Idempotency-Key") String k) {
        return journals.returnToDraft(Actor.of(a), id, c, k);
    }

    @PostMapping("/journals/{id}/cancel")
    Map<String, Object> cancel(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody AccountingCommands.Reason c,
            @RequestHeader("Idempotency-Key") String k) {
        return journals.cancel(Actor.of(a), id, c, k);
    }

    // ------------------------------------------------------------------ posted entries

    @GetMapping("/entries/{id}")
    Map<String, Object> entry(Authentication a, @PathVariable String id) {
        return query.entry(Actor.of(a), id);
    }

    @PostMapping("/entries/{id}/reverse")
    Map<String, Object> reverse(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody AccountingCommands.Reverse c,
            @RequestHeader("Idempotency-Key") String k) {
        return reversals.reverse(Actor.of(a), id, c, k);
    }
}
