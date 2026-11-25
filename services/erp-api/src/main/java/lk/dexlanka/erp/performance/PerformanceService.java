package lk.dexlanka.erp.performance;

import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closing OEE days (V15). Calendars keep no history and downtime can still be reported or corrected for past days, so
 * the figures of a completed day are frozen in {@code oee_day_snapshots}: closing is idempotent per work center and
 * day (an already closed day is left unchanged), and recalculating is an explicit command with a reason that
 * supersedes the current version and keeps it as history (database trigger {@code oee_snapshot_guard}).
 */
@Service
public class PerformanceService {
    private final Store s;
    private final OeeEngine engine;

    PerformanceService(Store s, OeeEngine engine) {
        this.s = s;
        this.engine = engine;
    }

    @Transactional
    public Map<String, Object> close(Actor a, PerformanceCommands.Close c, String key) {
        a.allow("PLANNER");
        LocalDate day = PerformanceQuery.date(c.date(), "date");
        boolean recalculate = Boolean.TRUE.equals(c.recalculate());
        String reason = c.reason() == null ? "" : c.reason().strip();
        return s.once(a, key, "oee-close:" + day, c, () -> {
            LocalDate today = OeeEngine.today();
            require(day.isBefore(today), "Only completed days can be closed; " + day + " is not over yet");
            require(!day.isBefore(today.minusDays(366)), "Choose a day within the last year");
            require(
                    !recalculate || reason.length() >= 5,
                    "Give a reason of at least 5 characters for the recalculation");
            List<Map<String, Object>> machines;
            if (c.machineIds() == null || c.machineIds().isEmpty()) machines = engine.machines(a.tenant(), null);
            else {
                machines = new ArrayList<>();
                for (String m : c.machineIds().stream().distinct().toList())
                    machines.add(engine.machines(a.tenant(), m).getFirst());
            }
            require(!machines.isEmpty(), "There are no work centers to close");
            var ids = machines.stream().map(m -> str(m, "id")).toList();
            var live = engine.live(a.tenant(), ids, day, day, Instant.now());
            var closed = new ArrayList<String>();
            var recalculated = new ArrayList<String>();
            var unchanged = new ArrayList<String>();
            var snapshots = new ArrayList<Map<String, Object>>();
            for (var m : machines) {
                String machine = str(m, "id");
                String code = str(m, "code");
                var current = s.list(
                        "SELECT id,version,oee FROM oee_day_snapshots WHERE tenant_id=? AND machine_id=? AND day=?::date AND status='CURRENT' FOR UPDATE",
                        a.tenant(),
                        machine,
                        day.toString());
                var figures = live.get(machine).get(day);
                int version = 1;
                String supersedes = null;
                if (!current.isEmpty()) {
                    var row = current.getFirst();
                    if (!recalculate) {
                        unchanged.add(code);
                        snapshots.add(snapshot(
                                machine, code, ((Number) row.get("version")).intValue(), "UNCHANGED", row.get("oee")));
                        continue;
                    }
                    supersedes = str(row, "id");
                    version = ((Number) row.get("version")).intValue() + 1;
                    s.db().update(
                                    "UPDATE oee_day_snapshots SET status='SUPERSEDED',superseded_at=now() WHERE tenant_id=? AND id=?",
                                    a.tenant(),
                                    supersedes);
                }
                insert(a, machine, day, version, supersedes, supersedes == null ? "" : reason, figures);
                (supersedes == null ? closed : recalculated).add(code);
                snapshots.add(snapshot(
                        machine, code, version, supersedes == null ? "CLOSED" : "RECALCULATED", figures.get("oee")));
            }
            if (!closed.isEmpty() || recalculated.isEmpty())
                s.audit(
                        a,
                        "OEE_DAY_CLOSED",
                        day.toString(),
                        closed.isEmpty()
                                ? "OEE of " + day + " was already closed for " + String.join(", ", unchanged)
                                : "OEE of " + day + " closed for " + String.join(", ", closed)
                                        + (unchanged.isEmpty()
                                                ? ""
                                                : " (already closed: " + String.join(", ", unchanged) + ")"));
            if (!recalculated.isEmpty())
                s.audit(
                        a,
                        "OEE_DAY_RECALCULATED",
                        day.toString(),
                        "OEE of " + day + " recalculated for " + String.join(", ", recalculated) + ": " + reason);
            var out = new LinkedHashMap<String, Object>();
            out.put("date", day.toString());
            out.put("closed", closed);
            out.put("recalculated", recalculated);
            out.put("unchanged", unchanged);
            out.put("snapshots", snapshots);
            return out;
        });
    }

    private void insert(
            Actor a,
            String machine,
            LocalDate day,
            int version,
            String supersedes,
            String reason,
            Map<String, Object> live) {
        var figures = new LinkedHashMap<>(live);
        figures.remove("closed");
        s.db().update(
                        """
                        INSERT INTO oee_day_snapshots(id,tenant_id,machine_id,day,version,status,method,oee_status,calendar_source,
                          calendar_code,calendar_minutes,scheduled_minutes,planned_stop_minutes,planned_minutes,run_minutes,
                          setup_minutes,breakdown_minutes,other_stop_minutes,unrecorded_minutes,net_run_minutes,
                          fully_productive_minutes,good_count,total_count,availability,performance,quality,oee,figures,reason,
                          supersedes_id,closed_by)
                        VALUES(?,?,?,?::date,?,'CURRENT',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)""",
                        id(),
                        a.tenant(),
                        machine,
                        day.toString(),
                        version,
                        OeeCalculator.METHOD,
                        figures.get("status"),
                        figures.get("calendarSource"),
                        figures.get("calendarCode"),
                        figures.get("calendarMinutes"),
                        figures.get("scheduledMinutes"),
                        figures.get("plannedStopMinutes"),
                        figures.get("plannedMinutes"),
                        figures.get("runMinutes"),
                        Store.dec(figures.get("setupMinutes")).add(Store.dec(figures.get("changeoverMinutes"))),
                        figures.get("breakdownMinutes"),
                        figures.get("otherStopMinutes"),
                        figures.get("unrecordedMinutes"),
                        figures.get("netRunMinutes"),
                        figures.get("fullyProductiveMinutes"),
                        figures.get("goodCount"),
                        figures.get("totalCount"),
                        figures.get("availability"),
                        figures.get("performance"),
                        figures.get("quality"),
                        figures.get("oee"),
                        s.encode(figures),
                        reason,
                        supersedes,
                        a.id());
    }

    private static Map<String, Object> snapshot(String machine, String code, int version, String result, Object oee) {
        var m = new LinkedHashMap<String, Object>();
        m.put("machineId", machine);
        m.put("code", code);
        m.put("version", version);
        m.put("result", result);
        m.put("oee", oee == null ? null : new BigDecimal(oee.toString()));
        return m;
    }
}
