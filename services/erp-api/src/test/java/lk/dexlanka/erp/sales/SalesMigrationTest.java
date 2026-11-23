package lk.dexlanka.erp.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import lk.dexlanka.erp.IntegrationTestBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V3 migrates existing single-item sales orders: a private schema is migrated to V2, legacy rows are inserted by a
 * fixture migration (V2_1, so Flyway's search path and the V1 triggers apply), and then V3 runs.
 */
class SalesMigrationTest extends IntegrationTestBase {
    private static final String LEGACY = """
            INSERT INTO tenants(id,name) VALUES('t1','Legacy');
            INSERT INTO users(id,tenant_id,email,name,password_hash,role) VALUES('u1','t1','legacy-%1$s@example.test','Legacy','x','ADMIN');
            INSERT INTO factories(id,tenant_id,name,location) VALUES('f1','t1','Plant','Here');
            INSERT INTO warehouses(id,tenant_id,factory_id,name,kind) VALUES('w1','t1','f1','Finished','FINISHED');
            INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost) VALUES('fg','t1','FG','Bottle','FINISHED_GOOD','pcs',10);
            INSERT INTO partners(id,tenant_id,name,type) VALUES('c1','t1','Customer','CUSTOMER'),('s1','t1','Supplier','SUPPLIER');
            INSERT INTO machines(id,tenant_id,factory_id,code,name,kind,status,hourly_rate) VALUES('m1','t1','f1','M','M','M','IDLE',1);
            INSERT INTO boms(id,tenant_id,product_id,revision,status,output_qty) VALUES('b1','t1','fg','R1','DRAFT',1);
            INSERT INTO sales_orders(id,tenant_id,number,customer_id,product_id,quantity,unit_price,delivered,due_date,status)
                VALUES('so1','t1','SO-OLD-1','c1','fg',10,25.5,4,'2026-01-15','PARTIAL'),
                      ('so2','t1','SO-OLD-2','c1','fg',3,7,0,'2026-01-20','CONFIRMED');
            INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost)
                VALUES('l1','t1','FG-L1','fg','w1','RELEASED','OPENING','X',9.5);
            INSERT INTO stock_ledger(id,tenant_id,lot_id,item_id,warehouse_id,uom,delta,value_delta,unit_cost,reference,reason,actor)
                VALUES('e1','t1','l1','fg','w1','pcs',10,95,9.5,'OPEN','OPENING','Legacy');
            INSERT INTO stock_ledger(id,tenant_id,lot_id,item_id,warehouse_id,uom,delta,value_delta,unit_cost,reference,reason,actor)
                VALUES('e2','t1','l1','fg','w1','pcs',-4,-38.4,9.5,'DN-OLD','CUSTOMER_DISPATCH','Legacy');
            INSERT INTO deliveries(id,tenant_id,sales_order_id,lot_id,quantity,reference) VALUES('d1','t1','so1','l1',4,'DN-OLD');
            INSERT INTO manufacturing_orders(id,tenant_id,number,product_id,bom_id,bom_revision,sales_order_id,machine_id,factory_id,quantity,priority,due_date,created_by)
                VALUES('mo1','t1','MO-OLD','fg','b1','R1','so1','m1','f1',6,'NORMAL','2026-01-10','u1'),
                      ('mo2','t1','MO-STOCK','fg','b1','R1',NULL,'m1','f1',5,'NORMAL','2026-01-10','u1');
            """;

    @Test
    void existingSingleItemOrdersBecomeAHeaderWithOneLine() throws Exception {
        String schema = "sales_v3_" + id().replace("-", "");
        var folder = Files.createTempDirectory("dexlanka-v3-migration-");
        try {
            Files.writeString(folder.resolve("V2_1__legacy_sales.sql"), LEGACY.formatted(schema));
            Flyway.configure()
                    .dataSource(s.db().getDataSource())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration", "filesystem:" + folder)
                    .target("3")
                    .load()
                    .migrate();
            String q = schema + ".";

            var line = row("SELECT * FROM " + q + "sales_order_lines WHERE sales_order_id='so1'");
            assertThat(line.get("line_no")).isEqualTo(1);
            assertThat(line.get("product_id")).isEqualTo("fg");
            assertThat(dec(line.get("quantity"))).isEqualByComparingTo("10");
            assertThat(dec(line.get("unit_price"))).isEqualByComparingTo("25.5");
            assertThat(dec(line.get("discount_pct"))).isZero();
            assertThat(dec(line.get("delivered"))).isEqualByComparingTo("4");
            assertThat(dec(line.get("returned"))).isZero();
            assertThat(dec(line.get("line_total"))).isEqualByComparingTo("255");
            assertThat(count("SELECT COUNT(*) FROM " + q + "sales_order_lines")).isEqualTo(2);

            var header = row("SELECT * FROM " + q + "sales_orders WHERE id='so1'");
            assertThat(header.get("status")).isEqualTo("PARTIAL");
            assertThat(header.get("number")).isEqualTo("SO-OLD-1");
            assertThat(dec(header.get("subtotal"))).isEqualByComparingTo("255");
            assertThat(dec(header.get("total"))).isEqualByComparingTo("255");
            assertThat(header.get("confirmed_at")).isNotNull();
            assertThat(header).doesNotContainKeys("product_id", "quantity", "unit_price", "delivered");
            assertThat(dec(row("SELECT total FROM " + q + "sales_orders WHERE id='so2'")
                            .get("total")))
                    .isEqualByComparingTo("21");

            var delivery = row("SELECT * FROM " + q + "deliveries WHERE id='d1'");
            assertThat(delivery.get("sales_order_line_id")).isEqualTo(line.get("id"));
            assertThat(delivery.get("sales_order_id")).isEqualTo("so1");
            assertThat(dec(delivery.get("revenue_amount"))).isEqualByComparingTo("102");
            assertThat(dec(delivery.get("cost_amount"))).isEqualByComparingTo("38.4"); // the posted COGS, not 4 x 9.5
            assertThat(dec(delivery.get("returned"))).isZero();

            assertThat(row("SELECT sales_order_line_id FROM " + q + "manufacturing_orders WHERE id='mo1'")
                            .get("sales_order_line_id"))
                    .isEqualTo(line.get("id"));
            assertThat(row("SELECT sales_order_line_id FROM " + q + "manufacturing_orders WHERE id='mo2'")
                            .get("sales_order_line_id"))
                    .isNull();
            assertThat(count("SELECT COUNT(*) FROM " + q + "stock_ledger")).isEqualTo(2);

            // New statuses are allowed, unknown ones are not, and a line cannot point at another order.
            update("UPDATE " + q + "sales_orders SET status='CLOSED' WHERE id='so2'");
            assertThatThrownBy(() -> update("UPDATE " + q + "sales_orders SET status='SHIPPED' WHERE id='so2'"))
                    .hasMessageContaining("sales_orders_status_check");
            String otherLine = str(row("SELECT id FROM " + q + "sales_order_lines WHERE sales_order_id='so2'"), "id");
            assertThatThrownBy(() ->
                            update("UPDATE " + q + "deliveries SET sales_order_line_id=? WHERE id='d1'", otherLine))
                    .hasMessageContaining("deliveries_sales_order_line_fk");
        } finally {
            update("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            try (var files = Files.list(folder)) {
                for (var file : files.toList()) Files.delete(file);
            }
            Files.delete(folder);
        }
    }
}
