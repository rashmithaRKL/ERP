package lk.dexlanka.erp.admin;

import static lk.dexlanka.erp.platform.Store.require;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Accounting period administration on {@code financial_periods}: a period ends on {@code end_date} and starts the day
 * after the previous period's end. Posting semantics are unchanged: {@code Store.openPeriod} rejects postings while a
 * closed period covers the current (database) date, and every posting is dated now, so a closed past period can no
 * longer receive postings and closing a period that ends today stops all postings for the rest of the day.
 *
 * <p>Rules: periods close in date order, only once their end date has been reached; only the most recently closed
 * period can be reopened, with a reason (audited); a reopened period must be closed again before a later one.
 */
@Service
public class PeriodService {
    private final Store s;

    PeriodService(Store s) {
        this.s = s;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> periods(Actor a) {
        a.allow();
        var items = s.list("""
                SELECT x.end_date::text AS id,x.end_date::text AS end_date,x.start_date::text AS start_date,x.closed,x.closed_at,x.reopened_at,x.reopen_reason,x.created_at,
                       cu.name AS closed_by,ru.name AS reopened_by,
                       (SELECT COUNT(*) FROM journal_entries e WHERE e.tenant_id=x.tenant_id
                          AND e.created_at::date<=x.end_date AND (x.start_date IS NULL OR e.created_at::date>=x.start_date)) AS journal_entries,
                       (SELECT COALESCE(SUM(l.debit),0) FROM journal_lines l
                          JOIN journal_entries e ON e.tenant_id=l.tenant_id AND e.id=l.entry_id
                          WHERE l.tenant_id=x.tenant_id AND e.created_at::date<=x.end_date
                            AND (x.start_date IS NULL OR e.created_at::date>=x.start_date)) AS journal_total
                FROM (SELECT p.*,lag(p.end_date) OVER (ORDER BY p.end_date)+1 AS start_date
                      FROM financial_periods p WHERE p.tenant_id=?) x
                LEFT JOIN users cu ON cu.id=x.closed_by
                LEFT JOIN users ru ON ru.id=x.reopened_by
                ORDER BY x.end_date DESC
                LIMIT 120""", a.tenant());
        var result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("today", s.db().queryForObject("SELECT CURRENT_DATE::text", String.class));
        result.put("latestClosed", latestClosed(a));
        result.put(
                "postingBlocked",
                !s.list(
                                "SELECT 1 FROM financial_periods WHERE tenant_id=? AND closed=true AND end_date>=CURRENT_DATE",
                                a.tenant())
                        .isEmpty());
        return result;
    }

    @Transactional
    public Map<String, Object> close(Actor a, AdminCommands.ClosePeriod c, String key) {
        a.allow();
        LocalDate end = date(c.endDate());
        return s.once(a, key, "close-period:" + end, c, () -> {
            LocalDate today = LocalDate.parse(s.db().queryForObject("SELECT CURRENT_DATE::text", String.class));
            require(!end.isAfter(today), "A period can be closed once its end date has been reached");
            String latest = latestClosed(a);
            require(
                    latest == null || end.isAfter(LocalDate.parse(latest)),
                    "Periods close in order: choose an end date after " + latest + ", the latest closed period");
            var reopened = s.list(
                    "SELECT end_date::text AS end_date FROM financial_periods WHERE tenant_id=? AND NOT closed AND end_date<? ORDER BY end_date LIMIT 1",
                    a.tenant(),
                    end);
            require(
                    reopened.isEmpty(),
                    "Close the reopened period ending "
                            + reopened.stream()
                                    .findFirst()
                                    .map(r -> r.get("end_date"))
                                    .orElse("")
                            + " first");
            s.db().update("""
                            INSERT INTO financial_periods(tenant_id,end_date,closed,closed_by,closed_at) VALUES(?,?,true,?,now())
                            ON CONFLICT (tenant_id,end_date) DO UPDATE SET closed=true,closed_by=EXCLUDED.closed_by,closed_at=now()""", a.tenant(), end, a.id());
            s.audit(
                    a,
                    "PERIOD_CLOSED",
                    "Period to " + end,
                    (latest == null ? "All postings" : "Postings after " + latest) + " up to " + end
                            + " are closed"
                            + (end.equals(today) ? "; no postings are accepted for the rest of today" : ""));
            return Map.of("endDate", end.toString(), "closed", true);
        });
    }

    @Transactional
    public Map<String, Object> reopen(Actor a, AdminCommands.ReopenPeriod c, String key) {
        a.allow();
        return s.once(a, key, "reopen-period", c, () -> {
            String latest = latestClosed(a);
            require(latest != null, "There is no closed period to reopen");
            s.db().update(
                            "UPDATE financial_periods SET closed=false,reopened_by=?,reopened_at=now(),reopen_reason=? WHERE tenant_id=? AND end_date=?::date",
                            a.id(),
                            c.reason().strip(),
                            a.tenant(),
                            latest);
            s.audit(a, "PERIOD_REOPENED", "Period to " + latest, c.reason().strip());
            return Map.of("endDate", latest, "closed", false);
        });
    }

    private String latestClosed(Actor a) {
        return s.db().queryForObject(
                        "SELECT MAX(end_date)::text FROM financial_periods WHERE tenant_id=? AND closed",
                        String.class,
                        a.tenant());
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a valid yyyy-MM-dd end date");
        }
    }
}
