package lk.dexlanka.erp.quality;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;
import static lk.dexlanka.erp.quality.QualityRules.blankToEmpty;
import static lk.dexlanka.erp.quality.QualityRules.blankToNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inspection plans (V12): characteristics per item or item category and inspection type, the defect-code catalogue,
 * and the evaluation of measurements recorded with an inspection decision ({@link QualityService#inspect}).
 *
 * <p>Decision rules: every active characteristic of the governing plan needs a result. A critical characteristic out of
 * specification makes the decision FAILED whatever the inspector chose; a non-critical one out of specification may
 * still pass, but only with a note on that measurement explaining the deviation. Plans are looked up when the decision
 * is recorded (SQL {@code applicable_inspection_plan}), and measurements keep a snapshot of the specification.
 */
@Service
public class InspectionPlanService {
    private final Store s;
    private final QualityRules rules;

    InspectionPlanService(Store s, QualityRules rules) {
        this.s = s;
        this.rules = rules;
    }

    // ------------------------------------------------------------------ plans

    @Transactional
    public Map<String, Object> create(Actor a, InspectionPlanCommands.Plan c, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "inspection-plan", c, () -> {
            String item = blankToNull(c.itemId());
            String category = blankToNull(c.categoryId());
            require(
                    (item == null) != (category == null),
                    "Choose either an item or an item category for the plan, not both");
            String subject;
            if (item != null) {
                var i = s.one("SELECT sku,name FROM items WHERE tenant_id=? AND id=?", a.tenant(), item);
                subject = i.get("sku") + " " + i.get("name");
            } else
                subject = "category "
                        + s.one("SELECT name FROM item_categories WHERE tenant_id=? AND id=?", a.tenant(), category)
                                .get("name");
            require(
                    s.list(
                                    "SELECT 1 FROM inspection_plans WHERE tenant_id=? AND active AND inspection_type=? AND item_id IS NOT DISTINCT FROM ?::text AND category_id IS NOT DISTINCT FROM ?::text",
                                    a.tenant(),
                                    c.inspectionType(),
                                    item,
                                    category)
                            .isEmpty(),
                    "An active " + c.inspectionType().toLowerCase() + " inspection plan already exists for " + subject
                            + "; edit it instead");
            String id = id();
            String number = rules.documentNumber(a, "inspection_plans", "QP");
            s.db().update(
                            "INSERT INTO inspection_plans(id,tenant_id,number,name,item_id,category_id,inspection_type,notes,created_by) VALUES(?,?,?,?,?,?,?,?,?)",
                            id,
                            a.tenant(),
                            number,
                            c.name().strip(),
                            item,
                            category,
                            c.inspectionType(),
                            blankToEmpty(c.notes()),
                            a.id());
            insertCharacteristics(a, id, c.characteristics());
            s.audit(
                    a,
                    "INSPECTION_PLAN_CREATED",
                    number,
                    c.name().strip() + " · " + c.inspectionType() + " inspection of " + subject + " · "
                            + c.characteristics().size() + " characteristics");
            return Map.of("id", id, "number", number, "version", 1);
        });
    }

    @Transactional
    public Map<String, Object> update(Actor a, String id, InspectionPlanCommands.PlanUpdate c, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "inspection-plan:" + id, c, () -> {
            var p = s.one("SELECT * FROM inspection_plans WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
            if (c.active() && !Boolean.TRUE.equals(p.get("active")))
                require(
                        s.list(
                                        "SELECT 1 FROM inspection_plans WHERE tenant_id=? AND id<>? AND active AND inspection_type=? AND item_id IS NOT DISTINCT FROM ?::text AND category_id IS NOT DISTINCT FROM ?::text",
                                        a.tenant(),
                                        id,
                                        p.get("inspection_type"),
                                        p.get("item_id"),
                                        p.get("category_id"))
                                .isEmpty(),
                        "Another active plan already covers this item or category and inspection type");
            int version = ((Number) p.get("version")).intValue() + 1;
            s.db().update(
                            "UPDATE inspection_plans SET name=?,notes=?,active=?,version=?,updated_by=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            c.name().strip(),
                            blankToEmpty(c.notes()),
                            c.active(),
                            version,
                            a.id(),
                            a.tenant(),
                            id);
            s.db().update(
                            "UPDATE inspection_characteristics SET active=false WHERE tenant_id=? AND plan_id=? AND active",
                            a.tenant(),
                            id);
            insertCharacteristics(a, id, c.characteristics());
            s.audit(
                    a,
                    "INSPECTION_PLAN_UPDATED",
                    str(p, "number"),
                    "Version " + version + " · " + c.characteristics().size() + " characteristics"
                            + (c.active() ? "" : " · deactivated"));
            return Map.of("id", id, "number", p.get("number"), "version", version);
        });
    }

    private void insertCharacteristics(Actor a, String plan, List<InspectionPlanCommands.Characteristic> list) {
        var names = new HashSet<String>();
        int seq = 0;
        for (var ch : list) {
            String name = ch.name().strip();
            require(names.add(name.toLowerCase()), "Characteristic " + name + " appears twice in the plan");
            boolean variable = ch.kind().equals("VARIABLE");
            if (variable) {
                require(
                        ch.lowerLimit() != null || ch.upperLimit() != null,
                        "Give " + name + " a lower or upper specification limit");
                require(
                        ch.lowerLimit() == null
                                || ch.upperLimit() == null
                                || ch.lowerLimit().compareTo(ch.upperLimit()) <= 0,
                        "The lower limit of " + name + " is above its upper limit");
                require(
                        ch.nominal() == null
                                || ((ch.lowerLimit() == null || ch.nominal().compareTo(ch.lowerLimit()) >= 0)
                                        && (ch.upperLimit() == null
                                                || ch.nominal().compareTo(ch.upperLimit()) <= 0)),
                        "The nominal value of " + name + " lies outside its limits");
                require(ch.sampleSize() <= 20, "Measure at most 20 samples of " + name + " per inspection");
            } else
                require(
                        ch.nominal() == null && ch.lowerLimit() == null && ch.upperLimit() == null,
                        name + " is a pass/fail check; it has no nominal value or limits");
            String defect = blankToNull(ch.defectCode());
            if (defect != null) rules.activeDefect(a, defect);
            s.db().update(
                            "INSERT INTO inspection_characteristics(id,tenant_id,plan_id,seq,name,kind,unit,nominal,lower_limit,upper_limit,sample_size,critical,defect_code,method) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            id(),
                            a.tenant(),
                            plan,
                            ++seq,
                            name,
                            ch.kind(),
                            variable ? blankToNull(ch.unit()) : null,
                            variable ? ch.nominal() : null,
                            variable ? ch.lowerLimit() : null,
                            variable ? ch.upperLimit() : null,
                            ch.sampleSize(),
                            ch.critical(),
                            defect,
                            blankToEmpty(ch.method()));
        }
    }

    // ------------------------------------------------------------------ defect codes

    @Transactional
    public Map<String, Object> createDefectCode(Actor a, InspectionPlanCommands.DefectCode c, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "defect-code", c, () -> {
            require(
                    s.list("SELECT 1 FROM defect_codes WHERE tenant_id=? AND code=?", a.tenant(), c.code())
                            .isEmpty(),
                    "Defect code " + c.code() + " already exists");
            s.db().update(
                            "INSERT INTO defect_codes(tenant_id,code,name,description,active) VALUES(?,?,?,?,?)",
                            a.tenant(),
                            c.code(),
                            c.name().strip(),
                            blankToEmpty(c.description()),
                            c.active());
            s.audit(a, "DEFECT_CODE_SAVED", c.code(), c.name().strip() + (c.active() ? "" : " · inactive"));
            return Map.of("code", c.code(), "status", "CREATED");
        });
    }

    @Transactional
    public Map<String, Object> updateDefectCode(Actor a, String code, InspectionPlanCommands.DefectCode c, String key) {
        a.allow("QUALITY");
        return s.once(a, key, "defect-code:" + code, c, () -> {
            var d = s.one("SELECT * FROM defect_codes WHERE tenant_id=? AND code=? FOR UPDATE", a.tenant(), code);
            require(c.code().equals(code), "A defect code cannot be renamed; create a new code instead");
            require(
                    c.active() || !Boolean.TRUE.equals(d.get("system")),
                    "Defect code " + code + " is used by automatic nonconformances and stays active");
            s.db().update(
                            "UPDATE defect_codes SET name=?,description=?,active=?,updated_at=now() WHERE tenant_id=? AND code=?",
                            c.name().strip(),
                            blankToEmpty(c.description()),
                            c.active(),
                            a.tenant(),
                            code);
            s.audit(a, "DEFECT_CODE_SAVED", code, c.name().strip() + (c.active() ? "" : " · inactive"));
            return Map.of("code", code, "status", "UPDATED");
        });
    }

    // ------------------------------------------------------------------ measurements (inside QualityService.inspect)

    /** One characteristic's evaluated result. */
    record Result(
            Map<String, Object> characteristic,
            List<BigDecimal> values,
            Integer nonconforming,
            boolean conforming,
            String note) {}

    /** The evaluation of all measurements against the plan. */
    record Evaluation(
            Map<String, Object> plan,
            List<Result> results,
            List<String> criticalFailures,
            List<String> unexplained,
            String defectCode) {
        boolean criticalFailure() {
            return !criticalFailures.isEmpty();
        }

        List<String> outOfSpec() {
            return results.stream()
                    .filter(r -> !r.conforming())
                    .map(r -> str(r.characteristic(), "name"))
                    .toList();
        }
    }

    /** The id of the plan governing an inspection of {@code item} of {@code type}, or null. */
    String applicablePlan(Actor a, Object item, Object type) {
        return s.db().queryForObject("SELECT applicable_inspection_plan(?,?,?)", String.class, a.tenant(), item, type);
    }

    Evaluation evaluate(Actor a, String planId, List<QualityCommands.Measurement> measurements) {
        var plan = s.one("SELECT * FROM inspection_plans WHERE tenant_id=? AND id=?", a.tenant(), planId);
        var characteristics = s.list(
                "SELECT * FROM inspection_characteristics WHERE tenant_id=? AND plan_id=? AND active ORDER BY seq",
                a.tenant(),
                planId);
        require(
                measurements != null && !measurements.isEmpty(),
                "Inspection plan " + plan.get("number") + " governs this inspection: record its "
                        + characteristics.size() + " measurements with the decision");
        var byId = new LinkedHashMap<String, QualityCommands.Measurement>();
        for (var m : measurements)
            require(byId.put(m.characteristicId(), m) == null, "A characteristic was measured twice");
        var known = new HashSet<String>();
        for (var ch : characteristics) known.add(str(ch, "id"));
        for (String given : byId.keySet())
            require(
                    known.contains(given),
                    "A measurement does not belong to the current version of plan " + plan.get("number")
                            + "; reload the plan and measure again");
        var results = new ArrayList<Result>();
        var critical = new ArrayList<String>();
        var unexplained = new ArrayList<String>();
        String defect = null;
        String anyDefect = null;
        for (var ch : characteristics) {
            String name = str(ch, "name");
            var m = byId.get(str(ch, "id"));
            require(m != null, "Record a result for " + name);
            int sample = ((Number) ch.get("sample_size")).intValue();
            boolean conforming;
            List<BigDecimal> values = null;
            Integer nonconforming = null;
            if (ch.get("kind").equals("VARIABLE")) {
                require(
                        m.values() != null && m.values().size() == sample,
                        "Enter " + sample + (sample == 1 ? " reading" : " readings") + " for " + name);
                values = m.values();
                BigDecimal lower = ch.get("lower_limit") == null ? null : dec(ch.get("lower_limit"));
                BigDecimal upper = ch.get("upper_limit") == null ? null : dec(ch.get("upper_limit"));
                conforming = values.stream()
                        .allMatch(v -> (lower == null || v.compareTo(lower) >= 0)
                                && (upper == null || v.compareTo(upper) <= 0));
            } else {
                require(
                        m.nonconforming() != null && m.nonconforming() <= sample,
                        "Enter the nonconforming units (0–" + sample + ") found for " + name);
                nonconforming = m.nonconforming();
                conforming = nonconforming == 0;
            }
            String note = blankToEmpty(m.note());
            results.add(new Result(ch, values, nonconforming, conforming, note));
            if (conforming) continue;
            String code = (String) ch.get("defect_code");
            if (Boolean.TRUE.equals(ch.get("critical"))) {
                critical.add(name);
                if (defect == null) defect = code;
            } else if (note.isEmpty()) unexplained.add(name);
            if (anyDefect == null) anyDefect = code;
        }
        return new Evaluation(plan, results, critical, unexplained, defect != null ? defect : anyDefect);
    }

    /** Writes the measurements (immutable) of a decided inspection. */
    void record(Actor a, String inspection, Evaluation e) {
        for (var r : e.results()) {
            var ch = r.characteristic();
            String id = id();
            BigDecimal min = null;
            BigDecimal max = null;
            BigDecimal mean = null;
            if (r.values() != null) {
                min = r.values().stream().min(BigDecimal::compareTo).orElseThrow();
                max = r.values().stream().max(BigDecimal::compareTo).orElseThrow();
                mean = r.values().stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(r.values().size()), 6, RoundingMode.HALF_UP);
            }
            s.db().update(
                            "INSERT INTO inspection_measurements(id,tenant_id,inspection_id,plan_id,plan_version,characteristic_id,seq,name,kind,unit,nominal,lower_limit,upper_limit,sample_size,critical,min_value,max_value,mean_value,nonconforming,conforming,note,recorded_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            id,
                            a.tenant(),
                            inspection,
                            e.plan().get("id"),
                            e.plan().get("version"),
                            ch.get("id"),
                            ch.get("seq"),
                            ch.get("name"),
                            ch.get("kind"),
                            ch.get("unit"),
                            ch.get("nominal"),
                            ch.get("lower_limit"),
                            ch.get("upper_limit"),
                            ch.get("sample_size"),
                            ch.get("critical"),
                            min,
                            max,
                            mean,
                            r.nonconforming(),
                            r.conforming(),
                            r.note(),
                            a.id());
            if (r.values() != null)
                for (int i = 0; i < r.values().size(); i++)
                    s.db().update(
                                    "INSERT INTO inspection_readings(tenant_id,measurement_id,sample_no,value) VALUES(?,?,?,?)",
                                    a.tenant(),
                                    id,
                                    i + 1,
                                    r.values().get(i));
        }
    }
}
