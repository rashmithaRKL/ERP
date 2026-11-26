package lk.dexlanka.erp.scheduling;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.reporting.StateContributor;
import lk.dexlanka.erp.reporting.StateRequest;
import lk.dexlanka.erp.reporting.StateSection;
import org.springframework.stereotype.Component;

/**
 * {@code /api/state} key {@code schedule} (order 2300, every role): the published run ({@code published}: id, number,
 * published_at, horizon_end; null when none), the number of its orders that are projected late and still open
 * ({@code late_orders}), unscheduled orders ({@code unscheduled_orders}) and up to five late orders ({@code late}) for
 * the overview and the attention center. V16 adds {@code changes_since_publish} and {@code last_change_at}: changes to
 * orders, operations, maintenance windows, downtime and calendars after the published run was created ({@link
 * ScheduleChanges}), so the overview, attention center and schedule page can say it may be out of date. Screens read
 * {@code /api/schedule/**} for everything else.
 */
@Component
class ScheduleStateSections implements StateContributor {
    private static final String STILL_OPEN = "o.status NOT IN ('QUALITY_REVIEW','COMPLETED','CLOSED','CANCELLED')";

    private final Store s;
    private final ScheduleChanges changes;

    ScheduleStateSections(Store s, ScheduleChanges changes) {
        this.s = s;
        this.changes = changes;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(StateSection.of("schedule", 2300, this::schedule));
    }

    private Map<String, Object> schedule(StateRequest r) {
        var result = new LinkedHashMap<String, Object>();
        var runs = s.list(
                "SELECT id,number,created_at,published_at,horizon_start,horizon_end FROM schedule_runs WHERE tenant_id=? AND status='PUBLISHED'",
                r.tenant());
        if (runs.isEmpty()) {
            result.put("published", null);
            result.put("late_orders", 0);
            result.put("unscheduled_orders", 0);
            result.put("late", List.of());
            result.put("changes_since_publish", 0);
            result.put("last_change_at", null);
            return result;
        }
        var run = runs.getFirst();
        result.put("published", run);
        var stale = changes.since(r.tenant(), run);
        result.put("changes_since_publish", stale.get("changes"));
        result.put("last_change_at", stale.get("last_change_at"));
        result.put(
                "late_orders",
                s.db().queryForObject(
                                "SELECT COUNT(*) FROM schedule_run_orders ro JOIN manufacturing_orders o ON o.tenant_id=ro.tenant_id AND o.id=ro.order_id WHERE ro.tenant_id=? AND ro.run_id=? AND ro.late AND "
                                        + STILL_OPEN,
                                Long.class,
                                r.tenant(),
                                run.get("id")));
        result.put(
                "unscheduled_orders",
                s.db().queryForObject(
                                "SELECT COUNT(*) FROM schedule_run_orders ro JOIN manufacturing_orders o ON o.tenant_id=ro.tenant_id AND o.id=ro.order_id WHERE ro.tenant_id=? AND ro.run_id=? AND ro.status IN ('NO_ROUTING','BEYOND_HORIZON') AND "
                                        + STILL_OPEN,
                                Long.class,
                                r.tenant(),
                                run.get("id")));
        result.put("late", s.list("""
                        SELECT ro.order_id,o.number,i.name AS product,ro.priority,ro.due_date,ro.projected_end,ro.lateness_minutes,ro.status
                        FROM schedule_run_orders ro
                        JOIN manufacturing_orders o ON o.tenant_id=ro.tenant_id AND o.id=ro.order_id
                        JOIN items i ON i.tenant_id=o.tenant_id AND i.id=o.product_id
                        WHERE ro.tenant_id=? AND ro.run_id=? AND ro.late AND """ + " " + STILL_OPEN + """

                        ORDER BY ro.lateness_minutes DESC,ro.sequence
                        LIMIT 5""", r.tenant(), run.get("id")));
        return result;
    }
}
