package lk.dexlanka.erp.quality;

import static lk.dexlanka.erp.quality.QualityPaging.args;
import static lk.dexlanka.erp.quality.QualityPaging.code;
import static lk.dexlanka.erp.quality.QualityPaging.like;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model of inspection plans (V12): the paged plan list, one plan with its characteristics, the plan governing a
 * pending inspection (for measurement entry) and the measurements recorded with a decided inspection. Every role may
 * read.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class InspectionPlanQuery {
    static final List<String> TYPES = List.of("INCOMING", "FINAL", "RETURN", "REWORK");

    private final Store s;

    InspectionPlanQuery(Store s) {
        this.s = s;
    }

    /**
     * Plans newest first: {@code {items, next, total}}. {@code status}: {@code active} (default), {@code inactive} or
     * {@code all}; {@code q} matches plan number/name, item SKU/name and category name.
     */
    public Map<String, Object> page(Actor a, String status, String type, String q, int limit, String before) {
        String st = code(status == null ? "ACTIVE" : status, List.of("ACTIVE", "INACTIVE"), "Unknown plan filter");
        String ty = code(type, TYPES, "Unknown inspection type filter");
        String like = like(q);
        String from = """
                 FROM inspection_plans p
                LEFT JOIN items i ON i.tenant_id=p.tenant_id AND i.id=p.item_id
                LEFT JOIN item_categories c ON c.tenant_id=p.tenant_id AND c.id=p.category_id
                WHERE p.tenant_id=?
                  AND (?::text IS NULL OR p.active=(?::text='ACTIVE'))
                  AND (?::text IS NULL OR p.inspection_type=?)
                  AND (?::text IS NULL OR p.number ILIKE ? OR p.name ILIKE ? OR i.sku ILIKE ? OR i.name ILIKE ?
                       OR c.name ILIKE ?)""";
        return QualityPaging.page(
                s,
                """
                SELECT p.*,i.sku,i.name AS item,i.uom,c.name AS category,
                       (SELECT COUNT(*) FROM inspection_characteristics x
                        WHERE x.tenant_id=p.tenant_id AND x.plan_id=p.id AND x.active) AS characteristics,
                       (SELECT COUNT(*) FROM inspection_characteristics x
                        WHERE x.tenant_id=p.tenant_id AND x.plan_id=p.id AND x.active AND x.critical) AS critical""",
                from,
                "p",
                "inspection_plans",
                args(a.tenant(), st, st, ty, ty, like, like, like, like, like, like),
                limit,
                before);
    }

    /** One plan with its current characteristics and how many decisions it has governed. */
    public Map<String, Object> plan(Actor a, String id) {
        var plan = s.one("""
                SELECT p.*,i.sku,i.name AS item,i.uom,c.name AS category,u.name AS created_by_name,
                       v.name AS updated_by_name,
                       (SELECT COUNT(DISTINCT m.inspection_id) FROM inspection_measurements m
                        WHERE m.tenant_id=p.tenant_id AND m.plan_id=p.id) AS inspections
                FROM inspection_plans p
                LEFT JOIN items i ON i.tenant_id=p.tenant_id AND i.id=p.item_id
                LEFT JOIN item_categories c ON c.tenant_id=p.tenant_id AND c.id=p.category_id
                LEFT JOIN users u ON u.id=p.created_by
                LEFT JOIN users v ON v.id=p.updated_by
                WHERE p.tenant_id=? AND p.id=?""", a.tenant(), id);
        var out = new LinkedHashMap<String, Object>();
        out.put("plan", plan);
        out.put("characteristics", characteristics(a, id));
        return out;
    }

    /**
     * The plan that governs a pending inspection's decision ({@code plan} null when none) with its characteristics, so
     * the inspector can enter measurements against the specification.
     */
    public Map<String, Object> forInspection(Actor a, String inspectionId) {
        var inspection = s.one("""
                SELECT q.id,q.type,q.status,l.item_id,l.number AS lot,i.sku,i.name AS item,i.uom,
                       applicable_inspection_plan(q.tenant_id,l.item_id,q.type) AS plan_id
                FROM inspections q
                JOIN lots l ON l.tenant_id=q.tenant_id AND l.id=q.lot_id
                JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                WHERE q.tenant_id=? AND q.id=?""", a.tenant(), inspectionId);
        var out = new LinkedHashMap<String, Object>();
        out.put("inspection", inspection);
        Object plan = inspection.get("plan_id");
        out.put(
                "plan",
                plan == null
                        ? null
                        : s.one("SELECT * FROM inspection_plans WHERE tenant_id=? AND id=?", a.tenant(), plan));
        out.put("characteristics", plan == null ? List.of() : characteristics(a, plan.toString()));
        return out;
    }

    /** Measurements recorded with a decided inspection, each with its readings ({@code readings}: numbers in order). */
    public Map<String, Object> measurements(Actor a, String inspectionId) {
        var inspection = s.one(
                "SELECT id,status,type,inspected_by,inspected_at FROM inspections WHERE tenant_id=? AND id=?",
                a.tenant(),
                inspectionId);
        var rows = s.list("""
                SELECT m.*,p.number AS plan_number,p.name AS plan_name FROM inspection_measurements m
                JOIN inspection_plans p ON p.tenant_id=m.tenant_id AND p.id=m.plan_id
                WHERE m.tenant_id=? AND m.inspection_id=? ORDER BY m.seq""", a.tenant(), inspectionId);
        var readings = s.list("""
                SELECT r.measurement_id,r.value FROM inspection_readings r
                JOIN inspection_measurements m ON m.tenant_id=r.tenant_id AND m.id=r.measurement_id
                WHERE r.tenant_id=? AND m.inspection_id=? ORDER BY r.measurement_id,r.sample_no""", a.tenant(), inspectionId);
        var items = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            var copy = new LinkedHashMap<>(row);
            copy.put(
                    "readings",
                    readings.stream()
                            .filter(r -> r.get("measurement_id").equals(row.get("id")))
                            .map(r -> r.get("value"))
                            .toList());
            items.add(copy);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("inspection", inspection);
        out.put("items", items);
        return out;
    }

    /** The tenant's defect codes (active first). */
    public Map<String, Object> defectCodes(Actor a) {
        return Map.of("items", s.list("""
                        SELECT d.*,(SELECT COUNT(*) FROM ncrs n WHERE n.tenant_id=d.tenant_id AND n.defect_code=d.code) AS ncrs
                        FROM defect_codes d WHERE d.tenant_id=? ORDER BY d.active DESC,d.code""", a.tenant()));
    }

    private List<Map<String, Object>> characteristics(Actor a, String plan) {
        return s.list("""
                SELECT x.*,d.name AS defect_name FROM inspection_characteristics x
                LEFT JOIN defect_codes d ON d.tenant_id=x.tenant_id AND d.code=x.defect_code
                WHERE x.tenant_id=? AND x.plan_id=? AND x.active ORDER BY x.seq""", a.tenant(), plan);
    }
}
