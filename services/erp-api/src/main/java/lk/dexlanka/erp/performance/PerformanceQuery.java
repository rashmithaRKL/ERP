package lk.dexlanka.erp.performance;

import static lk.dexlanka.erp.performance.OeeCalculator.minutes;
import static lk.dexlanka.erp.performance.OeeCalculator.ratio;
import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The OEE read model (every signed-in role; closing days is a planner command): OEE by work center × day or week,
 * plant roll-up, trend series, losses (waterfall, six big losses, downtime Pareto, quality losses), utilization and
 * data-coverage warnings, and the detail of one work-center day. Ranges are Asia/Colombo dates, at most {@value
 * OeeEngine#MAX_DAYS} days, never after today (today is computed so far).
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PerformanceQuery {
    private static final String[] MONTHS = {
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    private final Store s;
    private final OeeEngine engine;

    PerformanceQuery(Store s, OeeEngine engine) {
        this.s = s;
        this.engine = engine;
    }

    record Range(LocalDate from, LocalDate to) {}

    static Range range(String from, String to) {
        LocalDate today = OeeEngine.today();
        LocalDate last = blank(to) ? today : date(to, "end date");
        LocalDate first = blank(from) ? last.minusDays(13) : date(from, "start date");
        if (last.isAfter(today)) last = today;
        require(!first.isAfter(today), "The range must start today or earlier");
        OeeEngine.checkRange(first, last);
        return new Range(first, last);
    }

    // ------------------------------------------------------------------ OEE matrix, plant roll-up and trend

    /**
     * {@code GET /api/performance/oee}: {@code plant} (roll-up of the range), {@code series} (plant per period: the
     * trend), {@code workCenters} (each with its roll-up and one cell per period: the heatmap), {@code periods},
     * {@code coverage} and {@code warnings} (data coverage and data quality, newest first, at most 200).
     */
    public Map<String, Object> oee(Actor a, String from, String to, String machine, String bucket) {
        var r = range(from, to);
        String b = blank(bucket) ? "day" : bucket.strip().toLowerCase(Locale.ROOT);
        require(b.equals("day") || b.equals("week"), "Choose the day or week view");
        var machines = engine.machines(a.tenant(), machine);
        var ids = machines.stream().map(m -> str(m, "id")).toList();
        var days = engine.days(a.tenant(), ids, r.from(), r.to());

        var periods = new LinkedHashMap<String, Map<String, Object>>();
        for (LocalDate d = r.from(); !d.isAfter(r.to()); d = d.plusDays(1)) {
            String key = periodKey(d, b);
            if (!periods.containsKey(key)) {
                var p = new LinkedHashMap<String, Object>();
                p.put("key", key);
                LocalDate start = b.equals("week") ? max(d.with(DayOfWeek.MONDAY), r.from()) : d;
                LocalDate end = b.equals("week") ? min(d.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)), r.to()) : d;
                p.put("from", start.toString());
                p.put("to", end.toString());
                p.put("label", b.equals("week") ? label(start) + " – " + label(end) : label(d));
                p.put("partial", !end.isBefore(OeeEngine.today()));
                periods.put(key, p);
            }
        }

        var plant = new Rollup();
        var series = new LinkedHashMap<String, Rollup>();
        for (String key : periods.keySet()) series.put(key, new Rollup());
        var workCenters = new ArrayList<Map<String, Object>>();
        var warnings = new ArrayList<Map<String, Object>>();
        for (var m : machines) {
            String id = str(m, "id");
            var total = new Rollup();
            var cells = new LinkedHashMap<String, Object>();
            var cellRollups = new LinkedHashMap<String, Rollup>();
            var calendars = new LinkedHashSet<String>();
            for (var e : days.get(id).entrySet()) {
                var f = e.getValue();
                String key = periodKey(e.getKey(), b);
                total.add(f);
                plant.add(f);
                series.get(key).add(f);
                calendars.add(str(f, "calendarSource"));
                if (b.equals("day")) {
                    var cell = OeeCalculator.brief(f);
                    cell.remove("stops");
                    cell.put("period", key);
                    cells.put(key, cell);
                } else cellRollups.computeIfAbsent(key, k -> new Rollup()).add(f);
                if (f.get("warnings") instanceof List<?> list)
                    for (Object w : list)
                        if (w instanceof Map<?, ?> map) {
                            var row = new LinkedHashMap<String, Object>();
                            for (var x : map.entrySet()) row.put(String.valueOf(x.getKey()), x.getValue());
                            row.put("machineId", id);
                            row.put("machine", m.get("code"));
                            row.put("day", e.getKey().toString());
                            warnings.add(row);
                        }
            }
            for (var e : cellRollups.entrySet()) {
                var cell = e.getValue().toMap();
                cell.put("period", e.getKey());
                cells.put(e.getKey(), cell);
            }
            var row = new LinkedHashMap<String, Object>(m);
            row.put("calendarSources", new ArrayList<>(calendars));
            row.put("totals", total.toMap());
            row.put("cells", new ArrayList<>(cells.values()));
            workCenters.add(row);
        }
        var trend = new ArrayList<Map<String, Object>>();
        for (var e : series.entrySet()) {
            var point = e.getValue().toMap();
            point.putAll(periods.get(e.getKey()));
            trend.add(point);
        }
        warnings.sort((x, y) -> {
            int c = str(y, "day").compareTo(str(x, "day"));
            if (c != 0) return c;
            c = Boolean.compare("info".equals(x.get("severity")), "info".equals(y.get("severity")));
            return c != 0 ? c : str(x, "machine").compareTo(str(y, "machine"));
        });
        var out = new LinkedHashMap<String, Object>();
        out.put("from", r.from().toString());
        out.put("to", r.to().toString());
        out.put("today", OeeEngine.today().toString());
        out.put("bucket", b);
        out.put("method", OeeCalculator.METHOD);
        out.put("periods", new ArrayList<>(periods.values()));
        var plantMap = plant.toMap();
        out.put("plant", plantMap);
        out.put("series", trend);
        out.put("workCenters", workCenters);
        var coverage = new LinkedHashMap<String, Object>();
        coverage.put("timeCoverage", plantMap.get("timeCoverage"));
        coverage.put("outputCoverage", plantMap.get("outputCoverage"));
        coverage.put("plannedMinutes", plantMap.get("plannedMinutesAll"));
        coverage.put("measuredPlannedMinutes", plantMap.get("plannedMinutes"));
        coverage.put("untimedOutput", dec(plantMap.get("untimedGood")).add(dec(plantMap.get("untimedScrap"))));
        coverage.put("workCenterDays", plantMap.get("days"));
        coverage.put("closedDays", plantMap.get("closedDays"));
        coverage.put("statusCounts", plantMap.get("statusCounts"));
        var codes = new TreeMap<String, Integer>();
        for (var w : warnings) codes.merge(str(w, "code"), 1, Integer::sum);
        coverage.put("warningCounts", codes);
        out.put("coverage", coverage);
        out.put("warningTotal", warnings.size());
        out.put("warnings", warnings.size() > 200 ? new ArrayList<>(warnings.subList(0, 200)) : warnings);
        return out;
    }

    // ------------------------------------------------------------------ losses

    /**
     * {@code GET /api/performance/losses}: {@code waterfall} (calendar → scheduled → planned production → run → net run
     * → fully productive time of the measured work-center days), {@code sixLosses}, {@code pareto} (downtime minutes in
     * shift time by reason), {@code quality} (in-process scrap, untimed output, post-production scrap dispositions and
     * rework).
     */
    public Map<String, Object> losses(Actor a, String from, String to, String machine) {
        var r = range(from, to);
        var machines = engine.machines(a.tenant(), machine);
        var ids = machines.stream().map(m -> str(m, "id")).toList();
        var days = engine.days(a.tenant(), ids, r.from(), r.to());
        var total = new Rollup();
        var reasons = new LinkedHashMap<String, double[]>(); // minutes, planned minutes
        var events = new HashMap<String, LinkedHashSet<String>>();
        var byMachine = new ArrayList<Map<String, Object>>();
        for (var m : machines) {
            var wc = new Rollup();
            for (var f : days.get(str(m, "id")).values()) {
                total.add(f);
                wc.add(f);
                if (f.get("stops") instanceof List<?> stops)
                    for (Object o : stops)
                        if (o instanceof Map<?, ?> stop) {
                            String reason = String.valueOf(stop.get("reason"));
                            var acc = reasons.computeIfAbsent(reason, k -> new double[2]);
                            acc[0] += Rollup.num(cast(stop), "minutes");
                            acc[1] += Rollup.num(cast(stop), "plannedMinutes");
                            if (stop.get("events") instanceof List<?> numbers)
                                for (Object n : numbers)
                                    events.computeIfAbsent(reason, k -> new LinkedHashSet<>()).add(String.valueOf(n));
                        }
            }
            var t = wc.toMap();
            var row = new LinkedHashMap<String, Object>();
            row.put("id", m.get("id"));
            row.put("code", m.get("code"));
            row.put("name", m.get("name"));
            row.put("goodCount", t.get("goodCount"));
            row.put("scrapCount", t.get("scrapCount"));
            row.put("totalCount", t.get("totalCount"));
            row.put("goodRate", t.get("goodRate"));
            row.put("untimedGood", t.get("untimedGood"));
            row.put("untimedScrap", t.get("untimedScrap"));
            byMachine.add(row);
        }
        var t = total.toMap();
        double calendar = Rollup.num(t, "measuredCalendarMinutes");
        double scheduled = Rollup.num(t, "measuredScheduledMinutes");
        double planned = Rollup.num(t, "plannedMinutes");
        double run = Rollup.num(t, "runMinutes");
        double net = Rollup.num(t, "netRunMinutes");
        double fp = Rollup.num(t, "fullyProductiveMinutes");
        var waterfall = new ArrayList<Map<String, Object>>();
        waterfall.add(step("calendar", "Calendar time", "TOTAL", calendar));
        waterfall.add(step("notScheduled", "Outside shifts", "LOSS", calendar - scheduled));
        waterfall.add(step("scheduled", "Shift time", "TOTAL", scheduled));
        waterfall.add(step("plannedStops", "Planned stops", "LOSS", scheduled - planned));
        waterfall.add(step("planned", "Planned production time", "TOTAL", planned));
        waterfall.add(step("availabilityLoss", "Availability losses", "LOSS", planned - run));
        waterfall.add(step("run", "Run time", "TOTAL", run));
        waterfall.add(step("performanceLoss", "Performance losses", "LOSS", run - net));
        waterfall.add(step("netRun", "Net run time", "TOTAL", net));
        waterfall.add(step("qualityLoss", "Quality losses", "LOSS", net - fp));
        waterfall.add(step("fullyProductive", "Fully productive time", "TOTAL", fp));

        var six = new ArrayList<Map<String, Object>>();
        six.add(loss(
                "BREAKDOWNS",
                "Equipment failure",
                "AVAILABILITY",
                Rollup.num(t, "breakdownMinutes"),
                "Downtime events with reason Breakdown inside planned production time"));
        six.add(loss(
                "SETUP_ADJUSTMENTS",
                "Setup and adjustments",
                "AVAILABILITY",
                Rollup.num(t, "setupMinutes") + Rollup.num(t, "changeoverMinutes"),
                "SETUP time logs plus Changeover downtime"));
        six.add(loss(
                "SMALL_STOPS_REDUCED_SPEED",
                "Small stops and reduced speed",
                "PERFORMANCE",
                run - net,
                "Run time less net run time; small stops inside a running time log are not recorded apart from slow cycles"));
        six.add(loss(
                "PRODUCTION_REJECTS",
                "Production rejects",
                "QUALITY",
                net - fp,
                "Scrap reported on operations, in net run time"));
        var startup = loss(
                "STARTUP_REJECTS",
                "Start-up rejects",
                "QUALITY",
                null,
                "Not measured: operation reports do not separate start-up rejects from steady-state scrap; they are included in production rejects");
        six.add(startup);
        var otherStops = loss(
                "OTHER_STOPS",
                "Other recorded stops",
                "AVAILABILITY",
                Rollup.num(t, "otherStopMinutes"),
                "Material shortage, no operator, quality hold, utilities and other unplanned downtime (outside the classic six)");
        six.add(otherStops);
        six.add(loss(
                "UNRECORDED_IDLE",
                "Unrecorded idle time",
                "AVAILABILITY",
                Rollup.num(t, "unrecordedMinutes"),
                "Planned production time with neither a running operation nor recorded downtime (outside the classic six)"));

        double paretoTotal = reasons.values().stream().mapToDouble(x -> x[0]).sum();
        var sorted = new ArrayList<>(reasons.entrySet());
        sorted.sort((x, y) -> Double.compare(y.getValue()[0], x.getValue()[0]));
        var pareto = new ArrayList<Map<String, Object>>();
        double cumulative = 0;
        for (var e : sorted) {
            cumulative += e.getValue()[0];
            var row = new LinkedHashMap<String, Object>();
            row.put("reason", e.getKey());
            row.put("minutes", minutes(e.getValue()[0]));
            row.put("plannedMinutes", minutes(e.getValue()[1]));
            row.put("events", events.getOrDefault(e.getKey(), new LinkedHashSet<>()).size());
            row.put("share", paretoTotal > 0 ? ratio(e.getValue()[0] / paretoTotal) : null);
            row.put("cumulative", paretoTotal > 0 ? ratio(cumulative / paretoTotal) : null);
            pareto.add(row);
        }

        var out = new LinkedHashMap<String, Object>();
        out.put("from", r.from().toString());
        out.put("to", r.to().toString());
        out.put("totals", t);
        out.put("waterfall", waterfall);
        out.put("sixLosses", six);
        out.put("pareto", pareto);
        out.put("quality", quality(a, r, ids, machines, t, byMachine));
        return out;
    }

    private Map<String, Object> quality(
            Actor a,
            Range r,
            List<String> ids,
            List<Map<String, Object>> machines,
            Map<String, Object> totals,
            List<Map<String, Object>> byMachine) {
        String in = String.join(",", ids);
        var start = OeeEngine.utc(OeeEngine.dayStart(r.from()));
        var end = OeeEngine.utc(OeeEngine.dayStart(r.to().plusDays(1)));
        // The work center that produced a lot: the last operation of its routed order, else the order's work center.
        String producer =
                """
                COALESCE((SELECT op.machine_id FROM mo_operations op WHERE op.tenant_id=mo.tenant_id AND op.order_id=mo.id
                          ORDER BY op.seq DESC LIMIT 1), mo.machine_id)""";
        var scrap = s.list(
                """
                SELECT w.machine_id,COUNT(*) AS dispositions,SUM(w.quantity) AS quantity,SUM(w.value_amount) AS value FROM (
                  SELECT %s AS machine_id,d.quantity,d.value_amount
                  FROM ncr_dispositions d
                  JOIN lots l ON l.tenant_id=d.tenant_id AND l.id=d.lot_id AND l.source_type='MANUFACTURING'
                  JOIN manufacturing_orders mo ON mo.tenant_id=l.tenant_id AND mo.id=l.source_id
                  WHERE d.tenant_id=? AND d.type='SCRAP' AND d.status='POSTED' AND d.posted_at>=? AND d.posted_at<?) w
                WHERE w.machine_id=ANY(string_to_array(?,','))
                GROUP BY w.machine_id"""
                        .formatted(producer),
                a.tenant(),
                start,
                end,
                in);
        var rework = s.list(
                """
                SELECT w.machine_id,COUNT(*) AS orders,COUNT(*) FILTER (WHERE w.status='COMPLETED') AS completed,
                       SUM(w.input_quantity) AS input_quantity,
                       SUM(w.input_quantity) FILTER (WHERE w.status='COMPLETED') AS completed_input,
                       SUM(w.output_quantity) AS output_quantity FROM (
                  SELECT %s AS machine_id,ro.status,ro.input_quantity,ro.output_quantity
                  FROM rework_orders ro
                  JOIN lots l ON l.tenant_id=ro.tenant_id AND l.id=ro.input_lot_id AND l.source_type='MANUFACTURING'
                  JOIN manufacturing_orders mo ON mo.tenant_id=l.tenant_id AND mo.id=l.source_id
                  WHERE ro.tenant_id=? AND ro.created_at>=? AND ro.created_at<?) w
                WHERE w.machine_id=ANY(string_to_array(?,','))
                GROUP BY w.machine_id"""
                        .formatted(producer),
                a.tenant(),
                start,
                end,
                in);
        var scrapBy = new HashMap<String, Map<String, Object>>();
        for (var row : scrap) scrapBy.put(str(row, "machine_id"), row);
        var reworkBy = new HashMap<String, Map<String, Object>>();
        for (var row : rework) reworkBy.put(str(row, "machine_id"), row);
        BigDecimal scrapQty = BigDecimal.ZERO, scrapValue = BigDecimal.ZERO;
        long dispositions = 0, reworkOrders = 0, reworkCompleted = 0;
        BigDecimal reworkInput = BigDecimal.ZERO, reworkCompletedInput = BigDecimal.ZERO, reworkOutput = BigDecimal.ZERO;
        for (var row : byMachine) {
            var sc = scrapBy.get(str(row, "id"));
            row.put("postProductionScrap", sc == null ? BigDecimal.ZERO : dec(sc.get("quantity")));
            row.put("postProductionScrapValue", sc == null ? BigDecimal.ZERO : dec(sc.get("value")));
            var rw = reworkBy.get(str(row, "id"));
            row.put("reworkInput", rw == null ? BigDecimal.ZERO : dec(rw.get("input_quantity")));
            if (sc != null) {
                scrapQty = scrapQty.add(dec(sc.get("quantity")));
                scrapValue = scrapValue.add(dec(sc.get("value")));
                dispositions += ((Number) sc.get("dispositions")).longValue();
            }
            if (rw != null) {
                reworkOrders += ((Number) rw.get("orders")).longValue();
                reworkCompleted += ((Number) rw.get("completed")).longValue();
                reworkInput = reworkInput.add(dec(rw.get("input_quantity")));
                reworkCompletedInput = reworkCompletedInput.add(dec(rw.get("completed_input")));
                reworkOutput = reworkOutput.add(dec(rw.get("output_quantity")));
            }
        }
        var q = new LinkedHashMap<String, Object>();
        var inProcess = new LinkedHashMap<String, Object>();
        inProcess.put("goodCount", totals.get("goodCount"));
        inProcess.put("scrapCount", totals.get("scrapCount"));
        inProcess.put("goodRate", totals.get("goodRate"));
        q.put("inProcess", inProcess);
        var untimed = new LinkedHashMap<String, Object>();
        untimed.put("goodCount", totals.get("untimedGood"));
        untimed.put("scrapCount", totals.get("untimedScrap"));
        q.put("untimed", untimed);
        var post = new LinkedHashMap<String, Object>();
        post.put("dispositions", dispositions);
        post.put("quantity", scrapQty);
        post.put("value", scrapValue);
        post.put(
                "note",
                "Scrap dispositions (V12) posted in the range on lots produced by these work centers; reported apart from the OEE quality rate, which uses the operation reports");
        q.put("postProduction", post);
        var rw = new LinkedHashMap<String, Object>();
        rw.put("orders", reworkOrders);
        rw.put("completed", reworkCompleted);
        rw.put("inputQuantity", reworkInput);
        rw.put("outputQuantity", reworkOutput);
        rw.put(
                "yield",
                reworkCompletedInput.signum() > 0
                        ? ratio(reworkOutput.doubleValue() / reworkCompletedInput.doubleValue())
                        : null);
        q.put("rework", rw);
        q.put("byWorkCenter", byMachine);
        return q;
    }

    // ------------------------------------------------------------------ one work-center day

    /**
     * {@code GET /api/performance/days/{machineId}/{date}}: the live figures with working intervals, stops, operations
     * and warnings; the current snapshot when the day is closed (with {@code calendarChanged} / {@code differs} when the
     * live calculation no longer matches it) and every snapshot version.
     */
    public Map<String, Object> day(Actor a, String machineId, String date) {
        var machine = engine.machines(a.tenant(), machineId).getFirst();
        LocalDate day = date(date, "date");
        require(!day.isAfter(OeeEngine.today()), "Choose today or an earlier day");
        String id = str(machine, "id");
        var live = engine.live(a.tenant(), List.of(id), day, day, java.time.Instant.now())
                .get(id)
                .get(day);
        var snapshot = engine.snapshots(a.tenant(), List.of(id), day, day).get(id).get(day);
        var out = new LinkedHashMap<String, Object>();
        out.put("machine", machine);
        out.put("date", day.toString());
        out.put("today", OeeEngine.today().toString());
        out.put("live", live);
        out.put("snapshot", snapshot);
        if (snapshot != null) {
            out.put(
                    "calendarChanged",
                    Math.abs(Rollup.num(live, "scheduledMinutes") - Rollup.num(snapshot, "scheduledMinutes")) >= 0.01);
            out.put("differs", !same(live.get("oee"), snapshot.get("oee"))
                    || Math.abs(Rollup.num(live, "plannedMinutes") - Rollup.num(snapshot, "plannedMinutes")) >= 0.01);
        }
        out.put("history", s.list(
                """
                SELECT o.id,o.version,o.status,o.oee_status,o.availability,o.performance,o.quality,o.oee,
                       o.planned_minutes,o.run_minutes,o.reason,o.closed_at,u.name AS closed_by,o.superseded_at
                FROM oee_day_snapshots o JOIN users u ON u.id=o.closed_by
                WHERE o.tenant_id=? AND o.machine_id=? AND o.day=?::date
                ORDER BY o.version DESC""",
                a.tenant(),
                id,
                day.toString()));
        return out;
    }

    // ------------------------------------------------------------------ /api/state key

    /**
     * Yesterday's plant OEE for the overview (a completed day): OEE, A, P, Q, time and output coverage, measured and
     * closed work centers. Null when no work center has a measured OEE that day.
     */
    Map<String, Object> yesterday(String tenant) {
        LocalDate day = OeeEngine.today().minusDays(1);
        var machines = engine.machines(tenant, null);
        if (machines.isEmpty()) return null;
        var ids = machines.stream().map(m -> str(m, "id")).toList();
        var days = engine.days(tenant, ids, day, day);
        var plant = new Rollup();
        for (String id : ids) plant.add(days.get(id).get(day));
        if (plant.measuredDays() == 0) return null;
        var t = plant.toMap();
        var out = new LinkedHashMap<String, Object>();
        out.put("day", day.toString());
        for (String key : List.of(
                "oee",
                "availability",
                "performance",
                "quality",
                "timeCoverage",
                "outputCoverage",
                "utilization",
                "measuredDays",
                "closedDays",
                "warningCount")) out.put(key, t.get(key));
        out.put("workCenters", ids.size());
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> step(String key, String label, String kind, double minutes) {
        var m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("label", label);
        m.put("kind", kind);
        m.put("minutes", minutes(minutes));
        return m;
    }

    private static Map<String, Object> loss(String key, String label, String category, Double minutes, String source) {
        var m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("label", label);
        m.put("category", category);
        m.put("measured", minutes != null);
        m.put("minutes", minutes == null ? null : minutes(minutes));
        m.put("source", source);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static boolean same(Object x, Object y) {
        if (x == null || y == null) return x == y;
        return Math.abs(Double.parseDouble(x.toString()) - Double.parseDouble(y.toString())) < 0.000001;
    }

    static String periodKey(LocalDate d, String bucket) {
        if (bucket.equals("week"))
            return String.format(
                    "%d-W%02d", d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        return d.toString();
    }

    private static String label(LocalDate d) {
        return d.getDayOfMonth() + " " + MONTHS[d.getMonthValue() - 1];
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    static LocalDate date(String value, String label) {
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a valid calendar date for the " + label);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
