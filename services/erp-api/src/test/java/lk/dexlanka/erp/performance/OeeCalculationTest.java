package lk.dexlanka.erp.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V15 OEE definitions with hand-computed figures (all minutes, Asia/Colombo). Plant calendar: day 06:00–14:00 (break
 * 10:00–10:30) and evening 14:00–22:00 (break 18:00–18:30), Monday to Friday = 900 scheduled minutes a day. BM-2 works a
 * night calendar 22:00–06:00 (break 02:00–02:30), Monday to Friday. The routed bottle runs 0.5 ideal minutes per unit
 * on operation 10.
 */
class OeeCalculationTest extends OeeTestSupport {
    String plant, nights, routed, op10, untimedBm1, untimedPk1;

    @BeforeEach
    void calendarsAndOrders() {
        var weekdays = List.of(1, 2, 3, 4, 5);
        String day = pattern("DAY", "06:00", "14:00", "10:00", 30, weekdays);
        String eve = pattern("EVE", "14:00", "22:00", "18:00", 30, weekdays);
        String night = pattern("NIGHT", "22:00", "06:00", "02:00", 30, weekdays);
        plant = calendar("TWO", List.of(day, eve), true);
        nights = calendar("NIGHTS", List.of(night), false);
        assign(bm2, nights);
        // Orders created before the routing is approved have no routing (Phase 1 style output, no time records).
        untimedBm1 = order("300", bm1, "NORMAL");
        untimedPk1 = order("520", pk1, "NORMAL");
        approvedRouting(
                bottle,
                "R1",
                op(10, "Blow", bm1, List.of(bm2), "30", "0.5", "1", null, null, false),
                op(20, "Pack", pk1));
        routed = order("5000", bm1, "NORMAL");
        op10 = operation(routed, 10);
        assertThat(orderRow(untimedBm1).get("routing_id")).isNull();
        assertThat(orderRow(routed).get("routing_id")).isNotNull();
    }

    /**
     * Monday on BM-1: planned maintenance 06:00–07:00 (planned stop), setup 07:00–07:30, run 07:30–10:00, 10:30–12:00,
     * breakdown 12:00–13:00, run 13:00–18:00, changeover 18:30–19:00 (flagged planned, still an availability loss),
     * run 19:00–21:00, material shortage 21:00–21:20, nothing recorded 21:20–22:00. 1,100 good + 100 scrap reported.
     */
    void mondayOnBm1() {
        downtime(bm1, "PLANNED_MAINTENANCE", true, at(MON, "06:00"), at(MON, "07:00"));
        log(bm1, routed, op10, "SETUP", at(MON, "07:00"), at(MON, "07:30"));
        log(bm1, routed, op10, "RUN", at(MON, "07:30"), at(MON, "10:00"));
        report(bm1, routed, op10, "500", "40", at(MON, "10:00"));
        log(bm1, routed, op10, "RUN", at(MON, "10:30"), at(MON, "12:00"));
        downtime(bm1, "BREAKDOWN", false, at(MON, "12:00"), at(MON, "13:00"));
        log(bm1, routed, op10, "RUN", at(MON, "13:00"), at(MON, "18:00"));
        report(bm1, routed, op10, "400", "40", at(MON, "18:00"));
        downtime(bm1, "CHANGEOVER", true, at(MON, "18:30"), at(MON, "19:00"));
        log(bm1, routed, op10, "RUN", at(MON, "19:00"), at(MON, "21:00"));
        report(bm1, routed, op10, "200", "20", at(MON, "21:00"));
        downtime(bm1, "MATERIAL_SHORTAGE", false, at(MON, "21:00"), at(MON, "21:20"));
    }

    @Test
    void dayWithShiftsBreaksPlannedAndUnplannedStopsAndSetup() {
        mondayOnBm1();
        var f = live(bm1, MON);
        assertThat(f.get("status")).isEqualTo("MEASURED");
        assertThat(f.get("calendarSource")).isEqualTo("PLANT_DEFAULT");
        nearMinutes(f.get("calendarMinutes"), 1440);
        nearMinutes(f.get("scheduledMinutes"), 900); // 2 × 450: breaks are not working time
        nearMinutes(f.get("plannedStopMinutes"), 60);
        nearMinutes(f.get("plannedMinutes"), 840);
        nearMinutes(f.get("setupMinutes"), 30);
        nearMinutes(f.get("changeoverMinutes"), 30);
        nearMinutes(f.get("breakdownMinutes"), 60);
        nearMinutes(f.get("otherStopMinutes"), 20);
        nearMinutes(f.get("runMinutes"), 660); // 150 + 90 + 300 + 120
        nearMinutes(f.get("unrecordedMinutes"), 40);
        nearMinutes(f.get("derivedRunMinutes"), 700); // 840 − 110 stops − 30 setup; 40 more than logged
        nearMinutes(f.get("idealMinutes"), 600); // 1,200 × 0.5
        near(f.get("availability"), 660.0 / 840); // 0.785714
        near(f.get("performance"), 600.0 / 660); // 0.909091
        near(f.get("quality"), 1100.0 / 1200); // 0.916667
        near(f.get("oee"), 550.0 / 840); // 0.654762 = ideal × good / planned production time
        nearMinutes(f.get("netRunMinutes"), 600);
        nearMinutes(f.get("fullyProductiveMinutes"), 550);
        // Busy = setup 30 + changeover 30 + run 660 (breakdown and material shortage are not busy).
        nearMinutes(f.get("busyInShiftMinutes"), 720);
        near(f.get("utilization"), 720.0 / 900);
        near(f.get("capacityUtilization"), 720.0 / 1440);
        assertThat(codes(f)).doesNotContain("UNRECORDED_TIME", "LOG_DOWNTIME_OVERLAP", "RUN_OUTSIDE_PLANNED_TIME");
        var stops = list(f.get("stops"));
        assertThat(stops)
                .extracting(r -> r.get("reason"))
                .containsExactly("BREAKDOWN", "CHANGEOVER", "MATERIAL_SHORTAGE", "PLANNED_MAINTENANCE");
        var operations = list(f.get("operations"));
        assertThat(operations).hasSize(1);
        nearMinutes(operations.getFirst().get("runMinutes"), 660);
        nearMinutes(operations.getFirst().get("setupMinutes"), 30);
    }

    @Test
    void midnightSplitAndOvernightShift() {
        // Run 23:00 Monday → 01:00 Tuesday on the night calendar, reported at 01:00; breakdown 23:30 Tue → 00:30 Wed.
        log(bm2, routed, op10, "RUN", at(MON, "23:00"), at(TUE, "01:00"));
        report(bm2, routed, op10, "100", "0", at(TUE, "01:00"));
        downtime(bm2, "BREAKDOWN", false, at(TUE, "23:30"), at(WED, "00:30"));

        var mon = live(bm2, MON);
        assertThat(mon.get("calendarSource")).isEqualTo("ASSIGNED");
        nearMinutes(mon.get("scheduledMinutes"), 120); // only 22:00–24:00 of Monday's night shift
        nearMinutes(mon.get("runMinutes"), 60);
        near(mon.get("availability"), 0.5);
        near(mon.get("performance"), 0); // nothing reported on Monday
        near(mon.get("oee"), 0);
        assertThat(mon.get("quality")).isNull();
        assertThat(codes(mon)).contains("RUN_WITHOUT_REPORT");

        var tue = live(bm2, TUE);
        nearMinutes(tue.get("scheduledMinutes"), 450); // 00:00–02:00, 02:30–06:00, 22:00–24:00
        nearMinutes(tue.get("runMinutes"), 60);
        nearMinutes(tue.get("breakdownMinutes"), 30);
        nearMinutes(tue.get("unrecordedMinutes"), 360);
        near(tue.get("availability"), 60.0 / 450);
        near(tue.get("performance"), 50.0 / 60);
        near(tue.get("quality"), 1);
        near(tue.get("oee"), 50.0 / 450);
        assertThat(codes(tue)).contains("UNRECORDED_TIME");

        var wed = live(bm2, WED);
        nearMinutes(wed.get("breakdownMinutes"), 30);
        assertThat(wed.get("status")).isEqualTo("MEASURED");
        near(wed.get("availability"), 0);
        near(wed.get("oee"), 0);
        assertThat(wed.get("performance")).isNull();
    }

    @Test
    void holidayIsNotApplicableNotZero() {
        holiday(plant, FRI);
        log(bm1, routed, op10, "RUN", at(FRI, "08:00"), at(FRI, "09:00"));
        report(bm1, routed, op10, "100", "0", at(FRI, "09:00"));
        var fri = live(bm1, FRI);
        assertThat(fri.get("status")).isEqualTo("NOT_SCHEDULED");
        nearMinutes(fri.get("scheduledMinutes"), 0);
        assertThat(fri.get("oee")).isNull();
        assertThat(fri.get("availability")).isNull();
        assertThat(fri.get("utilization")).isNull();
        nearMinutes(fri.get("runOutsideMinutes"), 60);
        near(fri.get("capacityUtilization"), 60.0 / 1440);
        assertThat(codes(fri)).contains("RUN_OUTSIDE_PLANNED_TIME");
        // Saturday has no shift at all on the plant calendar.
        assertThat(live(pk1, FRI.plusDays(1)).get("status")).isEqualTo("NOT_SCHEDULED");
        // A day with planned time and nothing recorded is not measured (not 0 %).
        var idle = live(pk1, TUE);
        assertThat(idle.get("status")).isEqualTo("NO_DATA");
        assertThat(idle.get("oee")).isNull();
        near(idle.get("utilization"), 0);
        assertThat(codes(idle)).contains("NO_ACTIVITY");
    }

    @Test
    void performanceAboveHundredPercentIsCappedAndFlagged() {
        log(bm1, routed, op10, "RUN", at(TUE, "08:00"), at(TUE, "09:00"));
        report(bm1, routed, op10, "150", "0", at(TUE, "09:00")); // 75 ideal minutes in 60 run minutes
        var tue = live(bm1, TUE);
        near(tue.get("performanceRaw"), 1.25);
        near(tue.get("performance"), 1);
        near(tue.get("availability"), 60.0 / 900);
        near(tue.get("oee"), 60.0 / 900);
        assertThat(codes(tue)).contains("PERFORMANCE_OVER_100");
    }

    @Test
    void logsOverlappingDowntimeCountTheDowntimeOnce() {
        log(bm1, routed, op10, "RUN", at(WED, "08:00"), at(WED, "10:00"));
        downtime(bm1, "BREAKDOWN", false, at(WED, "09:00"), at(WED, "09:30"));
        report(bm1, routed, op10, "180", "0", at(WED, "10:00"));
        var wed = live(bm1, WED);
        nearMinutes(wed.get("runMinutes"), 90);
        nearMinutes(wed.get("breakdownMinutes"), 30);
        nearMinutes(wed.get("overlapMinutes"), 30);
        near(wed.get("performance"), 1); // 90 ideal minutes in 90 run minutes
        assertThat(codes(wed)).contains("LOG_DOWNTIME_OVERLAP");
    }

    @Test
    void routingLessOutputIsUntimedAndPlantRollUpIsTimeWeighted() throws Exception {
        mondayOnBm1();
        actual(untimedBm1, "280", "20", at(MON, "15:00"));
        actual(untimedPk1, "500", "20", at(MON, "14:00"));
        log(bm2, routed, op10, "RUN", at(MON, "23:00"), at(TUE, "01:00"));

        var bm1Day = live(bm1, MON);
        near(bm1Day.get("oee"), 550.0 / 840); // untimed output never changes the OEE ...
        near(bm1Day.get("outputCoverage"), 1200.0 / 1500); // ... it lowers the coverage
        assertThat(codes(bm1Day)).contains("UNTIMED_OUTPUT");
        var pk1Day = live(pk1, MON);
        assertThat(pk1Day.get("status")).isEqualTo("UNTIMED");
        assertThat(pk1Day.get("oee")).isNull();
        near(pk1Day.get("outputCoverage"), 0);

        var oee = json(
                query(viewer, "/api/performance/oee?from=" + MON + "&to=" + MON).andExpect(status().isOk()));
        var plantRow = map(oee.get("plant"));
        // Time-weighted: (550 + 0) / (840 + 120), not the average of 65.5 % and 0 %.
        near(plantRow.get("oee"), 550.0 / 960);
        near(plantRow.get("availability"), 720.0 / 960);
        near(plantRow.get("performance"), 600.0 / 720);
        near(plantRow.get("quality"), 550.0 / 600);
        near(plantRow.get("timeCoverage"), 960.0 / (840 + 120 + 900));
        near(plantRow.get("outputCoverage"), 1200.0 / 2020);
        assertThat(plantRow.get("measuredDays")).isEqualTo(2);
        var rows = list(oee.get("workCenters"));
        assertThat(rows).extracting(r -> r.get("code")).containsExactly("BM-1", "BM-2", "PK-1");
        var pkCells = list(rows.get(2).get("cells"));
        assertThat(pkCells.getFirst().get("status")).isEqualTo("UNTIMED");
        assertThat(list(oee.get("series"))).hasSize(1);
        near(list(oee.get("series")).getFirst().get("oee"), 550.0 / 960);
        assertThat(map(map(oee.get("coverage")).get("warningCounts"))).containsKeys("UNTIMED_OUTPUT");
        assertThat(list(oee.get("warnings")))
                .anySatisfy(w -> assertThat(w.get("machine")).isEqualTo("PK-1"));
    }

    @Test
    void sixLossesWaterfallParetoAndQualityLosses() throws Exception {
        mondayOnBm1();
        var losses = json(query(operator, "/api/performance/losses?from=" + MON + "&to=" + MON + "&machine=" + bm1)
                .andExpect(status().isOk()));
        var waterfall = list(losses.get("waterfall"));
        Map<String, Double> steps = new java.util.HashMap<>();
        for (var s : waterfall)
            steps.put(str(s, "key"), Double.parseDouble(s.get("minutes").toString()));
        assertThat(steps.get("calendar")).isEqualTo(1440.0);
        assertThat(steps.get("scheduled")).isEqualTo(900.0);
        assertThat(steps.get("planned")).isEqualTo(840.0);
        assertThat(steps.get("run")).isEqualTo(660.0);
        assertThat(steps.get("netRun")).isEqualTo(600.0);
        assertThat(steps.get("fullyProductive")).isEqualTo(550.0);
        assertThat(steps.get("availabilityLoss")).isEqualTo(180.0);

        Map<String, Map<String, Object>> six = new java.util.HashMap<>();
        for (var l : list(losses.get("sixLosses"))) six.put(str(l, "key"), l);
        nearMinutes(six.get("BREAKDOWNS").get("minutes"), 60);
        nearMinutes(six.get("SETUP_ADJUSTMENTS").get("minutes"), 60); // setup log 30 + changeover 30
        nearMinutes(six.get("SMALL_STOPS_REDUCED_SPEED").get("minutes"), 60); // 660 − 600
        nearMinutes(six.get("PRODUCTION_REJECTS").get("minutes"), 50); // 600 − 550
        assertThat(six.get("STARTUP_REJECTS").get("measured")).isEqualTo(false);
        assertThat(six.get("STARTUP_REJECTS").get("minutes")).isNull();
        nearMinutes(six.get("OTHER_STOPS").get("minutes"), 20);
        nearMinutes(six.get("UNRECORDED_IDLE").get("minutes"), 40);

        var pareto = list(losses.get("pareto"));
        assertThat(pareto).hasSize(4);
        nearMinutes(pareto.getFirst().get("minutes"), 60);
        near(pareto.getLast().get("cumulative"), 1);
        var reasons = pareto.stream().map(p -> str(p, "reason")).toList();
        assertThat(reasons.subList(2, 4)).containsExactly("CHANGEOVER", "MATERIAL_SHORTAGE");

        var quality = map(losses.get("quality"));
        near(map(quality.get("inProcess")).get("scrapCount"), 100);
        assertThat(map(quality.get("postProduction")).get("dispositions")).isEqualTo(0);
    }

    @Test
    void weekBucketRollsUpTheDaysOfAnIsoWeek() throws Exception {
        mondayOnBm1();
        log(bm1, routed, op10, "RUN", at(TUE, "08:00"), at(TUE, "09:00"));
        report(bm1, routed, op10, "120", "0", at(TUE, "09:00"));
        var oee = json(query(planner, "/api/performance/oee?bucket=week&machine=" + bm1 + "&from=" + MON + "&to=" + TUE)
                .andExpect(status().isOk()));
        assertThat(list(oee.get("periods"))).extracting(p -> p.get("key")).containsExactly("2026-W10");
        var cell = list(list(oee.get("workCenters")).getFirst().get("cells")).getFirst();
        // Monday 550 fully productive minutes of 840 planned + Tuesday 60 of 900.
        near(cell.get("oee"), (550.0 + 60) / (840 + 900));
        near(cell.get("availability"), (660.0 + 60) / (840 + 900));
        assertThat(cell.get("measuredDays")).isEqualTo(2);
        query(planner, "/api/performance/oee?bucket=month").andExpect(status().isConflict());
        query(planner, "/api/performance/oee?from=" + MON + "&to=" + MON.plusDays(100))
                .andExpect(status().isConflict());
        query(planner, "/api/performance/oee?from=yesterday").andExpect(status().isBadRequest());
    }
}
