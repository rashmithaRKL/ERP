package lk.dexlanka.erp.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.routing.CalendarService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the base, item master, planning and V10 routing demo seeds inside one transaction that is rolled back, so the
 * shared test database keeps no demo tenant.
 */
class RoutingDemoSeedTest extends IntegrationTestBase {
    @Autowired
    DemoSeed base;

    @Autowired
    ItemMasterDemoSeed itemMaster;

    @Autowired
    PlanningDemoSeed planning;

    @Autowired
    RoutingDemoSeed routing;

    @Autowired
    CalendarService calendars;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void seedsSkillsCalendarsRoutingsAndAnOrderWithOneOperationDoneAndTheNextRunning() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            base.seedDemo();
            itemMaster.seed();
            planning.seed();
            routing.seed();

            assertThat(count("SELECT COUNT(*) FROM skills WHERE tenant_id='demo'"))
                    .isEqualTo(5);
            assertThat(count("SELECT COUNT(*) FROM user_skills WHERE tenant_id='demo' AND user_id='demo-operator'"))
                    .isEqualTo(5);
            assertThat(str(row("SELECT code FROM shift_calendars WHERE tenant_id='demo' AND plant_default"), "code"))
                    .isEqualTo("TWO-SHIFT");
            assertThat(count("SELECT COUNT(*) FROM work_center_calendars WHERE tenant_id='demo'"))
                    .isEqualTo(2);

            // Approved routings for FG-001, SF-001 and FG-101 are current; FG-101 also has an R02 draft.
            assertThat(count("SELECT COUNT(*) FROM routing_defaults WHERE tenant_id='demo'"))
                    .isEqualTo(3);
            assertThat(count("SELECT COUNT(*) FROM routings WHERE tenant_id='demo' AND status='DRAFT'"))
                    .isEqualTo(1);
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM routings WHERE tenant_id='demo' AND status='APPROVED' AND approved_by='demo-admin' AND created_by='demo-planner'"))
                    .isEqualTo(3);

            // The FG-001 order: operation 10 done after a four-hour run, operation 20 running (in setup).
            var order = row(
                    "SELECT o.* FROM manufacturing_orders o WHERE o.tenant_id='demo' AND o.product_id='bottle' AND o.routing_id IS NOT NULL");
            assertThat(order.get("status")).isEqualTo("IN_PROGRESS");
            var blow = row(
                    "SELECT * FROM mo_operations WHERE tenant_id='demo' AND order_id=? AND seq=10", order.get("id"));
            assertThat(blow.get("status")).isEqualTo("DONE");
            assertThat(dec(blow.get("good_qty"))).isEqualByComparingTo("4960");
            assertThat(decimal(
                            "SELECT SUM(minutes) FROM mo_operation_time_logs WHERE tenant_id='demo' AND operation_id=?",
                            blow.get("id")))
                    .isEqualByComparingTo("240");
            var pack = row(
                    "SELECT * FROM mo_operations WHERE tenant_id='demo' AND order_id=? AND seq=20", order.get("id"));
            assertThat(pack.get("status")).isEqualTo("IN_PROGRESS");
            assertThat(pack.get("activity")).isEqualTo("SETUP");
            assertThat(pack.get("operator_id")).isEqualTo("demo-operator");

            // Preform and PET bottle orders are released with their first operation ready.
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM mo_operations op JOIN manufacturing_orders o ON o.tenant_id=op.tenant_id AND o.id=op.order_id WHERE op.tenant_id='demo' AND op.seq=10 AND op.status='READY' AND o.status='RELEASED'"))
                    .isEqualTo(2);

            // Packing works the plant default two-shift calendar: day + evening on a weekday.
            LocalDate monday = LocalDate.now(CalendarService.ZONE).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            if (!monday.equals(LocalDate.now(CalendarService.ZONE).plusDays(9)))
                assertThat(calendars.availableMinutes("demo", "pk01", monday)).isEqualTo(900);

            // Idempotent: a second start changes nothing.
            long audits = count("SELECT COUNT(*) FROM audit_events WHERE tenant_id='demo'");
            routing.seed();
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id='demo'"))
                    .isEqualTo(audits);
            status.setRollbackOnly();
        });
        assertThat(count("SELECT COUNT(*) FROM routings WHERE tenant_id='demo'"))
                .isZero();
    }
}
