package lk.dexlanka.erp.traceability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.procurement.ProcurementCommands;
import lk.dexlanka.erp.procurement.SupplierReturnService;
import lk.dexlanka.erp.quality.NcrCommands;
import lk.dexlanka.erp.quality.ReworkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The V14 trace engine over real postings: every edge type, both directions, every entry point, cycles and depth. */
class TraceEngineTest extends TraceTestSupport {
    @Autowired
    SupplierReturnService supplierReturns;

    @Autowired
    ReworkService rework;

    private Map<String, Object> trace(String type, String id, String direction) throws Exception {
        return json(query(viewer, "/api/traceability/trace?type=" + type + "&id=" + id + "&direction=" + direction)
                .andExpect(status().isOk()));
    }

    /** Edges always run left to right in the drawing: from a lower level to a higher one. */
    private static void assertLeftToRight(Map<String, Object> trace) {
        var level = new java.util.HashMap<Object, Integer>();
        for (var n : list(trace.get("nodes"))) level.put(n.get("id"), ((Number) n.get("level")).intValue());
        for (var e : list(trace.get("edges")))
            assertThat(level.get(e.get("from")))
                    .as("level of %s -> %s", e.get("from"), e.get("to"))
                    .isLessThan(level.get(e.get("to")));
    }

    @Test
    void tracesAReceiptThroughTwoLevelsToCustomersAndBack() throws Exception {
        var receipt = purchasedResin("100");
        String resinLot = receipt[1];
        var preforms = produce(preformBom, "1000"); // consumes 20 kg of the 100 kg lot
        var bottles = produce(bottleBom, "400"); // consumes 400 of the 1,000 preforms
        String store = secondFinishedWarehouse();
        String moved = transfer(bottles[1], finished, store, "100");
        var saleA = sell(customerA, bottles[1], "120", "DN-A1");
        var saleB = sell(customerB, moved, "30", "DN-B1");
        String returned = customerReturn(saleA[1], "20");

        // Forward from the purchased resin lot.
        var forward = trace("LOT", resinLot, "FORWARD");
        assertThat(map(forward.get("root"))).containsEntry("id", "lot:" + resinLot);
        assertThat(node(forward, "lot:" + resinLot)).containsEntry("side", "ROOT").containsEntry("level", 0);
        assertThat(num(node(forward, "lot:" + resinLot).get("onHand"))).isEqualByComparingTo("80");
        assertThat(num(edge(forward, "lot:" + resinLot, "mo:" + preforms[0], "ISSUED").get("quantity")))
                .isEqualByComparingTo("20");
        assertThat(num(edge(forward, "mo:" + preforms[0], "lot:" + preforms[1], "PRODUCED").get("quantity")))
                .isEqualByComparingTo("1000");
        assertThat(num(edge(forward, "lot:" + preforms[1], "mo:" + bottles[0], "ISSUED").get("quantity")))
                .isEqualByComparingTo("400");
        assertThat(num(edge(forward, "mo:" + bottles[0], "lot:" + bottles[1], "PRODUCED").get("quantity")))
                .isEqualByComparingTo("400");
        assertThat(num(edge(forward, "lot:" + bottles[1], "lot:" + moved, "TRANSFERRED").get("quantity")))
                .isEqualByComparingTo("100");
        var toAlpha = edge(forward, "lot:" + bottles[1], "delivery:" + saleA[1], "DELIVERED");
        assertThat(num(toAlpha.get("quantity"))).isEqualByComparingTo("120");
        assertThat(toAlpha).containsEntry("reference", "DN-A1");
        assertThat(toAlpha.get("at")).isNotNull();
        assertThat(node(forward, "delivery:" + saleA[1]))
                .containsEntry("customer", "Alpha Beverages")
                .containsEntry("salesOrderId", saleA[0]);
        assertThat(num(edge(forward, "lot:" + moved, "delivery:" + saleB[1], "DELIVERED").get("quantity")))
                .isEqualByComparingTo("30");
        assertThat(node(forward, "delivery:" + saleB[1])).containsEntry("customer", "Beta Foods");
        assertThat(num(edge(forward, "delivery:" + saleA[1], "lot:" + returned, "RETURNED").get("quantity")))
                .isEqualByComparingTo("20");
        assertThat(node(forward, "lot:" + bottles[1])).containsEntry("sku", "FG-BOT").containsEntry("status", "RELEASED");
        assertThat(num(node(forward, "lot:" + bottles[1]).get("onHand"))).isEqualByComparingTo("180");
        assertThat(list(forward.get("nodes"))).allSatisfy(n -> assertThat(n.get("side")).isIn("ROOT", "FORWARD"));
        assertLeftToRight(forward);
        assertThat(map(forward.get("summary"))).containsEntry("customers", 2).containsEntry("deliveries", 2);
        // The indented tree starts at the root's direct successors.
        var tree = list(map(forward.get("tree")).get("forward"));
        assertThat(tree.getFirst()).containsEntry("node", "mo:" + preforms[0]).containsEntry("indent", 1);
        assertThat(tree).anySatisfy(r -> assertThat(r).containsEntry("node", "lot:" + returned));
        assertThat(list(map(forward.get("tree")).get("backward"))).isEmpty();

        // Backward from the customer-return lot: delivery, finished lot, two orders, the resin receipt and supplier.
        var backward = trace("LOT", returned, "BACKWARD");
        edge(backward, "delivery:" + saleA[1], "lot:" + returned, "RETURNED");
        edge(backward, "lot:" + bottles[1], "delivery:" + saleA[1], "DELIVERED");
        edge(backward, "mo:" + bottles[0], "lot:" + bottles[1], "PRODUCED");
        edge(backward, "lot:" + preforms[1], "mo:" + bottles[0], "ISSUED");
        edge(backward, "mo:" + preforms[0], "lot:" + preforms[1], "PRODUCED");
        edge(backward, "lot:" + resinLot, "mo:" + preforms[0], "ISSUED");
        var received = edge(backward, "receipt:" + receipt[2], "lot:" + resinLot, "RECEIVED");
        assertThat(num(received.get("quantity"))).isEqualByComparingTo("100");
        assertThat(received).containsEntry("reference", receipt[3]);
        assertThat(node(backward, "receipt:" + receipt[2]))
                .containsEntry("supplier", "Resin Supplier")
                .containsEntry("purchaseOrderId", receipt[0]);
        assertThat(list(backward.get("nodes")))
                .filteredOn(n -> !"ROOT".equals(n.get("side")))
                .allSatisfy(n -> assertThat(((Number) n.get("level")).intValue()).isNegative());
        assertLeftToRight(backward);
        // The transferred lot is a sibling of the returned goods, not an ancestor.
        assertThat(hasNode(backward, "lot:" + moved)).isFalse();

        // Entry by manufacturing order: material behind it, output and customers ahead.
        var byOrder = trace("MO", bottles[0], "BOTH");
        assertThat(map(byOrder.get("root"))).containsEntry("kind", "MANUFACTURING_ORDER");
        assertThat(node(byOrder, "mo:" + bottles[0])).containsEntry("side", "ROOT");
        assertThat(node(byOrder, "lot:" + preforms[1])).containsEntry("side", "BACKWARD");
        assertThat(node(byOrder, "receipt:" + receipt[2])).containsEntry("side", "BACKWARD");
        assertThat(node(byOrder, "lot:" + bottles[1])).containsEntry("side", "FORWARD");
        assertThat(node(byOrder, "lot:" + returned)).containsEntry("side", "FORWARD");
        assertLeftToRight(byOrder);
        assertThat(list(map(byOrder.get("tree")).get("backward"))).isNotEmpty();

        // Entry by goods receipt (GRN number) and by purchase order.
        var byReceipt = trace("RECEIPT", receipt[3], "BOTH");
        assertThat(map(byReceipt.get("root"))).containsEntry("kind", "GOODS_RECEIPT").containsEntry("supplier", "Resin Supplier");
        edge(byReceipt, "grn:" + receipt[3], "lot:" + resinLot, "RECEIVED");
        assertThat(hasNode(byReceipt, "delivery:" + saleB[1])).isTrue();
        var byPo = trace("PO", receipt[0], "FORWARD");
        edge(byPo, "po:" + receipt[0], "lot:" + resinLot, "RECEIVED");
        assertThat(hasNode(byPo, "lot:" + returned)).isTrue();

        // Entry by delivery reference and by sales order.
        var byDelivery = trace("DELIVERY", "DN-A1", "BOTH");
        edge(byDelivery, "lot:" + bottles[1], "dref:DN-A1", "DELIVERED");
        edge(byDelivery, "dref:DN-A1", "lot:" + returned, "RETURNED");
        assertThat(hasNode(byDelivery, "receipt:" + receipt[2])).isTrue();
        var bySalesOrder = trace("SO", saleB[0], "BACKWARD");
        edge(bySalesOrder, "lot:" + moved, "so:" + saleB[0], "DELIVERED");
        edge(bySalesOrder, "lot:" + bottles[1], "lot:" + moved, "TRANSFERRED");
        assertThat(hasNode(bySalesOrder, "lot:" + resinLot)).isTrue();

        // Entry by supplier and receipt dates: every lot received from it and everything made from them.
        String today = LocalDate.now(ZoneId.of("Asia/Colombo")).toString();
        var bySupplier = json(query(
                        viewer,
                        "/api/traceability/trace?type=SUPPLIER&id=" + supplier + "&from=" + today + "&to=" + today
                                + "&direction=FORWARD")
                .andExpect(status().isOk()));
        edge(bySupplier, "supplier:" + supplier, "lot:" + resinLot, "RECEIVED");
        assertThat(hasNode(bySupplier, "delivery:" + saleA[1])).isTrue();
        String tomorrow = LocalDate.now(ZoneId.of("Asia/Colombo")).plusDays(1).toString();
        var none = json(query(
                        viewer,
                        "/api/traceability/trace?type=SUPPLIER&id=" + supplier + "&from=" + tomorrow + "&to=" + tomorrow)
                .andExpect(status().isOk()));
        assertThat(list(none.get("nodes"))).hasSize(1);

        // Search finds the entry points.
        var orderNumber = str(row("SELECT number FROM manufacturing_orders WHERE tenant_id=? AND id=?", t, bottles[0]), "number");
        var hits = list(json(query(viewer, "/api/traceability/search?q=" + orderNumber).andExpect(status().isOk()))
                .get("items"));
        assertThat(hits).anySatisfy(h -> assertThat(h).containsEntry("type", "MO").containsEntry("id", bottles[0]));
        var lotHits = list(json(query(viewer, "/api/traceability/search?q=" + lotNumber(resinLot))).get("items"));
        assertThat(lotHits).anySatisfy(h -> assertThat(h).containsEntry("type", "LOT").containsEntry("id", resinLot));
        var supplierHits = list(json(query(viewer, "/api/traceability/search?q=Resin")).get("items"));
        assertThat(supplierHits).anySatisfy(h -> assertThat(h).containsEntry("type", "SUPPLIER"));
        assertThat(list(json(query(viewer, "/api/traceability/search?q=DN-A")).get("items")))
                .anySatisfy(h -> assertThat(h).containsEntry("type", "DELIVERY").containsEntry("id", "DN-A1"));
        query(viewer, "/api/traceability/search?q=x").andExpect(status().isConflict());
    }

    @Test
    void followsSupplierReturnsMaintenanceAdjustmentsScrapAndRework() throws Exception {
        var receipt = purchasedResin("100");
        String resinLot = receipt[1];
        supplierReturns.returnToSupplier(
                warehouse, receipt[2], new ProcurementCommands.SupplierReturn(bd("10"), "Wet resin", "DBN-1"), id());
        s.ledger(admin, resinLot, bd("-2"), "MWO-T-1", "MAINTENANCE_ISSUE");
        s.ledger(admin, resinLot, bd("0.5"), "MWO-T-1", "MAINTENANCE_RETURN");
        s.ledger(admin, resinLot, bd("-3"), "ADJ-T-1", "ADJUSTMENT_DAMAGE");

        var preforms = produce(preformBom, "1000");
        var bottles = produce(bottleBom, "200");
        // A nonconformance on the bottles: 10 scrapped, 40 reworked with 5 extra preforms into 38 good bottles.
        String ncr = str(
                ncrs.raise(
                        qa,
                        new NcrCommands.Raise(
                                "PRODUCTION",
                                "MAJOR",
                                "DIM",
                                "Neck finish",
                                "Out of round",
                                "",
                                null,
                                bottles[1],
                                bd("50"),
                                null,
                                null),
                        id()),
                "id");
        ncrs.disposition(qa, ncr, new NcrCommands.Disposition("SCRAP", bd("10"), "Crushed", null), id());
        ncrs.disposition(qa, ncr, new NcrCommands.Disposition("REWORK", bd("40"), "Re-trim", "Trim necks"), id());
        String rw = str(row("SELECT id FROM rework_orders WHERE tenant_id=? AND ncr_id=?", t, ncr), "id");
        rework.complete(
                qa,
                rw,
                new NcrCommands.ReworkComplete(
                        bd("38"), bd("0"), List.of(new NcrCommands.Material(preforms[1], bd("5"))), "Done"),
                id());
        String reworked = str(row("SELECT output_lot_id FROM rework_orders WHERE tenant_id=? AND id=?", t, rw), "output_lot_id");
        String ncrNumber = str(row("SELECT number FROM ncrs WHERE tenant_id=? AND id=?", t, ncr), "number");

        var resin = trace("LOT", resinLot, "FORWARD");
        var returned = list(resin.get("edges")).stream()
                .filter(e -> "SUPPLIER_RETURN".equals(e.get("kind")))
                .findFirst()
                .orElseThrow();
        assertThat(num(returned.get("quantity"))).isEqualByComparingTo("10");
        assertThat(node(resin, str(returned, "to")).get("detail").toString()).contains("Resin Supplier");
        assertThat(num(edge(resin, "lot:" + resinLot, "out:MAINTENANCE:MWO-T-1", "MAINTENANCE").get("quantity")))
                .isEqualByComparingTo("1.5");
        assertThat(num(edge(resin, "lot:" + resinLot, "out:ADJUSTMENT:ADJ-T-1", "ADJUSTMENT").get("quantity")))
                .isEqualByComparingTo("3");
        // 100 − 10 returned − 1.5 maintenance − 3 adjusted − 20 to the preform order.
        assertThat(num(node(resin, "lot:" + resinLot).get("onHand"))).isEqualByComparingTo("65.5");

        var bottleTrace = trace("LOT", bottles[1], "FORWARD");
        assertThat(num(edge(bottleTrace, "lot:" + bottles[1], "out:SCRAP:" + ncrNumber, "SCRAP").get("quantity")))
                .isEqualByComparingTo("10");
        assertThat(num(edge(bottleTrace, "lot:" + bottles[1], "rw:" + rw, "REWORK_INPUT").get("quantity")))
                .isEqualByComparingTo("40");
        assertThat(num(edge(bottleTrace, "rw:" + rw, "lot:" + reworked, "REWORKED").get("quantity")))
                .isEqualByComparingTo("38");

        var preformTrace = trace("LOT", preforms[1], "FORWARD");
        assertThat(num(edge(preformTrace, "lot:" + preforms[1], "rw:" + rw, "REWORK_MATERIAL").get("quantity")))
                .isEqualByComparingTo("5");
        assertThat(hasNode(preformTrace, "lot:" + reworked)).isTrue();

        var back = trace("LOT", reworked, "BACKWARD");
        edge(back, "lot:" + bottles[1], "rw:" + rw, "REWORK_INPUT");
        edge(back, "lot:" + preforms[1], "rw:" + rw, "REWORK_MATERIAL");
        edge(back, "receipt:" + receipt[2], "lot:" + resinLot, "RECEIVED");
        assertLeftToRight(back);
    }

    @Test
    void walksCyclesSafelyAndStopsAtTheDepthLimit() throws Exception {
        // Six lots linked L0 → L1 → … → L5 → L0 by posted transfer lines (a cycle no command can create).
        String other = secondFinishedWarehouse();
        String[] lots = new String[6];
        for (int i = 0; i < lots.length; i++) {
            lots[i] = id();
            update(
                    "INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost) VALUES(?,?,?,?,?,'RELEASED','TRANSFER','x',1)",
                    lots[i],
                    t,
                    "CYC-" + i + "-" + lots[i].substring(0, 6),
                    bottle,
                    finished);
        }
        String transferId = id();
        update(
                "INSERT INTO stock_transfers(id,tenant_id,number,from_warehouse_id,to_warehouse_id,created_by) VALUES(?,?,?,?,?,?)",
                transferId,
                t,
                "TR-CYC-" + transferId.substring(0, 6),
                finished,
                other,
                admin.id());
        for (int i = 0; i < lots.length; i++)
            update(
                    "INSERT INTO stock_transfer_lines(id,tenant_id,transfer_id,line_no,source_lot_id,item_id,quantity,destination_lot_id,value_amount) VALUES(?,?,?,?,?,?,1,?,1)",
                    id(),
                    t,
                    transferId,
                    i + 1,
                    lots[i],
                    bottle,
                    lots[(i + 1) % lots.length]);
        update(
                "UPDATE stock_transfers SET status='POSTED',posted_by=?,posted_at=now() WHERE tenant_id=? AND id=?",
                admin.id(),
                t,
                transferId);

        var closure = traces.forward(t, List.of(lots[0]), 30);
        assertThat(closure.lots()).hasSize(6).containsEntry(lots[0], 0).containsEntry(lots[3], 3);
        assertThat(traces.backward(t, List.of(lots[0]), 30).lots()).hasSize(6).containsEntry(lots[5], 1);

        var full = trace("LOT", lots[0], "BOTH");
        assertThat(list(full.get("nodes"))).hasSize(6);
        assertThat(edges(full, "TRANSFERRED")).hasSize(6);

        var limited = json(query(viewer, "/api/traceability/trace?id=" + lots[0] + "&direction=FORWARD&depth=2")
                .andExpect(status().isOk()));
        assertThat(list(limited.get("nodes"))).extracting(n -> n.get("id"))
                .containsExactlyInAnyOrder("lot:" + lots[0], "lot:" + lots[1], "lot:" + lots[2]);
        assertThat(limited).containsEntry("depth", 2);
        query(viewer, "/api/traceability/trace?id=" + lots[0] + "&depth=0").andExpect(status().isConflict());
        query(viewer, "/api/traceability/trace?id=" + lots[0] + "&depth=31").andExpect(status().isConflict());
        query(viewer, "/api/traceability/trace?type=NOPE&id=" + lots[0]).andExpect(status().isConflict());
    }

    @Test
    void tracesAreTenantScoped() throws Exception {
        var receipt = purchasedResin("10");
        var other = createTenant("Other trace tenant");
        var stranger = createUser(other.id(), "QUALITY");
        query(stranger, "/api/traceability/trace?id=" + receipt[1]).andExpect(status().isNotFound());
        query(stranger, "/api/traceability/trace?type=PO&id=" + receipt[0]).andExpect(status().isNotFound());
        query(stranger, "/api/traceability/trace?type=SUPPLIER&id=" + supplier).andExpect(status().isNotFound());
        assertThat(traces.forward(other.id(), List.of(receipt[1]), 5).lots()).isEmpty();
        assertThat(list(json(query(stranger, "/api/traceability/search?q=" + lotNumber(receipt[1]))).get("items")))
                .isEmpty();
        // Every role of the lot's own tenant may trace.
        for (var actor : List.of(viewer, operator, salesRep, warehouse))
            query(actor, "/api/traceability/trace?id=" + receipt[1]).andExpect(status().isOk());
    }
}
