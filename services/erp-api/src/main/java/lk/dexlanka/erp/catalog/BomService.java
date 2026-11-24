package lk.dexlanka.erp.catalog;

import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BOM revision tools (V7) and the multi-level approval rules.
 *
 * <ul>
 *   <li>{@link #copy}: an approved revision becomes a new draft with the same output, yield and components.
 *   <li>{@link #updateDraft}: a draft's output, yield and components are replaced (approved revisions are immutable).
 *   <li>{@link #checkApprovable}: called by {@link CatalogService#approveBom} before a revision is frozen.
 * </ul>
 */
@Service
public class BomService {
    private final Store s;

    BomService(Store s) {
        this.s = s;
    }

    @Transactional
    public Map<String, Object> copy(Actor a, String id, BomCommands.Copy c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "copy-bom:" + id, c, () -> {
            var source = s.one(
                    "SELECT b.*,i.active,i.sku FROM boms b JOIN items i ON i.tenant_id=b.tenant_id AND i.id=b.product_id WHERE b.tenant_id=? AND b.id=?",
                    a.tenant(),
                    id);
            require(
                    "APPROVED".equals(source.get("status")),
                    "Only approved revisions can be copied; edit the draft revision instead");
            require(Boolean.TRUE.equals(source.get("active")), "This product is inactive; reactivate it first");
            String revision = c.revision().strip();
            require(!revision.isEmpty(), "Enter a revision reference");
            require(
                    s.list(
                                    "SELECT 1 FROM boms WHERE tenant_id=? AND product_id=? AND lower(revision)=lower(?)",
                                    a.tenant(),
                                    source.get("product_id"),
                                    revision)
                            .isEmpty(),
                    "Revision " + revision + " already exists for " + str(source, "sku"));
            String draft = id();
            s.db().update(
                            "INSERT INTO boms(id,tenant_id,product_id,revision,status,output_qty,yield_factor,source_bom_id) VALUES(?,?,?,?,'DRAFT',?,?,?)",
                            draft,
                            a.tenant(),
                            source.get("product_id"),
                            revision,
                            source.get("output_qty"),
                            source.get("yield_factor"),
                            id);
            for (var line :
                    s.list("SELECT item_id,quantity FROM bom_lines WHERE tenant_id=? AND bom_id=?", a.tenant(), id))
                s.db().update(
                                "INSERT INTO bom_lines(id,tenant_id,bom_id,item_id,quantity) VALUES(?,?,?,?,?)",
                                id(),
                                a.tenant(),
                                draft,
                                line.get("item_id"),
                                line.get("quantity"));
            s.audit(
                    a,
                    "BOM_COPIED",
                    revision,
                    str(source, "sku") + " revision " + str(source, "revision") + " copied into a new draft");
            var result = new LinkedHashMap<String, Object>();
            result.put("id", draft);
            result.put("status", "DRAFT");
            result.put("revision", revision);
            result.put("sourceId", id);
            return result;
        });
    }

    @Transactional
    public Map<String, Object> updateDraft(Actor a, String id, BomCommands.Draft c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "edit-bom:" + id, c, () -> {
            var b = s.one("SELECT * FROM boms WHERE tenant_id=? AND id=?", a.tenant(), id);
            require(
                    "DRAFT".equals(b.get("status")),
                    "Approved revisions are immutable; copy the revision into a new draft to change it");
            s.quantity("pcs", c.outputQty());
            var seen = new HashSet<String>();
            for (var l : c.lines()) {
                require(seen.add(l.itemId()), "A material can appear only once per BOM");
                require(!l.itemId().equals(b.get("product_id")), "A product cannot be a component of its own BOM");
                var material = s.one(
                        "SELECT * FROM items WHERE tenant_id=? AND id=? AND type<>'FINISHED_GOOD'",
                        a.tenant(),
                        l.itemId());
                require(
                        Boolean.TRUE.equals(material.get("active")),
                        "Material " + str(material, "sku") + " is inactive and cannot be used on new BOMs");
                s.quantity(str(material, "uom"), l.quantity());
            }
            s.db().update(
                            "UPDATE boms SET output_qty=?,yield_factor=? WHERE tenant_id=? AND id=?",
                            c.outputQty(),
                            c.yieldFactor(),
                            a.tenant(),
                            id);
            s.db().update("DELETE FROM bom_lines WHERE tenant_id=? AND bom_id=?", a.tenant(), id);
            for (var l : c.lines())
                s.db().update(
                                "INSERT INTO bom_lines(id,tenant_id,bom_id,item_id,quantity) VALUES(?,?,?,?,?)",
                                id(),
                                a.tenant(),
                                id,
                                l.itemId(),
                                l.quantity());
            s.audit(a, "BOM_DRAFT_UPDATED", str(b, "revision"), c.lines().size() + " components");
            return Map.of("id", id, "status", "DRAFT");
        });
    }

    /**
     * Multi-level approval rules, checked inside {@link CatalogService#approveBom}'s transaction (tenant locked):
     *
     * <ol>
     *   <li>Every {@code SEMI_FINISHED} component needs an approved BOM of its own. Revisions are approved bottom-up, so
     *       the structure below an approved revision is always complete and explodable (planning, child orders).
     *   <li>No component may lead back to the product through any approved revision at any depth (A → B → A).
     * </ol>
     */
    public void checkApprovable(Actor a, Map<String, Object> bom) {
        var graph = BomGraph.load(s, a.tenant());
        String product = str(bom, "product_id");
        List<BomGraph.Line> lines = graph.lines(str(bom, "id"));
        var missing = new TreeSet<String>();
        for (var line : lines) {
            var item = graph.item(line.itemId());
            if (item != null && "SEMI_FINISHED".equals(item.type()) && !graph.active.containsKey(item.id()))
                missing.add(item.sku());
        }
        require(
                missing.isEmpty(),
                "Approve the BOM of each semi-finished component first (bottom-up): " + String.join(", ", missing));
        for (var line : lines) {
            var path = graph.approvedPath(line.itemId(), product);
            if (path != null) {
                path.addFirst(product);
                require(
                        false,
                        "This revision would create a BOM cycle: "
                                + String.join(
                                        " → ", path.stream().map(graph::sku).toList()));
            }
        }
    }
}
