package lk.dexlanka.erp.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.quality.QualityCommands;
import lk.dexlanka.erp.quality.QualityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

class CustomerReturnsTest extends SalesFixture {
    @Autowired
    QualityService qc;

    String so, lot, delivery;

    /** 10 bottles at Rs. 25 less 10% (revenue 225) shipped from a lot costing Rs. 10 each (COGS 100). */
    @BeforeEach
    void delivered() {
        so = confirmed(line(bottle, "10", "25", "10"));
        lot = finishedLot(bottle, "10", "10");
        dispatch(so, 1, "DN-RET", lot, "10");
        delivery = str(row("SELECT id FROM deliveries WHERE tenant_id=? AND sales_order_id=?", t, so), "id");
    }

    Map<String, Object> giveBack(String quantity) {
        return returns.customerReturn(
                salesRep, delivery, new SalesCommands.Return(bd(quantity), "Damaged in transit"), id());
    }

    @Test
    void returnPostsQuarantineLotCompensatingJournalsAndInspection() throws Exception {
        long dispatchLines = count(
                "SELECT COUNT(*) FROM journal_lines l JOIN journal_entries e ON e.tenant_id=l.tenant_id AND e.id=l.entry_id WHERE l.tenant_id=? AND e.reference='DN-RET'",
                t);
        var result = json(command(
                        warehouse,
                        "/api/sales/deliveries/" + delivery + "/returns",
                        new SalesCommands.Return(bd("4"), "Two cartons crushed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_QC")));
        String number = str(result, "number");
        var newLot = row("SELECT * FROM lots WHERE tenant_id=? AND id=?", t, result.get("lotId"));
        assertThat(newLot.get("status")).isEqualTo("QUARANTINE");
        assertThat(newLot.get("warehouse_id")).isEqualTo(finished);
        assertThat(newLot.get("source_type")).isEqualTo("CUSTOMER_RETURN");
        assertThat(newLot.get("number")).isEqualTo(number);
        assertThat(dec(newLot.get("unit_cost"))).isEqualByComparingTo("10");
        assertThat(stock(str(result, "lotId"))).isEqualByComparingTo("4");
        assertThat(decimal(
                        "SELECT value_amount FROM stock_balances WHERE tenant_id=? AND lot_id=?",
                        t,
                        result.get("lotId")))
                .isEqualByComparingTo("40");
        var inspection = row("SELECT * FROM inspections WHERE tenant_id=? AND id=?", t, result.get("inspectionId"));
        assertThat(inspection.get("type")).isEqualTo("RETURN");
        assertThat(inspection.get("status")).isEqualTo("PENDING");

        // Compensating entries: revenue/receivable for 4 of 10 (90) and COGS into held stock (40).
        assertThat(account("SALES_REVENUE", number)).isEqualByComparingTo("90");
        assertThat(account("ACCOUNTS_RECEIVABLE", number)).isEqualByComparingTo("-90");
        assertThat(account("FG_INVENTORY", number)).isEqualByComparingTo("40");
        assertThat(account("COST_OF_GOODS_SOLD", number)).isEqualByComparingTo("-40");
        assertThat(account("SALES_REVENUE", null)).isEqualByComparingTo("-135");
        assertThat(journalImbalance()).isZero();
        // The original dispatch postings are untouched.
        assertThat(count(
                        "SELECT COUNT(*) FROM journal_lines l JOIN journal_entries e ON e.tenant_id=l.tenant_id AND e.id=l.entry_id WHERE l.tenant_id=? AND e.reference='DN-RET'",
                        t))
                .isEqualTo(dispatchLines);
        assertThat(dec(row("SELECT returned FROM deliveries WHERE tenant_id=? AND id=?", t, delivery)
                        .get("returned")))
                .isEqualByComparingTo("4");
        assertThat(dec(salesLine(so, 1).get("returned"))).isEqualByComparingTo("4");
        assertThat(dec(salesLine(so, 1).get("delivered"))).isEqualByComparingTo("10");
        var customerReturn = row("SELECT * FROM customer_returns WHERE tenant_id=? AND id=?", t, result.get("id"));
        assertThat(dec(customerReturn.get("credit_amount"))).isEqualByComparingTo("90");
        assertThat(dec(customerReturn.get("cost_amount"))).isEqualByComparingTo("40");
        assertThatThrownBy(() -> update("UPDATE customer_returns SET quantity=1 WHERE tenant_id=?", t))
                .hasMessageContaining("immutable");

        var detail = json(query(viewer, "/api/sales/orders/" + so));
        var listed = (Map<?, ?>) ((List<?>) detail.get("returns")).getFirst();
        assertThat(listed.get("status")).isEqualTo("AWAITING_QC");
        assertThat(listed.get("delivery_reference")).isEqualTo("DN-RET");
        assertThat(values(detail.get("activity"), "action")).contains("CUSTOMER_RETURN_POSTED");
    }

    @Test
    void returnsCannotExceedWhatIsStillOutAndTheRestReversesExactly() {
        giveBack("3");
        assertThatThrownBy(() -> giveBack("8")).hasMessageContaining("not yet returned (7 pcs)");
        assertThatThrownBy(() -> giveBack("0.5")).hasMessageContaining("whole numbers");
        giveBack("7");
        assertThat(decimal(
                        "SELECT SUM(credit_amount) FROM customer_returns WHERE tenant_id=? AND delivery_id=?",
                        t,
                        delivery))
                .isEqualByComparingTo(
                        dec(row("SELECT revenue_amount FROM deliveries WHERE tenant_id=? AND id=?", t, delivery)
                                .get("revenue_amount")));
        assertThat(decimal(
                        "SELECT SUM(cost_amount) FROM customer_returns WHERE tenant_id=? AND delivery_id=?",
                        t,
                        delivery))
                .isEqualByComparingTo("100");
        assertThatThrownBy(() -> giveBack("1")).hasMessageContaining("not yet returned");
        assertThat(account("SALES_REVENUE", null)).isZero();
        assertThat(account("COST_OF_GOODS_SOLD", null)).isZero();
        assertThat(journalImbalance()).isZero();
    }

    @Test
    void qualityPassReleasesReturnedStockForDispatch() throws Exception {
        var result = giveBack("4");
        qc.inspect(
                quality,
                str(result, "inspectionId"),
                new QualityCommands.Inspect("PASSED", "Cartons replaced; bottles intact"),
                id());
        assertThat(row("SELECT status FROM lots WHERE tenant_id=? AND id=?", t, result.get("lotId"))
                        .get("status"))
                .isEqualTo("RELEASED");
        String next = confirmed(line(bottle, "4", "25", null));
        dispatch(next, 1, "DN-AGAIN", str(result, "lotId"), "4");
        assertThat(stock(str(result, "lotId"))).isZero();
        var detail = json(query(viewer, "/api/sales/orders/" + so));
        assertThat(((Map<?, ?>) ((List<?>) detail.get("returns")).getFirst()).get("status"))
                .isEqualTo("RESTOCKED");
        assertThat(values(detail.get("activity"), "action")).contains("QC_PASSED");
        assertThat(journalImbalance()).isZero();
    }

    @Test
    void qualityFailRejectsTheReturnedLotAndKeepsItHeld() throws Exception {
        var result = giveBack("4");
        qc.inspect(quality, str(result, "inspectionId"), new QualityCommands.Inspect("FAILED", "Contaminated"), id());
        assertThat(row("SELECT status FROM lots WHERE tenant_id=? AND id=?", t, result.get("lotId"))
                        .get("status"))
                .isEqualTo("REJECTED");
        assertThat(decimal(
                        "SELECT value_amount FROM stock_balances WHERE tenant_id=? AND lot_id=?",
                        t,
                        result.get("lotId")))
                .isEqualByComparingTo("40");
        String next = confirmed(line(bottle, "4", "25", null));
        assertThatThrownBy(() -> dispatch(next, 1, "DN-NO", str(result, "lotId"), "4"))
                .hasMessageContaining("Quality-held or rejected");
        var detail = json(query(viewer, "/api/sales/orders/" + so));
        assertThat(((Map<?, ?>) ((List<?>) detail.get("returns")).getFirst()).get("status"))
                .isEqualTo("REJECTED");
    }

    @Test
    void returnsAreRoleCheckedTenantScopedIdempotentAndPeriodGuarded() throws Exception {
        assertThatThrownBy(
                        () -> returns.customerReturn(operator, delivery, new SalesCommands.Return(bd("1"), "x"), id()))
                .isInstanceOf(AccessDeniedException.class);
        var stranger = createUser(createTenant("Elsewhere").id(), "ADMIN");
        command(stranger, "/api/sales/deliveries/" + delivery + "/returns", new SalesCommands.Return(bd("1"), "x"))
                .andExpect(status().isNotFound());
        command(salesRep, "/api/sales/deliveries/" + delivery + "/returns", Map.of("quantity", "1", "reason", ""))
                .andExpect(status().isBadRequest());

        String key = id();
        var body = new SalesCommands.Return(bd("2"), "Wrong colour");
        var first = returns.customerReturn(salesRep, delivery, body, key);
        var again = returns.customerReturn(salesRep, delivery, body, key);
        assertThat(again.get("id")).isEqualTo(first.get("id"));
        assertThat(count("SELECT COUNT(*) FROM customer_returns WHERE tenant_id=?", t))
                .isEqualTo(1);
        assertThatThrownBy(() -> returns.customerReturn(
                        salesRep, delivery, new SalesCommands.Return(bd("3"), "Wrong colour"), key))
                .hasMessageContaining("different request");

        update("INSERT INTO financial_periods(tenant_id,end_date,closed) VALUES(?,CURRENT_DATE,true)", t);
        long lots = count("SELECT COUNT(*) FROM lots WHERE tenant_id=?", t);
        assertThatThrownBy(() -> giveBack("1")).hasMessageContaining("period is closed");
        assertThat(count("SELECT COUNT(*) FROM lots WHERE tenant_id=?", t)).isEqualTo(lots);
        assertThat(count("SELECT COUNT(*) FROM customer_returns WHERE tenant_id=?", t))
                .isEqualTo(1);
    }
}
