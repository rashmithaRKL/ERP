package lk.dexlanka.erp.admin;

import static lk.dexlanka.erp.platform.Store.require;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The tenant's audit trail for administrators: keyset-paged ({@code created_at DESC, id DESC}) with filters, the
 * distinct actors and action codes for the filter pickers, and a streamed CSV export of the same filter.
 *
 * <p>Date filters are calendar days in {@value #ZONE} (the deployment's business time zone), inclusive on both ends.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AuditQuery {
    static final String ZONE = "Asia/Colombo";
    /** Rows written by one export at most; the response says when the filter matched more. */
    public static final int EXPORT_CAP = 50_000;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Store s;
    private final JdbcTemplate streaming;

    AuditQuery(Store s, DataSource dataSource) {
        this.s = s;
        // A separate template with a fetch size: inside the read-only transaction (autocommit off) the PostgreSQL
        // driver then reads the export through a cursor instead of loading every row.
        this.streaming = new JdbcTemplate(dataSource);
        this.streaming.setFetchSize(500);
    }

    /** Validated audit filters; every field is optional. */
    public record Filter(String actor, String action, String q, String from, String to) {
        Filter normalized() {
            String f = Queries.blankToNull(from);
            String t = Queries.blankToNull(to);
            LocalDate fromDate = date(f, "from");
            LocalDate toDate = date(t, "to");
            require(
                    fromDate == null || toDate == null || !toDate.isBefore(fromDate),
                    "The end date must be on or after the start date");
            return new Filter(Queries.blankToNull(actor), Queries.blankToNull(action), Queries.like(q), f, t);
        }

        private static LocalDate date(String value, String name) {
            if (value == null) return null;
            try {
                return LocalDate.parse(value);
            } catch (DateTimeException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a yyyy-MM-dd date for '" + name + "'");
            }
        }
    }

    private static final String WHERE = """
             WHERE e.tenant_id=?
              AND (?::text IS NULL OR e.actor=?)
              AND (?::text IS NULL OR e.action=?)
              AND (?::text IS NULL OR e.reference ILIKE ? OR e.detail ILIKE ?)
              AND (?::date IS NULL OR e.created_at >= (?::date)::timestamp AT TIME ZONE '%1$s')
              AND (?::date IS NULL OR e.created_at < ((?::date) + 1)::timestamp AT TIME ZONE '%1$s')
            """.formatted(ZONE);

    private static List<Object> args(Actor a, Filter f) {
        var args = new ArrayList<Object>();
        args.add(a.tenant());
        args.add(f.actor());
        args.add(f.actor());
        args.add(f.action());
        args.add(f.action());
        args.add(f.q());
        args.add(f.q());
        args.add(f.q());
        args.add(f.from());
        args.add(f.from());
        args.add(f.to());
        args.add(f.to());
        return args;
    }

    /** {@code {items, next}}; {@code q} matches the reference (document number, email…) or the detail text. */
    public Map<String, Object> page(Actor a, Filter filter, int limit, String before) {
        a.allow();
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
        var f = filter.normalized();
        String cursor = Queries.blankToNull(before);
        var args = args(a, f);
        args.add(cursor);
        args.add(cursor);
        args.add(limit + 1);
        var rows = s.list(
                "SELECT e.id,e.actor,e.action,e.reference,e.detail,e.created_at FROM audit_events e" + WHERE + """
                  AND (?::text IS NULL OR (e.created_at,e.id) <
                       (SELECT c.created_at,c.id FROM audit_events c WHERE c.tenant_id=e.tenant_id AND c.id=?))
                ORDER BY e.created_at DESC,e.id DESC
                LIMIT ?""",
                args.toArray());
        return Queries.page(rows, limit);
    }

    /** Distinct actors and action codes of the tenant's audit trail (filter pickers), at most 500 each. */
    public Map<String, Object> facets(Actor a) {
        a.allow();
        var result = new LinkedHashMap<String, Object>();
        result.put(
                "actors",
                s.db().queryForList(
                                "SELECT DISTINCT actor FROM audit_events WHERE tenant_id=? ORDER BY actor LIMIT 500",
                                String.class,
                                a.tenant()));
        result.put(
                "actions",
                s.db().queryForList(
                                "SELECT DISTINCT action FROM audit_events WHERE tenant_id=? ORDER BY action LIMIT 500",
                                String.class,
                                a.tenant()));
        return result;
    }

    /** How many events match {@code filter} (counted up to {@link #EXPORT_CAP} + 1). */
    public long count(Actor a, Filter filter) {
        a.allow();
        var args = args(a, filter.normalized());
        args.add(EXPORT_CAP + 1);
        return s.db().queryForObject(
                        "SELECT COUNT(*) FROM (SELECT 1 FROM audit_events e" + WHERE + " LIMIT ?) x",
                        Long.class,
                        args.toArray());
    }

    /**
     * Writes the matching events as CSV (newest first, at most {@link #EXPORT_CAP} rows) to {@code out}. Cells that a
     * spreadsheet would evaluate as a formula are prefixed with an apostrophe.
     */
    public void export(Actor a, Filter filter, Writer out) {
        a.allow();
        var args = args(a, filter.normalized());
        args.add(EXPORT_CAP);
        try {
            out.write("﻿");
            out.write("Date and time (" + ZONE + "),Actor,Action,Reference,Detail\r\n");
            var zone = ZoneId.of(ZONE);
            streaming.query(
                    "SELECT e.actor,e.action,e.reference,e.detail,e.created_at FROM audit_events e" + WHERE
                            + " ORDER BY e.created_at DESC,e.id DESC LIMIT ?",
                    (RowCallbackHandler) rs -> {
                        try {
                            var at = rs.getObject("created_at", OffsetDateTime.class);
                            out.write(cell(at.atZoneSameInstant(zone).format(TIMESTAMP)));
                            out.write(',');
                            out.write(cell(rs.getString("actor")));
                            out.write(',');
                            out.write(cell(rs.getString("action")));
                            out.write(',');
                            out.write(cell(rs.getString("reference")));
                            out.write(',');
                            out.write(cell(rs.getString("detail")));
                            out.write("\r\n");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    },
                    args.toArray());
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Exports of the audit trail are themselves audited (who downloaded which filter, how many rows). */
    @Transactional
    public void recordExport(Actor a, Filter filter, long rows) {
        a.allow();
        var parts = new ArrayList<String>();
        if (Queries.blankToNull(filter.actor()) != null)
            parts.add("actor " + filter.actor().trim());
        if (Queries.blankToNull(filter.action()) != null)
            parts.add("action " + filter.action().trim());
        if (Queries.blankToNull(filter.q()) != null)
            parts.add("text \"" + filter.q().trim() + "\"");
        if (Queries.blankToNull(filter.from()) != null)
            parts.add("from " + filter.from().trim());
        if (Queries.blankToNull(filter.to()) != null)
            parts.add("to " + filter.to().trim());
        s.audit(
                a,
                "AUDIT_EXPORTED",
                "AUDIT_TRAIL",
                Math.min(rows, EXPORT_CAP) + (rows > EXPORT_CAP ? " (capped)" : "") + " events"
                        + (parts.isEmpty() ? "" : " · " + String.join(", ", parts)));
    }

    static String cell(String value) {
        String raw = value == null ? "" : value;
        if (!raw.isEmpty() && "=+-@\t\r".indexOf(raw.charAt(0)) >= 0) raw = "'" + raw;
        return "\"" + raw.replace("\"", "\"\"") + "\"";
    }
}
