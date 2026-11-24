package lk.dexlanka.erp.manufacturing;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/**
 * Semi-finished shortages of one order (V7), shared by the child-order command and the order tree read model.
 *
 * <p>For every {@code SEMI_FINISHED} material of the order: {@code shortage = required − reserved/issued for this order −
 * released stock left for this order − supply of this order's open child orders} (still to produce, plus output
 * awaiting final quality control). Released unreserved stock is allocated to open (draft/approved) orders in due-date
 * order, like the MRP pegging: earlier orders' requirements not covered by their own children come first. Safety stock
 * is left to MRP. A child order for a shortage is due {@code lead_time_days} of the component before the parent.
 */
@Component
class ChildPlanning {
    static final Set<String> PLANNABLE = Set.of("DRAFT", "APPROVED");

    private final Store s;

    ChildPlanning(Store s) {
        this.s = s;
    }

    List<Map<String, Object>> shortages(String tenant, Map<String, Object> order) {
        boolean open = PLANNABLE.contains(str(order, "status"));
        LocalDate due = LocalDate.parse(str(order, "due_date"));
        var result = new ArrayList<Map<String, Object>>();
        for (var r : s.list("""
                WITH supply AS (
                    SELECT c.parent_order_id AS order_id,c.product_id AS item_id,
                           SUM(GREATEST(0,c.quantity-c.good_qty-c.scrap_qty)) AS remaining,COUNT(*) AS orders
                    FROM manufacturing_orders c
                    WHERE c.tenant_id=? AND c.parent_order_id IS NOT NULL AND c.status<>'CANCELLED'
                    GROUP BY c.parent_order_id,c.product_id),
                qc AS (
                    SELECT c.parent_order_id AS order_id,c.product_id AS item_id,SUM(b.quantity) AS in_qc
                    FROM manufacturing_orders c
                    JOIN lots l ON l.tenant_id=c.tenant_id AND l.source_type='MANUFACTURING' AND l.source_id=c.id
                    JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                    WHERE c.tenant_id=? AND c.parent_order_id IS NOT NULL AND l.status='QUARANTINE'
                    GROUP BY c.parent_order_id,c.product_id)
                SELECT om.item_id,om.required_qty,i.sku,i.name,i.uom,i.active,i.lead_time_days,
                       COALESCE((SELECT SUM(r.quantity) FROM reservations r
                                 WHERE r.tenant_id=om.tenant_id AND r.order_id=om.order_id AND r.item_id=om.item_id
                                   AND r.status IN ('RESERVED','ISSUED')),0) AS reserved,
                       COALESCE((SELECT SUM(b.quantity-b.reserved) FROM lots l
                                 JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                                 WHERE l.tenant_id=om.tenant_id AND l.item_id=om.item_id AND l.status='RELEASED'),0) AS available,
                       COALESCE((SELECT SUM(GREATEST(0,e.required_qty-COALESCE(es.remaining,0)-COALESCE(eq.in_qc,0)))
                                 FROM order_materials e
                                 JOIN manufacturing_orders eo ON eo.tenant_id=e.tenant_id AND eo.id=e.order_id
                                 LEFT JOIN supply es ON es.order_id=e.order_id AND es.item_id=e.item_id
                                 LEFT JOIN qc eq ON eq.order_id=e.order_id AND eq.item_id=e.item_id
                                 WHERE e.tenant_id=om.tenant_id AND e.item_id=om.item_id AND e.order_id<>om.order_id
                                   AND eo.status IN ('DRAFT','APPROVED')
                                   AND (eo.due_date,eo.created_at,eo.id)<(po.due_date,po.created_at,po.id)),0) AS earlier_demand,
                       COALESCE(sp.remaining,0) AS child_remaining,COALESCE(sp.orders,0) AS child_orders,
                       COALESCE(q.in_qc,0) AS child_in_qc,
                       bom.id AS bom_id,bom.revision AS bom_revision
                FROM order_materials om
                JOIN manufacturing_orders po ON po.tenant_id=om.tenant_id AND po.id=om.order_id
                JOIN items i ON i.tenant_id=om.tenant_id AND i.id=om.item_id
                LEFT JOIN supply sp ON sp.order_id=om.order_id AND sp.item_id=om.item_id
                LEFT JOIN qc q ON q.order_id=om.order_id AND q.item_id=om.item_id
                LEFT JOIN LATERAL (
                    SELECT b.id,b.revision FROM boms b
                    WHERE b.tenant_id=om.tenant_id AND b.product_id=om.item_id AND b.status='APPROVED'
                    ORDER BY b.created_at DESC,b.revision DESC LIMIT 1) bom ON true
                WHERE om.tenant_id=? AND om.order_id=? AND i.type='SEMI_FINISHED'
                ORDER BY i.sku""", tenant, tenant, tenant, order.get("id"))) {
            BigDecimal required = dec(r.get("required_qty"));
            BigDecimal childSupply = dec(r.get("child_remaining")).add(dec(r.get("child_in_qc")));
            BigDecimal forOrder = dec(r.get("available"))
                    .subtract(dec(r.get("earlier_demand")))
                    .max(BigDecimal.ZERO);
            BigDecimal shortage = open
                    ? required.subtract(dec(r.get("reserved")))
                            .subtract(forOrder)
                            .subtract(childSupply)
                            .max(BigDecimal.ZERO)
                    : BigDecimal.ZERO;
            if ("pcs".equals(r.get("uom"))) shortage = shortage.setScale(0, RoundingMode.CEILING);
            int lead = ((Number) r.get("lead_time_days")).intValue();
            var row = new LinkedHashMap<String, Object>();
            row.put("item_id", r.get("item_id"));
            row.put("sku", r.get("sku"));
            row.put("name", r.get("name"));
            row.put("uom", r.get("uom"));
            row.put("active", r.get("active"));
            row.put("lead_time_days", lead);
            row.put("required_qty", required);
            row.put("reserved", dec(r.get("reserved")));
            row.put("available", dec(r.get("available")));
            row.put("earlier_demand", dec(r.get("earlier_demand")));
            row.put("available_for_order", forOrder);
            row.put("child_supply", childSupply);
            row.put("child_orders", r.get("child_orders"));
            row.put("shortage", shortage);
            row.put("bom_id", r.get("bom_id"));
            row.put("bom_revision", r.get("bom_revision"));
            row.put("suggested_due_date", due.minusDays(lead).toString());
            row.put(
                    "plannable",
                    open && shortage.signum() > 0 && r.get("bom_id") != null && Boolean.TRUE.equals(r.get("active")));
            result.add(row);
        }
        return result;
    }
}
