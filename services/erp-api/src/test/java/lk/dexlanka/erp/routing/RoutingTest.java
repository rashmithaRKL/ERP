package lk.dexlanka.erp.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.RoutingTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** V10 routing revisions: authoring, independent approval, immutability, copy, current revision and read model. */
class RoutingTest extends RoutingTestBase {

    private RoutingCommands.Routing body(String revision, RoutingCommands.Operation... ops) {
        return new RoutingCommands.Routing(bottle, revision, "Bottle line", List.of(ops));
    }

    @Test
    void approvalByAnotherPersonFreezesTheRevisionAndMakesItCurrent() throws Exception {
        String skill = skill("BLOW");
        var created = json(command(
                        planner,
                        "/api/routings",
                        body(
                                "R1",
                                op(10, "Blow", bm1, List.of(bm2), "30", "0.05", "1", skill, 2, false),
                                op(20, "Pack", pk1, List.of(), "0", "0.02", "2", null, null, true)))
                .andExpect(status().isOk()));
        String id = str(created, "id");
        assertThat(str(created, "status")).isEqualTo("DRAFT");
        assertThat(str(created, "number")).startsWith("RT-");
        assertThat(count("SELECT COUNT(*) FROM routing_operations WHERE tenant_id=? AND routing_id=?", t, id))
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM routing_operation_alternates WHERE tenant_id=?", t))
                .isEqualTo(1);

        // Self-approval is banned for the author, ADMIN included.
        command(planner, "/api/routings/" + id + "/approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("different")));
        command(otherPlanner, "/api/routings/" + id + "/approve", null).andExpect(status().isOk());

        var r = row("SELECT * FROM routings WHERE tenant_id=? AND id=?", t, id);
        assertThat(r.get("status")).isEqualTo("APPROVED");
        assertThat(r.get("approved_by")).isEqualTo(otherPlanner.id());
        assertThat(row("SELECT routing_id FROM routing_defaults WHERE tenant_id=? AND product_id=?", t, bottle)
                        .get("routing_id"))
                .isEqualTo(id);

        // Approved revisions are immutable through the API ...
        command(planner, "/api/routings/" + id, new RoutingCommands.Draft("R1", "", List.of(op(10, "Blow", bm1))))
                .andExpect(status().isConflict());
        // ... and in the database (V2-style triggers).
        assertThatThrownBy(() -> update("UPDATE routings SET notes='x' WHERE tenant_id=? AND id=?", t, id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Approved routings are immutable");
        assertThatThrownBy(() -> update(
                        "UPDATE routing_operations SET setup_minutes=1 WHERE tenant_id=? AND routing_id=?", t, id))
                .hasMessageContaining("Operations of an approved routing are immutable");
        assertThatThrownBy(() -> update("DELETE FROM routing_operation_alternates WHERE tenant_id=?", t))
                .hasMessageContaining("Operations of an approved routing are immutable");
        assertThatThrownBy(() -> update(
                        "INSERT INTO routing_operations(id,tenant_id,routing_id,seq,name,machine_id) VALUES(?,?,?,30,'Extra',?)",
                        id(),
                        t,
                        id,
                        pk1))
                .hasMessageContaining("Operations of an approved routing are immutable");
        assertThatThrownBy(() -> update("DELETE FROM routings WHERE tenant_id=? AND id=?", t, id))
                .hasMessageContaining("Approved routings are immutable");
        // Only approved revisions can be current.
        String draft = draftRouting(bottle, "R9", op(10, "Blow", bm1));
        assertThatThrownBy(() -> update(
                        "UPDATE routing_defaults SET routing_id=? WHERE tenant_id=? AND product_id=?",
                        draft,
                        t,
                        bottle))
                .hasMessageContaining("Only an approved routing can be the current revision");

        assertThat(rows(
                        "SELECT action FROM audit_events WHERE tenant_id=? AND reference=? ORDER BY created_at",
                        t,
                        r.get("number")))
                .extracting(e -> e.get("action"))
                .containsExactly("ROUTING_DRAFT_CREATED", "ROUTING_APPROVED");
    }

    @Test
    void theLastEditorCannotApproveEither() throws Exception {
        String id = draftRouting(bottle, "R1", op(10, "Blow", bm1));
        command(
                        otherPlanner,
                        "/api/routings/" + id,
                        new RoutingCommands.Draft("R1a", "Edited", List.of(op(10, "Blow", bm1), op(20, "Pack", pk1))))
                .andExpect(status().isOk());
        assertThat(row("SELECT revision,updated_by FROM routings WHERE tenant_id=? AND id=?", t, id))
                .containsEntry("revision", "R1a")
                .containsEntry("updated_by", otherPlanner.id());
        assertThat(count("SELECT COUNT(*) FROM routing_operations WHERE tenant_id=? AND routing_id=?", t, id))
                .isEqualTo(2);
        command(otherPlanner, "/api/routings/" + id + "/approve", null).andExpect(status().isConflict());
        command(planner, "/api/routings/" + id + "/approve", null).andExpect(status().isConflict());
        command(admin, "/api/routings/" + id + "/approve", null).andExpect(status().isOk());
    }

    @Test
    void copyIntoADraftAndSwitchTheCurrentRevision() throws Exception {
        String r1 = approvedRouting(
                bottle,
                "R1",
                op(10, "Blow", bm1, List.of(bm2), "30", "0.05", "1", null, null, false),
                op(20, "Pack", pk1));
        String draft = draftRouting(bottle, "D1", op(10, "Blow", bm1));
        command(planner, "/api/routings/" + draft + "/copy", new RoutingCommands.Copy("X"))
                .andExpect(status().isConflict());
        command(planner, "/api/routings/" + r1 + "/copy", new RoutingCommands.Copy("r1"))
                .andExpect(status().isConflict()); // revisions are unique per product, case-insensitively

        var copy = json(command(planner, "/api/routings/" + r1 + "/copy", new RoutingCommands.Copy("R2"))
                .andExpect(status().isOk()));
        String r2 = str(copy, "id");
        var copied = row("SELECT * FROM routings WHERE tenant_id=? AND id=?", t, r2);
        assertThat(copied).containsEntry("status", "DRAFT").containsEntry("source_routing_id", r1);
        assertThat(rows(
                        "SELECT seq,name,machine_id,setup_minutes FROM routing_operations WHERE tenant_id=? AND routing_id=? ORDER BY seq",
                        t,
                        r2))
                .extracting(o -> o.get("seq"), o -> o.get("machine_id"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(10, bm1), org.assertj.core.groups.Tuple.tuple(20, pk1));
        assertThat(count(
                        "SELECT COUNT(*) FROM routing_operation_alternates x JOIN routing_operations o ON o.tenant_id=x.tenant_id AND o.id=x.operation_id WHERE x.tenant_id=? AND o.routing_id=?",
                        t,
                        r2))
                .isEqualTo(1);

        // Edit and approve the copy: it becomes current; "make current" switches back to R1.
        routings.updateDraft(
                planner,
                r2,
                new RoutingCommands.Draft(
                        "R2", "", List.of(op(10, "Blow", bm1, List.of(bm2), "20", "0.04", "1", null, null, false))),
                id());
        routings.approve(otherPlanner, r2, id());
        assertThat(currentRouting()).isEqualTo(r2);
        command(viewer, "/api/routings/" + r1 + "/make-current", null).andExpect(status().isForbidden());
        command(planner, "/api/routings/" + draft + "/make-current", null).andExpect(status().isConflict());
        command(planner, "/api/routings/" + r1 + "/make-current", null).andExpect(status().isOk());
        assertThat(currentRouting()).isEqualTo(r1);

        // Compare R1 → R2: op 10 changed (setup, run), op 20 removed.
        var compare = json(
                query(planner, "/api/routings/compare?from=" + r1 + "&to=" + r2).andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var ops = (List<Map<String, Object>>) compare.get("operations");
        assertThat(ops).extracting(o -> o.get("status")).containsExactly("CHANGED", "REMOVED");
        assertThat(ops.getFirst().get("changes")).asList().containsExactly("setup_minutes", "run_minutes_per_unit");
    }

    @Test
    void operationsAreValidated() throws Exception {
        String other = machine("PK-2", "Packaging");
        String raw = item("RM2", "RAW_MATERIAL", "kg", "1");
        String inactive = skill("OLD");
        skills.update(planner, inactive, new SkillCommands.SkillUpdate("Old skill", "", false), id());
        var foreign = createTenant();
        String foreignMachine = id();
        update(
                "INSERT INTO machines(id,tenant_id,factory_id,code,name,kind,status,hourly_rate) VALUES(?,?,?,'F-1','Foreign','Blow moulding','IDLE',1)",
                foreignMachine,
                foreign.id(),
                foreign.plant());

        command(
                        planner,
                        "/api/routings",
                        body("A", op(10, "Blow", bm1, List.of(other), "0", "1", "1", null, null, false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("is not a Blow moulding")));
        command(
                        planner,
                        "/api/routings",
                        body("A", op(10, "Blow", bm1, List.of(bm1), "0", "1", "1", null, null, false)))
                .andExpect(status().isConflict());
        command(planner, "/api/routings", body("A", op(10, "Blow", bm1), op(10, "Pack", pk1)))
                .andExpect(status().isConflict());
        command(planner, "/api/routings", body("A", op(10, "Blow", bm1, List.of(), "0", "1", "1", inactive, 1, false)))
                .andExpect(status().isConflict());
        command(planner, "/api/routings", body("A", op(10, "Blow", foreignMachine)))
                .andExpect(status().isNotFound());
        command(planner, "/api/routings", new RoutingCommands.Routing(raw, "A", "", List.of(op(10, "Mix", bm1))))
                .andExpect(status().isConflict());
        command(planner, "/api/routings", new RoutingCommands.Routing(bottle, "A", "", List.of()))
                .andExpect(status().isBadRequest());
        command(planner, "/api/routings", body("A", op(10, "Blow", bm1, List.of(), "-1", "1", "1", null, null, false)))
                .andExpect(status().isBadRequest());
        assertThat(count("SELECT COUNT(*) FROM routings WHERE tenant_id=?", t)).isZero();
    }

    @Test
    void permissionsIdempotencyAndTenantIsolation() throws Exception {
        var routing = body("R1", op(10, "Blow", bm1));
        command(operator, "/api/routings", routing).andExpect(status().isForbidden());
        command(viewer, "/api/routings", routing).andExpect(status().isForbidden());

        String key = id();
        var first = json(command(planner, "/api/routings", routing, key).andExpect(status().isOk()));
        var replay = json(command(planner, "/api/routings", routing, key).andExpect(status().isOk()));
        assertThat(replay.get("id")).isEqualTo(first.get("id"));
        assertThat(count("SELECT COUNT(*) FROM routings WHERE tenant_id=?", t)).isEqualTo(1);
        command(planner, "/api/routings", body("R2", op(10, "Blow", bm1)), key).andExpect(status().isConflict());

        var other = createTenant();
        var outsider = createUser(other.id(), "ADMIN");
        query(outsider, "/api/routings/" + first.get("id")).andExpect(status().isNotFound());
        command(outsider, "/api/routings/" + first.get("id") + "/approve", null).andExpect(status().isNotFound());
        assertThat(json(query(outsider, "/api/routings").andExpect(status().isOk()))
                        .get("items"))
                .asList()
                .isEmpty();
    }

    @Test
    void listAndDetail() throws Exception {
        String r1 =
                approvedRouting(bottle, "R1", op(10, "Blow", bm1, List.of(bm2), "30", "0.05", "1", null, null, false));
        draftRouting(bottle, "R2", op(10, "Blow", bm1));

        var page = json(query(operator, "/api/routings?limit=1").andExpect(status().isOk()));
        assertThat(page.get("items")).asList().hasSize(1);
        assertThat(str(page, "next")).isNotEmpty();
        assertThat(((Number) page.get("total")).intValue()).isEqualTo(2);
        var next = json(query(operator, "/api/routings?limit=1&before=" + page.get("next")));
        assertThat(next.get("items")).asList().hasSize(1);
        assertThat(str(next, "next")).isEmpty();

        var current = json(query(operator, "/api/routings?status=current"));
        assertThat(current.get("items")).asList().hasSize(1);

        var detail = json(query(operator, "/api/routings/" + r1).andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var routing = (Map<String, Object>) detail.get("routing");
        assertThat(routing).containsEntry("current", true).containsEntry("revision", "R1");
        @SuppressWarnings("unchecked")
        var ops = (List<Map<String, Object>>) detail.get("operations");
        assertThat(ops.getFirst()).containsEntry("machine_code", "BM-1");
        assertThat(ops.getFirst().get("alternates")).asList().hasSize(1);
        assertThat(detail.get("revisions")).asList().hasSize(2);
    }

    private String currentRouting() {
        return str(
                row("SELECT routing_id FROM routing_defaults WHERE tenant_id=? AND product_id=?", t, bottle),
                "routing_id");
    }
}
