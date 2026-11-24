package lk.dexlanka.erp.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.manufacturing.ManufacturingCommands;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V9: sales-order lines in sales units, mirroring V6 purchase-order lines. The entered quantity and price stay on the
 * line with its unit and factor; the base quantity (exact, {@code UnitService.toBaseExact}) drives dispatch, delivered
 * quantities, manufacturing demand and planning; revenue per delivery is computed from the entered price.
 */
class SalesUnitsTest extends SalesFixture {
    String pellets;

    @BeforeEach
    void units() {
        // Bottles: a box of 12 is sold, a pallet only bought. Pellets: sold by the kg or the tonne (standard unit).
        pellets = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,'FG-P','Pellets','FINISHED_GOOD','kg',300,0)",
                pellets,
                t);
        update(
                "INSERT INTO item_unit_conversions(id,tenant_id,item_id,unit_code,factor,purchase,sales) VALUES(?,?,?,'box',12,false,true),(?,?,?,'pallet',600,true,false),(?,?,?,'drum',0.333333333333,false,true)",
                id(),
                t,
                bottle,
                id(),
                t,
                bottle,
                id(),
                t,
                pellets);
    }

    static SalesCommands.Line line(String product, String quantity, String unit, String price, String discount) {
        return new SalesCommands.Line(
                null, product, bd(quantity), bd(price), discount == null ? null : bd(discount), unit);
    }

    @Test
    void linesKeepTheEnteredUnitAndConvertExactlyToBaseQuantities() throws Exception {
        var created = json(command(
                        salesRep,
                        "/api/sales/orders",
                        order(
                                line(bottle, "2", "box", "300", "10"),
                                line(pellets, "0.0125", "t", "400000", null),
                                line(cap, "7", null, "2", null)))
                .andExpect(status().isOk()));
        String so = str(created, "id");

        var boxes = salesLine(so, 1);
        assertThat(boxes.get("unit")).isEqualTo("box");
        assertThat(dec(boxes.get("quantity"))).isEqualByComparingTo("2");
        assertThat(dec(boxes.get("factor"))).isEqualByComparingTo("12");
        assertThat(dec(boxes.get("base_quantity"))).isEqualByComparingTo("24");
        assertThat(dec(boxes.get("unit_price"))).isEqualByComparingTo("300");
        assertThat(dec(boxes.get("base_unit_price"))).isEqualByComparingTo("25");
        assertThat(dec(boxes.get("line_total"))).isEqualByComparingTo("540"); // 2 x 300 less 10 %

        var tonnes = salesLine(so, 2);
        assertThat(tonnes.get("unit")).isEqualTo("t");
        assertThat(dec(tonnes.get("base_quantity"))).isEqualByComparingTo("12.5");
        assertThat(dec(tonnes.get("base_unit_price"))).isEqualByComparingTo("400");
        assertThat(dec(tonnes.get("line_total"))).isEqualByComparingTo("5000");

        var base = salesLine(so, 3);
        assertThat(base.get("unit")).isEqualTo("pcs");
        assertThat(dec(base.get("factor"))).isEqualByComparingTo("1");
        assertThat(dec(base.get("base_quantity"))).isEqualByComparingTo("7");
        assertThat(dec(header(so).get("total"))).isEqualByComparingTo("5554");

        // The detail and the planning lines read base quantities.
        var detail = json(query(salesRep, "/api/sales/orders/" + so).andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var lines = (List<Map<String, Object>>) detail.get("lines");
        assertThat(lines.getFirst()).containsEntry("unit", "box").containsEntry("uom", "pcs");
        assertThat(dec(lines.getFirst().get("outstanding"))).isEqualByComparingTo("24");
        sales.confirm(salesRep, so, id());
        query(planner, "/api/state")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesLines[?(@.unit=='box')].quantity").value(24.0))
                .andExpect(jsonPath("$.salesLines[?(@.unit=='box')].entered_quantity")
                        .value(2.0));
    }

    @Test
    void onlySalesUnitsAndExactConversionsAreAccepted() throws Exception {
        command(salesRep, "/api/sales/orders", order(line(bottle, "1", "pallet", "10", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("SKU FG-B is not sold in pallet; enable the conversion for sales or use another unit"));
        command(salesRep, "/api/sales/orders", order(line(bottle, "1", "roll", "10", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("No conversion from roll to pcs is defined for SKU FG-B"));
        // 1 drum = 0.333333333333 kg cannot be expressed exactly at the kilogram's 6 decimal places.
        command(salesRep, "/api/sales/orders", order(line(pellets, "1", "drum", "10", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("1 drum is not a whole multiple of kg at 6 decimal places"));
        // Packages are whole units; grams convert exactly into kilograms.
        command(salesRep, "/api/sales/orders", order(line(bottle, "1.5", "box", "10", null)))
                .andExpect(status().isConflict());
        String so = draft(line(pellets, "1234.567", "g", "1", null));
        assertThat(dec(salesLine(so, 1).get("base_quantity"))).isEqualByComparingTo("1.234567");
        assertThat(dec(salesLine(so, 1).get("base_unit_price"))).isEqualByComparingTo("1000");
        assertThat(count("SELECT COUNT(*) FROM sales_orders WHERE tenant_id=?", t))
                .isEqualTo(1);
    }

    @Test
    void dispatchRevenueAndManufacturingDemandUseBaseQuantities() throws Exception {
        String so = confirmed(line(bottle, "2", "box", "100", null)); // 24 pcs, Rs. 200
        String lot = finishedLot(bottle, "30", "4");

        // Production planned against the line is limited to its 24 base pieces.
        String due = LocalDate.now().plusDays(3).toString();
        command(
                        planner,
                        "/api/manufacturing",
                        new ManufacturingCommands.Manufacture(
                                bom, machine, so, bd("25"), "NORMAL", due, lineId(so, 1), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Manufacturing quantity exceeds unplanned customer demand"));
        command(
                        planner,
                        "/api/manufacturing",
                        new ManufacturingCommands.Manufacture(
                                bom, machine, so, bd("24"), "NORMAL", due, lineId(so, 1), null))
                .andExpect(status().isOk());

        // 5 of 24 pieces at Rs. 100 per box of 12: Rs. 41.666667; the last delivery absorbs the residual.
        dispatch(so, 1, "DN-1", lot, "5");
        assertThat(decimal("SELECT revenue_amount FROM deliveries WHERE tenant_id=? AND reference='DN-1'", t))
                .isEqualByComparingTo("41.666667");
        assertThat(header(so).get("status")).isEqualTo("PARTIAL");
        assertThatThrownBy(() -> dispatch(so, 1, "DN-X", lot, "20"))
                .hasMessageContaining("Dispatch exceeds the outstanding customer quantity");
        dispatch(so, 1, "DN-2", lot, "19");
        assertThat(decimal("SELECT revenue_amount FROM deliveries WHERE tenant_id=? AND reference='DN-2'", t))
                .isEqualByComparingTo("158.333333");
        assertThat(dec(salesLine(so, 1).get("delivered"))).isEqualByComparingTo("24");
        assertThat(header(so).get("status")).isEqualTo("DELIVERED");
        assertThat(account("SALES_REVENUE", null)).isEqualByComparingTo("-200");
        assertThat(journalImbalance()).isZero();
    }

    @Test
    void editingADraftCanChangeTheUnit() throws Exception {
        String so = draft(line(bottle, "24", null, "25", null));
        var edited = order(new SalesCommands.Line(lineId(so, 1), bottle, bd("3"), bd("290"), null, "box"));
        command(salesRep, "/api/sales/orders/" + so, edited).andExpect(status().isOk());
        var l = salesLine(so, 1);
        assertThat(l.get("id")).isEqualTo(lineId(so, 1));
        assertThat(l.get("unit")).isEqualTo("box");
        assertThat(dec(l.get("base_quantity"))).isEqualByComparingTo("36");
        assertThat(dec(l.get("base_unit_price"))).isEqualByComparingTo("24.166667");
        assertThat(dec(header(so).get("total"))).isEqualByComparingTo("870");
    }

    @Test
    void migrationGivesExistingLinesTheirBaseUnit() throws Exception {
        String schema = "sales_v9_" + id().replace("-", "");
        var folder = Files.createTempDirectory("dexlanka-v9-migration-");
        try {
            Files.writeString(folder.resolve("V8_1__existing_sales.sql"), """
                    INSERT INTO tenants(id,name) VALUES('t1','Existing');
                    INSERT INTO users(id,tenant_id,email,name,password_hash,role)
                        VALUES('u1','t1','v9-%s@example.test','Owner','x','ADMIN');
                    INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost)
                        VALUES('fg','t1','FG','Bottle','FINISHED_GOOD','pcs',10),('fk','t1','FK','Flakes','FINISHED_GOOD','kg',4);
                    INSERT INTO partners(id,tenant_id,name,type) VALUES('c1','t1','Customer','CUSTOMER');
                    INSERT INTO sales_orders(id,tenant_id,number,customer_id,due_date,status,subtotal,total,created_by)
                        VALUES('so1','t1','SO-1','c1','2026-01-15','PARTIAL',282.5,282.5,'u1');
                    INSERT INTO sales_order_lines(id,tenant_id,sales_order_id,line_no,product_id,quantity,unit_price,discount_pct,delivered)
                        VALUES('l1','t1','so1',1,'fg',10,25.5,0,4),('l2','t1','so1',2,'fk',2.5,11,0,0);
                    """.formatted(schema));
            Flyway.configure()
                    .dataSource(s.db().getDataSource())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration", "filesystem:" + folder)
                    .target("9")
                    .load()
                    .migrate();
            String q = schema + ".";
            var pieces = row("SELECT * FROM " + q + "sales_order_lines WHERE id='l1'");
            assertThat(pieces.get("unit")).isEqualTo("pcs");
            assertThat(dec(pieces.get("factor"))).isEqualByComparingTo("1");
            assertThat(dec(pieces.get("quantity"))).isEqualByComparingTo("10");
            assertThat(dec(pieces.get("base_quantity"))).isEqualByComparingTo("10");
            assertThat(dec(pieces.get("base_unit_price"))).isEqualByComparingTo("25.5");
            assertThat(dec(pieces.get("line_total"))).isEqualByComparingTo("255");
            assertThat(dec(pieces.get("delivered"))).isEqualByComparingTo("4");
            var kilograms = row("SELECT * FROM " + q + "sales_order_lines WHERE id='l2'");
            assertThat(kilograms.get("unit")).isEqualTo("kg");
            assertThat(dec(kilograms.get("base_quantity"))).isEqualByComparingTo("2.5");

            // Delivered quantities are now bounded by the base quantity; units must exist for the tenant.
            assertThatThrownBy(() -> update("UPDATE " + q + "sales_order_lines SET delivered=11 WHERE id='l1'"))
                    .hasMessageContaining("sales_order_lines_delivered_check");
            assertThatThrownBy(() -> update("UPDATE " + q + "sales_order_lines SET unit='crate' WHERE id='l1'"))
                    .hasMessageContaining("sales_order_lines_unit_fkey");
        } finally {
            update("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            try (var files = Files.list(folder)) {
                for (var file : files.toList()) Files.delete(file);
            }
            Files.delete(folder);
        }
    }
}
