package lk.dexlanka.erp.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** V12 CAPA: lifecycle, root cause, action ownership, overdue flags, independent verification, permissions, tenancy. */
class CapaTest extends QualityTestSupport {
    static final String YESTERDAY = LocalDate.now().minusDays(1).toString();
    static final String NEXT_WEEK = LocalDate.now().plusDays(7).toString();

    CapaCommands.Update analysis(String ncr) {
        return new CapaCommands.Update(
                "Short shots on bottles",
                "Bottles incomplete at the base",
                qa.id(),
                NEXT_WEEK,
                "MACHINE",
                List.of("Low melt temperature", "Heater band failed", "No preventive check", "", ""),
                "Heater band failure not detected",
                ncr == null ? List.of() : List.of(ncr));
    }

    @Test
    void capaRunsFromOpenToClosedWithIndependentVerification() throws Exception {
        var rejected = rejectedBottles("10");
        var created = json(command(
                        qa,
                        "/api/quality/capas",
                        new CapaCommands.Capa(
                                "Short shots",
                                "Bottles incomplete",
                                "NCR",
                                qa.id(),
                                NEXT_WEEK,
                                List.of(rejected[2], rejected[2])))
                .andExpect(status().isOk()));
        String capa = str(created, "id");
        assertThat(created).containsEntry("status", "OPEN");
        assertThat(count("SELECT COUNT(*) FROM capa_ncrs WHERE tenant_id=? AND capa_id=?", t, capa))
                .isEqualTo(1);

        // Starting needs a root cause and an action.
        assertThatThrownBy(() -> capas.start(qa, capa, new CapaCommands.Note("go"), id()))
                .hasMessageContaining("root-cause");
        capas.update(qa, capa, analysis(rejected[2]), id());
        assertThatThrownBy(() -> capas.start(qa, capa, new CapaCommands.Note("go"), id()))
                .hasMessageContaining("at least one");
        String corrective = str(
                capas.addAction(
                        qa,
                        capa,
                        new CapaCommands.Action("CORRECTIVE", "Replace heater band", planner.id(), YESTERDAY),
                        id()),
                "id");
        String preventive = str(
                capas.addAction(
                        qa,
                        capa,
                        new CapaCommands.Action("PREVENTIVE", "Weekly heater check", qa.id(), NEXT_WEEK),
                        id()),
                "id");
        capas.start(qa, capa, new CapaCommands.Note("Root cause confirmed"), id());
        assertThat(capa(capa)).containsEntry("status", "IN_PROGRESS");

        // The corrective action is overdue: flagged in the lists and the state counts.
        var detail = json(query(viewer, "/api/quality/capas/" + capa).andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var actions = (List<Map<String, Object>>) detail.get("actions");
        assertThat(actions.get(0)).containsEntry("overdue", true);
        assertThat(actions.get(1)).containsEntry("overdue", false);
        var overdue = json(query(viewer, "/api/quality/capa-actions?overdue=true"));
        assertThat((List<?>) overdue.get("items")).hasSize(1);
        assertThat((List<?>)
                        json(query(viewer, "/api/quality/capas?overdue=true")).get("items"))
                .hasSize(1);
        assertThat((List<?>) json(query(planner, "/api/quality/capa-actions?mine=true"))
                        .get("items"))
                .hasSize(1);
        var alerts = (Map<?, ?>) json(query(planner, "/api/state")).get("qualityAlerts");
        assertThat(((Number) alerts.get("overdue_capa_actions")).intValue()).isEqualTo(1);
        assertThat(((Number) alerts.get("my_overdue_capa_actions")).intValue()).isEqualTo(1);

        // Not ready for verification while an action is open.
        assertThatThrownBy(() -> capas.submit(qa, capa, new CapaCommands.Note("done"), id()))
                .hasMessageContaining("still open");
        // The action owner (a planner) completes their own action; others outside quality cannot.
        assertThatThrownBy(() -> capas.completeAction(viewer, corrective, new CapaCommands.Note("x"), id()))
                .isInstanceOf(AccessDeniedException.class);
        command(planner, "/api/quality/capa-actions/" + corrective + "/complete", new CapaCommands.Note("Replaced"))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> capas.completeAction(planner, preventive, new CapaCommands.Note("x"), id()))
                .isInstanceOf(AccessDeniedException.class);
        capas.completeAction(qa, preventive, new CapaCommands.Note("Checklist issued"), id());
        assertThat(((Number) ((Map<?, ?>) json(query(planner, "/api/state")).get("qualityAlerts"))
                                .get("overdue_capa_actions"))
                        .intValue())
                .isZero();

        capas.submit(qa, capa, new CapaCommands.Note("Ready"), id());
        assertThat(capa(capa)).containsEntry("status", "VERIFICATION");
        // Actions are frozen during verification.
        assertThatThrownBy(() -> capas.addAction(
                        qa, capa, new CapaCommands.Action("PREVENTIVE", "More", qa.id(), NEXT_WEEK), id()))
                .hasMessageContaining("open or in progress");
        // The owner cannot verify their own CAPA; a different quality user can.
        var verify = new CapaCommands.Verify(true, "No short shots in 2 weeks");
        assertThatThrownBy(() -> capas.verify(qa, capa, verify, id())).hasMessageContaining("different person");
        assertThatThrownBy(() -> capas.verify(planner, capa, verify, id())).isInstanceOf(AccessDeniedException.class);
        capas.verify(qa2, capa, verify, id());
        assertThat(capa(capa))
                .containsEntry("status", "CLOSED")
                .containsEntry("verified_by", qa2.id())
                .containsEntry("effective", true);
        assertThatThrownBy(() -> capas.update(qa, capa, analysis(null), id())).hasMessageContaining("closed");
        var ncrDetail = json(query(viewer, "/api/quality/ncrs/" + rejected[2]));
        assertThat((List<?>) ncrDetail.get("capas")).hasSize(1);
    }

    @Test
    void notEffectiveReturnsTheCapaToWorkAndCancellingClosesItsActions() {
        String capa = str(
                capas.create(
                        qa,
                        new CapaCommands.Capa("Audit finding", "Gauge records", "AUDIT", qa.id(), null, null),
                        id()),
                "id");
        capas.update(qa, capa, analysis(null), id());
        String action = str(
                capas.addAction(
                        qa, capa, new CapaCommands.Action("CORRECTIVE", "Restore records", qa.id(), NEXT_WEEK), id()),
                "id");
        capas.start(qa, capa, new CapaCommands.Note("go"), id());
        capas.completeAction(qa, action, new CapaCommands.Note("Restored"), id());
        capas.submit(qa, capa, new CapaCommands.Note("Ready"), id());
        var reopened = capas.verify(admin, capa, new CapaCommands.Verify(false, "Records still incomplete"), id());
        assertThat(reopened).containsEntry("status", "IN_PROGRESS");
        assertThat(capa(capa)).containsEntry("effective", false);
        String late = str(
                capas.addAction(
                        qa,
                        capa,
                        new CapaCommands.Action("PREVENTIVE", "Audit monthly", planner.id(), YESTERDAY),
                        id()),
                "id");
        capas.cancel(qa, capa, new CapaCommands.Note("Superseded by CAPA on calibration"), id());
        assertThat(capa(capa)).containsEntry("status", "CANCELLED");
        assertThat(row("SELECT status FROM capa_actions WHERE tenant_id=? AND id=?", t, late))
                .containsEntry("status", "CANCELLED");
        assertThatThrownBy(() -> capas.cancel(qa, capa, new CapaCommands.Note("again"), id()))
                .hasMessageContaining("already");
    }

    @Test
    void capaCommandsArePermissionedIdempotentAndTenantScoped() throws Exception {
        var body = new CapaCommands.Capa("Supplier gels", "Gels in resin", "SUPPLIER", qa.id(), NEXT_WEEK, null);
        command(planner, "/api/quality/capas", body).andExpect(status().isForbidden());
        String key = id();
        var first = json(command(qa, "/api/quality/capas", body, key).andExpect(status().isOk()));
        var replay = json(command(qa, "/api/quality/capas", body, key).andExpect(status().isOk()));
        assertThat(replay.get("id")).isEqualTo(first.get("id"));
        assertThat(count("SELECT COUNT(*) FROM capas WHERE tenant_id=?", t)).isEqualTo(1);
        command(qa, "/api/quality/capas", new CapaCommands.Capa("Other", "x", "INTERNAL", qa.id(), null, null), key)
                .andExpect(status().isConflict());
        command(
                        qa,
                        "/api/quality/capas",
                        new CapaCommands.Capa("Bad date", "x", "INTERNAL", qa.id(), "2026-02-30", null))
                .andExpect(status().isBadRequest());
        // A deactivated user cannot own new work.
        update("UPDATE users SET active=false WHERE id=?", operator.id());
        command(
                        qa,
                        "/api/quality/capas/" + first.get("id") + "/actions",
                        new CapaCommands.Action("CORRECTIVE", "x", operator.id(), NEXT_WEEK))
                .andExpect(status().isConflict());
        var stranger = createTenant("Other CAPA tenant");
        var outsider = createUser(stranger.id(), "QUALITY");
        query(outsider, "/api/quality/capas/" + first.get("id")).andExpect(status().isNotFound());
        command(outsider, "/api/quality/capas/" + first.get("id") + "/cancel", new CapaCommands.Note("x"))
                .andExpect(status().isNotFound());
        // An owner from another tenant is not ours.
        command(qa, "/api/quality/capas", new CapaCommands.Capa("x", "x", "INTERNAL", outsider.id(), null, null))
                .andExpect(status().isNotFound());
        var people = json(query(viewer, "/api/quality/people"));
        assertThat((List<?>) people.get("items")).hasSize(7);
        assertThat((List<?>)
                        json(query(viewer, "/api/quality/capas?status=active")).get("items"))
                .hasSize(1);
    }

    private Map<String, Object> capa(String id) {
        return row("SELECT * FROM capas WHERE tenant_id=? AND id=?", t, id);
    }
}
