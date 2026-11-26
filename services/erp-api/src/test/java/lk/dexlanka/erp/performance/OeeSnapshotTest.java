package lk.dexlanka.erp.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.routing.CalendarCommands;
import lk.dexlanka.erp.routing.CalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Closing OEE days (V15): snapshots are idempotent per work center and day, immune to later calendar edits, recalculated
 * only with a reason (history kept, database guard), planner-only, tenant-isolated; plus the {@code performance} state
 * key.
 */
class OeeSnapshotTest extends OeeTestSupport {
    String day, routed, op10;

    @BeforeEach
    void history() {
        day = pattern("DAY", "06:00", "14:00", "10:00", 30, List.of(1, 2, 3, 4, 5));
        calendar("PLANT", List.of(day), true);
        approvedRouting(bottle, "R1", op(10, "Blow", bm1, List.of(), "30", "0.5", "1", null, null, false));
        routed = order("5000", bm1, "NORMAL");
        op10 = operation(routed, 10);
        // Monday: 450 planned minutes, run 06:00–10:00 (240), 720 reported, all good.
        log(bm1, routed, op10, "RUN", at(MON, "06:00"), at(MON, "10:00"));
        report(bm1, routed, op10, "360", "0", at(MON, "10:00"));
    }

    Map<String, Object> cell(Actor viewerActor, String machine, LocalDate date) throws Exception {
        var oee = json(query(viewerActor, "/api/performance/oee?machine=" + machine + "&from=" + date + "&to=" + date)
                .andExpect(status().isOk()));
        return list(list(oee.get("workCenters")).getFirst().get("cells")).getFirst();
    }

    @Test
    void closingFreezesTheDayAndIsIdempotentPerWorkCenterAndDay() throws Exception {
        var before = cell(viewer, bm1, MON);
        assertThat(before.get("closed")).isEqualTo(false);
        near(before.get("oee"), 180.0 / 450);

        var body = new PerformanceCommands.Close(MON.toString(), List.of(bm1), null, null);
        var first = json(command(planner, "/api/performance/close", body, "oee-close-key-1")
                .andExpect(status().isOk()));
        assertThat(first.get("closed")).isEqualTo(List.of("BM-1"));
        // Same key, same body: the stored result; a new key: nothing changes (already closed).
        assertThat(json(command(planner, "/api/performance/close", body, "oee-close-key-1")
                        .andExpect(status().isOk())))
                .isEqualTo(first);
        var again = json(command(planner, "/api/performance/close", body).andExpect(status().isOk()));
        assertThat(again.get("unchanged")).isEqualTo(List.of("BM-1"));
        command(
                        planner,
                        "/api/performance/close",
                        new PerformanceCommands.Close(TUE.toString(), null, null, null),
                        "oee-close-key-1")
                .andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM oee_day_snapshots WHERE tenant_id=? AND machine_id=?", t, bm1))
                .isEqualTo(1);
        var closed = cell(viewer, bm1, MON);
        assertThat(closed.get("closed")).isEqualTo(true);
        assertThat(closed.get("version")).isEqualTo(1);
        near(closed.get("oee"), 180.0 / 450);

        // Closing every work center (no list) closes BM-2 and PK-1 too and leaves BM-1 unchanged.
        var all = json(command(
                        planner,
                        "/api/performance/close",
                        new PerformanceCommands.Close(MON.toString(), null, false, null))
                .andExpect(status().isOk()));
        assertThat(all.get("closed")).isEqualTo(List.of("BM-2", "PK-1"));
        assertThat(all.get("unchanged")).isEqualTo(List.of("BM-1"));
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='OEE_DAY_CLOSED'", t))
                .isEqualTo(3);
    }

    @Test
    void calendarEditsDoNotRewriteClosedDaysUntilRecalculatedWithAReason() throws Exception {
        command(
                        planner,
                        "/api/performance/close",
                        new PerformanceCommands.Close(MON.toString(), List.of(bm1), null, null))
                .andExpect(status().isOk());
        // The day shift becomes 06:00–18:00 (break 10:00–10:30): 690 minutes. Calendars keep no history.
        calendarAdmin.updatePattern(
                planner,
                day,
                new CalendarCommands.ShiftPatternUpdate(
                        "Long day", "06:00", "18:00", "10:00", 30, List.of(1, 2, 3, 4, 5)),
                id());
        nearMinutes(live(bm1, MON).get("plannedMinutes"), 690);
        var frozen = cell(viewer, bm1, MON);
        nearMinutes(frozen.get("plannedMinutes"), 450);
        near(frozen.get("oee"), 180.0 / 450);

        var detail =
                json(query(operator, "/api/performance/days/" + bm1 + "/" + MON).andExpect(status().isOk()));
        assertThat(detail.get("calendarChanged")).isEqualTo(true);
        assertThat(detail.get("differs")).isEqualTo(true);
        nearMinutes(map(detail.get("snapshot")).get("plannedMinutes"), 450);
        nearMinutes(map(detail.get("live")).get("plannedMinutes"), 690);
        assertThat(list(map(detail.get("snapshot")).get("intervals"))).hasSize(2);

        command(
                        planner,
                        "/api/performance/close",
                        new PerformanceCommands.Close(MON.toString(), List.of(bm1), true, "  "))
                .andExpect(status().isConflict());
        var recalculated = json(command(
                        planner,
                        "/api/performance/close",
                        new PerformanceCommands.Close(
                                MON.toString(), List.of(bm1), true, "Long day shift agreed with the union"))
                .andExpect(status().isOk()));
        assertThat(recalculated.get("recalculated")).isEqualTo(List.of("BM-1"));
        var v2 = cell(viewer, bm1, MON);
        assertThat(v2.get("version")).isEqualTo(2);
        nearMinutes(v2.get("plannedMinutes"), 690);
        near(v2.get("oee"), 180.0 / 690);
        var history = list(
                json(query(viewer, "/api/performance/days/" + bm1 + "/" + MON)).get("history"));
        assertThat(history).extracting(h -> h.get("version")).containsExactly(2, 1);
        assertThat(history).extracting(h -> h.get("status")).containsExactly("CURRENT", "SUPERSEDED");
        assertThat(history.getFirst().get("reason")).isEqualTo("Long day shift agreed with the union");
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='OEE_DAY_RECALCULATED'", t))
                .isEqualTo(1);

        // The database keeps the history: no edits, no deletes, superseded rows frozen.
        String current = str(row("SELECT id FROM oee_day_snapshots WHERE tenant_id=? AND status='CURRENT'", t), "id");
        String old = str(row("SELECT id FROM oee_day_snapshots WHERE tenant_id=? AND status='SUPERSEDED'", t), "id");
        assertThatThrownBy(() -> update("UPDATE oee_day_snapshots SET oee=0.9 WHERE id=?", current))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> update("DELETE FROM oee_day_snapshots WHERE id=?", old))
                .hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> update("UPDATE oee_day_snapshots SET reason='x' WHERE id=?", old))
                .hasMessageContaining("immutable");
    }

    @Test
    void onlyCompletedDaysOfYourOwnWorkCentersCanBeClosedByPlanners() throws Exception {
        var body = new PerformanceCommands.Close(MON.toString(), List.of(bm1), null, null);
        for (var role : List.of(operator, quality, viewer, warehouse))
            command(role, "/api/performance/close", body).andExpect(status().isForbidden());
        command(admin, "/api/performance/close", body).andExpect(status().isOk());
        LocalDate today = LocalDate.now(CalendarService.ZONE);
        command(planner, "/api/performance/close", new PerformanceCommands.Close(today.toString(), null, null, null))
                .andExpect(status().isConflict());
        command(
                        planner,
                        "/api/performance/close",
                        new PerformanceCommands.Close(today.plusDays(1).toString(), null, null, null))
                .andExpect(status().isConflict());
        command(planner, "/api/performance/close", new PerformanceCommands.Close("2026-13-45", null, null, null))
                .andExpect(status().isBadRequest());
        command(planner, "/api/performance/close", Map.of("machineIds", List.of(bm1)))
                .andExpect(status().isBadRequest());

        var otherTenant = createTenant("Other OEE tenant");
        var stranger = createUser(otherTenant.id(), "PLANNER");
        command(stranger, "/api/performance/close", body).andExpect(status().isNotFound());
        query(stranger, "/api/performance/days/" + bm1 + "/" + MON).andExpect(status().isNotFound());
        query(stranger, "/api/performance/oee?machine=" + bm1).andExpect(status().isNotFound());
        query(stranger, "/api/performance/losses?machine=" + bm1).andExpect(status().isNotFound());
        var theirs = json(query(stranger, "/api/performance/oee?from=" + MON + "&to=" + MON)
                .andExpect(status().isOk()));
        assertThat(list(theirs.get("workCenters"))).isEmpty();
        assertThat(map(theirs.get("plant")).get("oee")).isNull();
    }

    @Test
    void stateKeyShowsYesterdaysPlantOeeOrNull() throws Exception {
        LocalDate yesterday = LocalDate.now(CalendarService.ZONE).minusDays(1);
        // A tenant without work centers has nothing to measure.
        var other = createTenant("OEE state tenant");
        var reader = createUser(other.id(), "VIEWER");
        assertThat(json(query(reader, "/api/state").andExpect(status().isOk()))).containsEntry("performance", null);
        // A work center without any shift calendar is available around the clock, and flagged.
        String loose = id();
        update(
                "INSERT INTO machines(id,tenant_id,factory_id,code,name,kind,status,hourly_rate) VALUES(?,?,?,'X-1','Loose','Other','IDLE',0)",
                loose,
                other.id(),
                other.plant());
        var always = engine.live(other.id(), List.of(loose), MON, MON, java.time.Instant.now())
                .get(loose)
                .get(MON);
        assertThat(always.get("calendarSource")).isEqualTo("NONE");
        nearMinutes(always.get("scheduledMinutes"), 1440);
        assertThat(codes(always)).contains("NO_CALENDAR", "NO_ACTIVITY");

        // Yesterday 08:00–10:00 run on BM-1 with 240 units of 0.5 ideal minutes, all good.
        log(bm1, routed, op10, "RUN", at(yesterday, "08:00"), at(yesterday, "10:00"));
        report(bm1, routed, op10, "240", "0", at(yesterday, "10:00"));
        var state = json(query(viewer, "/api/state").andExpect(status().isOk()));
        assertThat(state).containsKey("performance");
        if (yesterday.getDayOfWeek().getValue() <= 5) {
            var performance = map(state.get("performance"));
            assertThat(performance.get("day")).isEqualTo(yesterday.toString());
            near(performance.get("oee"), 120.0 / 450);
            near(performance.get("availability"), 120.0 / 450);
            near(performance.get("performance"), 1);
            near(performance.get("timeCoverage"), 450.0 / (3 * 450));
            assertThat(performance.get("workCenters")).isEqualTo(3);
        } else assertThat(state.get("performance")).isNull(); // no shifts on the plant calendar at weekends
    }
}
