package lk.dexlanka.erp.routing;

import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Versioned routings (V10): draft authoring, independent approval, copy into a new draft and the current revision per
 * product. Only PLANNER (and ADMIN) author. Approved revisions and their operations are immutable in the database
 * ({@code approved_routing_immutable}, {@code approved_routing_operation_immutable} triggers), exactly like V2 BOMs.
 *
 * <ul>
 *   <li>Approval needs a person other than the routing's creator and its last editor (ADMIN included).
 *   <li>Approving a revision makes it the product's current revision ({@code routing_defaults}); {@link #makeCurrent}
 *       switches back to an older approved revision. New manufacturing orders snapshot the current revision.
 * </ul>
 */
@Service
public class RoutingService {
    static final Set<String> ROUTABLE = Set.of("FINISHED_GOOD", "SEMI_FINISHED");

    private final Store s;

    RoutingService(Store s) {
        this.s = s;
    }

    @Transactional
    public Map<String, Object> create(Actor a, RoutingCommands.Routing c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "routing", c, () -> {
            var product = product(a, c.productId());
            String revision = c.revision().strip();
            uniqueRevision(a, c.productId(), revision, null, str(product, "sku"));
            validate(a, c.operations());
            String id = id();
            String number = s.number(a, "RT");
            s.db().update(
                            "INSERT INTO routings(id,tenant_id,number,product_id,revision,status,notes,created_by,updated_by) VALUES(?,?,?,?,?,'DRAFT',?,?,?)",
                            id,
                            a.tenant(),
                            number,
                            c.productId(),
                            revision,
                            notes(c.notes()),
                            a.id(),
                            a.id());
            writeOperations(a, id, c.operations());
            s.audit(
                    a,
                    "ROUTING_DRAFT_CREATED",
                    number,
                    str(product, "sku") + " revision " + revision + "; "
                            + c.operations().size() + " operations");
            return result(id, number, "DRAFT", revision);
        });
    }

    @Transactional
    public Map<String, Object> updateDraft(Actor a, String id, RoutingCommands.Draft c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "edit-routing:" + id, c, () -> {
            var r = routing(a, id);
            require(
                    "DRAFT".equals(r.get("status")),
                    "Approved routings are immutable; copy the revision into a new draft to change it");
            var product = product(a, str(r, "product_id"));
            String revision = c.revision().strip();
            uniqueRevision(a, str(r, "product_id"), revision, id, str(product, "sku"));
            validate(a, c.operations());
            s.db().update(
                            "UPDATE routings SET revision=?,notes=?,updated_by=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            revision,
                            notes(c.notes()),
                            a.id(),
                            a.tenant(),
                            id);
            s.db().update(
                            "DELETE FROM routing_operation_alternates WHERE tenant_id=? AND operation_id IN (SELECT id FROM routing_operations WHERE tenant_id=? AND routing_id=?)",
                            a.tenant(),
                            a.tenant(),
                            id);
            s.db().update("DELETE FROM routing_operations WHERE tenant_id=? AND routing_id=?", a.tenant(), id);
            writeOperations(a, id, c.operations());
            s.audit(
                    a,
                    "ROUTING_DRAFT_UPDATED",
                    str(r, "number"),
                    "Revision " + revision + "; " + c.operations().size() + " operations");
            return result(id, str(r, "number"), "DRAFT", revision);
        });
    }

    @Transactional
    public Map<String, Object> approve(Actor a, String id, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "approve-routing:" + id, Map.of(), () -> {
            var r = routing(a, id);
            require("DRAFT".equals(r.get("status")), "Only draft routing revisions can be approved");
            require(
                    !a.id().equals(r.get("created_by")) && !a.id().equals(r.get("updated_by")),
                    "A different authorized person must approve this routing (not its author or last editor)");
            var product =
                    s.one("SELECT sku,active FROM items WHERE tenant_id=? AND id=?", a.tenant(), r.get("product_id"));
            require(
                    Boolean.TRUE.equals(product.get("active")),
                    "This product is inactive and its routing cannot be approved");
            var operations = s.list(
                    "SELECT o.seq,o.skill_id,sk.code AS skill_code,sk.active AS skill_active FROM routing_operations o LEFT JOIN skills sk ON sk.tenant_id=o.tenant_id AND sk.id=o.skill_id WHERE o.tenant_id=? AND o.routing_id=? ORDER BY o.seq",
                    a.tenant(),
                    id);
            require(!operations.isEmpty(), "A routing requires at least one operation");
            var inactive = operations.stream()
                    .filter(o -> o.get("skill_id") != null && !Boolean.TRUE.equals(o.get("skill_active")))
                    .map(o -> str(o, "skill_code"))
                    .distinct()
                    .toList();
            require(
                    inactive.isEmpty(),
                    "Inactive skills cannot be required by an approved routing: " + String.join(", ", inactive));
            s.db().update(
                            "UPDATE routings SET status='APPROVED',approved_by=?,approved_at=now() WHERE tenant_id=? AND id=?",
                            a.id(),
                            a.tenant(),
                            id);
            setCurrent(a, str(r, "product_id"), id);
            s.audit(
                    a,
                    "ROUTING_APPROVED",
                    str(r, "number"),
                    str(product, "sku") + " revision " + str(r, "revision") + " frozen and made the current routing");
            return result(id, str(r, "number"), "APPROVED", str(r, "revision"));
        });
    }

    @Transactional
    public Map<String, Object> copy(Actor a, String id, RoutingCommands.Copy c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "copy-routing:" + id, c, () -> {
            var source = routing(a, id);
            require(
                    "APPROVED".equals(source.get("status")),
                    "Only approved revisions can be copied; edit the draft revision instead");
            var product = product(a, str(source, "product_id"));
            String revision = c.revision().strip();
            uniqueRevision(a, str(source, "product_id"), revision, null, str(product, "sku"));
            String draft = id();
            String number = s.number(a, "RT");
            s.db().update(
                            "INSERT INTO routings(id,tenant_id,number,product_id,revision,status,notes,source_routing_id,created_by,updated_by) VALUES(?,?,?,?,?,'DRAFT',?,?,?,?)",
                            draft,
                            a.tenant(),
                            number,
                            source.get("product_id"),
                            revision,
                            source.get("notes"),
                            id,
                            a.id(),
                            a.id());
            for (var op : s.list(
                    "SELECT * FROM routing_operations WHERE tenant_id=? AND routing_id=? ORDER BY seq",
                    a.tenant(),
                    id)) {
                String opId = id();
                s.db().update(
                                "INSERT INTO routing_operations(id,tenant_id,routing_id,seq,name,machine_id,setup_minutes,run_minutes_per_unit,crew_size,skill_id,skill_level,inspection_point,instructions) SELECT ?,tenant_id,?,seq,name,machine_id,setup_minutes,run_minutes_per_unit,crew_size,skill_id,skill_level,inspection_point,instructions FROM routing_operations WHERE tenant_id=? AND id=?",
                                opId,
                                draft,
                                a.tenant(),
                                op.get("id"));
                s.db().update(
                                "INSERT INTO routing_operation_alternates(tenant_id,operation_id,machine_id) SELECT tenant_id,?,machine_id FROM routing_operation_alternates WHERE tenant_id=? AND operation_id=?",
                                opId,
                                a.tenant(),
                                op.get("id"));
            }
            s.audit(
                    a,
                    "ROUTING_COPIED",
                    number,
                    str(product, "sku") + " revision " + str(source, "revision") + " (" + str(source, "number")
                            + ") copied into draft revision " + revision);
            var out = result(draft, number, "DRAFT", revision);
            out.put("sourceId", id);
            return out;
        });
    }

    /** Makes an approved revision the product's current routing (the one new manufacturing orders snapshot). */
    @Transactional
    public Map<String, Object> makeCurrent(Actor a, String id, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "current-routing:" + id, Map.of(), () -> {
            var r = routing(a, id);
            require("APPROVED".equals(r.get("status")), "Only an approved revision can be the current routing");
            setCurrent(a, str(r, "product_id"), id);
            s.audit(
                    a,
                    "ROUTING_MADE_CURRENT",
                    str(r, "number"),
                    "Revision " + str(r, "revision") + " is the current routing for new manufacturing orders");
            return result(id, str(r, "number"), "APPROVED", str(r, "revision"));
        });
    }

    // ------------------------------------------------------------------ rules

    private Map<String, Object> routing(Actor a, String id) {
        return s.one("SELECT * FROM routings WHERE tenant_id=? AND id=?", a.tenant(), id);
    }

    private Map<String, Object> product(Actor a, String productId) {
        var product = s.one("SELECT id,sku,type,active FROM items WHERE tenant_id=? AND id=?", a.tenant(), productId);
        require(
                ROUTABLE.contains(str(product, "type")),
                "Routings describe how finished or semi-finished products are made");
        require(Boolean.TRUE.equals(product.get("active")), "This product is inactive; reactivate it first");
        return product;
    }

    private void uniqueRevision(Actor a, String product, String revision, String except, String sku) {
        require(!revision.isEmpty(), "Enter a revision reference");
        require(
                s.list(
                                "SELECT 1 FROM routings WHERE tenant_id=? AND product_id=? AND lower(revision)=lower(?) AND (?::text IS NULL OR id<>?)",
                                a.tenant(),
                                product,
                                revision,
                                except,
                                except)
                        .isEmpty(),
                "Routing revision " + revision + " already exists for " + sku);
    }

    /**
     * Sequence numbers unique; work centers of this tenant; alternates distinct from the primary, of the same machine
     * kind and listed once; required skills active.
     */
    private void validate(Actor a, List<RoutingCommands.Operation> operations) {
        var seqs = new HashSet<Integer>();
        for (var op : operations) {
            require(seqs.add(op.seq()), "Operation sequence " + op.seq() + " is used twice");
            var machine =
                    s.one("SELECT id,code,kind FROM machines WHERE tenant_id=? AND id=?", a.tenant(), op.machineId());
            var alternates = new HashSet<String>();
            for (String alt : op.alternateMachineIds() == null ? List.<String>of() : op.alternateMachineIds()) {
                require(
                        !alt.equals(op.machineId()),
                        "Operation " + op.seq() + ": the primary work center cannot also be an alternate");
                require(alternates.add(alt), "Operation " + op.seq() + ": an alternate work center is listed twice");
                var other = s.one("SELECT code,kind FROM machines WHERE tenant_id=? AND id=?", a.tenant(), alt);
                require(
                        str(other, "kind").equals(str(machine, "kind")),
                        "Operation " + op.seq() + ": alternate " + str(other, "code") + " (" + str(other, "kind")
                                + ") is not a " + str(machine, "kind") + " work center like "
                                + str(machine, "code"));
            }
            if (op.skillId() != null && !op.skillId().isBlank()) {
                var skill =
                        s.one("SELECT code,active FROM skills WHERE tenant_id=? AND id=?", a.tenant(), op.skillId());
                require(
                        Boolean.TRUE.equals(skill.get("active")),
                        "Skill " + str(skill, "code") + " is inactive and cannot be required");
            }
        }
    }

    private void writeOperations(Actor a, String routing, List<RoutingCommands.Operation> operations) {
        for (var op : operations.stream()
                .sorted((x, y) -> Integer.compare(x.seq(), y.seq()))
                .toList()) {
            String opId = id();
            boolean skilled = op.skillId() != null && !op.skillId().isBlank();
            s.db().update(
                            "INSERT INTO routing_operations(id,tenant_id,routing_id,seq,name,machine_id,setup_minutes,run_minutes_per_unit,crew_size,skill_id,skill_level,inspection_point,instructions) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            opId,
                            a.tenant(),
                            routing,
                            op.seq(),
                            op.name().strip(),
                            op.machineId(),
                            op.setupMinutes(),
                            op.runMinutesPerUnit(),
                            op.crewSize(),
                            skilled ? op.skillId() : null,
                            skilled && op.skillLevel() != null ? op.skillLevel() : 1,
                            Boolean.TRUE.equals(op.inspectionPoint()),
                            op.instructions() == null ? "" : op.instructions().strip());
            if (op.alternateMachineIds() != null)
                for (String alt : op.alternateMachineIds())
                    s.db().update(
                                    "INSERT INTO routing_operation_alternates(tenant_id,operation_id,machine_id) VALUES(?,?,?)",
                                    a.tenant(),
                                    opId,
                                    alt);
        }
    }

    private void setCurrent(Actor a, String product, String routing) {
        s.db().update(
                        "INSERT INTO routing_defaults(tenant_id,product_id,routing_id,updated_by) VALUES(?,?,?,?) ON CONFLICT (tenant_id,product_id) DO UPDATE SET routing_id=EXCLUDED.routing_id,updated_by=EXCLUDED.updated_by,updated_at=now()",
                        a.tenant(),
                        product,
                        routing,
                        a.id());
    }

    private static String notes(String notes) {
        return notes == null ? "" : notes.strip();
    }

    private static LinkedHashMap<String, Object> result(String id, String number, String status, String revision) {
        var out = new LinkedHashMap<String, Object>();
        out.put("id", id);
        out.put("number", number);
        out.put("status", status);
        out.put("revision", revision);
        return out;
    }
}
