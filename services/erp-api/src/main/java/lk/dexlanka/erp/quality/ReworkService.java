package lk.dexlanka.erp.quality;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;
import static lk.dexlanka.erp.quality.QualityRules.REWORK_ISSUE;
import static lk.dexlanka.erp.quality.QualityRules.REWORK_MATERIAL;
import static lk.dexlanka.erp.quality.QualityRules.REWORK_OUTPUT;
import static lk.dexlanka.erp.quality.QualityRules.REWORK_WIP;
import static lk.dexlanka.erp.quality.QualityRules.blankToEmpty;
import static lk.dexlanka.erp.quality.QualityRules.inventoryAccount;
import static lk.dexlanka.erp.quality.QualityRules.plain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rework orders ({@code RW-…}, V12). A REWORK disposition opens the order and moves the nonconforming quantity out of
 * the held lot into rework WIP (ledger {@code REWORK_ISSUE}, journal Dr {@code REWORK_WIP} / Cr the lot's inventory
 * account). Completing it issues optional extra material from released lots ({@code REWORK_MATERIAL}, Dr
 * {@code REWORK_WIP} / Cr their inventory account), applies labour (Dr {@code REWORK_WIP} / Cr
 * {@code CONVERSION_CLEARING}) and receives the output into a new quarantined lot ({@code source_type='REWORK'},
 * {@code source_id} = the order) valued at input + material + labour ({@code REWORK_OUTPUT}, Dr inventory / Cr
 * {@code REWORK_WIP}), so rework WIP returns to zero for every order. A {@code REWORK} inspection gates the new lot, and
 * {@code rework_lot_links} records every input lot → output lot for genealogy.
 */
@Service
public class ReworkService {
    private final Store s;
    private final QualityRules rules;

    ReworkService(Store s, QualityRules rules) {
        this.s = s;
        this.rules = rules;
    }

    /**
     * Opens a rework order for {@code quantity} of the held lot (inside the disposition command's transaction).
     * Returns {@code id}, {@code number} and {@code value} (the input value moved into rework WIP).
     */
    Map<String, Object> open(
            Actor a,
            Map<String, Object> ncr,
            String dispositionId,
            Map<String, Object> lot,
            BigDecimal quantity,
            String instructions) {
        String id = id();
        String number = rules.documentNumber(a, "rework_orders", "RW");
        BigDecimal value = s.ledger(a, str(lot, "id"), quantity.negate(), number, REWORK_ISSUE)
                .abs();
        s.journal(
                a,
                number,
                "Nonconforming stock of lot " + lot.get("number") + " moved into rework",
                REWORK_WIP,
                inventoryAccount(lot.get("item_type")),
                value);
        s.db().update(
                        "INSERT INTO rework_orders(id,tenant_id,number,ncr_id,disposition_id,item_id,input_lot_id,input_quantity,input_value,instructions,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        id,
                        a.tenant(),
                        number,
                        ncr.get("id"),
                        dispositionId,
                        lot.get("item_id"),
                        lot.get("id"),
                        quantity,
                        value,
                        blankToEmpty(instructions),
                        a.id());
        s.audit(
                a,
                "REWORK_ORDER_CREATED",
                number,
                plain(quantity) + " " + lot.get("uom") + " of " + lot.get("sku") + " from lot " + lot.get("number")
                        + " for " + ncr.get("number") + " · input value Rs. " + value);
        var out = new LinkedHashMap<String, Object>();
        out.put("id", id);
        out.put("number", number);
        out.put("value", value);
        return out;
    }

    @Transactional
    public Map<String, Object> complete(Actor a, String id, NcrCommands.ReworkComplete c, String key) {
        a.allow("QUALITY", "OPERATOR", "PLANNER");
        return s.once(a, key, "rework-complete:" + id, c, () -> {
            s.openPeriod(a);
            var rw = s.one("SELECT * FROM rework_orders WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
            require(rw.get("status").equals("OPEN"), "Rework order " + rw.get("number") + " is already completed");
            var input = rules.lot(a, str(rw, "input_lot_id"));
            String number = str(rw, "number");
            String uom = str(input, "uom");
            s.quantity(uom, c.outputQuantity());
            BigDecimal inputQty = dec(rw.get("input_quantity"));
            require(
                    c.outputQuantity().compareTo(inputQty) <= 0,
                    "Output cannot exceed the " + plain(inputQty) + " " + uom + " taken into rework");
            List<NcrCommands.Material> materials = c.materials() == null ? List.of() : c.materials();
            var seen = new HashSet<String>();
            BigDecimal materialCost = BigDecimal.ZERO;
            var materialLinks = new java.util.ArrayList<Object[]>();
            for (var m : materials) {
                require(seen.add(m.lotId()), "List each material lot once");
                require(!m.lotId().equals(rw.get("input_lot_id")), "The reworked lot cannot also be its material");
                var lot = rules.lockedLot(a, m.lotId());
                require(
                        lot.get("status").equals("RELEASED") && !Boolean.TRUE.equals(lot.get("inspection_pending")),
                        "Rework material must come from quality-released stock; lot " + lot.get("number") + " is "
                                + str(lot, "status").toLowerCase());
                s.quantity(str(lot, "uom"), m.quantity());
                BigDecimal available = dec(lot.get("quantity")).subtract(dec(lot.get("reserved")));
                require(
                        m.quantity().compareTo(available) <= 0,
                        "Only " + plain(available) + " " + lot.get("uom") + " of lot " + lot.get("number")
                                + " is available (unreserved)");
                BigDecimal value = s.ledger(a, m.lotId(), m.quantity().negate(), number, REWORK_MATERIAL)
                        .abs();
                s.journal(
                        a,
                        number,
                        "Material " + lot.get("sku") + " issued to rework",
                        REWORK_WIP,
                        inventoryAccount(lot.get("item_type")),
                        value);
                s.db().update(
                                "INSERT INTO rework_materials(id,tenant_id,rework_order_id,lot_id,item_id,quantity,value_amount) VALUES(?,?,?,?,?,?,?)",
                                id(),
                                a.tenant(),
                                id,
                                m.lotId(),
                                lot.get("item_id"),
                                m.quantity(),
                                value);
                materialLinks.add(new Object[] {m.lotId(), m.quantity(), value});
                materialCost = materialCost.add(value);
            }
            s.journal(a, number, "Rework labour applied", REWORK_WIP, QualityRules.CONVERSION_CLEARING, c.labourCost());
            BigDecimal total = dec(rw.get("input_value")).add(materialCost).add(c.labourCost());
            String lot = id();
            String lotNumber = s.list("SELECT 1 FROM lots WHERE tenant_id=? AND number=?", a.tenant(), number)
                            .isEmpty()
                    ? number
                    : rules.lotNumber(a, "RW");
            s.db().update(
                            "INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost) VALUES(?,?,?,?,?,'QUARANTINE','REWORK',?,?)",
                            lot,
                            a.tenant(),
                            lotNumber,
                            rw.get("item_id"),
                            input.get("warehouse_id"),
                            id,
                            total.divide(c.outputQuantity(), 6, RoundingMode.HALF_UP));
            s.ledger(a, lot, c.outputQuantity(), number, REWORK_OUTPUT, total);
            s.journal(
                    a,
                    number,
                    "Reworked output into quality quarantine",
                    inventoryAccount(input.get("item_type")),
                    REWORK_WIP,
                    total);
            String inspection = id();
            s.db().update(
                            "INSERT INTO inspections(id,tenant_id,lot_id,type) VALUES(?,?,?,'REWORK')",
                            inspection,
                            a.tenant(),
                            lot);
            s.db().update(
                            "INSERT INTO rework_lot_links(id,tenant_id,rework_order_id,input_lot_id,output_lot_id,role,quantity,value_amount) VALUES(?,?,?,?,?,'REWORKED',?,?)",
                            id(),
                            a.tenant(),
                            id,
                            rw.get("input_lot_id"),
                            lot,
                            rw.get("input_quantity"),
                            rw.get("input_value"));
            for (var m : materialLinks)
                s.db().update(
                                "INSERT INTO rework_lot_links(id,tenant_id,rework_order_id,input_lot_id,output_lot_id,role,quantity,value_amount) VALUES(?,?,?,?,?,'MATERIAL',?,?)",
                                id(),
                                a.tenant(),
                                id,
                                m[0],
                                lot,
                                m[1],
                                m[2]);
            s.db().update(
                            "UPDATE rework_orders SET status='COMPLETED',material_cost=?,labour_cost=?,output_lot_id=?,output_quantity=?,output_value=?,completion_note=?,completed_by=?,completed_at=now() WHERE tenant_id=? AND id=?",
                            materialCost,
                            c.labourCost(),
                            lot,
                            c.outputQuantity(),
                            total,
                            blankToEmpty(c.note()),
                            a.id(),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "REWORK_COMPLETED",
                    number,
                    plain(c.outputQuantity()) + " " + uom + " of " + plain(inputQty) + " reworked into lot " + lotNumber
                            + " (quality quarantine) · value Rs. " + total + " = input Rs. " + rw.get("input_value")
                            + " + material Rs. " + materialCost + " + labour Rs. " + c.labourCost());
            var out = new LinkedHashMap<String, Object>();
            out.put("id", id);
            out.put("number", number);
            out.put("status", "COMPLETED");
            out.put("outputLotId", lot);
            out.put("outputLot", lotNumber);
            out.put("inspectionId", inspection);
            out.put("outputValue", total);
            return out;
        });
    }
}
