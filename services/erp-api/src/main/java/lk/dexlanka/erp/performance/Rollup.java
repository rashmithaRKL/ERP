package lk.dexlanka.erp.performance;

import static lk.dexlanka.erp.performance.OeeCalculator.minutes;
import static lk.dexlanka.erp.performance.OeeCalculator.ratio;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import lk.dexlanka.erp.platform.Store;

/**
 * Time-weighted roll-up of work-center days (several days, several work centers or both). Ratios are never averaged:
 * the time components of the measured days are summed and divided, so A × P × Q = OEE also holds for the roll-up:
 *
 * <ul>
 *   <li>Availability = Σ run / Σ planned production time;
 *   <li>Performance = Σ net run / Σ run;
 *   <li>Quality = Σ fully productive / Σ net run (weighted by net run time);
 *   <li>OEE = Σ fully productive / Σ planned production time.
 * </ul>
 *
 * Only days with a measured OEE contribute to these sums; the planned time of days that are not measured (no data,
 * untimed output) is reported as the uncovered share. Utilization and counts include every day.
 */
final class Rollup {
    private double calendar, scheduled, plannedAll, plannedStop, busy, busyInShift;
    private double mCalendar, mScheduled, mPlannedStop, planned, run, net, fp, setup, changeover, breakdown, other;
    private double unrecorded;
    private BigDecimal good = BigDecimal.ZERO, scrap = BigDecimal.ZERO, covered = BigDecimal.ZERO;
    private BigDecimal untimedGood = BigDecimal.ZERO, untimedScrap = BigDecimal.ZERO;
    private int days, measured, closed, warnings;
    private final Map<String, Integer> statuses = new TreeMap<>();

    void add(Map<String, Object> f) {
        days++;
        statuses.merge(Store.str(f, "status"), 1, Integer::sum);
        if (Boolean.TRUE.equals(f.get("closed"))) closed++;
        if (f.get("warnings") instanceof java.util.List<?> w) warnings += w.size();
        calendar += num(f, "calendarMinutes");
        scheduled += num(f, "scheduledMinutes");
        plannedAll += num(f, "plannedMinutes");
        plannedStop += num(f, "plannedStopMinutes");
        busy += num(f, "busyMinutes");
        busyInShift += num(f, "busyInShiftMinutes");
        good = good.add(Store.dec(f.get("goodCount")));
        scrap = scrap.add(Store.dec(f.get("scrapCount")));
        covered = covered.add(Store.dec(f.get("coveredCount")));
        untimedGood = untimedGood.add(Store.dec(f.get("untimedGood")));
        untimedScrap = untimedScrap.add(Store.dec(f.get("untimedScrap")));
        if (f.get("oee") == null) return;
        measured++;
        mCalendar += num(f, "calendarMinutes");
        mScheduled += num(f, "scheduledMinutes");
        mPlannedStop += num(f, "plannedStopMinutes");
        planned += num(f, "plannedMinutes");
        run += num(f, "runMinutes");
        net += num(f, "netRunMinutes");
        fp += num(f, "fullyProductiveMinutes");
        setup += num(f, "setupMinutes");
        changeover += num(f, "changeoverMinutes");
        breakdown += num(f, "breakdownMinutes");
        other += num(f, "otherStopMinutes");
        unrecorded += num(f, "unrecordedMinutes");
    }

    int measuredDays() {
        return measured;
    }

    /** Most telling status: MEASURED when any day is measured, else UNTIMED, NO_DATA, NOT_SCHEDULED. */
    String status() {
        if (measured > 0) return OeeCalculator.MEASURED;
        for (String s : new String[] {OeeCalculator.UNTIMED, OeeCalculator.NO_DATA, OeeCalculator.MEASURED})
            if (statuses.containsKey(s)) return s;
        return days == 0 ? null : OeeCalculator.NOT_SCHEDULED;
    }

    Map<String, Object> toMap() {
        var m = new LinkedHashMap<String, Object>();
        m.put("status", status());
        m.put("days", days);
        m.put("measuredDays", measured);
        m.put("closedDays", closed);
        m.put("warningCount", warnings);
        m.put("statusCounts", new TreeMap<>(statuses));
        m.put("availability", planned > 0 ? ratio(run / planned) : null);
        m.put("performance", measured > 0 && run > 0 ? ratio(net / run) : null);
        m.put("quality", measured > 0 && net > 0 ? ratio(fp / net) : null);
        m.put("oee", planned > 0 ? ratio(fp / planned) : null);
        m.put("utilization", scheduled > 0 ? ratio(busyInShift / scheduled) : null);
        m.put("capacityUtilization", calendar > 0 ? ratio(busy / calendar) : null);
        m.put("timeCoverage", plannedAll > 0 ? ratio(planned / plannedAll) : null);
        BigDecimal total = good.add(scrap);
        BigDecimal output = total.add(untimedGood).add(untimedScrap);
        m.put("outputCoverage", output.signum() > 0 ? ratio(covered.doubleValue() / output.doubleValue()) : null);
        m.put("calendarMinutes", minutes(calendar));
        m.put("scheduledMinutes", minutes(scheduled));
        m.put("plannedStopMinutes", minutes(plannedStop));
        m.put("plannedMinutesAll", minutes(plannedAll));
        m.put("plannedMinutes", minutes(planned));
        m.put("runMinutes", minutes(run));
        m.put("netRunMinutes", minutes(net));
        m.put("fullyProductiveMinutes", minutes(fp));
        m.put("setupMinutes", minutes(setup));
        m.put("changeoverMinutes", minutes(changeover));
        m.put("breakdownMinutes", minutes(breakdown));
        m.put("otherStopMinutes", minutes(other));
        m.put("unrecordedMinutes", minutes(unrecorded));
        m.put("busyMinutes", minutes(busy));
        m.put("busyInShiftMinutes", minutes(busyInShift));
        m.put("measuredCalendarMinutes", minutes(mCalendar));
        m.put("measuredScheduledMinutes", minutes(mScheduled));
        m.put("measuredPlannedStopMinutes", minutes(mPlannedStop));
        m.put("goodCount", good);
        m.put("scrapCount", scrap);
        m.put("totalCount", total);
        m.put("coveredCount", covered);
        m.put("untimedGood", untimedGood);
        m.put("untimedScrap", untimedScrap);
        m.put("goodRate", total.signum() > 0 ? ratio(good.doubleValue() / total.doubleValue()) : null);
        return m;
    }

    static double num(Map<String, Object> f, String key) {
        Object v = f.get(key);
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }
}
