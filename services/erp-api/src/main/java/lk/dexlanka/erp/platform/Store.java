package lk.dexlanka.erp.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Posting primitives shared by every domain command. Nothing here opens a transaction: call these methods from inside a
 * {@code @Transactional} domain service method so that the tenant lock, idempotency record, business rows, stock ledger,
 * journal, audit and outbox commit or roll back together.
 *
 * <ul>
 *   <li>{@link #once} - tenant lock + idempotency (validates the key, replays a stored result, rejects key reuse).
 *   <li>{@link #ledger} - the only way to change stock quantity/value (a DB trigger maintains {@code stock_balances}).
 *   <li>{@link #journal} - balanced two-line journal entry; rejects posting into a closed period. V24: every entry has a
 *       posting date (system postings: today in Asia/Colombo) and a source; the overloads post on an explicit posting
 *       date, including multi-line MANUAL and REVERSAL entries.
 *   <li>{@link #audit} - audit event plus {@code LOCAL_ONLY} outbox event.
 *   <li>{@link #require} - business-rule guard, mapped to HTTP 409; {@link #one} maps "no row" to HTTP 404.
 * </ul>
 */
@Component
public class Store {
    /** The business time zone (ADR-006): posting dates are calendar days here. */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Colombo");

    /** V24 journal entry sources: posting code, approved manual journals, reversals of posted entries. */
    public static final String SYSTEM = "SYSTEM";

    public static final String MANUAL = "MANUAL";
    public static final String REVERSAL = "REVERSAL";

    private final JdbcTemplate db;
    private final ObjectMapper json;

    Store(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /** Plain JDBC access for domain inserts/updates. Always scope statements by {@code tenant_id}. */
    public JdbcTemplate db() {
        return db;
    }

    // ------------------------------------------------------------------ value helpers

    public static String id() {
        return UUID.randomUUID().toString();
    }

    public static BigDecimal dec(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }

    public static BigDecimal money(BigDecimal v) {
        return v.setScale(6, RoundingMode.HALF_UP);
    }

    public static String str(Map<String, ?> m, String k) {
        return Objects.toString(m.get(k), "");
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    // ------------------------------------------------------------------ queries and JSON

    public List<Map<String, Object>> list(String sql, Object... args) {
        return db.queryForList(sql, args);
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = list(sql, args);
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found in your organization");
        return rows.getFirst();
    }

    public String encode(Object data) {
        try {
            return json.writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, Object> decode(String data) {
        try {
            return json.readValue(
                    data, json.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ command envelope

    /** Serializes all commands of the actor's tenant (row lock on {@code tenants}) until the transaction ends. */
    public void lock(Actor a) {
        one("SELECT id FROM tenants WHERE id=? FOR UPDATE", a.tenant());
    }

    /**
     * Runs {@code work} at most once per tenant and {@code Idempotency-Key}. Locks the tenant first. A repeat with the same
     * {@code action} and an identical {@code body} (SHA-256 of its JSON) returns the stored result without running
     * {@code work}; a repeat with a different action or body is a 409. {@code action} must be unique per command and
     * target, e.g. {@code "approve-po:" + id}.
     */
    public Map<String, Object> once(
            Actor a, String key, String action, Object body, Supplier<Map<String, Object>> work) {
        require(key != null && key.matches("[a-zA-Z0-9_-]{8,120}"), "A valid Idempotency-Key is required");
        lock(a);
        String hash;
        try {
            hash = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(encode(body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var previous = list("SELECT * FROM idempotency_keys WHERE tenant_id=? AND request_key=?", a.tenant(), key);
        if (!previous.isEmpty()) {
            var p = previous.getFirst();
            require(
                    action.equals(p.get("action")) && hash.equals(p.get("payload_hash")),
                    "Idempotency key was already used for a different request");
            return decode(p.get("response").toString());
        }
        var result = work.get();
        db.update(
                "INSERT INTO idempotency_keys(tenant_id,request_key,action,payload_hash,response) VALUES(?,?,?,?,?::jsonb)",
                a.tenant(),
                key,
                action,
                hash,
                encode(result));
        return result;
    }

    /** Human-readable document number such as {@code PO-2026-1A2B3C}. */
    public String number(Actor a, String prefix) {
        return prefix + "-" + java.time.Year.now().getValue() + "-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ audit, journal, stock

    /** Records an audit event and a matching outbox event (same transaction as the business change). */
    public void audit(Actor a, String action, String reference, String detail) {
        db.update(
                "INSERT INTO audit_events(id,tenant_id,actor,action,reference,detail) VALUES(?,?,?,?,?,?)",
                id(),
                a.tenant(),
                a.name(),
                action,
                reference,
                detail);
        db.update(
                "INSERT INTO outbox_events(id,tenant_id,event_type,reference,payload) VALUES(?,?,?,?,?::jsonb)",
                id(),
                a.tenant(),
                action,
                reference,
                encode(Map.of("actor", a.name(), "detail", detail, "version", 1)));
    }

    /** 409 when a closed financial period covers today. Call before any posting command. */
    public void openPeriod(Actor a) {
        require(
                list(
                                "SELECT 1 FROM financial_periods WHERE tenant_id=? AND closed=true AND end_date>=CURRENT_DATE",
                                a.tenant())
                        .isEmpty(),
                "The financial period is closed");
    }

    /**
     * V24: 409 when a closed financial period covers {@code postingDate} (periods close in date order, so: any closed
     * period ending on or after it). The database refuses such an entry too ({@code journal_entries_open_period}).
     */
    public void openPeriod(Actor a, LocalDate postingDate) {
        require(
                list(
                                "SELECT 1 FROM financial_periods WHERE tenant_id=? AND closed=true AND end_date>=?",
                                a.tenant(),
                                postingDate)
                        .isEmpty(),
                "The financial period covering " + postingDate + " is closed");
    }

    /** V24: today's business date (Asia/Colombo, ADR-006), the posting date of system postings. */
    public static LocalDate today() {
        return LocalDate.now(BUSINESS_ZONE);
    }

    /**
     * Posts a balanced debit/credit pair for {@code amount} (skipped when zero; negative amounts are rejected). V24: the
     * entry is dated today (the column default: the Asia/Colombo date) with source SYSTEM; the guard is unchanged (a
     * closed period covering the current date refuses it) and the database also refuses a posting date inside a closed
     * period.
     */
    public void journal(Actor a, String reference, String description, String debit, String credit, BigDecimal amount) {
        openPeriod(a);
        if (amount.signum() == 0) return;
        require(amount.signum() > 0, "Journal amount must be positive");
        String entry = id();
        db.update(
                "INSERT INTO journal_entries(id,tenant_id,reference,description,actor) VALUES(?,?,?,?,?)",
                entry,
                a.tenant(),
                reference,
                description,
                a.name());
        db.update(
                "INSERT INTO journal_lines(id,tenant_id,entry_id,account,debit,credit) VALUES(?,?,?,?,?,0),(?,?,?,?,0,?)",
                id(),
                a.tenant(),
                entry,
                debit,
                money(amount),
                id(),
                a.tenant(),
                entry,
                credit,
                money(amount));
    }

    /**
     * V24: a balanced debit/credit pair dated {@code postingDate} (source SYSTEM), for documents that carry their own
     * accounting date. Refused when a closed period covers the posting date. Returns the entry id, or null when {@code
     * amount} is zero.
     */
    public String journal(
            Actor a,
            String reference,
            String description,
            String debit,
            String credit,
            BigDecimal amount,
            LocalDate postingDate) {
        if (amount.signum() == 0) return null;
        require(amount.signum() > 0, "Journal amount must be positive");
        return journal(
                a,
                reference,
                description,
                postingDate,
                SYSTEM,
                null,
                List.of(
                        new JournalLine(debit, money(amount), BigDecimal.ZERO, null),
                        new JournalLine(credit, BigDecimal.ZERO, money(amount), null)));
    }

    /** V24: one line of a multi-line journal entry; exactly one of {@code debit} and {@code credit} is positive. */
    public record JournalLine(String account, BigDecimal debit, BigDecimal credit, String memo) {}

    /**
     * V24: posts a balanced multi-line entry dated {@code postingDate} and returns its id. {@code source} is {@link
     * #SYSTEM}, {@link #MANUAL} (an approved manual journal) or {@link #REVERSAL} (then {@code reversesEntryId} names the
     * reversed entry; the database allows one reversal per entry). Needs at least two lines, each with exactly one
     * positive side, and debits equal to credits; refused when a closed period covers the posting date. Accounts are
     * checked by the database: codes of the tenant's chart only (deferred foreign key), never inactive or header accounts.
     */
    public String journal(
            Actor a,
            String reference,
            String description,
            LocalDate postingDate,
            String source,
            String reversesEntryId,
            List<JournalLine> lines) {
        require(List.of(SYSTEM, MANUAL, REVERSAL).contains(source), "Unknown journal source");
        require(REVERSAL.equals(source) == (reversesEntryId != null), "A reversal names the entry it reverses");
        require(lines.size() >= 2, "A journal entry needs at least two lines");
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (var l : lines) {
            BigDecimal dr = l.debit() == null ? BigDecimal.ZERO : l.debit();
            BigDecimal cr = l.credit() == null ? BigDecimal.ZERO : l.credit();
            require(dr.signum() >= 0 && cr.signum() >= 0, "Journal amounts must be positive");
            require((dr.signum() == 0) != (cr.signum() == 0), "Each journal line is either a debit or a credit");
            debits = debits.add(money(dr));
            credits = credits.add(money(cr));
        }
        require(debits.compareTo(credits) == 0, "Journal entry must balance");
        openPeriod(a, postingDate);
        String entry = id();
        db.update(
                "INSERT INTO journal_entries(id,tenant_id,reference,description,actor,posting_date,source,reverses_entry_id) VALUES(?,?,?,?,?,?,?,?)",
                entry,
                a.tenant(),
                reference,
                description,
                a.name(),
                postingDate,
                source,
                reversesEntryId);
        var values = new ArrayList<String>();
        var args = new ArrayList<Object>();
        for (var l : lines) {
            values.add("(?,?,?,?,?,?,?)");
            args.add(id());
            args.add(a.tenant());
            args.add(entry);
            args.add(l.account());
            args.add(money(l.debit() == null ? BigDecimal.ZERO : l.debit()));
            args.add(money(l.credit() == null ? BigDecimal.ZERO : l.credit()));
            args.add(l.memo() == null || l.memo().isBlank() ? null : l.memo().strip());
        }
        db.update(
                "INSERT INTO journal_lines(id,tenant_id,entry_id,account,debit,credit,memo) VALUES "
                        + String.join(",", values),
                args.toArray());
        return entry;
    }

    // ------------------------------------------------------------------ input guards

    public void validDate(String date) {
        try {
            java.time.LocalDate.parse(date);
        } catch (java.time.DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a valid required-by calendar date");
        }
    }

    public void quantity(String uom, BigDecimal qty) {
        require(
                !uom.equals("pcs") || qty.stripTrailingZeros().scale() <= 0,
                "Pieces must be whole numbers; no implicit unit conversion is allowed");
    }

    /**
     * Posts a stock movement for a lot and returns its value delta. Without an explicit value a receipt is valued at the
     * lot unit cost and an issue at the balance's average value (a full depletion removes the exact remaining value).
     */
    public BigDecimal ledger(Actor a, String lot, BigDecimal delta, String reference, String reason) {
        return ledger(a, lot, delta, reference, reason, null);
    }

    public BigDecimal ledger(
            Actor a, String lot, BigDecimal delta, String reference, String reason, BigDecimal explicitValue) {
        var l = one(
                "SELECT l.*,i.uom FROM lots l JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id WHERE l.tenant_id=? AND l.id=?",
                a.tenant(),
                lot);
        quantity(str(l, "uom"), delta);
        BigDecimal value = explicitValue;
        if (value == null && delta.signum() < 0) {
            var b = one("SELECT * FROM stock_balances WHERE tenant_id=? AND lot_id=? FOR UPDATE", a.tenant(), lot);
            require(dec(b.get("quantity")).compareTo(delta.abs()) >= 0, "Insufficient stock");
            value = delta.abs().compareTo(dec(b.get("quantity"))) == 0
                    ? dec(b.get("value_amount")).negate()
                    : money(dec(b.get("value_amount"))
                            .multiply(delta)
                            .divide(dec(b.get("quantity")), 12, RoundingMode.HALF_UP));
        }
        if (value == null) value = money(delta.multiply(dec(l.get("unit_cost"))));
        db.update(
                "INSERT INTO stock_ledger(id,tenant_id,lot_id,item_id,warehouse_id,uom,delta,value_delta,unit_cost,reference,reason,actor) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id(),
                a.tenant(),
                lot,
                l.get("item_id"),
                l.get("warehouse_id"),
                l.get("uom"),
                money(delta),
                value,
                l.get("unit_cost"),
                reference,
                reason,
                a.name());
        return value;
    }
}
