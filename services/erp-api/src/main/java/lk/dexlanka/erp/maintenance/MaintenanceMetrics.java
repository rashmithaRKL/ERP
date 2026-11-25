package lk.dexlanka.erp.maintenance;

import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reliability figures from the effective downtime record ({@code machine_downtime}) for a period of Asia/Colombo
 * calendar days ({@code from}..{@code to} inclusive, default the last 30 days; the period ends now at the latest):
 *
 * <ul>
 *   <li><b>Downtime minutes</b> per machine and per reason: event durations clipped to the period (open events run to
 *       now), split into planned and unplanned.
 *   <li><b>Failures</b>: BREAKDOWN events that started in the period.
 *   <li><b>MTTR</b>: the mean full duration of the closed failures, in minutes.
 *   <li><b>MTBF</b>: (period − all downtime in the period) / failures, in hours. Without shift calendars the period is
 *       calendar time; null without failures.
 *   <li><b>Pareto</b>: downtime minutes by reason, largest first, with share and cumulative share in percent.
 * </ul>
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MaintenanceMetrics {
    private final Store s;

    MaintenanceMetrics(Store s) {
        this.s = s;
    }

    public Map<String, Object> metrics(Actor a, String from, String to, String machine) {
        LocalDate today = MaintenanceRules.today();
        LocalDate first = blank(from) ? today.minusDays(29) : MaintenanceRules.date(from, "period start");
        LocalDate last = blank(to) ? today : MaintenanceRules.date(to, "period end");
        require(!last.isBefore(first), "The period must end on or after its start");
        require(ChronoUnit.DAYS.between(first, last) < 366, "Choose a period of at most 366 days");
        String machineId = blank(machine) ? null : machine.strip();
        if (machineId != null) s.one("SELECT id FROM machines WHERE tenant_id=? AND id=?", a.tenant(), machineId);
        OffsetDateTime start = MaintenanceQuery.dayStart(first);
        OffsetDateTime end = MaintenanceQuery.dayStart(last.plusDays(1));
        OffsetDateTime now = MaintenanceRules.now();
        Instant until = (end.isAfter(now) ? now : end).toInstant();
        double period = Math.max(0, seconds(start.toInstant(), until) / 60.0);

        var machines = new LinkedHashMap<String, Acc>();
        for (var m : s.list(
                "SELECT id,code,name,status FROM machines WHERE tenant_id=? AND (?::text IS NULL OR id=?) ORDER BY code",
                a.tenant(),
                machineId,
                machineId)) machines.put(str(m, "id"), new Acc(m));
        var reasons = new LinkedHashMap<String, double[]>(); // reason -> {minutes, events}
        for (var e : s.list(
                """
                SELECT machine_id,reason,planned,started_at,ended_at FROM machine_downtime
                WHERE tenant_id=? AND (?::text IS NULL OR machine_id=?)
                  AND started_at<? AND COALESCE(ended_at,'infinity'::timestamptz)>?""",
                a.tenant(),
                machineId,
                machineId,
                OffsetDateTime.ofInstant(until, java.time.ZoneOffset.UTC),
                start)) {
            Instant began = MaintenanceRules.instant(e.get("started_at"));
            Instant ended = e.get("ended_at") == null ? now.toInstant() : MaintenanceRules.instant(e.get("ended_at"));
            Instant from0 = began.isBefore(start.toInstant()) ? start.toInstant() : began;
            Instant to0 = ended.isAfter(until) ? until : ended;
            double minutes = Math.max(0, seconds(from0, to0) / 60.0);
            var acc = machines.get(str(e, "machine_id"));
            if (acc == null) continue;
            boolean planned = Boolean.TRUE.equals(e.get("planned"));
            acc.events++;
            acc.downtime += minutes;
            if (planned) acc.planned += minutes;
            else acc.unplanned += minutes;
            if (e.get("reason").equals("BREAKDOWN") && !began.isBefore(start.toInstant())) {
                acc.failures++;
                if (e.get("ended_at") != null) {
                    acc.repairs++;
                    acc.repairMinutes += seconds(began, ended) / 60.0;
                }
            }
            var r = reasons.computeIfAbsent(str(e, "reason"), k -> new double[2]);
            r[0] += minutes;
            r[1]++;
        }

        var rows = new ArrayList<Map<String, Object>>();
        var total = new Acc(Map.of());
        for (var acc : machines.values()) {
            rows.add(acc.toMap(period));
            total.events += acc.events;
            total.downtime += acc.downtime;
            total.planned += acc.planned;
            total.unplanned += acc.unplanned;
            total.failures += acc.failures;
            total.repairs += acc.repairs;
            total.repairMinutes += acc.repairMinutes;
        }
        double totalMinutes = reasons.values().stream().mapToDouble(r -> r[0]).sum();
        var pareto = new ArrayList<Map<String, Object>>();
        double cumulative = 0;
        var sorted = new ArrayList<>(reasons.entrySet());
        sorted.sort((x, y) -> Double.compare(y.getValue()[0], x.getValue()[0]));
        for (var r : sorted) {
            cumulative += r.getValue()[0];
            var row = new LinkedHashMap<String, Object>();
            row.put("reason", r.getKey());
            row.put("minutes", round(r.getValue()[0], 1));
            row.put("events", (long) r.getValue()[1]);
            row.put("share", totalMinutes == 0 ? BigDecimal.ZERO : round(r.getValue()[0] * 100 / totalMinutes, 1));
            row.put("cumulative", totalMinutes == 0 ? BigDecimal.ZERO : round(cumulative * 100 / totalMinutes, 1));
            pareto.add(row);
        }
        // Fleet MTBF: total operating time of the machines in scope over all failures.
        var totals = total.figures(period * machines.size());
        var result = new LinkedHashMap<String, Object>();
        result.put("from", first.toString());
        result.put("to", last.toString());
        result.put("periodMinutes", round(period, 1));
        result.put("machines", rows);
        result.put("pareto", pareto);
        result.put("totals", totals);
        return result;
    }

    private static final class Acc {
        final Map<String, Object> machine;
        long events;
        long failures;
        long repairs;
        double downtime;
        double planned;
        double unplanned;
        double repairMinutes;

        Acc(Map<String, Object> machine) {
            this.machine = machine;
        }

        Map<String, Object> toMap(double period) {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", machine.get("id"));
            m.put("code", machine.get("code"));
            m.put("name", machine.get("name"));
            m.put("status", machine.get("status"));
            m.putAll(figures(period));
            return m;
        }

        Map<String, Object> figures(double period) {
            var m = new LinkedHashMap<String, Object>();
            m.put("events", events);
            m.put("failures", failures);
            m.put("downtimeMinutes", round(downtime, 1));
            m.put("plannedMinutes", round(planned, 1));
            m.put("unplannedMinutes", round(unplanned, 1));
            m.put("mttrMinutes", repairs == 0 ? null : round(repairMinutes / repairs, 1));
            m.put("mtbfHours", failures == 0 ? null : round(Math.max(0, period - downtime) / 60.0 / failures, 2));
            return m;
        }
    }

    private static double seconds(Instant from, Instant to) {
        return Duration.between(from, to).toMillis() / 1000.0;
    }

    private static BigDecimal round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static List<String> reasons() {
        return List.of(MaintenanceCommands.REASONS.split("\\|"));
    }
}
