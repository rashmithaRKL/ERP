package lk.dexlanka.erp.maintenance;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lookups and rules shared by the maintenance commands. Deliberately not {@code @Transactional}: every method runs inside
 * the calling command's transaction, after its tenant lock.
 *
 * <ul>
 *   <li>Machine status: this package owns every transition into and out of {@code MAINTENANCE} (starting and completing
 *       a work order) and the planners' manual corrections; each change is recorded in {@code machine_status_changes}.
 *   <li>Downtime: effective events (OPEN, CLOSED) never overlap per machine (DB trigger); open events are closed, closed
 *       events are only corrected (SUPERSEDED + a new CLOSED or VOID event).
 *   <li>GL: spare parts are expensed to {@value #EXPENSE_ACCOUNT} from the item's inventory account (finished goods
 *       {@code FG_INVENTORY}, every other type including {@code SPARE_PART} {@code RAW_INVENTORY}); labour is expensed
 *       against {@value #LABOUR_CLEARING} at the tenant's labour rate (default {@link #DEFAULT_LABOUR_RATE}).
 * </ul>
 */
@Component
class MaintenanceRules {
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Colombo");
    static final String EXPENSE_ACCOUNT = "MAINTENANCE_EXPENSE";
    static final String LABOUR_CLEARING = "MAINTENANCE_LABOUR_CLEARING";
    static final BigDecimal DEFAULT_LABOUR_RATE = new BigDecimal("1500");
    /** Work orders that still hold the machine's maintenance attention. */
    static final Set<String> OPEN_ORDER = Set.of("REQUESTED", "APPROVED", "SCHEDULED", "IN_PROGRESS");
    /** Client clocks may run slightly ahead of the server. */
    private static final Duration CLOCK_TOLERANCE = Duration.ofMinutes(5);

    private final Store s;
    private final ObjectMapper json;

    MaintenanceRules(Store s, ObjectMapper json) {
        this.s = s;
        this.json = json;
    }

    // ------------------------------------------------------------------ time

    static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    static LocalDate today() {
        return LocalDate.now(BUSINESS_ZONE);
    }

    /**
     * An ISO-8601 date-time ({@code 2026-11-25T08:30:00+05:30}, {@code …Z}); without an offset it is read as Asia/Colombo
     * local time. Null for a blank value; 400 for anything else.
     */
    static OffsetDateTime time(String value, String label) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value.strip()).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value.strip())
                        .atZone(BUSINESS_ZONE)
                        .toOffsetDateTime()
                        .withOffsetSameInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Use a valid date and time for the " + label);
            }
        }
    }

    static OffsetDateTime timeOrNow(String value, String label) {
        var t = time(value, label);
        return t == null ? now() : t;
    }

    /** 409 unless {@code t} is not in the future (downtime is recorded as it happens or afterwards). */
    static void requireNotFuture(OffsetDateTime t, String what) {
        require(!t.isAfter(now().plus(CLOCK_TOLERANCE)), what + " cannot be in the future");
    }

    static LocalDate date(String value, String label) {
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a valid calendar date for the " + label);
        }
    }

    static long minutes(Object start, Object end) {
        return Duration.between(instant(start), instant(end)).toMinutes();
    }

    static java.time.Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp t) return t.toInstant();
        if (value instanceof OffsetDateTime o) return o.toInstant();
        return OffsetDateTime.parse(value.toString()).toInstant();
    }

    // ------------------------------------------------------------------ lookups

    /** A tenant-owned machine, locked for the rest of the command (404 when it is not ours). */
    Map<String, Object> machine(Actor a, String id) {
        return s.one("SELECT * FROM machines WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
    }

    Map<String, Object> workOrder(Actor a, String id) {
        return s.one("SELECT * FROM maintenance_work_orders WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
    }

    Map<String, Object> downtime(Actor a, String id) {
        return s.one("SELECT * FROM downtime_events WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
    }

    /** The machine's open downtime event, or null. */
    Map<String, Object> openDowntime(Actor a, String machineId) {
        var rows = s.list(
                "SELECT * FROM downtime_events WHERE tenant_id=? AND machine_id=? AND status='OPEN' FOR UPDATE",
                a.tenant(),
                machineId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Numbers of manufacturing orders currently running on the machine. */
    List<String> runningOrders(Actor a, String machineId) {
        return s
                .list(
                        "SELECT number FROM manufacturing_orders WHERE tenant_id=? AND machine_id=? AND status IN ('IN_PROGRESS','PARTIALLY_COMPLETED') ORDER BY number",
                        a.tenant(),
                        machineId)
                .stream()
                .map(r -> str(r, "number"))
                .toList();
    }

    /** An active user of this tenant who may carry out maintenance work (MAINTENANCE or ADMIN). */
    Map<String, Object> technician(Actor a, String userId) {
        var u = s.one(
                "SELECT id,name,role,active FROM users WHERE tenant_id=? AND id=?", a.tenant(), userId.strip());
        require(Boolean.TRUE.equals(u.get("active")), u.get("name") + " is deactivated");
        require(
                Set.of("MAINTENANCE", "ADMIN").contains(str(u, "role")),
                u.get("name") + " is not a maintenance technician");
        return u;
    }

    BigDecimal labourRate(String tenant) {
        var rows = s.list("SELECT labour_rate FROM maintenance_settings WHERE tenant_id=?", tenant);
        return rows.isEmpty() ? DEFAULT_LABOUR_RATE : dec(rows.getFirst().get("labour_rate"));
    }

    BigDecimal lastMeterReading(Actor a, String machineId) {
        var rows = s.list(
                "SELECT reading FROM machine_meter_readings WHERE tenant_id=? AND machine_id=? ORDER BY recorded_at DESC,id DESC LIMIT 1",
                a.tenant(),
                machineId);
        return rows.isEmpty() ? null : dec(rows.getFirst().get("reading"));
    }

    /** A document number such as {@code MWO-2026-1A2B3C}, unique in {@code table}. */
    String number(Actor a, String table, String prefix) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = s.number(a, prefix);
            if (s.list("SELECT 1 FROM " + table + " WHERE tenant_id=? AND number=?", a.tenant(), candidate)
                    .isEmpty()) return candidate;
        }
        throw new IllegalStateException("Could not allocate a unique document number");
    }

    // ------------------------------------------------------------------ downtime

    /**
     * Whether downtime of {@code reason} is planned: BREAKDOWN is always unplanned, PLANNED_MAINTENANCE always planned,
     * CHANGEOVER planned unless stated otherwise, every other reason unplanned unless stated otherwise.
     */
    static boolean planned(String reason, Boolean requested) {
        return switch (reason) {
            case "BREAKDOWN" -> {
                require(requested == null || !requested, "A breakdown is always unplanned downtime");
                yield false;
            }
            case "PLANNED_MAINTENANCE" -> {
                require(requested == null || requested, "Planned maintenance is always planned downtime");
                yield true;
            }
            case "CHANGEOVER" -> requested == null || requested;
            default -> requested != null && requested;
        };
    }

    static String reasonLabel(Object reason) {
        return switch (String.valueOf(reason)) {
            case "BREAKDOWN" -> "Breakdown";
            case "PLANNED_MAINTENANCE" -> "Planned maintenance";
            case "CHANGEOVER" -> "Changeover";
            case "MATERIAL_SHORTAGE" -> "Material shortage";
            case "NO_OPERATOR" -> "No operator";
            case "QUALITY_HOLD" -> "Quality hold";
            case "UTILITIES" -> "Utilities";
            default -> "Other";
        };
    }

    static String statusLabel(Object status) {
        String code = String.valueOf(status);
        return code.charAt(0) + code.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /**
     * Inserts a downtime event (OPEN when {@code end} is null) and returns its {@code {id, number}}. The DB trigger
     * rejects an overlap with another effective event of the machine.
     */
    Map<String, Object> insertDowntime(
            Actor a,
            Map<String, Object> machine,
            String reason,
            boolean planned,
            OffsetDateTime start,
            OffsetDateTime end,
            String manufacturingOrderId,
            String workOrderId,
            String notes,
            String status,
            String correctsId,
            String correctionReason) {
        if (end != null) require(end.isAfter(start), "The downtime must end after it starts");
        String id = id();
        String number = number(a, "downtime_events", "DT");
        s.db().update("""
                        INSERT INTO downtime_events(id,tenant_id,number,machine_id,reason,planned,started_at,ended_at,status,
                          manufacturing_order_id,work_order_id,notes,reported_by,closed_by,closed_at,corrects_id,correction_reason)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", id, a.tenant(), number, machine.get("id"), reason, planned, start, end, status == null ? (end == null ? "OPEN" : "CLOSED") : status, manufacturingOrderId, workOrderId, notes == null ? "" : notes.strip(), a.id(), end == null ? null : a.id(), end == null ? null : now(), correctsId, correctionReason);
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("number", number);
        return result;
    }

    /** Closes an open event at {@code end}; {@code note} (optional) is appended to its notes. */
    void closeDowntime(Actor a, Map<String, Object> event, OffsetDateTime end, String note) {
        require(
                end.toInstant().isAfter(instant(event.get("started_at"))),
                "The end of downtime " + event.get("number") + " must be after its start");
        String notes = str(event, "notes");
        if (note != null && !note.isBlank()) notes = notes.isBlank() ? note.strip() : notes + "\n" + note.strip();
        s.db().update(
                        "UPDATE downtime_events SET status='CLOSED',ended_at=?,closed_by=?,closed_at=?,notes=? WHERE tenant_id=? AND id=?",
                        end,
                        a.id(),
                        now(),
                        notes,
                        a.tenant(),
                        event.get("id"));
    }

    // ------------------------------------------------------------------ machine status

    /** Sets the machine's status and records the change (no-op when it already has that status). */
    void setMachineStatus(
            Actor a, Map<String, Object> machine, String to, String source, String reference, String reason) {
        String from = str(machine, "status");
        if (from.equals(to)) return;
        s.db().update(
                        "UPDATE machines SET status=? WHERE tenant_id=? AND id=?", to, a.tenant(), machine.get("id"));
        s.db().update(
                        "INSERT INTO machine_status_changes(id,tenant_id,machine_id,from_status,to_status,source,reference,reason,changed_by) VALUES(?,?,?,?,?,?,?,?,?)",
                        id(),
                        a.tenant(),
                        machine.get("id"),
                        from,
                        to,
                        source,
                        reference,
                        reason == null ? "" : reason.strip(),
                        a.id());
    }

    // ------------------------------------------------------------------ checklists

    /** A checklist snapshot of plan tasks: {@code [{task, result: null, note: ""}]}. */
    static List<Map<String, Object>> checklist(List<String> tasks) {
        var out = new ArrayList<Map<String, Object>>();
        for (String task : tasks) {
            if (task == null || task.isBlank()) continue;
            var item = new LinkedHashMap<String, Object>();
            item.put("task", task.strip());
            item.put("result", null);
            item.put("note", "");
            out.add(item);
        }
        return out;
    }

    /** The order's stored checklist with {@code results} applied (409 for an index outside the list). */
    List<Map<String, Object>> applyResults(Object stored, List<MaintenanceCommands.ChecklistResult> results) {
        var items = readChecklist(stored);
        if (results != null)
            for (var r : results) {
                require(r.index() < items.size(), "Checklist item " + (r.index() + 1) + " does not exist");
                var item = items.get(r.index());
                item.put("result", r.result() == null || r.result().isBlank() ? null : r.result());
                item.put("note", r.note() == null ? "" : r.note().strip());
            }
        return items;
    }

    /** The stored checklist JSON ({@code jsonb}) as editable rows. */
    List<Map<String, Object>> readChecklist(Object stored) {
        if (stored == null) return new ArrayList<>();
        try {
            List<LinkedHashMap<String, Object>> rows =
                    json.readValue(stored.toString(), new TypeReference<List<LinkedHashMap<String, Object>>>() {});
            return new ArrayList<>(rows);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A plan's checklist JSON ({@code ["task", ...]}) as task texts. */
    List<String> readTasks(Object stored) {
        if (stored == null) return new ArrayList<>();
        try {
            List<String> tasks = json.readValue(stored.toString(), new TypeReference<List<String>>() {});
            return new ArrayList<>(tasks);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static long openItems(List<Map<String, Object>> checklist) {
        return checklist.stream().filter(i -> i.get("result") == null).count();
    }
}
