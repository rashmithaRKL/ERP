package lk.dexlanka.erp.performance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * OEE of one work center for one window (normally one Asia/Colombo business day, clipped to "now" for today). Pure
 * calculation over already loaded records; the method is documented in {@code docs/architecture/ARCHITECTURE.md}
 * ("OEE method") and {@code docs/FEATURES.md}.
 *
 * <p>Every instant of the window is classified once, in this precedence:
 *
 * <ol>
 *   <li>outside the shift working time (CalendarService) or inside a <b>planned stop</b> (an effective downtime event
 *       flagged planned, except CHANGEOVER) — not planned production time;
 *   <li><b>breakdown</b> (BREAKDOWN downtime), then <b>changeover</b> (CHANGEOVER downtime, planned or not), then
 *       <b>other stops</b> (any other unplanned downtime);
 *   <li><b>setup</b> (SETUP time logs), then <b>run</b> (RUN time logs);
 *   <li>otherwise <b>unrecorded idle</b> (scheduled, not stopped, not running: no record explains the time).
 * </ol>
 *
 * Availability = run / planned production time; Performance = Σ(ideal minutes per unit × reported count) / run time
 * of operations that have an ideal cycle time (capped at 100 %, the raw value is reported and flagged); Quality = good
 * / (good + scrap) of the operation reports; OEE = A × P × Q. Output of orders without a routing has no time records:
 * it is reported as untimed output and lowers the coverage, never the OEE.
 */
final class OeeCalculator {
    /** Version of the method; stored with every snapshot. */
    static final int METHOD = 1;

    static final String MEASURED = "MEASURED";
    static final String NOT_SCHEDULED = "NOT_SCHEDULED";
    static final String NO_DATA = "NO_DATA";
    static final String UNTIMED = "UNTIMED";

    /** Unrecorded idle above this share of planned production time (and 30 minutes) is flagged. */
    static final double UNRECORDED_SHARE = 0.20;

    private static final long MINUTE = 60_000;

    record Working(Instant start, Instant end, String kind, String label) {}

    /** An effective downtime event ({@code machine_downtime}); {@code end} null = still open. */
    record Stop(String number, String reason, boolean planned, Instant start, Instant end) {}

    /** A time log; {@code end} null = still open; {@code ideal} = the operation's run minutes per base unit. */
    record Log(
            String operationId,
            String orderId,
            String orderNumber,
            int seq,
            String name,
            String activity,
            Instant start,
            Instant end,
            BigDecimal ideal) {}

    /** A quantity report of an operation on this work center. */
    record Report(
            String operationId,
            String orderId,
            String orderNumber,
            int seq,
            String name,
            BigDecimal good,
            BigDecimal scrap,
            BigDecimal ideal) {}

    record Calendar(String source, String code, String name) {}

    record Input(
            String machineId,
            LocalDate day,
            Instant windowStart,
            Instant windowEnd,
            boolean partial,
            Calendar calendar,
            List<Working> working,
            List<Stop> stops,
            List<Log> logs,
            List<Report> reports,
            BigDecimal untimedGood,
            BigDecimal untimedScrap) {}

    private OeeCalculator() {}

    static Map<String, Object> compute(Input in) {
        long ws = in.windowStart().toEpochMilli();
        long we = Math.max(ws, in.windowEnd().toEpochMilli());
        Spans window = Spans.of(ws, we);

        var workingSpans = new ArrayList<long[]>();
        for (var w : in.working())
            workingSpans.add(new long[] {w.start().toEpochMilli(), w.end().toEpochMilli()});
        Spans scheduled = Spans.union(workingSpans).intersect(window);

        var plannedStops = new ArrayList<long[]>();
        var breakdowns = new ArrayList<long[]>();
        var changeovers = new ArrayList<long[]>();
        var others = new ArrayList<long[]>();
        boolean openDowntime = false;
        for (var stop : in.stops()) {
            long[] span = {
                stop.start().toEpochMilli(),
                stop.end() == null ? we : stop.end().toEpochMilli()
            };
            if (stop.end() == null) openDowntime = true;
            if (stop.reason().equals("BREAKDOWN")) breakdowns.add(span);
            else if (stop.reason().equals("CHANGEOVER")) changeovers.add(span);
            else if (stop.planned()) plannedStops.add(span);
            else others.add(span);
        }
        Spans planned = scheduled.minus(Spans.union(plannedStops));
        Spans plannedStopTime = scheduled.minus(planned);
        Spans ub = Spans.union(breakdowns).intersect(window);
        Spans uc = Spans.union(changeovers).intersect(window).minus(ub);
        Spans uo = Spans.union(others).intersect(window).minus(ub).minus(uc);
        Spans down = ub.union(uc).union(uo);

        var setupSpans = new ArrayList<long[]>();
        var runSpans = new ArrayList<long[]>();
        String openLogSince = null;
        for (var log : in.logs()) {
            long[] span = {
                log.start().toEpochMilli(), log.end() == null ? we : log.end().toEpochMilli()
            };
            if (log.end() == null && (log.start().toEpochMilli() < ws || !in.partial()))
                openLogSince = log.start().toString();
            (log.activity().equals("SETUP") ? setupSpans : runSpans).add(span);
        }
        Spans setupLogs = Spans.union(setupSpans).intersect(window);
        Spans runLogs = Spans.union(runSpans).intersect(window).minus(setupLogs);
        Spans setup = planned.intersect(setupLogs).minus(down);
        Spans run = planned.intersect(runLogs).minus(down);
        Spans unrecorded = planned.minus(down).minus(setupLogs).minus(runLogs);

        // Run time per operation (the part of each RUN log that counts as run time) and reports per operation.
        var ops = new LinkedHashMap<String, Op>();
        for (var log : in.logs()) {
            var op = ops.computeIfAbsent(
                    log.operationId(),
                    k -> new Op(log.orderId(), log.orderNumber(), log.seq(), log.name(), log.ideal()));
            long end = log.end() == null ? we : log.end().toEpochMilli();
            Spans part = Spans.of(log.start().toEpochMilli(), end);
            if (log.activity().equals("RUN")) op.run += part.intersect(run).millis();
            else op.setup += part.intersect(setup).millis();
        }
        BigDecimal good = BigDecimal.ZERO, scrap = BigDecimal.ZERO, coveredCount = BigDecimal.ZERO;
        double idealMinutes = 0, idealGood = 0;
        for (var r : in.reports()) {
            var op = ops.computeIfAbsent(
                    r.operationId(), k -> new Op(r.orderId(), r.orderNumber(), r.seq(), r.name(), r.ideal()));
            op.good = op.good.add(r.good());
            op.scrap = op.scrap.add(r.scrap());
            op.reports++;
            good = good.add(r.good());
            scrap = scrap.add(r.scrap());
            if (r.ideal().signum() > 0) {
                coveredCount = coveredCount.add(r.good()).add(r.scrap());
                idealMinutes +=
                        r.ideal().doubleValue() * r.good().add(r.scrap()).doubleValue();
                idealGood += r.ideal().doubleValue() * r.good().doubleValue();
            }
        }
        BigDecimal total = good.add(scrap);
        double runMillis = run.millis();
        double coveredRun = 0;
        for (var op : ops.values()) if (op.ideal.signum() > 0) coveredRun += op.run;
        coveredRun = Math.min(coveredRun, runMillis);

        double plannedMin = planned.minutes();
        double runMin = runMillis / MINUTE;
        double coveredRunMin = coveredRun / MINUTE;
        BigDecimal untimedGood = in.untimedGood();
        BigDecimal untimedScrap = in.untimedScrap();
        BigDecimal untimed = untimedGood.add(untimedScrap);
        boolean anyLogs = !setupLogs.isEmpty() || !runLogs.isEmpty();
        boolean anyStops = !planned.intersect(down).isEmpty();

        String status;
        if (planned.isEmpty()) status = NOT_SCHEDULED;
        else if (untimed.signum() > 0 && !anyLogs && in.reports().isEmpty()) status = UNTIMED;
        else if (!anyLogs && in.reports().isEmpty() && !anyStops) status = NO_DATA;
        else status = MEASURED;

        Double availability = null, performance = null, performanceRaw = null, quality = null, oee = null;
        Double netRun = null, fullyProductive = null;
        if (total.signum() > 0) quality = good.doubleValue() / total.doubleValue();
        if (status.equals(MEASURED)) {
            availability = runMin / plannedMin;
            if (runMin <= 0) {
                oee = 0.0;
                netRun = 0.0;
                fullyProductive = 0.0;
            } else if (coveredRunMin > 0) {
                performanceRaw = idealMinutes / coveredRunMin;
                performance = Math.min(1.0, performanceRaw);
                netRun = runMin * performance;
                if (performance == 0) {
                    oee = 0.0;
                    fullyProductive = 0.0;
                } else {
                    fullyProductive = netRun * quality;
                    oee = availability * performance * quality;
                }
            }
        }

        // Downtime per reason within the scheduled shift time (planned stops included), for the Pareto.
        var stopRows = new TreeMap<String, StopAcc>();
        for (var stop : in.stops()) {
            long end = stop.end() == null ? we : stop.end().toEpochMilli();
            double minutes = Spans.of(stop.start().toEpochMilli(), end)
                    .intersect(scheduled)
                    .minutes();
            if (minutes <= 0) continue;
            var acc = stopRows.computeIfAbsent(stop.reason(), k -> new StopAcc());
            acc.minutes += minutes;
            if (stop.planned()) acc.plannedMinutes += minutes;
            acc.numbers.add(stop.number());
        }

        var f = new LinkedHashMap<String, Object>();
        f.put("machineId", in.machineId());
        f.put("day", in.day().toString());
        f.put("status", status);
        f.put("partial", in.partial());
        f.put("method", METHOD);
        f.put("calendarSource", in.calendar().source());
        f.put("calendarCode", in.calendar().code());
        f.put("calendarName", in.calendar().name());
        f.put("calendarMinutes", minutes(window.minutes()));
        f.put("scheduledMinutes", minutes(scheduled.minutes()));
        f.put("plannedStopMinutes", minutes(plannedStopTime.minutes()));
        f.put("plannedMinutes", minutes(plannedMin));
        f.put("runMinutes", minutes(runMin));
        f.put("coveredRunMinutes", minutes(coveredRunMin));
        f.put("setupMinutes", minutes(setup.minutes()));
        f.put("changeoverMinutes", minutes(planned.intersect(uc).minutes()));
        f.put("breakdownMinutes", minutes(planned.intersect(ub).minutes()));
        f.put("otherStopMinutes", minutes(planned.intersect(uo).minutes()));
        f.put("unrecordedMinutes", minutes(unrecorded.minutes()));
        // Reconciliation: run time derived from the stop records (planned time − stops − setup) vs logged run time.
        double derivedRun = plannedMin - planned.intersect(down).minutes() - setup.minutes();
        f.put("derivedRunMinutes", minutes(derivedRun));
        f.put("runOutsideMinutes", minutes(runLogs.minus(planned).minutes()));
        f.put("overlapMinutes", minutes(setupLogs.union(runLogs).intersect(down).minutes()));
        Spans busy = setupLogs.union(runLogs).union(uc).minus(ub).minus(uo);
        f.put("busyMinutes", minutes(busy.minutes()));
        f.put("busyInShiftMinutes", minutes(busy.intersect(scheduled).minutes()));
        f.put("netRunMinutes", netRun == null ? null : minutes(netRun));
        f.put("fullyProductiveMinutes", fullyProductive == null ? null : minutes(fullyProductive));
        f.put("idealMinutes", minutes(idealMinutes));
        f.put("idealGoodMinutes", minutes(idealGood));
        f.put("goodCount", good);
        f.put("scrapCount", scrap);
        f.put("totalCount", total);
        f.put("coveredCount", coveredCount);
        f.put("untimedGood", untimedGood);
        f.put("untimedScrap", untimedScrap);
        f.put("availability", ratio(availability));
        f.put("performance", ratio(performance));
        f.put("performanceRaw", ratio(performanceRaw));
        f.put("quality", ratio(quality));
        f.put("oee", ratio(oee));
        f.put(
                "utilization",
                scheduled.isEmpty() ? null : ratio(busy.intersect(scheduled).minutes() / scheduled.minutes()));
        f.put("capacityUtilization", window.isEmpty() ? null : ratio(busy.minutes() / window.minutes()));
        BigDecimal output = total.add(untimed);
        f.put("outputCoverage", output.signum() == 0 ? null : ratio(coveredCount.doubleValue() / output.doubleValue()));

        var stops = new ArrayList<Map<String, Object>>();
        for (var e : stopRows.entrySet()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("reason", e.getKey());
            row.put("minutes", minutes(e.getValue().minutes));
            row.put("plannedMinutes", minutes(e.getValue().plannedMinutes));
            row.put("events", new ArrayList<>(e.getValue().numbers));
            stops.add(row);
        }
        f.put("stops", stops);

        var operations = new ArrayList<Map<String, Object>>();
        for (var e : ops.entrySet()) {
            var op = e.getValue();
            var row = new LinkedHashMap<String, Object>();
            row.put("operationId", e.getKey());
            row.put("orderId", op.orderId);
            row.put("orderNumber", op.orderNumber);
            row.put("seq", op.seq);
            row.put("name", op.name);
            row.put("idealMinutesPerUnit", op.ideal);
            row.put("runMinutes", minutes(op.run / (double) MINUTE));
            row.put("setupMinutes", minutes(op.setup / (double) MINUTE));
            row.put("goodCount", op.good);
            row.put("scrapCount", op.scrap);
            double opIdeal = op.ideal.doubleValue() * op.good.add(op.scrap).doubleValue();
            row.put(
                    "performance",
                    op.run > 0 && op.ideal.signum() > 0 ? ratio(opIdeal / (op.run / (double) MINUTE)) : null);
            operations.add(row);
        }
        f.put("operations", operations);

        var intervals = new ArrayList<Map<String, Object>>();
        for (var w : in.working()) {
            Spans clipped = Spans.of(w.start(), w.end()).intersect(window);
            if (clipped.isEmpty()) continue;
            var row = new LinkedHashMap<String, Object>();
            var pair = clipped.pairs().getFirst();
            row.put("start", Instant.ofEpochMilli(pair[0]).toString());
            row.put("end", Instant.ofEpochMilli(pair[1]).toString());
            row.put("kind", w.kind());
            row.put("label", w.label());
            intervals.add(row);
        }
        f.put("intervals", intervals);

        // ---------------------------------------------------------------- data-coverage and data-quality warnings
        var warnings = new ArrayList<Map<String, Object>>();
        if ("NONE".equals(in.calendar().source()))
            warn(
                    warnings,
                    "NO_CALENDAR",
                    "info",
                    "No shift calendar: the whole day counts as planned time",
                    null,
                    null);
        if (untimed.signum() > 0)
            warn(
                    warnings,
                    "UNTIMED_OUTPUT",
                    "warning",
                    "Output of orders without a routing has no time records and no ideal cycle time; it is not part of the OEE",
                    null,
                    untimed);
        if (status.equals(NO_DATA))
            warn(
                    warnings,
                    "NO_ACTIVITY",
                    "info",
                    "Planned production time without any time log, quantity report or downtime: not measured",
                    minutes(plannedMin),
                    null);
        if (openLogSince != null)
            warn(
                    warnings,
                    "OPEN_TIME_LOG",
                    "warning",
                    "A time log has been open since " + openLogSince + " without a pause or finish",
                    null,
                    null);
        if (openDowntime) warn(warnings, "OPEN_DOWNTIME", "info", "Downtime is still open", null, null);
        double outside = runLogs.minus(planned).minutes();
        if (outside >= 1)
            warn(
                    warnings,
                    "RUN_OUTSIDE_PLANNED_TIME",
                    "warning",
                    "Run time outside the shift calendar or during a planned stop is not counted",
                    minutes(outside),
                    null);
        double overlap = setupLogs.union(runLogs).intersect(down).minutes();
        if (overlap >= 1)
            warn(
                    warnings,
                    "LOG_DOWNTIME_OVERLAP",
                    "warning",
                    "Time logs overlap recorded downtime; the downtime is counted",
                    minutes(overlap),
                    null);
        if (performanceRaw != null && performanceRaw > 1.0)
            warn(
                    warnings,
                    "PERFORMANCE_OVER_100",
                    "warning",
                    "Performance above 100 % is capped: check the routing's run minutes per unit or unlogged run time",
                    null,
                    null);
        if (runMin - coveredRunMin >= 1)
            warn(
                    warnings,
                    "NO_IDEAL_CYCLE_TIME",
                    "warning",
                    "Run time of operations without an ideal cycle time is assumed to perform like the rest",
                    minutes(runMin - coveredRunMin),
                    null);
        double unrecordedMin = unrecorded.minutes();
        if (status.equals(MEASURED) && unrecordedMin >= 30 && unrecordedMin > UNRECORDED_SHARE * plannedMin)
            warn(
                    warnings,
                    "UNRECORDED_TIME",
                    "info",
                    "Planned time with neither a running operation nor recorded downtime (logged run time is "
                            + Math.round(runMin) + " min; planned time less stops and setup is "
                            + Math.round(derivedRun)
                            + " min)",
                    minutes(unrecordedMin),
                    null);
        for (var op : ops.values())
            if (op.run >= 60 * MINUTE && op.reports == 0)
                warn(
                        warnings,
                        "RUN_WITHOUT_REPORT",
                        "warning",
                        op.orderNumber + " operation " + op.seq + " ran without a quantity report on this day",
                        minutes(op.run / (double) MINUTE),
                        null);
        if (!in.reports().isEmpty() && runMin <= 0)
            warn(
                    warnings,
                    "REPORT_WITHOUT_RUN",
                    "info",
                    "Quantities were reported without run time on this day",
                    null,
                    total);
        f.put("warnings", warnings);
        return f;
    }

    /** Copies figures without the per-day detail lists (matrix cells, state key). */
    static Map<String, Object> brief(Map<String, Object> figures) {
        var out = new LinkedHashMap<>(figures);
        out.remove("intervals");
        out.remove("operations");
        return out;
    }

    private static void warn(
            List<Map<String, Object>> warnings,
            String code,
            String severity,
            String message,
            BigDecimal minutes,
            BigDecimal quantity) {
        var w = new LinkedHashMap<String, Object>();
        w.put("code", code);
        w.put("severity", severity);
        w.put("message", message);
        if (minutes != null) w.put("minutes", minutes);
        if (quantity != null) w.put("quantity", quantity);
        warnings.add(w);
    }

    static BigDecimal minutes(double value) {
        return BigDecimal.valueOf(Math.max(0, value)).setScale(4, RoundingMode.HALF_UP);
    }

    static BigDecimal ratio(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private static final class Op {
        final String orderId;
        final String orderNumber;
        final int seq;
        final String name;
        final BigDecimal ideal;
        long run;
        long setup;
        int reports;
        BigDecimal good = BigDecimal.ZERO;
        BigDecimal scrap = BigDecimal.ZERO;

        Op(String orderId, String orderNumber, int seq, String name, BigDecimal ideal) {
            this.orderId = orderId;
            this.orderNumber = orderNumber;
            this.seq = seq;
            this.name = name;
            this.ideal = ideal == null ? BigDecimal.ZERO : ideal;
        }
    }

    private static final class StopAcc {
        double minutes;
        double plannedMinutes;
        final Set<String> numbers = new LinkedHashSet<>();
    }
}
