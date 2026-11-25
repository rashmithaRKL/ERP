package lk.dexlanka.erp.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Keyset-paged maintenance lists and filters, per-machine history and summaries, and the state attention counts. */
class MaintenanceReadModelTest extends MaintenanceTestSupport {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    @Test
    void workOrderListFiltersAndPages() throws Exception {
        String a = str(orders.request(operator, corrective(m1, false), id()), "id");
        String b = approvedOrder(m2, false);
        String c = startedOrder(m1);
        plans.create(planner, plan(m2, 30, MaintenanceRules.today().minusDays(1).toString()), id());
        plans.generate(planner, new MaintenanceCommands.Generate(0), id());

        var all = json(query(viewer, "/api/maintenance/work-orders?limit=50").andExpect(status().isOk()));
        assertThat(items(all)).hasSize(4);
        @SuppressWarnings("unchecked")
        var counts = (Map<String, Object>) all.get("counts");
        assertThat(((Number) counts.get("REQUESTED")).intValue()).isEqualTo(1);
        assertThat(((Number) counts.get("IN_PROGRESS")).intValue()).isEqualTo(1);
        assertThat(items(query.workOrders(viewer, "REQUESTED", null, null, null, 50, null)))
                .extracting(r -> r.get("id"))
                .containsExactly(a);
        assertThat(items(query.workOrders(viewer, "OPEN", "CORRECTIVE", m1, null, 50, null)))
                .extracting(r -> r.get("id"))
                .containsExactlyInAnyOrder(a, c);
        assertThat(items(query.workOrders(viewer, null, "PREVENTIVE", null, null, 50, null)))
                .hasSize(1);
        String bNumber = str(workOrderRow(b), "number");
        assertThat(items(query.workOrders(viewer, null, null, null, bNumber.toLowerCase(), 50, null)))
                .extracting(r -> r.get("id"))
                .containsExactly(b);
        query(viewer, "/api/maintenance/work-orders?status=NOPE").andExpect(status().isBadRequest());
        query(viewer, "/api/maintenance/work-orders?limit=500").andExpect(status().isConflict());

        // Keyset paging visits every order exactly once.
        var seen = new ArrayList<Object>();
        String before = null;
        do {
            var page = query.workOrders(viewer, null, null, null, null, 1, before);
            seen.addAll(items(page).stream().map(r -> r.get("id")).toList());
            before = str(page, "next");
        } while (!before.isEmpty());
        assertThat(seen).hasSize(4).doesNotHaveDuplicates();

        var row = items(query.workOrders(viewer, "IN_PROGRESS", null, null, null, 50, null))
                .getFirst();
        assertThat(row.get("machine")).isEqualTo("M1");
        assertThat(row.get("open_downtime")).isNotNull();
    }

    @Test
    void downtimeListMachineHistoryAndSummaries() throws Exception {
        closedDowntime(operator, m1, "CHANGEOVER", "2025-04-01T08:00:00+05:30", "2025-04-01T08:30:00+05:30");
        var wrong = closedDowntime(operator, m1, "UTILITIES", "2025-04-02T08:00:00+05:30", "2025-04-02T08:30:00+05:30");
        downtime.correct(
                planner,
                str(wrong, "id"),
                new MaintenanceCommands.CorrectDowntime(
                        "UTILITIES",
                        null,
                        "2025-04-02T08:00:00+05:30",
                        "2025-04-02T08:20:00+05:30",
                        null,
                        "Clock",
                        false),
                id());
        closedDowntime(operator, m2, "NO_OPERATOR", "2025-04-03T08:00:00+05:30", "2025-04-03T09:00:00+05:30");
        var open = downtime.report(operator, report(m2, "BREAKDOWN", null, null), id());

        assertThat(items(query.downtime(viewer, null, null, null, null, null, false, 50, null)))
                .hasSize(4);
        assertThat(items(query.downtime(viewer, null, null, null, null, null, true, 50, null)))
                .hasSize(5);
        assertThat(items(query.downtime(viewer, m1, null, null, null, null, false, 50, null)))
                .hasSize(2);
        assertThat(items(query.downtime(viewer, null, "UTILITIES", null, null, null, false, 50, null)))
                .extracting(r -> r.get("corrects"))
                .containsExactly(row("SELECT number FROM downtime_events WHERE id=?", wrong.get("id"))
                        .get("number"));
        assertThat(items(query.downtime(viewer, null, null, "2025-04-02", "2025-04-03", null, false, 50, null)))
                .hasSize(2);
        var openOnly = items(query.downtime(viewer, null, null, null, null, "OPEN", false, 50, null));
        assertThat(openOnly).extracting(r -> r.get("id")).containsExactly(open.get("id"));
        var page1 = query.downtime(viewer, null, null, null, null, null, false, 2, null);
        var page2 = query.downtime(viewer, null, null, null, null, null, false, 2, str(page1, "next"));
        assertThat(items(page2)).hasSize(2);
        assertThat(str(page2, "next")).isEmpty();
        query(viewer, "/api/maintenance/downtime?reason=NOPE").andExpect(status().isBadRequest());
        query(viewer, "/api/maintenance/downtime?from=yesterday").andExpect(status().isBadRequest());

        // Machine summaries, detail and history.
        @SuppressWarnings("unchecked")
        var summaries = (List<Map<String, Object>>) query.machines(viewer).get("items");
        var m2Row = summaries.stream()
                .filter(r -> m2.equals(r.get("id")))
                .findFirst()
                .orElseThrow();
        assertThat(m2Row.get("open_downtime_reason")).isEqualTo("BREAKDOWN");
        String woId = startedOrder(m1);
        var detail = json(query(viewer, "/api/maintenance/machines/" + m1).andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var machine = (Map<String, Object>) detail.get("machine");
        assertThat(machine.get("status")).isEqualTo("MAINTENANCE");
        assertThat(detail.get("openDowntime")).isNotNull();
        assertThat((List<?>) detail.get("orders")).hasSize(1);
        assertThat(detail.get("metrics")).isNotNull();
        var history = query.history(viewer, m1, 50, null);
        assertThat(items(history)).extracting(r -> r.get("kind")).contains("DOWNTIME", "WORK_ORDER", "STATUS");
        var seen = new ArrayList<Object>();
        String before = null;
        do {
            var page = query.history(viewer, m1, 2, before);
            seen.addAll(items(page).stream().map(r -> r.get("id")).toList());
            before = str(page, "next");
        } while (!before.isEmpty());
        assertThat(seen).hasSize(items(history).size()).doesNotHaveDuplicates();
        assertThat(woId).isNotNull();

        // /api/state: open breakdowns for the attention center.
        var state = json(query(operator, "/api/state?days=14").andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var maintenance = (Map<String, Object>) state.get("maintenance");
        // The reported breakdown on M2 and the unplanned breakdown time opened by the unscheduled repair on M1.
        assertThat(((Number) maintenance.get("open_breakdowns")).intValue()).isEqualTo(2);
        assertThat(((Number) maintenance.get("in_progress")).intValue()).isEqualTo(1);
        assertThat((List<?>) maintenance.get("breakdowns")).hasSize(2);

        var other = createTenant();
        query(createUser(other.id(), "VIEWER"), "/api/maintenance/machines/" + m1)
                .andExpect(status().isNotFound());
        query(createUser(other.id(), "VIEWER"), "/api/maintenance/machines/" + m1 + "/history")
                .andExpect(status().isNotFound());
    }
}
