package lk.dexlanka.erp.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** Downtime events: reporting, closing, overlap rules, append-only corrections, permissions and idempotency. */
class DowntimeTest extends MaintenanceTestSupport {

    @Test
    void reportAndCloseAnOpenBreakdown() throws Exception {
        var open = json(command(operator, "/api/maintenance/downtime", report(m1, "BREAKDOWN", null, null))
                .andExpect(status().isOk()));
        assertThat(open.get("status")).isEqualTo("OPEN");
        assertThat(open.get("planned")).isEqualTo(false);
        assertThat(open.get("ended_at")).isNull();
        String number = str(row("SELECT number FROM downtime_events WHERE id=?", open.get("id")), "number");
        assertThat(number).startsWith("DT-");
        // Downtime alone never changes the machine status.
        assertThat(machineStatus(m1)).isEqualTo("IDLE");

        // A machine has at most one open event.
        command(operator, "/api/maintenance/downtime", report(m1, "CHANGEOVER", null, null))
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already has open downtime")));

        var closed = json(command(
                        technician,
                        "/api/maintenance/downtime/" + open.get("id") + "/close",
                        new MaintenanceCommands.CloseDowntime(null, "Reset the jam"))
                .andExpect(status().isOk()));
        assertThat(closed.get("status")).isEqualTo("CLOSED");
        assertThat(closed.get("ended_at")).isNotNull();
        var stored = row("SELECT * FROM downtime_events WHERE id=?", open.get("id"));
        assertThat(stored.get("closed_by")).isEqualTo(technician.id());
        assertThat(stored.get("notes")).isEqualTo("Reset the jam");
        command(
                        operator,
                        "/api/maintenance/downtime/" + open.get("id") + "/close",
                        new MaintenanceCommands.CloseDowntime(null, null))
                .andExpect(status().isConflict());
        assertThat(actions(number)).containsExactly("DOWNTIME_REPORTED", "DOWNTIME_CLOSED");
    }

    @Test
    void effectiveEventsOfOneMachineNeverOverlap() {
        closedDowntime(operator, m2, "BREAKDOWN", "2025-03-01T08:00:00Z", "2025-03-01T09:00:00Z");
        assertThatThrownBy(
                        () -> closedDowntime(operator, m2, "UTILITIES", "2025-03-01T08:30:00Z", "2025-03-01T09:30:00Z"))
                .hasMessageContaining("overlaps");
        // Back-to-back events touch but do not overlap; another machine is independent.
        closedDowntime(operator, m2, "UTILITIES", "2025-03-01T09:00:00Z", "2025-03-01T09:30:00Z");
        closedDowntime(operator, m1, "UTILITIES", "2025-03-01T08:30:00Z", "2025-03-01T09:30:00Z");
        // An open event started in the past overlaps every later closed event of the machine.
        assertThatThrownBy(() -> downtime.report(operator, report(m2, "OTHER", "2025-03-01T09:15:00Z", null), id()))
                .hasMessageContaining("overlaps");
        downtime.report(operator, report(m2, "OTHER", "2025-03-01T09:30:00Z", null), id());
        assertThat(count("SELECT COUNT(*) FROM machine_downtime WHERE tenant_id=? AND machine_id=?", t, m2))
                .isEqualTo(3);
        assertThatThrownBy(() -> downtime.report(operator, report(m1, "OTHER", "2025-03-01T08:00:00Z", null), id()))
                .hasMessageContaining("overlaps");
    }

    @Test
    void reasonsDecidePlannedAndTimesMustBeReal() {
        assertThatThrownBy(() -> downtime.report(
                        operator,
                        new MaintenanceCommands.ReportDowntime(
                                m1, "BREAKDOWN", true, "2025-03-02T08:00:00Z", "2025-03-02T09:00:00Z", null, ""),
                        id()))
                .hasMessageContaining("always unplanned");
        assertThatThrownBy(() -> downtime.report(
                        operator,
                        new MaintenanceCommands.ReportDowntime(
                                m1,
                                "PLANNED_MAINTENANCE",
                                false,
                                "2025-03-02T08:00:00Z",
                                "2025-03-02T09:00:00Z",
                                null,
                                ""),
                        id()))
                .hasMessageContaining("always planned");
        var changeover = closedDowntime(operator, m1, "CHANGEOVER", "2025-03-02T08:00:00Z", "2025-03-02T08:20:00Z");
        assertThat(changeover.get("planned")).isEqualTo(true);
        var shortage = closedDowntime(
                operator, m1, "MATERIAL_SHORTAGE", "2025-03-02T08:20:00+00:00", "2025-03-02T14:30:00+05:30");
        assertThat(shortage.get("planned")).isEqualTo(false);
        // Local times without an offset are Asia/Colombo.
        var local = closedDowntime(operator, m1, "OTHER", "2025-03-02T15:00", "2025-03-02T15:30");
        assertThat(MaintenanceRules.instant(local.get("started_at")))
                .isEqualTo(OffsetDateTime.of(2025, 3, 2, 9, 30, 0, 0, ZoneOffset.UTC)
                        .toInstant());

        String future = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2).toString();
        assertThatThrownBy(() -> downtime.report(operator, report(m1, "OTHER", future, null), id()))
                .hasMessageContaining("cannot be in the future");
        assertThatThrownBy(() -> closedDowntime(operator, m1, "OTHER", "2025-03-03T10:00:00Z", "2025-03-03T09:00:00Z"))
                .hasMessageContaining("end after it starts");
        assertThatThrownBy(() -> closedDowntime(operator, m1, "OTHER", "yesterday", null))
                .hasMessageContaining("valid date and time");
    }

    @Test
    void closedEventsChangeOnlyThroughCorrections() throws Exception {
        var original = closedDowntime(operator, m1, "BREAKDOWN", "2025-03-04T08:00:00Z", "2025-03-04T09:00:00Z");
        String id = str(original, "id");
        String number = str(row("SELECT number FROM downtime_events WHERE id=?", id), "number");

        // Only planners correct; operators and technicians cannot.
        var fix = new MaintenanceCommands.CorrectDowntime(
                "CHANGEOVER",
                null,
                "2025-03-04T08:00:00Z",
                "2025-03-04T08:45:00Z",
                null,
                "Logged the wrong reason",
                false);
        command(operator, "/api/maintenance/downtime/" + id + "/correct", fix).andExpect(status().isForbidden());
        assertThatThrownBy(() -> downtime.correct(technician, id, fix, id())).isInstanceOf(AccessDeniedException.class);

        var corrected = downtime.correct(planner, id, fix, id());
        assertThat(corrected.get("status")).isEqualTo("CLOSED");
        assertThat(corrected.get("corrects_id")).isEqualTo(id);
        assertThat(row("SELECT status FROM downtime_events WHERE id=?", id).get("status"))
                .isEqualTo("SUPERSEDED");
        var effective = rows("SELECT * FROM machine_downtime WHERE tenant_id=? AND machine_id=?", t, m1);
        assertThat(effective).hasSize(1);
        assertThat(effective.getFirst().get("reason")).isEqualTo("CHANGEOVER");
        assertThat(effective.getFirst().get("planned")).isEqualTo(true);

        // The original is corrected once; the history is append-only at the database level too.
        assertThatThrownBy(() -> downtime.correct(planner, id, fix, id())).hasMessageContaining("already corrected");
        String current = str(corrected, "id");
        assertThatThrownBy(() -> update("UPDATE downtime_events SET notes='x' WHERE id=?", current))
                .hasMessageContaining("Closed downtime events are immutable");
        assertThatThrownBy(() -> update("UPDATE downtime_events SET notes='x' WHERE id=?", id))
                .hasMessageContaining("Corrected downtime events are immutable");
        assertThatThrownBy(() -> update("DELETE FROM downtime_events WHERE id=?", current))
                .hasMessageContaining("cannot be deleted");

        // A correction may not overlap another effective event of the machine.
        closedDowntime(operator, m1, "UTILITIES", "2025-03-04T09:00:00Z", "2025-03-04T09:30:00Z");
        assertThatThrownBy(() -> downtime.correct(
                        planner,
                        current,
                        new MaintenanceCommands.CorrectDowntime(
                                "CHANGEOVER",
                                null,
                                "2025-03-04T08:00:00Z",
                                "2025-03-04T09:10:00Z",
                                null,
                                "Longer than logged",
                                false),
                        id()))
                .hasMessageContaining("overlaps");

        // Withdrawing records a VOID event; nothing effective is left in that window.
        var withdrawn = downtime.correct(
                planner,
                current,
                new MaintenanceCommands.CorrectDowntime(
                        "CHANGEOVER", null, "2025-03-04T08:00:00Z", "2025-03-04T08:45:00Z", null, "Duplicate", true),
                id());
        assertThat(withdrawn.get("status")).isEqualTo("VOID");
        assertThat(count(
                        "SELECT COUNT(*) FROM machine_downtime WHERE tenant_id=? AND machine_id=? AND started_at<'2025-03-04T09:00:00Z'",
                        t,
                        m1))
                .isZero();
        assertThat(actions(number)).containsExactly("DOWNTIME_REPORTED", "DOWNTIME_CORRECTED");
        String currentNumber = str(row("SELECT number FROM downtime_events WHERE id=?", current), "number");
        assertThat(actions(currentNumber)).contains("DOWNTIME_VOIDED");

        // Open events are closed, not corrected.
        var open = downtime.report(operator, report(m2, "OTHER", null, null), id());
        assertThatThrownBy(() -> downtime.correct(planner, str(open, "id"), fix, id()))
                .hasMessageContaining("Close the downtime");
    }

    @Test
    void permissionsTenantsAndIdempotency() throws Exception {
        var body = report(m1, "NO_OPERATOR", "2025-03-05T08:00:00Z", "2025-03-05T08:30:00Z");
        command(warehouse, "/api/maintenance/downtime", body).andExpect(status().isForbidden());
        command(viewer, "/api/maintenance/downtime", body).andExpect(status().isForbidden());
        command(planner, "/api/maintenance/downtime", report(m1, "BAD", null, null))
                .andExpect(status().isBadRequest());

        // Another tenant's machine and event are not found.
        var other = createTenant();
        var outsider = createUser(other.id(), "OPERATOR");
        command(outsider, "/api/maintenance/downtime", body).andExpect(status().isNotFound());
        var mine = closedDowntime(operator, m2, "OTHER", "2025-03-05T08:00:00Z", "2025-03-05T08:30:00Z");
        command(
                        outsider,
                        "/api/maintenance/downtime/" + mine.get("id") + "/close",
                        new MaintenanceCommands.CloseDowntime(null, null))
                .andExpect(status().isNotFound());
        assertThat((java.util.List<?>)
                        json(query(outsider, "/api/maintenance/downtime")).get("items"))
                .isEmpty();

        // A retry with the same key returns the stored result; the key cannot be reused for another body.
        String key = id();
        var first =
                json(command(operator, "/api/maintenance/downtime", body, key).andExpect(status().isOk()));
        var retry =
                json(command(operator, "/api/maintenance/downtime", body, key).andExpect(status().isOk()));
        assertThat(retry.get("id")).isEqualTo(first.get("id"));
        assertThat(count(
                        "SELECT COUNT(*) FROM downtime_events WHERE tenant_id=? AND machine_id=? AND reason='NO_OPERATOR'",
                        t,
                        m1))
                .isEqualTo(1);
        command(
                        operator,
                        "/api/maintenance/downtime",
                        report(m1, "NO_OPERATOR", "2025-03-06T08:00:00Z", "2025-03-06T08:30:00Z"),
                        key)
                .andExpect(status().isConflict());
    }
}
