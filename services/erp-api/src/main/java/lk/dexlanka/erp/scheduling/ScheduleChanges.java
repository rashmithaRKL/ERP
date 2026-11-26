package lk.dexlanka.erp.scheduling;

import java.util.LinkedHashMap;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/**
 * V16 stale-schedule hint: how many changes that a new scheduler run would see happened after a run was created (its
 * results are frozen at {@code created_at}). Counted from the tenant's audit trail (every command audits, index
 * {@code audit_tenant_created_id}), so it is cheap and needs no extra bookkeeping:
 *
 * <ul>
 *   <li><b>Orders and operations</b>: manufacturing-order lifecycle and output, operation start/pause/resume/setup
 *       done/quantity report/finish, duration estimates, for orders that are still open or were part of the run (an
 *       order created and finished after the run never affected it);
 *   <li><b>Maintenance windows</b>: maintenance work orders approved, scheduled, generated, started, completed or
 *       cancelled when they have a window that ends after the run's start (an approval without a window blocks no
 *       capacity); started or completed orders without a window count too (they open or close downtime);
 *   <li><b>Downtime</b>: downtime reported, closed, corrected or withdrawn, unless the event ended before the run's
 *       start;
 *   <li><b>Capacity and planner input</b>: shift patterns, calendars, holidays/overtime, calendar assignments, new work
 *       centers, pins.
 * </ul>
 *
 * It is a hint ("may be out of date"), not a re-schedule: planners run the scheduler again.
 */
@Component
class ScheduleChanges {
    private final Store s;

    ScheduleChanges(Store s) {
        this.s = s;
    }

    /** {@code {changes, last_change_at}} since {@code run} (a {@code schedule_runs} row with id, created_at, horizon_start). */
    Map<String, Object> since(String tenant, Map<String, Object> run) {
        var row = s.one(
                """
                SELECT COUNT(*) AS changes, MAX(e.created_at) AS last_change_at
                FROM audit_events e
                WHERE e.tenant_id=? AND e.created_at>?
                  AND (
                    (e.action IN ('MO_CREATED','MO_AUTO_APPROVED','MO_APPROVE','MO_RESERVE','MO_ISSUE','MO_START','MO_PAUSE',
                                  'MO_RESUME','MO_CLOSE','MO_CANCEL','MO_CHILDREN_CREATED','PRODUCTION_POSTED',
                                  'OP_STARTED','OP_PAUSED','OP_RESUMED','OP_SETUP_DONE','OP_REPORTED','OP_FINISHED',
                                  'SCHEDULE_ESTIMATE_SET','SCHEDULE_ESTIMATE_REMOVED')
                     AND EXISTS (SELECT 1 FROM manufacturing_orders o WHERE o.tenant_id=e.tenant_id AND o.number=e.reference
                                   AND (o.status NOT IN ('QUALITY_REVIEW','COMPLETED','CLOSED','CANCELLED')
                                        OR EXISTS (SELECT 1 FROM schedule_run_orders ro
                                                   WHERE ro.tenant_id=o.tenant_id AND ro.run_id=? AND ro.order_id=o.id))))
                    OR (e.action IN ('MWO_APPROVED','MWO_SCHEDULED','MWO_GENERATED','MWO_STARTED','MWO_COMPLETED','MWO_CANCELLED')
                     AND EXISTS (SELECT 1 FROM maintenance_work_orders w WHERE w.tenant_id=e.tenant_id AND w.number=e.reference
                                   AND (w.scheduled_end>? OR (w.scheduled_end IS NULL AND e.action IN ('MWO_STARTED','MWO_COMPLETED')))))
                    OR (e.action IN ('DOWNTIME_REPORTED','DOWNTIME_CLOSED','DOWNTIME_CORRECTED','DOWNTIME_VOIDED')
                     AND EXISTS (SELECT 1 FROM downtime_events d WHERE d.tenant_id=e.tenant_id AND d.number=e.reference
                                   AND COALESCE(d.ended_at,'infinity'::timestamptz)>?))
                    OR e.action IN ('SCHEDULE_PINNED','SCHEDULE_UNPINNED','SHIFT_PATTERN_UPDATED','CALENDAR_CREATED',
                                    'CALENDAR_UPDATED','CALENDAR_EXCEPTION_ADDED','CALENDAR_EXCEPTION_REMOVED',
                                    'WORK_CENTER_CALENDAR_ASSIGNED','WORK_CENTER_CREATED')
                  )""", tenant, run.get("created_at"), run.get("id"), run.get("horizon_start"), run.get("horizon_start"));
        var out = new LinkedHashMap<String, Object>();
        out.put("changes", row.get("changes"));
        out.put("last_change_at", row.get("last_change_at"));
        return out;
    }
}
