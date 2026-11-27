package lk.dexlanka.erp.accounting;

import static lk.dexlanka.erp.accounting.AccountingRules.FINANCE;
import static lk.dexlanka.erp.accounting.AccountingRules.amount;
import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reversal of a posted journal entry (V24, FINANCE and ADMIN): any SYSTEM or MANUAL entry, at most once (unique index
 * {@code journal_entries_reversed_once}), never a reversal itself. The REVERSAL entry repeats the original lines with
 * debit and credit swapped, keeps the original reference, and is dated in an open period on or after the original
 * posting date (default today). Reversing a manual journal's entry marks the journal REVERSED. Audited with the reason.
 *
 * <p>Reversing a system posting corrects the ledger only: stock, documents and subledgers are not touched (use the
 * owning document's own correction when one exists, e.g. a customer or supplier return).
 */
@Service
public class ReversalService {
    private final Store s;

    ReversalService(Store s) {
        this.s = s;
    }

    @Transactional
    public Map<String, Object> reverse(Actor a, String entryId, AccountingCommands.Reverse c, String key) {
        a.allow(FINANCE);
        LocalDate requested = AccountingRules.optionalDate(c.postingDate(), "posting date");
        return s.once(a, key, "je-reverse:" + entryId, c, () -> {
            var entry =
                    s.one("SELECT * FROM journal_entries WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), entryId);
            String reference = str(entry, "reference");
            require(
                    !Store.REVERSAL.equals(entry.get("source")),
                    "Entry " + reference + " is itself a reversal; post a new manual journal instead");
            var earlier = s.list(
                    "SELECT reference,posting_date::text AS posting_date FROM journal_entries WHERE tenant_id=? AND reverses_entry_id=?",
                    a.tenant(),
                    entryId);
            require(
                    earlier.isEmpty(),
                    "Entry " + reference + " was already reversed on "
                            + earlier.stream()
                                    .findFirst()
                                    .map(r -> str(r, "posting_date"))
                                    .orElse(""));
            LocalDate original = LocalDate.parse(entry.get("posting_date").toString());
            LocalDate date = requested == null ? Store.today() : requested;
            if (requested == null && date.isBefore(original)) date = original;
            require(
                    !date.isBefore(original),
                    "A reversal cannot be dated before the entry it reverses (" + original + ")");
            var lines = new ArrayList<Store.JournalLine>();
            BigDecimal total = BigDecimal.ZERO;
            for (var l : s.list(
                    "SELECT * FROM journal_lines WHERE tenant_id=? AND entry_id=? ORDER BY debit DESC,id",
                    a.tenant(),
                    entryId)) {
                String account = str(l, "account");
                AccountingRules.postable(s, a.tenant(), account, "Cannot reverse: ");
                lines.add(new Store.JournalLine(account, dec(l.get("credit")), dec(l.get("debit")), "Reversal"));
                total = total.add(dec(l.get("debit")));
            }
            String reason = c.reason().strip();
            String reversal = s.journal(
                    a,
                    reference,
                    "Reversal of " + str(entry, "description") + " · " + reason,
                    date,
                    Store.REVERSAL,
                    entryId,
                    lines);
            int journals = s.db().update("""
                            UPDATE manual_journals SET status='REVERSED',reversal_entry_id=?,reversed_by=?,reversed_at=now(),reversal_reason=?
                            WHERE tenant_id=? AND entry_id=? AND status='POSTED'""", reversal, a.id(), reason, a.tenant(), entryId);
            s.audit(
                    a,
                    "JOURNAL_REVERSED",
                    reference,
                    (journals > 0 ? "Manual journal" : "Entry") + " of " + original + " (Rs. " + amount(total)
                            + ") reversed on " + date + ": " + reason);
            var out = new LinkedHashMap<String, Object>();
            out.put("id", reversal);
            out.put("reversesEntryId", entryId);
            out.put("reference", reference);
            out.put("postingDate", date.toString());
            out.put("total", total);
            return out;
        });
    }
}
