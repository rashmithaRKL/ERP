package lk.dexlanka.erp.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * MTBF, MTTR, downtime minutes and the reason Pareto over 10–11 March 2025 (Asia/Colombo, 2,880 minutes), with events
 * clipped at the period edges, an event outside the period and a corrected event.
 */
class MaintenanceMetricsTest extends MaintenanceTestSupport {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }

    private void events() {
        closedDowntime(operator, m1, "BREAKDOWN", "2025-03-10T08:00:00+05:30", "2025-03-10T09:00:00+05:30");
        closedDowntime(operator, m1, "BREAKDOWN", "2025-03-11T10:00:00+05:30", "2025-03-11T10:30:00+05:30");
        closedDowntime(operator, m1, "CHANGEOVER", "2025-03-10T14:00:00+05:30", "2025-03-10T14:20:00+05:30");
        // Starts before the period: 30 of its 60 minutes count.
        closedDowntime(operator, m1, "MATERIAL_SHORTAGE", "2025-03-09T23:30:00+05:30", "2025-03-10T00:30:00+05:30");
        // Ends after the period: 10 of its 30 minutes count.
        closedDowntime(operator, m2, "UTILITIES", "2025-03-11T23:50:00+05:30", "2025-03-12T00:20:00+05:30");
        // Outside the period entirely.
        closedDowntime(operator, m2, "BREAKDOWN", "2025-03-08T10:00:00+05:30", "2025-03-08T11:00:00+05:30");
        // Reported as a one-hour breakdown, corrected to a quarter of an hour of "other".
        var wrong = closedDowntime(operator, m2, "BREAKDOWN", "2025-03-10T12:00:00+05:30", "2025-03-10T13:00:00+05:30");
        downtime.correct(
                planner,
                str(wrong, "id"),
                new MaintenanceCommands.CorrectDowntime(
                        "OTHER",
                        null,
                        "2025-03-10T12:00:00+05:30",
                        "2025-03-10T12:15:00+05:30",
                        null,
                        "Machine was only waiting for a forklift",
                        false),
                id());
    }

    @Test
    void mtbfMttrAndDowntimeByReason() throws Exception {
        events();
        var result = json(query(viewer, "/api/maintenance/metrics?from=2025-03-10&to=2025-03-11")
                .andExpect(status().isOk()));
        assertThat(dec(result.get("periodMinutes"))).isEqualByComparingTo("2880");

        var machines = list(result, "machines");
        assertThat(machines).extracting(m -> m.get("code")).containsExactly("M1", "M2");
        var first = machines.getFirst();
        assertThat(((Number) first.get("events")).intValue()).isEqualTo(4);
        assertThat(((Number) first.get("failures")).intValue()).isEqualTo(2);
        assertThat(dec(first.get("downtimeMinutes"))).isEqualByComparingTo("140");
        assertThat(dec(first.get("plannedMinutes"))).isEqualByComparingTo("20");
        assertThat(dec(first.get("unplannedMinutes"))).isEqualByComparingTo("120");
        assertThat(dec(first.get("mttrMinutes"))).isEqualByComparingTo("45");
        assertThat(dec(first.get("mtbfHours"))).isEqualByComparingTo("22.83"); // (2880 − 140) / 60 / 2
        var second = machines.get(1);
        assertThat(((Number) second.get("failures")).intValue()).isZero();
        assertThat(dec(second.get("downtimeMinutes"))).isEqualByComparingTo("25");
        assertThat(second.get("mttrMinutes")).isNull();
        assertThat(second.get("mtbfHours")).isNull();

        var pareto = list(result, "pareto");
        assertThat(pareto)
                .extracting(r -> r.get("reason"))
                .containsExactly("BREAKDOWN", "MATERIAL_SHORTAGE", "CHANGEOVER", "OTHER", "UTILITIES");
        assertThat(pareto).extracting(r -> dec(r.get("minutes")).intValue()).containsExactly(90, 30, 20, 15, 10);
        assertThat(pareto)
                .extracting(r -> dec(r.get("share")).toPlainString())
                .containsExactly("54.5", "18.2", "12.1", "9.1", "6.1");
        assertThat(pareto)
                .extracting(r -> dec(r.get("cumulative")).toPlainString())
                .containsExactly("54.5", "72.7", "84.8", "93.9", "100.0");

        @SuppressWarnings("unchecked")
        var totals = (Map<String, Object>) result.get("totals");
        assertThat(((Number) totals.get("failures")).intValue()).isEqualTo(2);
        assertThat(dec(totals.get("downtimeMinutes"))).isEqualByComparingTo("165");
        assertThat(dec(totals.get("mttrMinutes"))).isEqualByComparingTo("45");
        assertThat(dec(totals.get("mtbfHours"))).isEqualByComparingTo("46.63"); // (2 × 2880 − 165) / 60 / 2
    }

    @Test
    void oneMachineAndInvalidPeriods() throws Exception {
        events();
        var one = metrics.metrics(viewer, "2025-03-10", "2025-03-11", m1);
        assertThat(list(one, "machines")).hasSize(1);
        assertThat(list(one, "pareto"))
                .extracting(r -> r.get("reason"))
                .containsExactly("BREAKDOWN", "MATERIAL_SHORTAGE", "CHANGEOVER");
        // A single day: only the second breakdown and nothing clipped from the day before.
        var day = metrics.metrics(viewer, "2025-03-11", "2025-03-11", m1);
        var row = list(day, "machines").getFirst();
        assertThat(((Number) row.get("failures")).intValue()).isEqualTo(1);
        assertThat(dec(row.get("downtimeMinutes"))).isEqualByComparingTo("30");
        assertThat(dec(row.get("mtbfHours"))).isEqualByComparingTo("23.5"); // (1440 − 30) / 60

        assertThatThrownBy(() -> metrics.metrics(viewer, "2025-03-11", "2025-03-10", null))
                .hasMessageContaining("end on or after its start");
        assertThatThrownBy(() -> metrics.metrics(viewer, "2024-01-01", "2025-03-10", null))
                .hasMessageContaining("at most 366 days");
        var other = createTenant();
        query(createUser(other.id(), "PLANNER"), "/api/maintenance/metrics?machine=" + m1)
                .andExpect(status().isNotFound());
        assertThat(list(metrics.metrics(createUser(other.id(), "VIEWER"), "2025-03-10", "2025-03-11", null), "pareto"))
                .isEmpty();
    }
}
