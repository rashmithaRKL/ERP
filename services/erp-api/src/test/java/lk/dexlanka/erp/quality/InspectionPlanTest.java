package lk.dexlanka.erp.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

/** V12 inspection plans: plan-driven decisions, measurement validation, plan versions, defect codes, permissions. */
class InspectionPlanTest extends QualityTestSupport {

    @Test
    void criticalCharacteristicOutOfSpecFailsTheInspectionAndRaisesACriticalNcr() throws Exception {
        String plan = bottlePlan("FINAL");
        var c = characteristics(plan);
        var held = heldBottles("100", "FINAL");
        // The inspector asks to pass, but a critical wall-thickness reading is below 0.40 mm.
        var body = new QualityCommands.Inspect(
                "PASSED",
                "Looks fine",
                List.of(
                        readings(c.get(0), null, "0.45", "0.38", "0.44"),
                        readings(c.get(1), null, "22.0", "22.1"),
                        count(c.get(2), 0)));
        var result =
                json(command(qa, "/api/quality/" + held[1] + "/inspect", body).andExpect(status().isOk()));
        assertThat(result).containsEntry("status", "FAILED");
        assertThat(lotStatus(held[0])).isEqualTo("REJECTED");

        var measurements = rows(
                "SELECT * FROM inspection_measurements WHERE tenant_id=? AND inspection_id=? ORDER BY seq", t, held[1]);
        assertThat(measurements).hasSize(3);
        assertThat(measurements.get(0)).containsEntry("conforming", false).containsEntry("critical", true);
        assertThat(dec(measurements.get(0).get("min_value"))).isEqualByComparingTo("0.38");
        assertThat(dec(measurements.get(0).get("lower_limit"))).isEqualByComparingTo("0.40");
        assertThat(count(
                        "SELECT COUNT(*) FROM inspection_readings WHERE tenant_id=? AND measurement_id=?",
                        t,
                        measurements.get(0).get("id")))
                .isEqualTo(3);

        var ncr = ncr(str(result, "ncrId"));
        assertThat(ncr)
                .containsEntry("source", "INSPECTION")
                .containsEntry("severity", "CRITICAL")
                .containsEntry("defect_code", "DIM")
                .containsEntry("status", "OPEN")
                .containsEntry("lot_id", held[0])
                .containsEntry("inspection_id", held[1]);
        assertThat(dec(ncr.get("quantity"))).isEqualByComparingTo("100");
        assertThat(str(ncr, "description")).contains("Wall thickness");
        assertThat(str(row("SELECT detail FROM audit_events WHERE tenant_id=? AND action='QC_FAILED'", t), "detail"))
                .contains("critical characteristic out of specification");

        // Measurements are posted history.
        assertThatThrownBy(() -> update("UPDATE inspection_measurements SET note='x' WHERE tenant_id=?", t))
                .hasMessageContaining("immutable");

        var read = json(query(viewer, "/api/quality/inspections/" + held[1] + "/measurements")
                .andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) read.get("items");
        assertThat(items).hasSize(3);
        assertThat((List<?>) items.get(0).get("readings")).hasSize(3);
    }

    @Test
    void nonCriticalOutOfSpecPassesOnlyWithANote() {
        String plan = bottlePlan("FINAL");
        var c = characteristics(plan);
        var held = heldBottles("50", "FINAL");
        var heavy = List.of(
                readings(c.get(0), null, "0.45", "0.44", "0.46"),
                readings(c.get(1), null, "22.9", "22.1"),
                count(c.get(2), 0));
        assertThatThrownBy(() -> quality.inspect(qa, held[1], new QualityCommands.Inspect("PASSED", "ok", heavy), id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Explain the out-of-specification result of Weight");
        var explained = List.of(
                readings(c.get(0), null, "0.45", "0.44", "0.46"),
                readings(c.get(1), "Heavier neck finish accepted by customer drawing rev B", "22.9", "22.1"),
                count(c.get(2), 0));
        var result = quality.inspect(qa, held[1], new QualityCommands.Inspect("PASSED", "ok", explained), id());
        assertThat(result).containsEntry("status", "PASSED").doesNotContainKey("ncrId");
        assertThat(lotStatus(held[0])).isEqualTo("RELEASED");
        assertThat(row(
                        "SELECT * FROM inspection_measurements WHERE tenant_id=? AND inspection_id=? AND seq=2",
                        t,
                        held[1]))
                .containsEntry("conforming", false)
                .containsEntry("note", "Heavier neck finish accepted by customer drawing rev B");
        assertThat(count("SELECT COUNT(*) FROM ncrs WHERE tenant_id=?", t)).isZero();
    }

    @Test
    void measurementsMustMatchThePlan() {
        String plan = bottlePlan("FINAL");
        var c = characteristics(plan);
        var held = heldBottles("10", "FINAL");
        // No measurements although a plan governs the inspection.
        assertThatThrownBy(() -> quality.inspect(qa, held[1], new QualityCommands.Inspect("PASSED", "ok"), id()))
                .hasMessageContaining("governs this inspection");
        // A characteristic missing.
        assertThatThrownBy(() -> quality.inspect(
                        qa,
                        held[1],
                        new QualityCommands.Inspect(
                                "PASSED",
                                "ok",
                                List.of(readings(c.get(0), null, "0.45", "0.44", "0.46"), count(c.get(2), 0))),
                        id()))
                .hasMessageContaining("Record a result for Weight");
        // Wrong number of readings.
        assertThatThrownBy(() -> quality.inspect(
                        qa,
                        held[1],
                        new QualityCommands.Inspect(
                                "PASSED",
                                "ok",
                                List.of(
                                        readings(c.get(0), null, "0.45"),
                                        readings(c.get(1), null, "22", "22"),
                                        count(c.get(2), 0))),
                        id()))
                .hasMessageContaining("Enter 3 readings for Wall thickness");
        // More nonconforming units than the sample.
        assertThatThrownBy(() -> quality.inspect(
                        qa,
                        held[1],
                        new QualityCommands.Inspect(
                                "FAILED",
                                "ok",
                                List.of(
                                        readings(c.get(0), null, "0.45", "0.44", "0.46"),
                                        readings(c.get(1), null, "22", "22"),
                                        count(c.get(2), 11))),
                        id()))
                .hasMessageContaining("nonconforming units (0–10)");
        // A characteristic of another plan.
        assertThatThrownBy(() -> quality.inspect(
                        qa, held[1], new QualityCommands.Inspect("PASSED", "ok", List.of(count(id(), 0))), id()))
                .hasMessageContaining("does not belong");
        // Nothing was written by the refused decisions.
        assertThat(str(row("SELECT status FROM inspections WHERE tenant_id=? AND id=?", t, held[1]), "status"))
                .isEqualTo("PENDING");
        assertThat(count("SELECT COUNT(*) FROM inspection_measurements WHERE tenant_id=?", t))
                .isZero();

        // Without a plan, measurements are refused.
        String planless = lot(resin, raw, "QUARANTINE", "OPENING", "5", "400");
        String inspection = id();
        update("INSERT INTO inspections(id,tenant_id,lot_id,type) VALUES(?,?,?,'OPENING')", inspection, t, planless);
        assertThatThrownBy(() -> quality.inspect(
                        qa, inspection, new QualityCommands.Inspect("PASSED", "ok", List.of(count(c.get(2), 0))), id()))
                .hasMessageContaining("No inspection plan governs");
    }

    @Test
    void inspectionsWithoutAPlanBehaveAsBeforeAndFailuresRaiseAnNcr() {
        var held = heldBottles("40", "FINAL");
        var passed = quality.inspect(qa, held[1], new QualityCommands.Inspect("PASSED", "Visual ok"), id());
        assertThat(passed).containsEntry("status", "PASSED");
        assertThat(lotStatus(held[0])).isEqualTo("RELEASED");
        assertThat(count("SELECT COUNT(*) FROM inspection_measurements WHERE tenant_id=?", t))
                .isZero();

        var rejected = rejectedBottles("30");
        assertThat(lotStatus(rejected[0])).isEqualTo("REJECTED");
        var ncr = ncr(rejected[2]);
        assertThat(ncr)
                .containsEntry("severity", "MAJOR")
                .containsEntry("defect_code", "INSP")
                .containsEntry("status", "OPEN")
                .containsEntry("description", "Short shots");
        assertThat(dec(ncr.get("quantity"))).isEqualByComparingTo("30");
        // One NCR per failed inspection.
        assertThat(count("SELECT COUNT(*) FROM ncrs WHERE tenant_id=? AND inspection_id=?", t, rejected[1]))
                .isEqualTo(1);
    }

    @Test
    void itemPlansWinOverCategoryPlansAndReturnInspectionsFallBackToTheFinalPlan() throws Exception {
        String parent = id();
        String child = id();
        update("INSERT INTO item_categories(id,tenant_id,name) VALUES(?,?,'Containers')", parent, t);
        update("INSERT INTO item_categories(id,tenant_id,name,parent_id) VALUES(?,?,'Bottles',?)", child, t, parent);
        update("UPDATE items SET category_id=? WHERE tenant_id=? AND id=?", child, t, bottle);
        String categoryPlan = str(
                plans.create(
                        qa,
                        new InspectionPlanCommands.Plan(
                                "Containers final",
                                null,
                                parent,
                                "FINAL",
                                null,
                                List.of(attribute("Visual", 5, false, "VIS"))),
                        id()),
                "id");
        assertThat(plans.applicablePlan(qa, bottle, "FINAL")).isEqualTo(categoryPlan);
        assertThat(plans.applicablePlan(qa, bottle, "RETURN")).isEqualTo(categoryPlan);
        assertThat(plans.applicablePlan(qa, bottle, "REWORK")).isEqualTo(categoryPlan);
        assertThat(plans.applicablePlan(qa, bottle, "INCOMING")).isNull();
        String itemPlan = bottlePlan("FINAL");
        assertThat(plans.applicablePlan(qa, bottle, "RETURN")).isEqualTo(itemPlan);
        String returnPlan = bottlePlan("RETURN");
        assertThat(plans.applicablePlan(qa, bottle, "RETURN")).isEqualTo(returnPlan);

        // One active plan per item and type.
        assertThatThrownBy(() -> bottlePlan("FINAL")).hasMessageContaining("already exists");
        // Exactly one of item / category.
        assertThatThrownBy(() -> plans.create(
                        qa,
                        new InspectionPlanCommands.Plan(
                                "Both", bottle, parent, "FINAL", null, List.of(attribute("V", 1, false, null))),
                        id()))
                .hasMessageContaining("either an item or an item category");
        // A pending inspection shows the plan that governs it.
        var held = heldBottles("5", "RETURN");
        var forInspection = plans(held[1]);
        assertThat(((Map<?, ?>) forInspection.get("plan")).get("id")).isEqualTo(returnPlan);
        assertThat((List<?>) forInspection.get("characteristics")).hasSize(3);
    }

    @Test
    void editingAPlanCreatesANewVersionAndKeepsMeasurementSnapshots() {
        String plan = bottlePlan("FINAL");
        var held = heldBottles("10", "FINAL");
        quality.inspect(qa, held[1], new QualityCommands.Inspect("PASSED", "ok", goodBottle(plan)), id());
        var old = characteristics(plan);
        var updated = plans.update(
                qa,
                plan,
                new InspectionPlanCommands.PlanUpdate(
                        "Bottle final v2",
                        "Tighter wall",
                        true,
                        List.of(variable("Wall thickness", "0.46", "0.42", "0.50", 2, true, "DIM"))),
                id());
        assertThat(((Number) updated.get("version")).intValue()).isEqualTo(2);
        assertThat(characteristics(plan)).hasSize(1).doesNotContainAnyElementsOf(old);
        assertThat(count(
                        "SELECT COUNT(*) FROM inspection_characteristics WHERE tenant_id=? AND plan_id=? AND NOT active",
                        t,
                        plan))
                .isEqualTo(3);
        // The first decision keeps version 1 and its specification snapshot.
        var m = row(
                "SELECT * FROM inspection_measurements WHERE tenant_id=? AND inspection_id=? AND seq=1", t, held[1]);
        assertThat(((Number) m.get("plan_version")).intValue()).isEqualTo(1);
        assertThat(dec(m.get("lower_limit"))).isEqualByComparingTo("0.40");
        // A stale characteristic id from version 1 is refused on a new inspection.
        var next = heldBottles("10", "FINAL");
        var stale = List.of(
                readings(old.get(0), null, "0.45", "0.44", "0.46"),
                readings(old.get(1), null, "22.0", "22.1"),
                count(old.get(2), 0));
        assertThatThrownBy(() -> quality.inspect(qa, next[1], new QualityCommands.Inspect("PASSED", "ok", stale), id()))
                .hasMessageContaining("current version");
        // Limits must make sense.
        assertThatThrownBy(() -> plans.update(
                        qa,
                        plan,
                        new InspectionPlanCommands.PlanUpdate(
                                "x", null, true, List.of(variable("Wall", null, "0.5", "0.4", 2, true, null))),
                        id()))
                .hasMessageContaining("lower limit");
        assertThatThrownBy(() -> plans.update(
                        qa,
                        plan,
                        new InspectionPlanCommands.PlanUpdate(
                                "x", null, true, List.of(variable("Wall", null, null, null, 2, true, null))),
                        id()))
                .hasMessageContaining("specification limit");
        // Deactivated plans stop governing decisions.
        plans.update(
                qa,
                plan,
                new InspectionPlanCommands.PlanUpdate("off", null, false, List.of(attribute("Visual", 1, false, null))),
                id());
        assertThat(plans.applicablePlan(qa, bottle, "FINAL")).isNull();
        assertThat(quality.inspect(qa, next[1], new QualityCommands.Inspect("PASSED", "ok"), id()))
                .containsEntry("status", "PASSED");
    }

    @Test
    void plansAndDefectCodesArePermissionedIdempotentAndTenantScoped() throws Exception {
        var body = new InspectionPlanCommands.Plan(
                "Resin incoming", resin, null, "INCOMING", null, List.of(attribute("CoA present", 1, true, "DOC")));
        assertThatThrownBy(() -> plans.create(planner, body, id())).isInstanceOf(AccessDeniedException.class);
        command(warehouse, "/api/quality/plans", body).andExpect(status().isForbidden());
        String key = id();
        var first = json(command(qa, "/api/quality/plans", body, key).andExpect(status().isOk()));
        var replay = json(command(qa, "/api/quality/plans", body, key).andExpect(status().isOk()));
        assertThat(replay.get("id")).isEqualTo(first.get("id"));
        assertThat(count("SELECT COUNT(*) FROM inspection_plans WHERE tenant_id=?", t))
                .isEqualTo(1);
        var other = new InspectionPlanCommands.Plan(
                "Other", label, null, "INCOMING", null, List.of(attribute("CoA present", 1, true, "DOC")));
        command(qa, "/api/quality/plans", other, key).andExpect(status().isConflict());
        // Bean validation: an empty characteristic list.
        command(
                        qa,
                        "/api/quality/plans",
                        new InspectionPlanCommands.Plan("Empty", resin, null, "INCOMING", null, List.of()))
                .andExpect(status().isBadRequest());

        var page = json(query(viewer, "/api/quality/plans?type=INCOMING").andExpect(status().isOk()));
        assertThat((List<?>) page.get("items")).hasSize(1);
        query(viewer, "/api/quality/plans/" + first.get("id")).andExpect(status().isOk());

        var stranger = createTenant("Other quality tenant");
        var outsider = createUser(stranger.id(), "QUALITY");
        query(outsider, "/api/quality/plans/" + first.get("id")).andExpect(status().isNotFound());
        command(
                        outsider,
                        "/api/quality/plans/" + first.get("id"),
                        new InspectionPlanCommands.PlanUpdate(
                                "x", null, true, List.of(attribute("CoA present", 1, true, null))))
                .andExpect(status().isNotFound());
        assertThat((List<?>) json(query(outsider, "/api/quality/plans")).get("items"))
                .isEmpty();

        // Default defect codes are seeded for every tenant; INSP cannot be deactivated.
        var codes = json(query(viewer, "/api/quality/defect-codes"));
        assertThat((List<?>) codes.get("items")).hasSizeGreaterThanOrEqualTo(9);
        command(
                        qa,
                        "/api/quality/defect-codes",
                        new InspectionPlanCommands.DefectCode("BURR", "Burr", "Sharp edge", true))
                .andExpect(status().isOk());
        command(qa, "/api/quality/defect-codes", new InspectionPlanCommands.DefectCode("BURR", "Burr", "", true))
                .andExpect(status().isConflict());
        command(
                        qa,
                        "/api/quality/defect-codes/INSP",
                        new InspectionPlanCommands.DefectCode("INSP", "Failed", "", false))
                .andExpect(status().isConflict());
        command(qa, "/api/quality/defect-codes/BURR", new InspectionPlanCommands.DefectCode("BURR", "Burr", "", false))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> plans.create(
                        qa,
                        new InspectionPlanCommands.Plan(
                                "Burr check",
                                label,
                                null,
                                "INCOMING",
                                null,
                                List.of(attribute("Edge", 1, false, "BURR"))),
                        id()))
                .hasMessageContaining("inactive");
        command(planner, "/api/quality/defect-codes", new InspectionPlanCommands.DefectCode("SCR", "Scratch", "", true))
                .andExpect(status().isForbidden());
    }

    private Map<String, Object> plans(String inspection) throws Exception {
        return json(query(viewer, "/api/quality/inspections/" + inspection + "/plan")
                .andExpect(status().isOk()));
    }
}
