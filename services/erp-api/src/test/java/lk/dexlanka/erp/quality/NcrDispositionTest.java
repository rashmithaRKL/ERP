package lk.dexlanka.erp.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * V12 nonconformances and dispositions: manual and automatic NCRs, holds, scrap (ledger, journal, closed period),
 * concessions with independent approval, return to supplier through the supplier-return document, rework with value
 * conservation and lot links, partial dispositions, close/cancel, MO completion, permissions, idempotency, tenancy.
 */
class NcrDispositionTest extends QualityTestSupport {

    NcrCommands.Raise raise(String lot, String qty) {
        return new NcrCommands.Raise(
                "INTERNAL",
                "MAJOR",
                "VIS",
                "Scratched labels",
                "Found during cycle count",
                "Lot segregated",
                null,
                lot,
                bd(qty),
                null,
                null);
    }

    NcrCommands.Disposition disposition(String type, String qty) {
        return new NcrCommands.Disposition(type, bd(qty), "Decided by MRB", type.equals("REWORK") ? "Relabel" : null);
    }

    @Test
    void manualNcrHoldsAReleasedLotAndScrapPostsLedgerAndBalancedJournal() throws Exception {
        String released = lot(label, raw, "RELEASED", "OPENING", "100", "2");
        var raised =
                json(command(qa, "/api/quality/ncrs", raise(released, "10")).andExpect(status().isOk()));
        String ncr = str(raised, "id");
        assertThat(raised).containsEntry("holdApplied", true);
        assertThat(lotStatus(released)).isEqualTo("QUARANTINE");
        assertThat(ncr(ncr)).containsEntry("hold_applied", true).containsEntry("uom", "pcs");

        var scrap = json(command(qa, "/api/quality/ncrs/" + ncr + "/dispositions", disposition("SCRAP", "10"))
                .andExpect(status().isOk()));
        assertThat(scrap).containsEntry("status", "POSTED").containsEntry("ncrStatus", "DISPOSITIONED");
        String number = str(ncr(ncr), "number");
        var ledger = row("SELECT * FROM stock_ledger WHERE tenant_id=? AND reason='QUALITY_SCRAP'", t);
        assertThat(ledger).containsEntry("lot_id", released).containsEntry("reference", number);
        assertThat(dec(ledger.get("delta"))).isEqualByComparingTo("-10");
        assertThat(dec(ledger.get("value_delta"))).isEqualByComparingTo("-20");
        assertThat(balance("QUALITY_LOSS", number)).isEqualByComparingTo("20");
        assertThat(balance("RAW_INVENTORY", number)).isEqualByComparingTo("-20");
        assertThat(tenantJournalImbalance()).isZero();
        // Fully dispositioned: the containment hold lifts and the rest of the lot is usable again.
        assertThat(lotStatus(released)).isEqualTo("RELEASED");
        assertThat(dec(lotRow(released).get("quantity"))).isEqualByComparingTo("90");
        assertThat(ncr(ncr)).containsEntry("status", "DISPOSITIONED");

        command(qa, "/api/quality/ncrs/" + ncr + "/close", new NcrCommands.Close("Supplier informed"))
                .andExpect(status().isOk());
        assertThat(ncr(ncr)).containsEntry("status", "CLOSED").containsEntry("closed_by", qa.id());
        assertThat(rows(
                                "SELECT action FROM audit_events WHERE tenant_id=? AND reference=? ORDER BY created_at",
                                t,
                                number)
                        .stream()
                        .map(r -> r.get("action"))
                        .toList())
                .containsExactlyInAnyOrder("NCR_RAISED", "NCR_SCRAP_POSTED", "NCR_DISPOSITIONED", "NCR_CLOSED");
    }

    @Test
    void scrapIsRefusedInAClosedPeriodAndRollsBack() {
        var rejected = rejectedBottles("20");
        update("INSERT INTO financial_periods(tenant_id,end_date,closed) VALUES(?,CURRENT_DATE,true)", t);
        assertThatThrownBy(() -> ncrs.disposition(qa, rejected[2], disposition("SCRAP", "5"), id()))
                .hasMessageContaining("financial period is closed");
        assertThat(count("SELECT COUNT(*) FROM ncr_dispositions WHERE tenant_id=?", t))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM stock_ledger WHERE tenant_id=? AND reason='QUALITY_SCRAP'", t))
                .isZero();
        assertThat(dec(lotRow(rejected[0]).get("quantity"))).isEqualByComparingTo("20");
    }

    @Test
    void useAsIsNeedsAnIndependentApproverAndReleasesTheLot() {
        var rejected = rejectedBottles("50");
        var concession = ncrs.disposition(qa, rejected[2], disposition("USE_AS_IS", "50"), id());
        String d = str(concession, "id");
        assertThat(concession).containsEntry("status", "PENDING_APPROVAL").containsEntry("ncrStatus", "OPEN");
        assertThat(lotStatus(rejected[0])).isEqualTo("REJECTED");
        var approve = new NcrCommands.Decision("APPROVE", "Cosmetic only; customer agreed");
        assertThatThrownBy(() -> ncrs.decide(qa, d, approve, id())).hasMessageContaining("different authorized person");
        assertThatThrownBy(() -> ncrs.decide(planner, d, approve, id())).isInstanceOf(AccessDeniedException.class);
        var decided = ncrs.decide(qa2, d, approve, id());
        assertThat(decided).containsEntry("status", "POSTED").containsEntry("ncrStatus", "DISPOSITIONED");
        assertThat(lotStatus(rejected[0])).isEqualTo("RELEASED");
        assertThat(dec(lotRow(rejected[0]).get("quantity"))).isEqualByComparingTo("50");
        assertThat(row("SELECT * FROM ncr_dispositions WHERE tenant_id=? AND id=?", t, d))
                .containsEntry("decided_by", qa2.id());
        // An administrator who requested a concession cannot approve it either.
        var other = rejectedBottles("5");
        String own = str(ncrs.disposition(admin, other[2], disposition("USE_AS_IS", "5"), id()), "id");
        assertThatThrownBy(() -> ncrs.decide(admin, own, approve, id()))
                .hasMessageContaining("different authorized person");
    }

    @Test
    void partialDispositionsNeverExceedTheAffectedQuantityAndAConcessionWaitsForTheRest() {
        var rejected = rejectedBottles("100");
        String ncr = rejected[2];
        String concession = str(ncrs.disposition(qa, ncr, disposition("USE_AS_IS", "40"), id()), "id");
        ncrs.decide(qa2, concession, new NcrCommands.Decision("APPROVE", "ok"), id());
        // 40 accepted, 60 still without a disposition: the lot stays held.
        assertThat(ncr(ncr)).containsEntry("status", "OPEN");
        assertThat(lotStatus(rejected[0])).isEqualTo("REJECTED");
        ncrs.disposition(qa, ncr, disposition("SCRAP", "30"), id());
        assertThatThrownBy(() -> ncrs.disposition(qa, ncr, disposition("SCRAP", "31"), id()))
                .hasMessageContaining("Only 30 pcs");
        // A rejected concession frees its quantity.
        String second = str(ncrs.disposition(qa, ncr, disposition("USE_AS_IS", "30"), id()), "id");
        assertThatThrownBy(() -> ncrs.disposition(qa, ncr, disposition("SCRAP", "1"), id()))
                .hasMessageContaining("Only 0 pcs");
        ncrs.decide(qa2, second, new NcrCommands.Decision("REJECT", "Not acceptable"), id());
        var last = ncrs.disposition(qa, ncr, disposition("SCRAP", "30"), id());
        assertThat(last).containsEntry("ncrStatus", "DISPOSITIONED");
        // What is left in the lot is exactly the accepted quantity: released.
        assertThat(dec(lotRow(rejected[0]).get("quantity"))).isEqualByComparingTo("40");
        assertThat(lotStatus(rejected[0])).isEqualTo("RELEASED");
        assertThat(balance("QUALITY_LOSS", null)).isEqualByComparingTo("600");
        assertThat(tenantJournalImbalance()).isZero();
        // No dispositions once dispositioned.
        assertThatThrownBy(() -> ncrs.disposition(qa, ncr, disposition("SCRAP", "1"), id()))
                .hasMessageContaining("Only an open nonconformance");
    }

    @Test
    void returnToSupplierIsDecidedByQualityAndPostedByTheWarehouseAsASupplierReturn() throws Exception {
        var purchased = purchasedResin("200");
        quality.inspect(qa, purchased[2], new QualityCommands.Inspect("PASSED", "CoA ok"), id());
        String ncr = str(
                ncrs.raise(
                        qa,
                        new NcrCommands.Raise(
                                "SUPPLIER",
                                "MAJOR",
                                "MAT",
                                "Gel particles",
                                "Gels found in moulding",
                                null,
                                null,
                                purchased[1],
                                bd("80"),
                                null,
                                null),
                        id()),
                "id");
        assertThat(ncr(ncr)).containsEntry("supplier_id", supplier);
        assertThat(lotStatus(purchased[1])).isEqualTo("QUARANTINE");
        var decided = ncrs.disposition(qa, ncr, disposition("RETURN_TO_SUPPLIER", "80"), id());
        String d = str(decided, "id");
        assertThat(decided).containsEntry("status", "AWAITING_RETURN").containsEntry("ncrStatus", "OPEN");
        var ship = new NcrCommands.ShipReturn("DBN-77", null);
        assertThatThrownBy(() -> ncrs.shipReturn(qa, d, ship, id())).isInstanceOf(AccessDeniedException.class);
        var shipped = json(command(warehouse, "/api/quality/dispositions/" + d + "/return", ship)
                .andExpect(status().isOk()));
        assertThat(shipped).containsEntry("status", "POSTED").containsEntry("ncrStatus", "DISPOSITIONED");
        var supplierReturn = row("SELECT * FROM supplier_returns WHERE tenant_id=? AND lot_id=?", t, purchased[1]);
        assertThat(supplierReturn).containsEntry("debit_note", "DBN-77");
        assertThat(dec(supplierReturn.get("quantity"))).isEqualByComparingTo("80");
        assertThat(dec(supplierReturn.get("clearing_amount"))).isEqualByComparingTo("40000");
        assertThat(dec(row("SELECT returned FROM receipts WHERE tenant_id=? AND id=?", t, purchased[3])
                        .get("returned")))
                .isEqualByComparingTo("80");
        assertThat(dec(row("SELECT delta FROM stock_ledger WHERE tenant_id=? AND reason='SUPPLIER_RETURN'", t)
                        .get("delta")))
                .isEqualByComparingTo("-80");
        assertThat(row("SELECT * FROM ncr_dispositions WHERE tenant_id=? AND id=?", t, d))
                .containsEntry("supplier_return_id", supplierReturn.get("id"))
                .containsEntry("reference", supplierReturn.get("number"));
        // The rest of the lot is released again; GRNI was reversed for the uninvoiced return.
        assertThat(lotStatus(purchased[1])).isEqualTo("RELEASED");
        assertThat(dec(lotRow(purchased[1]).get("quantity"))).isEqualByComparingTo("120");
        assertThat(balance("GRNI", supplierReturn.get("number").toString())).isEqualByComparingTo("40000");
        assertThat(tenantJournalImbalance()).isZero();
        // Only purchased lots can go back to a supplier.
        var rejected = rejectedBottles("5");
        assertThatThrownBy(() -> ncrs.disposition(qa, rejected[2], disposition("RETURN_TO_SUPPLIER", "5"), id()))
                .hasMessageContaining("Only purchased lots");
    }

    @Test
    void aFailedIncomingInspectionKeepsItsAutomaticReturnAndRecordsItOnTheNcr() {
        var purchased = purchasedResin("50");
        var result = quality.inspect(qa, purchased[2], new QualityCommands.Inspect("FAILED", "Wet resin"), id());
        assertThat(result).containsEntry("status", "FAILED");
        // Phase 1 behaviour: the whole lot went back to the supplier.
        assertThat(dec(lotRow(purchased[1]).get("quantity"))).isZero();
        assertThat(count("SELECT COUNT(*) FROM stock_ledger WHERE tenant_id=? AND reason='SUPPLIER_RETURN_QC'", t))
                .isEqualTo(1);
        var ncr = ncr(str(result, "ncrId"));
        assertThat(ncr).containsEntry("status", "DISPOSITIONED").containsEntry("supplier_id", supplier);
        var d = row("SELECT * FROM ncr_dispositions WHERE tenant_id=? AND ncr_id=?", t, ncr.get("id"));
        assertThat(d)
                .containsEntry("type", "RETURN_TO_SUPPLIER")
                .containsEntry("status", "POSTED")
                .containsEntry("automatic", true)
                .containsEntry("receipt_id", purchased[3]);
        assertThat(dec(d.get("value_amount"))).isEqualByComparingTo("25000");
    }

    @Test
    void reworkConsumesTheHeldLotAndOutputsANewLotCarryingInputPlusReworkCost() throws Exception {
        var rejected = rejectedBottles("100");
        String labels = lot(label, raw, "RELEASED", "OPENING", "500", "2");
        var opened = json(command(qa, "/api/quality/ncrs/" + rejected[2] + "/dispositions", disposition("REWORK", "60"))
                .andExpect(status().isOk()));
        String rw = str(opened, "reworkOrderId");
        String number = str(opened, "reworkNumber");
        assertThat(opened).containsEntry("status", "POSTED").containsEntry("ncrStatus", "OPEN");
        assertThat(dec(opened.get("value"))).isEqualByComparingTo("600");
        assertThat(dec(lotRow(rejected[0]).get("quantity"))).isEqualByComparingTo("40");
        assertThat(row("SELECT * FROM stock_ledger WHERE tenant_id=? AND reason='REWORK_ISSUE'", t))
                .containsEntry("reference", number);
        assertThat(balance("REWORK_WIP", number)).isEqualByComparingTo("600");

        var complete = new NcrCommands.ReworkComplete(
                bd("58"), bd("120"), List.of(new NcrCommands.Material(labels, bd("60"))), "Relabelled");
        assertThatThrownBy(() -> rework.complete(viewer, rw, complete, id())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> rework.complete(
                        operator, rw, new NcrCommands.ReworkComplete(bd("61"), bd("0"), null, null), id()))
                .hasMessageContaining("cannot exceed");
        String held = lot(label, raw, "QUARANTINE", "OPENING", "50", "2");
        assertThatThrownBy(() -> rework.complete(
                        operator,
                        rw,
                        new NcrCommands.ReworkComplete(
                                bd("58"), bd("0"), List.of(new NcrCommands.Material(held, bd("5"))), null),
                        id()))
                .hasMessageContaining("quality-released stock");

        var done = json(command(operator, "/api/quality/rework/" + rw + "/complete", complete)
                .andExpect(status().isOk()));
        String output = str(done, "outputLotId");
        var out = lotRow(output);
        assertThat(out)
                .containsEntry("status", "QUARANTINE")
                .containsEntry("source_type", "REWORK")
                .containsEntry("source_id", rw)
                .containsEntry("number", number);
        // Value conservation: 600 input + 120 labels + 120 labour.
        assertThat(dec(out.get("quantity"))).isEqualByComparingTo("58");
        assertThat(dec(out.get("value_amount"))).isEqualByComparingTo("840");
        assertThat(dec(done.get("outputValue"))).isEqualByComparingTo("840");
        assertThat(balance("REWORK_WIP", number)).isZero();
        assertThat(balance("CONVERSION_CLEARING", number)).isEqualByComparingTo("-120");
        assertThat(balance("FG_INVENTORY", number)).isEqualByComparingTo("240");
        assertThat(tenantJournalImbalance()).isZero();
        var order = row("SELECT * FROM rework_orders WHERE tenant_id=? AND id=?", t, rw);
        assertThat(order).containsEntry("status", "COMPLETED").containsEntry("completed_by", operator.id());
        assertThat(dec(order.get("material_cost"))).isEqualByComparingTo("120");
        // Genealogy: held input lot and material lot → output lot.
        var links = rows(
                "SELECT * FROM rework_lot_links WHERE tenant_id=? AND rework_order_id=? ORDER BY role DESC", t, rw);
        assertThat(links).hasSize(2);
        assertThat(links.get(0))
                .containsEntry("role", "REWORKED")
                .containsEntry("input_lot_id", rejected[0])
                .containsEntry("output_lot_id", output);
        assertThat(dec(links.get(0).get("value_amount"))).isEqualByComparingTo("600");
        assertThat(links.get(1)).containsEntry("role", "MATERIAL").containsEntry("input_lot_id", labels);
        assertThatThrownBy(() -> update("DELETE FROM rework_lot_links WHERE tenant_id=?", t))
                .hasMessageContaining("immutable");
        assertThat(rows(
                                "SELECT reason FROM stock_ledger WHERE tenant_id=? AND reference=? ORDER BY created_at",
                                t,
                                number)
                        .stream()
                        .map(r -> r.get("reason"))
                        .toList())
                .containsExactlyInAnyOrder("REWORK_ISSUE", "REWORK_MATERIAL", "REWORK_OUTPUT");
        // Completing twice is refused; the reworked lot is released by its own inspection.
        assertThatThrownBy(() -> rework.complete(operator, rw, complete, id()))
                .hasMessageContaining("already completed");
        var inspection = row("SELECT * FROM inspections WHERE tenant_id=? AND lot_id=?", t, output);
        assertThat(inspection).containsEntry("type", "REWORK").containsEntry("status", "PENDING");
        quality.inspect(qa, str(inspection, "id"), new QualityCommands.Inspect("PASSED", "Re-inspected"), id());
        assertThat(lotStatus(output)).isEqualTo("RELEASED");

        var lotQuality = json(query(viewer, "/api/quality/lots/" + output).andExpect(status().isOk()));
        assertThat(((Map<?, ?>) lotQuality.get("reworkOrigin")).get("number")).isEqualTo(number);
        var inputQuality =
                json(query(viewer, "/api/quality/lots/" + rejected[0]).andExpect(status().isOk()));
        assertThat((List<?>) inputQuality.get("ncrs")).hasSize(1);
        assertThat((List<?>) inputQuality.get("reworkedInto")).hasSize(1);
        var detail = json(query(viewer, "/api/quality/rework/" + rw).andExpect(status().isOk()));
        assertThat((List<?>) detail.get("links")).hasSize(2);

        // The NCR can close only after rework is complete and everything is dispositioned.
        assertThatThrownBy(() -> ncrs.close(qa, rejected[2], new NcrCommands.Close("done"), id()))
                .hasMessageContaining("Disposition the whole affected quantity");
        ncrs.disposition(qa, rejected[2], disposition("SCRAP", "40"), id());
        ncrs.close(qa, rejected[2], new NcrCommands.Close("done"), id());

        var summary = json(query(viewer, "/api/quality/summary").andExpect(status().isOk()));
        assertThat(dec(summary.get("scrapValue"))).isEqualByComparingTo("400");
        assertThat(dec(summary.get("reworkCost"))).isEqualByComparingTo("240");
        assertThat((List<?>) summary.get("topDefects")).isNotEmpty();
    }

    @Test
    void anNcrWithOpenReworkCannotCloseAndPostedDispositionsCannotBeCancelled() {
        var rejected = rejectedBottles("10");
        String rw = str(ncrs.disposition(qa, rejected[2], disposition("REWORK", "10"), id()), "reworkOrderId");
        assertThat(ncr(rejected[2])).containsEntry("status", "DISPOSITIONED");
        assertThatThrownBy(() -> ncrs.close(qa, rejected[2], new NcrCommands.Close("x"), id()))
                .hasMessageContaining("Complete rework order");
        rework.complete(qa, rw, new NcrCommands.ReworkComplete(bd("10"), bd("0"), null, null), id());
        ncrs.close(qa, rejected[2], new NcrCommands.Close("Reworked"), id());

        var second = rejectedBottles("10");
        ncrs.disposition(qa, second[2], disposition("SCRAP", "2"), id());
        assertThatThrownBy(() -> ncrs.cancel(qa, second[2], new NcrCommands.Cancel("x"), id()))
                .hasMessageContaining("Dispositions were recorded");
    }

    @Test
    void raisingRulesCancelAndNcrsWithoutStock() {
        // A lot awaiting its inspection is not raised against manually.
        var held = heldBottles("10", "FINAL");
        assertThatThrownBy(() -> ncrs.raise(qa, raise(held[0], "1"), id()))
                .hasMessageContaining("awaiting its inspection");
        // One open NCR per lot, quantity within the lot.
        String released = lot(label, raw, "RELEASED", "OPENING", "100", "2");
        assertThatThrownBy(() -> ncrs.raise(qa, raise(released, "101"), id())).hasMessageContaining("holds only 100");
        String ncr = str(ncrs.raise(qa, raise(released, "5"), id()), "id");
        assertThatThrownBy(() -> ncrs.raise(qa, raise(released, "1"), id())).hasMessageContaining("already has open");
        // Editing while open.
        ncrs.update(
                qa, ncr, new NcrCommands.Update("CRITICAL", "PACK", "Wrong labels", "Mixed batch", "Segregated"), id());
        assertThat(ncr(ncr)).containsEntry("severity", "CRITICAL").containsEntry("defect_code", "PACK");
        // Cancelling lifts the containment hold.
        ncrs.cancel(qa, ncr, new NcrCommands.Cancel("Raised against the wrong lot"), id());
        assertThat(ncr(ncr)).containsEntry("status", "CANCELLED");
        assertThat(lotStatus(released)).isEqualTo("RELEASED");
        // A cancelled automatic NCR leaves the rejected lot rejected.
        var rejected = rejectedBottles("3");
        ncrs.cancel(qa, rejected[2], new NcrCommands.Cancel("Duplicate"), id());
        assertThat(lotStatus(rejected[0])).isEqualTo("REJECTED");
        // Without a lot there is nothing to disposition: it closes directly.
        String audit = str(
                ncrs.raise(
                        qa,
                        new NcrCommands.Raise(
                                "AUDIT",
                                "MINOR",
                                "DOC",
                                "Calibration record missing",
                                "Gauge G-12 record missing",
                                null,
                                null,
                                null,
                                BigDecimal.ZERO,
                                null,
                                null),
                        id()),
                "id");
        assertThatThrownBy(() -> ncrs.disposition(qa, audit, disposition("SCRAP", "1"), id()))
                .hasMessageContaining("no lot");
        ncrs.close(qa, audit, new NcrCommands.Close("Record restored"), id());
        assertThat(ncr(audit)).containsEntry("status", "CLOSED");
        // Inactive or unknown defect codes are refused.
        update("UPDATE defect_codes SET active=false WHERE tenant_id=? AND code='VIS'", t);
        assertThatThrownBy(() -> ncrs.raise(qa, raise(released, "1"), id())).hasMessageContaining("inactive");
    }

    @Test
    void dispositioningAFailedFinalLotLetsItsManufacturingOrderComplete() {
        String machine = id();
        String bom = id();
        String mo = id();
        update(
                "INSERT INTO machines(id,tenant_id,factory_id,code,name,kind,status,hourly_rate) VALUES(?,?,(SELECT factory_id FROM warehouses WHERE id=?),'M1','Line','Blow','RUNNING',0)",
                machine,
                t,
                finished);
        update(
                "INSERT INTO boms(id,tenant_id,product_id,revision,status,output_qty) VALUES(?,?,?,'R1','DRAFT',1)",
                bom,
                t,
                bottle);
        update(
                "INSERT INTO manufacturing_orders(id,tenant_id,number,product_id,bom_id,bom_revision,machine_id,factory_id,quantity,good_qty,status,priority,due_date,created_by) VALUES(?,?,'MO-Q1',?,?,'R1',?,(SELECT factory_id FROM warehouses WHERE id=?),20,20,'QUALITY_REVIEW','NORMAL',CURRENT_DATE,?)",
                mo,
                t,
                bottle,
                bom,
                machine,
                finished,
                planner.id());
        String lot = lot(bottle, finished, "QUARANTINE", "MANUFACTURING", "20", "10");
        update("UPDATE lots SET source_id=? WHERE tenant_id=? AND id=?", mo, t, lot);
        String inspection = id();
        update("INSERT INTO inspections(id,tenant_id,lot_id,type) VALUES(?,?,?,'FINAL')", inspection, t, lot);
        var failed = quality.inspect(qa, inspection, new QualityCommands.Inspect("FAILED", "Flash"), id());
        String ncr = str(failed, "ncrId");
        assertThat(ncr(ncr)).containsEntry("manufacturing_order_id", mo);
        assertThat(str(row("SELECT status FROM manufacturing_orders WHERE tenant_id=? AND id=?", t, mo), "status"))
                .isEqualTo("QUALITY_REVIEW");
        ncrs.disposition(qa, ncr, disposition("SCRAP", "20"), id());
        assertThat(str(row("SELECT status FROM manufacturing_orders WHERE tenant_id=? AND id=?", t, mo), "status"))
                .isEqualTo("COMPLETED");
    }

    @Test
    void ncrCommandsAreIdempotentAndTenantScoped() throws Exception {
        var rejected = rejectedBottles("10");
        String key = id();
        var body = disposition("SCRAP", "4");
        var first = json(command(qa, "/api/quality/ncrs/" + rejected[2] + "/dispositions", body, key)
                .andExpect(status().isOk()));
        var replay = json(command(qa, "/api/quality/ncrs/" + rejected[2] + "/dispositions", body, key)
                .andExpect(status().isOk()));
        assertThat(replay.get("id")).isEqualTo(first.get("id"));
        assertThat(count("SELECT COUNT(*) FROM stock_ledger WHERE tenant_id=? AND reason='QUALITY_SCRAP'", t))
                .isEqualTo(1);
        command(qa, "/api/quality/ncrs/" + rejected[2] + "/dispositions", disposition("SCRAP", "5"), key)
                .andExpect(status().isConflict());
        // Roles.
        command(planner, "/api/quality/ncrs/" + rejected[2] + "/dispositions", disposition("SCRAP", "1"))
                .andExpect(status().isForbidden());
        command(warehouse, "/api/quality/ncrs", raise(null, "0")).andExpect(status().isForbidden());
        query(warehouse, "/api/quality/ncrs").andExpect(status().isOk());
        // Lists and filters.
        var page = json(query(viewer, "/api/quality/ncrs?status=OPEN&severity=MAJOR&defect=INSP&item=" + bottle));
        assertThat((List<?>) page.get("items")).hasSize(1);
        assertThat(((Map<?, ?>) page.get("counts")).get("OPEN_MAJOR")).isEqualTo(1);
        assertThat((List<?>)
                        json(query(viewer, "/api/quality/ncrs?source=AUDIT")).get("items"))
                .isEmpty();
        query(viewer, "/api/quality/ncrs?severity=HUGE").andExpect(status().isConflict());
        var detail = json(query(viewer, "/api/quality/ncrs/" + rejected[2]).andExpect(status().isOk()));
        assertThat((List<?>) detail.get("dispositions")).hasSize(1);
        assertThat((List<?>) detail.get("activity")).isNotEmpty();
        // Another tenant sees nothing and cannot act.
        var stranger = createTenant("Other NCR tenant");
        var outsider = createUser(stranger.id(), "QUALITY");
        query(outsider, "/api/quality/ncrs/" + rejected[2]).andExpect(status().isNotFound());
        command(outsider, "/api/quality/ncrs/" + rejected[2] + "/dispositions", disposition("SCRAP", "1"))
                .andExpect(status().isNotFound());
        command(outsider, "/api/quality/ncrs", raise(rejected[0], "1")).andExpect(status().isNotFound());
        assertThat((List<?>) json(query(outsider, "/api/quality/ncrs")).get("items"))
                .isEmpty();
        // State attention counts.
        var state = json(query(viewer, "/api/state"));
        assertThat(((Number) ((Map<?, ?>) state.get("qualityAlerts")).get("open_ncrs")).intValue())
                .isEqualTo(1);
    }
}
