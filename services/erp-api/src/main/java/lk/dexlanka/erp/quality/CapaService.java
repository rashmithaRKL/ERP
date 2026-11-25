package lk.dexlanka.erp.quality;

import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;
import static lk.dexlanka.erp.quality.QualityRules.blankToEmpty;
import static lk.dexlanka.erp.quality.QualityRules.blankToNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrective and preventive actions ({@code CAPA-…}, V12). Lifecycle OPEN → IN_PROGRESS (root cause recorded, at
 * least one action) → VERIFICATION (every action done or cancelled, at least one done) → CLOSED (effectiveness verified
 * by a different person than the CAPA owner; "not effective" returns it to IN_PROGRESS); CANCELLED from any open state.
 * QUALITY/ADMIN manage CAPAs; an action's owner (any role) may complete their own action. An action is overdue while
 * it is open after its due date.
 */
@Service
public class CapaService {
    private static final Set<String> CLOSED = Set.of("CLOSED", "CANCELLED");

    private final Store s;
    private final QualityRules rules;

    CapaService(Store s, QualityRules rules) {
        this.s = s;
        this.rules = rules;
    }

    @Transactional
    public Map<String, Object> create(Actor a, CapaCommands.Capa c, String key) {
        a.allow("QUALITY");
        if (c.dueDate() != null) s.validDate(c.dueDate());
        return s.once(a, key, "capa-create", c, () -> {
            var owner = rules.activeUser(a, c.ownerId());
            var ncrs = ncrs(a, c.ncrIds());
            String id = id();
            String number = rules.documentNumber(a, "capas", "CAPA");
            s.db().update(
                            "INSERT INTO capas(id,tenant_id,number,title,problem,source,owner_id,due_date,created_by) VALUES(?,?,?,?,?,?,?,?::date,?)",
                            id,
                            a.tenant(),
                            number,
                            c.title().strip(),
                            c.problem().strip(),
                            c.source(),
                            c.ownerId(),
                            c.dueDate(),
                            a.id());
            link(a, id, ncrs);
            s.audit(
                    a,
                    "CAPA_CREATED",
                    number,
                    c.title().strip() + " · owner " + owner.get("name")
                            + (ncrs.isEmpty() ? "" : " · " + String.join(", ", numbers(a, ncrs))));
            return Map.of("id", id, "number", number, "status", "OPEN");
        });
    }

    @Transactional
    public Map<String, Object> update(Actor a, String id, CapaCommands.Update c, String key) {
        a.allow("QUALITY");
        if (c.dueDate() != null) s.validDate(c.dueDate());
        return s.once(a, key, "capa-update:" + id, c, () -> {
            var capa = locked(a, id);
            require(!CLOSED.contains(str(capa, "status")), "A closed or cancelled CAPA cannot be edited");
            var owner = rules.activeUser(a, c.ownerId());
            var ncrs = ncrs(a, c.ncrIds());
            List<String> whys = c.whys() == null ? List.of() : c.whys();
            String[] why = new String[5];
            for (int i = 0; i < 5; i++) why[i] = i < whys.size() ? blankToEmpty(whys.get(i)) : "";
            s.db().update(
                            "UPDATE capas SET title=?,problem=?,owner_id=?,due_date=?::date,root_cause_category=?,why_1=?,why_2=?,why_3=?,why_4=?,why_5=?,root_cause=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            c.title().strip(),
                            c.problem().strip(),
                            c.ownerId(),
                            c.dueDate(),
                            blankToNull(c.rootCauseCategory()),
                            why[0],
                            why[1],
                            why[2],
                            why[3],
                            why[4],
                            blankToEmpty(c.rootCause()),
                            a.tenant(),
                            id);
            s.db().update("DELETE FROM capa_ncrs WHERE tenant_id=? AND capa_id=?", a.tenant(), id);
            link(a, id, ncrs);
            s.audit(
                    a,
                    "CAPA_UPDATED",
                    str(capa, "number"),
                    c.title().strip() + " · owner " + owner.get("name")
                            + (blankToNull(c.rootCauseCategory()) == null
                                    ? ""
                                    : " · root cause " + c.rootCauseCategory()));
            return Map.of("id", id, "status", capa.get("status"));
        });
    }

    @Transactional
    public Map<String, Object> addAction(Actor a, String capaId, CapaCommands.Action c, String key) {
        a.allow("QUALITY");
        s.validDate(c.dueDate());
        return s.once(a, key, "capa-action:" + capaId, c, () -> {
            var capa = locked(a, capaId);
            require(
                    Set.of("OPEN", "IN_PROGRESS").contains(str(capa, "status")),
                    "Actions are added while a CAPA is open or in progress");
            var owner = rules.activeUser(a, c.ownerId());
            int seq = ((Number) s.one(
                                    "SELECT COALESCE(MAX(seq),0)+1 AS n FROM capa_actions WHERE tenant_id=? AND capa_id=?",
                                    a.tenant(),
                                    capaId)
                            .get("n"))
                    .intValue();
            String id = id();
            s.db().update(
                            "INSERT INTO capa_actions(id,tenant_id,capa_id,seq,type,description,owner_id,due_date,created_by) VALUES(?,?,?,?,?,?,?,?::date,?)",
                            id,
                            a.tenant(),
                            capaId,
                            seq,
                            c.type(),
                            c.description().strip(),
                            c.ownerId(),
                            c.dueDate(),
                            a.id());
            s.audit(
                    a,
                    "CAPA_ACTION_ADDED",
                    str(capa, "number"),
                    c.type() + " action " + seq + " for " + owner.get("name") + " due " + c.dueDate() + " · "
                            + c.description().strip());
            return Map.of("id", id, "capaId", capaId, "seq", seq, "status", "OPEN");
        });
    }

    /** Marks an action done: its owner (any role) or QUALITY/ADMIN. */
    @Transactional
    public Map<String, Object> completeAction(Actor a, String actionId, CapaCommands.Note c, String key) {
        return s.once(a, key, "capa-action-done:" + actionId, c, () -> {
            var action = action(a, actionId);
            if (!a.id().equals(action.get("owner_id"))) a.allow("QUALITY");
            require(
                    action.get("status").equals("OPEN"),
                    "This action is already " + str(action, "status").toLowerCase());
            var capa = locked(a, str(action, "capa_id"));
            require(
                    Set.of("OPEN", "IN_PROGRESS").contains(str(capa, "status")),
                    "Actions are completed while the CAPA is open or in progress");
            s.db().update(
                            "UPDATE capa_actions SET status='DONE',completion_note=?,completed_by=?,completed_at=now() WHERE tenant_id=? AND id=?",
                            c.note().strip(),
                            a.id(),
                            a.tenant(),
                            actionId);
            s.audit(
                    a,
                    "CAPA_ACTION_DONE",
                    str(capa, "number"),
                    "Action " + action.get("seq") + " done · " + c.note().strip());
            return Map.of("id", actionId, "status", "DONE");
        });
    }

    @Transactional
    public Map<String, Object> cancelAction(Actor a, String actionId, CapaCommands.Note c, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "capa-action-cancel:" + actionId, c, () -> {
            var action = action(a, actionId);
            require(action.get("status").equals("OPEN"), "Only an open action can be cancelled");
            var capa = locked(a, str(action, "capa_id"));
            require(!CLOSED.contains(str(capa, "status")), "The CAPA is closed");
            s.db().update(
                            "UPDATE capa_actions SET status='CANCELLED',completion_note=? WHERE tenant_id=? AND id=?",
                            c.note().strip(),
                            a.tenant(),
                            actionId);
            s.audit(
                    a,
                    "CAPA_ACTION_CANCELLED",
                    str(capa, "number"),
                    "Action " + action.get("seq") + " cancelled · " + c.note().strip());
            return Map.of("id", actionId, "status", "CANCELLED");
        });
    }

    /** OPEN → IN_PROGRESS once the root cause (category and analysis) is recorded and at least one action exists. */
    @Transactional
    public Map<String, Object> start(Actor a, String id, CapaCommands.Note c, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "capa-start:" + id, c, () -> {
            var capa = locked(a, id);
            require(capa.get("status").equals("OPEN"), "Only an open CAPA can be started");
            require(
                    capa.get("root_cause_category") != null
                            && !str(capa, "root_cause").isBlank(),
                    "Record the root-cause category and analysis first");
            require(
                    !s.list(
                                    "SELECT 1 FROM capa_actions WHERE tenant_id=? AND capa_id=? AND status<>'CANCELLED'",
                                    a.tenant(),
                                    id)
                            .isEmpty(),
                    "Plan at least one corrective or preventive action first");
            status(a, id, "IN_PROGRESS");
            s.audit(a, "CAPA_STARTED", str(capa, "number"), c.note().strip());
            return Map.of("id", id, "status", "IN_PROGRESS");
        });
    }

    /** IN_PROGRESS → VERIFICATION when every action is done (or cancelled) and at least one is done. */
    @Transactional
    public Map<String, Object> submit(Actor a, String id, CapaCommands.Note c, String key) {
        return s.once(a, key, "capa-submit:" + id, c, () -> {
            var capa = locked(a, id);
            if (!a.id().equals(capa.get("owner_id"))) a.allow("QUALITY");
            require(capa.get("status").equals("IN_PROGRESS"), "Only a CAPA in progress can go to verification");
            var open = s.list(
                    "SELECT seq FROM capa_actions WHERE tenant_id=? AND capa_id=? AND status='OPEN' ORDER BY seq",
                    a.tenant(),
                    id);
            require(
                    open.isEmpty(),
                    open.size() == 1
                            ? "Action " + open.getFirst().get("seq") + " is still open"
                            : open.size() + " actions are still open");
            require(
                    !s.list(
                                    "SELECT 1 FROM capa_actions WHERE tenant_id=? AND capa_id=? AND status='DONE'",
                                    a.tenant(),
                                    id)
                            .isEmpty(),
                    "At least one action must be done before verification");
            status(a, id, "VERIFICATION");
            s.audit(a, "CAPA_SUBMITTED", str(capa, "number"), c.note().strip());
            return Map.of("id", id, "status", "VERIFICATION");
        });
    }

    /** Effectiveness check by a different person than the owner: effective closes, not effective reopens the work. */
    @Transactional
    public Map<String, Object> verify(Actor a, String id, CapaCommands.Verify c, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "capa-verify:" + id, c, () -> {
            var capa = locked(a, id);
            require(capa.get("status").equals("VERIFICATION"), "This CAPA is not awaiting verification");
            require(
                    !a.id().equals(capa.get("owner_id")),
                    "A different person than the CAPA owner must verify its effectiveness");
            if (c.effective()) {
                s.db().update(
                                "UPDATE capas SET status='CLOSED',effective=true,verification_note=?,verified_by=?,verified_at=now(),closed_at=now(),updated_at=now() WHERE tenant_id=? AND id=?",
                                c.note().strip(),
                                a.id(),
                                a.tenant(),
                                id);
                s.audit(
                        a,
                        "CAPA_VERIFIED",
                        str(capa, "number"),
                        "Effective · " + c.note().strip());
                return Map.of("id", id, "status", "CLOSED");
            }
            s.db().update(
                            "UPDATE capas SET status='IN_PROGRESS',effective=false,verification_note=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            c.note().strip(),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "CAPA_REOPENED",
                    str(capa, "number"),
                    "Not effective · " + c.note().strip());
            return Map.of("id", id, "status", "IN_PROGRESS");
        });
    }

    @Transactional
    public Map<String, Object> cancel(Actor a, String id, CapaCommands.Note c, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "capa-cancel:" + id, c, () -> {
            var capa = locked(a, id);
            require(
                    !CLOSED.contains(str(capa, "status")),
                    "This CAPA is already " + str(capa, "status").toLowerCase());
            s.db().update(
                            "UPDATE capas SET status='CANCELLED',cancelled_by=?,cancelled_at=now(),cancel_reason=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            a.id(),
                            c.note().strip(),
                            a.tenant(),
                            id);
            // Its open actions are cancelled with it, so nothing stays overdue.
            s.db().update(
                            "UPDATE capa_actions SET status='CANCELLED',completion_note=? WHERE tenant_id=? AND capa_id=? AND status='OPEN'",
                            "CAPA cancelled: " + c.note().strip(),
                            a.tenant(),
                            id);
            s.audit(a, "CAPA_CANCELLED", str(capa, "number"), c.note().strip());
            return Map.of("id", id, "status", "CANCELLED");
        });
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> locked(Actor a, String id) {
        return s.one("SELECT * FROM capas WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
    }

    private Map<String, Object> action(Actor a, String id) {
        return s.one("SELECT * FROM capa_actions WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
    }

    private void status(Actor a, String id, String status) {
        s.db().update("UPDATE capas SET status=?,updated_at=now() WHERE tenant_id=? AND id=?", status, a.tenant(), id);
    }

    /** Distinct NCR ids of this tenant (404 for an unknown one). */
    private List<String> ncrs(Actor a, List<String> ids) {
        var out = new ArrayList<String>();
        if (ids == null) return out;
        for (String ncr : new LinkedHashSet<>(ids)) {
            s.one("SELECT id FROM ncrs WHERE tenant_id=? AND id=?", a.tenant(), ncr);
            out.add(ncr);
        }
        return out;
    }

    private void link(Actor a, String capa, List<String> ncrs) {
        for (String ncr : ncrs)
            s.db().update("INSERT INTO capa_ncrs(tenant_id,capa_id,ncr_id) VALUES(?,?,?)", a.tenant(), capa, ncr);
    }

    private List<String> numbers(Actor a, List<String> ncrs) {
        return ncrs.stream()
                .map(n -> str(s.one("SELECT number FROM ncrs WHERE tenant_id=? AND id=?", a.tenant(), n), "number"))
                .toList();
    }
}
