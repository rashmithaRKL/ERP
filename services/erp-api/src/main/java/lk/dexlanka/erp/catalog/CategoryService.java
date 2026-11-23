package lk.dexlanka.erp.catalog;

import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Item categories: a simple tenant-scoped hierarchy (optional parent), names unique per tenant. PLANNER. */
@Service
public class CategoryService {
    private final Store s;

    CategoryService(Store s) {
        this.s = s;
    }

    @Transactional
    public Map<String, Object> create(Actor a, ItemCommands.Category c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "item-category", c, () -> {
            String name = c.name().trim();
            requireNewName(a, name, null);
            String parent = ItemService.blankToNull(c.parentId());
            if (parent != null) {
                var p = s.one("SELECT * FROM item_categories WHERE tenant_id=? AND id=?", a.tenant(), parent);
                require(Boolean.TRUE.equals(p.get("active")), "Parent category " + p.get("name") + " is inactive");
            }
            String id = id();
            s.db().update(
                            "INSERT INTO item_categories(id,tenant_id,name,description,parent_id) VALUES(?,?,?,?,?)",
                            id,
                            a.tenant(),
                            name,
                            c.description() == null ? "" : c.description().trim(),
                            parent);
            s.audit(a, "ITEM_CATEGORY_CREATED", name, parent == null ? "Top-level category" : "Sub-category");
            return Map.of("id", id);
        });
    }

    @Transactional
    public Map<String, Object> update(Actor a, String id, ItemCommands.EditCategory c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "edit-item-category:" + id, c, () -> {
            var category = s.one("SELECT * FROM item_categories WHERE tenant_id=? AND id=?", a.tenant(), id);
            String name = c.name().trim();
            requireNewName(a, name, id);
            String parent = ItemService.blankToNull(c.parentId());
            if (parent != null && !parent.equals(category.get("parent_id"))) {
                s.one("SELECT id FROM item_categories WHERE tenant_id=? AND id=?", a.tenant(), parent);
                // Walk up from the new parent: reaching this category would create a cycle.
                String cursor = parent;
                for (int depth = 0; cursor != null && depth < 100; depth++) {
                    require(
                            !cursor.equals(id),
                            "A category cannot be placed under itself or one of its sub-categories");
                    var up = s.list(
                            "SELECT parent_id FROM item_categories WHERE tenant_id=? AND id=?", a.tenant(), cursor);
                    cursor = up.isEmpty() ? null : (String) up.getFirst().get("parent_id");
                }
            }
            String description = c.description() == null ? "" : c.description().trim();
            s.db().update(
                            "UPDATE item_categories SET name=?,description=?,parent_id=?,active=? WHERE tenant_id=? AND id=?",
                            name,
                            description,
                            parent,
                            c.active(),
                            a.tenant(),
                            id);
            var changes = new ArrayList<String>();
            if (!name.equals(category.get("name"))) changes.add("name " + category.get("name") + " → " + name);
            if (!Objects.equals(parent, category.get("parent_id"))) changes.add("parent changed");
            if (!description.equals(str(category, "description"))) changes.add("description updated");
            if (!c.active().equals(category.get("active"))) changes.add(c.active() ? "reactivated" : "deactivated");
            s.audit(
                    a,
                    "ITEM_CATEGORY_UPDATED",
                    name,
                    changes.isEmpty() ? "Saved without changes" : String.join("; ", changes));
            return Map.of("id", id, "active", c.active());
        });
    }

    private void requireNewName(Actor a, String name, String except) {
        require(
                s.list(
                                "SELECT 1 FROM item_categories WHERE tenant_id=? AND lower(name)=lower(?) AND id IS DISTINCT FROM ?",
                                a.tenant(),
                                name,
                                except)
                        .isEmpty(),
                "Category " + name + " already exists");
    }
}
