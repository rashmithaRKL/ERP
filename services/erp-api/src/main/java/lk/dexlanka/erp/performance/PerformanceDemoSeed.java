package lk.dexlanka.erp.performance;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import lk.dexlanka.erp.demo.DemoPersonas;
import lk.dexlanka.erp.demo.DemoSeed;
import lk.dexlanka.erp.maintenance.DowntimeService;
import lk.dexlanka.erp.maintenance.MaintenanceCommands;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.routing.CalendarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V15 demo data (fictional): fourteen days of <b>clearly marked, backfilled demonstration history</b> for the OEE
 * dashboard, then the completed days closed through the real close command.
 *
 * <p>Operation time logs and quantity reports cannot be posted with past timestamps through the shop-floor commands,
 * so this seed (demo mode only) inserts them directly, attached to three dedicated demonstration orders numbered
 * {@code MO-DEMO-OEE-1..3} (status CLOSED; execution records only — no stock, cost or production postings): two FG-001
 * bottle campaigns (operation 10 on the alternate BM-02, operation 20 on PK-01, with a changeover between them) and an
 * SF-001 preform campaign on IM-01 (moulding and the weight check alternate on the same cell, as its routing plans).
 * Every report carries the note {@value #NOTE}. The history respects the data rules: only inside the shift calendar,
 * never overlapping another time log or effective downtime of the work center (existing V10/V11 demo rows are left
 * alone), packing never ahead of the bottles blown. Four extra downtime events (one long breakdown on BM-02: the bad
 * day) are reported through the V11 command. BM-01 keeps the base demo's output of orders without a routing (untimed),
 * IM-02/IM-03 keep only their V11 downtime, so the page also shows untimed and not-measured work centers honestly.
 * Days {@code today − 14 … today − 2} are closed; yesterday stays open for the planner's close action.
 */
@Component
@Order(DemoSeed.ORDER + 150)
public class PerformanceDemoSeed implements ApplicationRunner {
    static final String NOTE = "Demo OEE history (backfilled by the V15 demo seed)";
    static final int DAYS = 14;
    private static final Logger log = LoggerFactory.getLogger(PerformanceDemoSeed.class);
    private static final String T = DemoPersonas.TENANT;
    private static final long MIN = 60_000;

    private final Store s;
    private final CalendarService calendars;
    private final DowntimeService downtime;
    private final PerformanceService performance;
    private final TransactionTemplate tx;
    private final boolean demo;

    PerformanceDemoSeed(
            Store s,
            CalendarService calendars,
            DowntimeService downtime,
            PerformanceService performance,
            PlatformTransactionManager transactions,
            @Value("${app.demo:false}") boolean demo) {
        this.s = s;
        this.calendars = calendars;
        this.downtime = downtime;
        this.performance = performance;
        this.tx = new TransactionTemplate(transactions);
        this.demo = demo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demo) return;
        try {
            tx.executeWithoutResult(status -> seed());
        } catch (RuntimeException e) {
            log.warn("OEE demo history (V15) was not added: {}", e.getMessage());
        }
    }

    /** One planned operation of a demonstration order and its generated history. */
    static final class Op {
        final String id = Store.id();
        final String orderId;
        final Map<String, Object> routing;
        final String machine;
        final BigDecimal ideal;
        final List<Object[]> logs = new ArrayList<>(); // {activity, start, end}
        final List<Object[]> reports = new ArrayList<>(); // {good, scrap, at}
        BigDecimal good = BigDecimal.ZERO, scrap = BigDecimal.ZERO;

        Op(String orderId, Map<String, Object> routing, String machine) {
            this.orderId = orderId;
            this.routing = routing;
            this.machine = machine;
            this.ideal = dec(routing.get("run_minutes_per_unit"));
        }

        BigDecimal total() {
            return good.add(scrap);
        }

        void report(long count, double scrapRate, long at) {
            if (count <= 0) return;
            long bad = Math.round(count * scrapRate);
            reports.add(new Object[] {BigDecimal.valueOf(count - bad), BigDecimal.valueOf(bad), at});
            good = good.add(BigDecimal.valueOf(count - bad));
            scrap = scrap.add(BigDecimal.valueOf(bad));
        }
    }

    /**
     * Idempotent: does nothing once a {@code demo-v15-} command or a demonstration order exists. Public for {@code
     * demo.PerformanceDemoSeedTest}, which runs it after the earlier seeds in a rolled-back transaction.
     */
    public void seed() {
        if (s.list("SELECT 1 FROM tenants WHERE id=?", T).isEmpty()) return;
        if (!s.list("SELECT 1 FROM idempotency_keys WHERE tenant_id=? AND request_key LIKE 'demo-v15-%' LIMIT 1", T)
                        .isEmpty()
                || !s.list("SELECT 1 FROM manufacturing_orders WHERE tenant_id=? AND number LIKE 'MO-DEMO-OEE-%'", T)
                        .isEmpty()) return;
        if (s.list("SELECT id FROM machines WHERE tenant_id=? AND id IN ('bm02','pk01','im01')", T)
                        .size()
                < 3) return;
        var bottle = routing("bottle");
        if (bottle.size() < 2) return; // the V10 routing seed did not run
        var preformItem = s.list("SELECT id FROM items WHERE tenant_id=? AND lower(sku)='sf-001'", T);
        var preform =
                preformItem.isEmpty() ? List.<Map<String, Object>>of() : routing(str(preformItem.getFirst(), "id"));

        LocalDate today = LocalDate.now(CalendarService.ZONE);
        LocalDate first = today.minusDays(DAYS);
        Instant start = OeeEngine.dayStart(first);
        Instant end = OeeEngine.dayStart(today);
        Instant changeover = OeeEngine.dayStart(today.minusDays(7));

        // Extra downtime through the V11 command (skipped where it would overlap existing records).
        stop(
                "bm02-breakdown",
                "bm02",
                "BREAKDOWN",
                today.minusDays(9),
                "09:40",
                420,
                "Clamp hydraulic hose burst; hose and seals replaced");
        stop(
                "im01-pm",
                "im01",
                "PLANNED_MAINTENANCE",
                today.minusDays(12),
                "06:00",
                120,
                "Mould cleaning and water-line descaling");
        stop(
                "pk01-material",
                "pk01",
                "MATERIAL_SHORTAGE",
                today.minusDays(11),
                "15:10",
                45,
                "Label reels not staged at the applicator");
        stop(
                "im01-quality",
                "im01",
                "QUALITY_HOLD",
                today.minusDays(4),
                "11:20",
                35,
                "Preform weight drift; dryer checked");

        // ---------------------------------------------------------------- bottle campaigns: BM-02 blows, PK-01 packs
        String orderA = Store.id(), orderB = Store.id();
        var blowA = new Op(orderA, bottle.get(0), "bm02");
        var packA = new Op(orderA, bottle.get(1), "pk01");
        var blowB = new Op(orderB, bottle.get(0), "bm02");
        var packB = new Op(orderB, bottle.get(1), "pk01");
        var bm02 = free("bm02", start, end);
        generate(bm02, start, changeover, blowA, null, new Random(1102), 0.85, 0.018, 25, 15);
        generate(bm02, changeover, end, blowB, null, new Random(1103), 0.86, 0.015, 25, 15);
        var pk01 = free("pk01", start, end);
        generate(pk01, start, changeover, packA, blowA, new Random(2201), 0.84, 0.004, 25, 15);
        generate(pk01, changeover, end, packB, blowB, new Random(2202), 0.85, 0.004, 25, 15);

        // ---------------------------------------------------------------- preforms on IM-01 (moulding + weight check)
        String orderC = Store.id();
        List<Op> preformOps = List.of();
        if (preform.size() >= 2 && preform.get(0).get("machine_id").equals("im01")) {
            var mould = new Op(orderC, preform.get(0), "im01");
            var check = new Op(orderC, preform.get(1), "im01");
            cell(free("im01", start, end), mould, check, new Random(3301));
            preformOps = List.of(mould, check);
        }

        int logs = 0, reports = 0;
        logs += insertOrder("MO-DEMO-OEE-1", orderA, "bottle", List.of(blowA, packA));
        logs += insertOrder("MO-DEMO-OEE-2", orderB, "bottle", List.of(blowB, packB));
        if (!preformOps.isEmpty())
            logs += insertOrder("MO-DEMO-OEE-3", orderC, str(preformItem.getFirst(), "id"), preformOps);
        for (var op : List.of(blowA, packA, blowB, packB)) reports += op.reports.size();
        for (var op : preformOps) reports += op.reports.size();
        s.audit(
                DemoPersonas.PLANNER,
                "DEMO_OEE_HISTORY_ADDED",
                "MO-DEMO-OEE",
                "Fictional demonstration history: " + logs + " operation time logs and " + reports
                        + " quantity reports backfilled for " + first + " – " + today.minusDays(1)
                        + " on BM-02, PK-01 and IM-01 (orders MO-DEMO-OEE-1..3, execution records only)");

        for (LocalDate d = first; d.isBefore(today.minusDays(1)); d = d.plusDays(1))
            performance.close(
                    DemoPersonas.PLANNER,
                    new PerformanceCommands.Close(d.toString(), null, null, null),
                    "demo-v15-close-" + d);
        log.info("OEE demo history (V15) added and {} days closed", DAYS - 1);
    }

    /** Operations of the product's current routing, in sequence. */
    private List<Map<String, Object>> routing(String product) {
        return s.list("""
                SELECT o.*,r.revision FROM routing_defaults d
                JOIN routings r ON r.tenant_id=d.tenant_id AND r.id=d.routing_id
                JOIN routing_operations o ON o.tenant_id=r.tenant_id AND o.routing_id=r.id
                WHERE d.tenant_id=? AND d.product_id=? ORDER BY o.seq""", T, product);
    }

    private void stop(
            String key, String machine, String reason, LocalDate day, String time, int minutes, String notes) {
        OffsetDateTime from =
                day.atTime(LocalTime.parse(time)).atZone(CalendarService.ZONE).toOffsetDateTime();
        OffsetDateTime to = from.plusMinutes(minutes);
        boolean busy = !s.list("""
                        SELECT 1 FROM machine_downtime WHERE tenant_id=? AND machine_id=?
                          AND started_at<? AND COALESCE(ended_at,'infinity'::timestamptz)>?
                        UNION ALL
                        SELECT 1 FROM mo_operation_time_logs WHERE tenant_id=? AND machine_id=?
                          AND started_at<? AND COALESCE(ended_at,'infinity'::timestamptz)>?""", T, machine, to, from, T, machine, to, from).isEmpty();
        if (busy) return;
        Actor reporter = reason.equals("PLANNED_MAINTENANCE") ? DemoPersonas.PLANNER : DemoPersonas.OPERATOR;
        downtime.report(
                reporter,
                new MaintenanceCommands.ReportDowntime(
                        machine, reason, null, from.toString(), to.toString(), null, notes + " (demo)"),
                "demo-v15-downtime-" + key);
    }

    /** Shift working time of a work center in [start, end) less its effective downtime and existing time logs. */
    private Spans free(String machine, Instant start, Instant end) {
        var working = new ArrayList<long[]>();
        for (var i : calendars.workingIntervals(T, machine, start, end))
            working.add(new long[] {
                i.start().toInstant().toEpochMilli(), i.end().toInstant().toEpochMilli()
            });
        var busy = new ArrayList<long[]>();
        for (var r : s.list(
                """
                SELECT started_at,ended_at FROM machine_downtime WHERE tenant_id=? AND machine_id=?
                  AND started_at<? AND COALESCE(ended_at,'infinity'::timestamptz)>?
                UNION ALL
                SELECT started_at,ended_at FROM mo_operation_time_logs WHERE tenant_id=? AND machine_id=?
                  AND started_at<? AND COALESCE(ended_at,'infinity'::timestamptz)>?""",
                T,
                machine,
                OeeEngine.utc(end),
                OeeEngine.utc(start),
                T,
                machine,
                OeeEngine.utc(end),
                OeeEngine.utc(start)))
            busy.add(new long[] {
                OeeEngine.instant(r.get("started_at")).toEpochMilli(),
                r.get("ended_at") == null
                        ? end.toEpochMilli()
                        : OeeEngine.instant(r.get("ended_at")).toEpochMilli()
            });
        return Spans.union(working).minus(Spans.union(busy));
    }

    /**
     * Runs {@code op} through the free time in [from, to): the routing setup first, a start-up setup after a long gap,
     * short unrecorded gaps (idle, small stops), reports every two hours of run. With a {@code supplier} (packing) the
     * reported count never exceeds the good output the supplier reported before; a segment is skipped while it has
     * nothing to pack.
     */
    private void generate(
            Spans free,
            Instant from,
            Instant to,
            Op op,
            Op supplier,
            Random rnd,
            double perf,
            double scrapRate,
            int maxGap,
            int startupSetup) {
        Spans window = free.intersect(Spans.of(from, to));
        long previousEnd = Long.MIN_VALUE;
        boolean first = true;
        long consumed = 0;
        for (var seg : window.pairs()) {
            long a = seg[0], b = seg[1];
            if (b - a < 20 * MIN) continue;
            long setup = first
                    ? Math.round(dec(op.routing.get("setup_minutes")).doubleValue()) * MIN
                    : (a - previousEnd >= 6 * 60 * MIN ? startupSetup * MIN : 0);
            if (!first) a += rnd.nextInt(maxGap + 1) * MIN;
            if (setup > 0 && a + setup < b) {
                op.logs.add(new Object[] {"SETUP", a, a + setup});
                a += setup;
            }
            first = false;
            long stopAt = b - rnd.nextInt(Math.max(1, maxGap / 2)) * MIN;
            while (stopAt - a >= 15 * MIN) {
                // Run for 1.5–4 hours, then a short unrecorded stop.
                long runEnd = Math.min(stopAt, a + (90 + rnd.nextInt(150)) * MIN);
                double dayPerf = perf + (rnd.nextDouble() - 0.5) * 0.08;
                if (supplier != null) {
                    long available = available(supplier, a) - consumed;
                    if (available < 500) {
                        a = runEnd + (10 + rnd.nextInt(20)) * MIN;
                        continue;
                    }
                }
                op.logs.add(new Object[] {"RUN", a, runEnd});
                for (long t = a; t < runEnd; ) {
                    long reportAt = Math.min(Math.min(runEnd, t + 120 * MIN), nextMidnight(t));
                    long count = (long) Math.floor((reportAt - t) / (double) MIN * dayPerf / op.ideal.doubleValue());
                    if (supplier != null) count = Math.min(count, available(supplier, stamp(reportAt)) - consumed);
                    double rate = scrapRate * (0.6 + rnd.nextDouble() * 0.8);
                    op.report(count, rate, stamp(reportAt));
                    consumed += Math.max(0, count);
                    t = reportAt;
                }
                a = runEnd + (3 + rnd.nextInt(maxGap)) * MIN;
            }
            previousEnd = b;
        }
    }

    /**
     * Quantities are reported at least at every local midnight, so a business day's counts match its run time; a report
     * closing the day is stamped one second before midnight.
     */
    private static long nextMidnight(long millis) {
        LocalDate day = LocalDate.ofInstant(Instant.ofEpochMilli(millis), CalendarService.ZONE);
        return OeeEngine.dayStart(day.plusDays(1)).toEpochMilli();
    }

    private static long stamp(long reportAt) {
        LocalDate day = LocalDate.ofInstant(Instant.ofEpochMilli(reportAt), CalendarService.ZONE);
        return OeeEngine.dayStart(day).toEpochMilli() == reportAt ? reportAt - 1000 : reportAt;
    }

    /** Good output the supplier reported up to {@code at}. */
    private static long available(Op supplier, long at) {
        long good = 0;
        for (var r : supplier.reports) if ((long) r[2] <= at) good += ((BigDecimal) r[0]).longValue();
        return good;
    }

    /**
     * IM-01 moulds preforms (operation 10) and checks them (operation 20) on the same cell: a check segment whenever
     * 30,000 preforms are waiting, moulding otherwise.
     */
    private void cell(Spans free, Op mould, Op check, Random rnd) {
        long previousEnd = Long.MIN_VALUE;
        boolean first = true;
        long checked = 0;
        for (var seg : free.pairs()) {
            long a = seg[0], b = seg[1];
            if (b - a < 20 * MIN) continue;
            long waiting = mould.good.longValue() - checked;
            Op op = waiting >= 30_000 ? check : mould;
            long setup = first
                    ? Math.round(dec(mould.routing.get("setup_minutes")).doubleValue()) * MIN
                    : (a - previousEnd >= 6 * 60 * MIN ? 20 * MIN : 0);
            if (!first) a += rnd.nextInt(16) * MIN;
            if (setup > 0 && a + setup < b) {
                mould.logs.add(new Object[] {"SETUP", a, a + setup});
                a += setup;
                op = mould;
            }
            first = false;
            long stopAt = b - rnd.nextInt(8) * MIN;
            while (stopAt - a >= 15 * MIN) {
                long runEnd = Math.min(stopAt, a + (100 + rnd.nextInt(120)) * MIN);
                double perf = (op == mould ? 0.83 : 0.86) + (rnd.nextDouble() - 0.5) * 0.08;
                op.logs.add(new Object[] {"RUN", a, runEnd});
                for (long t = a; t < runEnd; ) {
                    long reportAt = Math.min(Math.min(runEnd, t + 120 * MIN), nextMidnight(t));
                    long count = (long) Math.floor((reportAt - t) / (double) MIN * perf / op.ideal.doubleValue());
                    if (op == check) count = Math.min(count, mould.good.longValue() - checked);
                    op.report(count, op == mould ? 0.022 * (0.6 + rnd.nextDouble() * 0.8) : 0.003, stamp(reportAt));
                    if (op == check) checked += Math.max(0, count);
                    t = reportAt;
                }
                a = runEnd + (6 + rnd.nextInt(25)) * MIN;
                if (op == check && mould.good.longValue() - checked < 2_000) op = mould;
            }
            previousEnd = b;
        }
    }

    /** Inserts the demonstration order (CLOSED), its DONE operations, time logs and reports; returns the log count. */
    private int insertOrder(String number, String orderId, String product, List<Op> ops) {
        if (ops.stream().allMatch(o -> o.logs.isEmpty())) return 0;
        var boms = s.list(
                "SELECT id,revision FROM boms WHERE tenant_id=? AND product_id=? AND status='APPROVED' ORDER BY created_at DESC LIMIT 1",
                T,
                product);
        if (boms.isEmpty()) return 0;
        var bom = boms.getFirst();
        long firstLog = ops.stream()
                .flatMap(o -> o.logs.stream())
                .mapToLong(l -> (long) l[1])
                .min()
                .orElseThrow();
        long lastLog = ops.stream()
                .flatMap(o -> o.logs.stream())
                .mapToLong(l -> (long) l[2])
                .max()
                .orElseThrow();
        Op head = ops.getFirst();
        Op last = ops.getLast();
        BigDecimal quantity = head.total().max(BigDecimal.ONE);
        s.db().update(
                        """
                        INSERT INTO manufacturing_orders(id,tenant_id,number,product_id,bom_id,bom_revision,machine_id,factory_id,
                          quantity,good_qty,scrap_qty,status,priority,due_date,created_by,created_at,started_at,routing_id,
                          routing_revision)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,'CLOSED','NORMAL',?::date,?,?,?,?,?)""",
                        orderId,
                        T,
                        number,
                        product,
                        bom.get("id"),
                        bom.get("revision"),
                        head.machine,
                        T + "-plant",
                        quantity,
                        last.good,
                        last.scrap,
                        LocalDate.ofInstant(Instant.ofEpochMilli(lastLog), CalendarService.ZONE)
                                .toString(),
                        DemoPersonas.PLANNER.id(),
                        time(firstLog - 60 * MIN),
                        time(firstLog),
                        head.routing.get("routing_id"),
                        head.routing.get("revision"));
        int logs = 0;
        for (var op : ops) {
            long opStart = op.logs.isEmpty() ? firstLog : (long) op.logs.getFirst()[1];
            long opEnd = op.logs.isEmpty() ? lastLog : (long) op.logs.getLast()[2];
            s.db().update(
                            """
                            INSERT INTO mo_operations(id,tenant_id,order_id,routing_operation_id,seq,name,machine_id,planned_machine_id,
                              planned_quantity,setup_minutes,run_minutes_per_unit,planned_run_minutes,crew_size,skill_id,skill_level,
                              inspection_point,instructions,status,good_qty,scrap_qty,operator_id,ready_at,started_at,finished_at,
                              created_at,updated_at)
                            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DONE',?,?,?,?,?,?,?,?)""",
                            op.id,
                            T,
                            orderId,
                            op.routing.get("id"),
                            op.routing.get("seq"),
                            op.routing.get("name"),
                            op.machine,
                            op.routing.get("machine_id"),
                            quantity,
                            op.routing.get("setup_minutes"),
                            op.ideal,
                            quantity.multiply(op.ideal).setScale(6, RoundingMode.HALF_UP),
                            op.routing.get("crew_size"),
                            op.routing.get("skill_id"),
                            op.routing.get("skill_level"),
                            op.routing.get("inspection_point"),
                            NOTE,
                            op.good,
                            op.scrap,
                            DemoPersonas.OPERATOR.id(),
                            time(firstLog - 60 * MIN),
                            time(opStart),
                            time(opEnd),
                            time(firstLog - 60 * MIN),
                            time(opEnd));
            for (int i = 0; i < op.logs.size(); i++) {
                var l = op.logs.get(i);
                String reason = i == op.logs.size() - 1 ? "FINISHED" : l[0].equals("SETUP") ? "SWITCHED" : "PAUSED";
                s.db().update(
                                """
                                INSERT INTO mo_operation_time_logs(id,tenant_id,operation_id,order_id,machine_id,user_id,activity,
                                  crew_size,started_at,ended_at,end_reason)
                                VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                                Store.id(),
                                T,
                                op.id,
                                orderId,
                                op.machine,
                                DemoPersonas.OPERATOR.id(),
                                l[0],
                                op.routing.get("crew_size"),
                                time((long) l[1]),
                                time((long) l[2]),
                                reason);
                logs++;
            }
            for (var r : op.reports)
                s.db().update(
                                """
                                INSERT INTO mo_operation_reports(id,tenant_id,operation_id,order_id,machine_id,good_qty,scrap_qty,notes,
                                  reported_by,created_at)
                                VALUES(?,?,?,?,?,?,?,?,?,?)""",
                                Store.id(),
                                T,
                                op.id,
                                orderId,
                                op.machine,
                                r[0],
                                r[1],
                                NOTE,
                                DemoPersonas.OPERATOR.id(),
                                time((long) r[2]));
        }
        return logs;
    }

    private static OffsetDateTime time(long millis) {
        return OeeEngine.utc(Instant.ofEpochMilli(millis));
    }
}
