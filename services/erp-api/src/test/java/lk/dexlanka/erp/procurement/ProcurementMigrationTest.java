package lk.dexlanka.erp.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import lk.dexlanka.erp.IntegrationTestBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V6 migrates existing single-item purchase orders: a private schema is migrated to V5, legacy rows (an order awaiting
 * approval, a partially received and partly invoiced order, an order whose only receipt failed QC) are inserted by a
 * fixture migration (V5_1, so the earlier triggers apply), and then V6 runs.
 */
class ProcurementMigrationTest extends IntegrationTestBase {
    private static final String LEGACY = """
            INSERT INTO tenants(id,name) VALUES('t1','Legacy');
            INSERT INTO users(id,tenant_id,email,name,password_hash,role) VALUES('u1','t1','legacy-po-%1$s@example.test','Legacy','x','ADMIN');
            INSERT INTO factories(id,tenant_id,name,location) VALUES('f1','t1','Plant','Here');
            INSERT INTO warehouses(id,tenant_id,factory_id,name,kind) VALUES('w1','t1','f1','Raw','RAW');
            INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost) VALUES('rm','t1','RM','Resin','RAW_MATERIAL','kg',10);
            INSERT INTO partners(id,tenant_id,name,type) VALUES('s1','t1','Supplier','SUPPLIER');
            INSERT INTO purchase_orders(id,tenant_id,number,supplier_id,item_id,quantity,unit_cost,received,due_date,status,created_by)
                VALUES('po1','t1','PO-OLD-1','s1','rm',50,12.5,0,'2026-02-01','DRAFT','u1'),
                      ('po2','t1','PO-OLD-2','s1','rm',100,10,40,'2026-02-03','PARTIAL','u1'),
                      ('po3','t1','PO-OLD-3','s1','rm',20,10,0,'2026-02-05','PARTIAL','u1');
            INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost)
                VALUES('l1','t1','SUP-1','rm','w1','RELEASED','PURCHASE','po2',10),
                      ('l2','t1','SUP-2','rm','w1','QUARANTINE','PURCHASE','po2',10),
                      ('l3','t1','SUP-3','rm','w1','REJECTED','PURCHASE','po3',10);
            INSERT INTO stock_ledger(id,tenant_id,lot_id,item_id,warehouse_id,uom,delta,value_delta,unit_cost,reference,reason,actor)
                VALUES('e1','t1','l1','rm','w1','kg',25,250,10,'PO-OLD-2','GOODS_RECEIPT','Legacy'),
                      ('e2','t1','l2','rm','w1','kg',15,150,10,'PO-OLD-2','GOODS_RECEIPT','Legacy'),
                      ('e3','t1','l3','rm','w1','kg',20,200,10,'PO-OLD-3','GOODS_RECEIPT','Legacy'),
                      ('e4','t1','l3','rm','w1','kg',-20,-200,10,'po3','SUPPLIER_RETURN_QC','Legacy');
            INSERT INTO receipts(id,tenant_id,purchase_order_id,lot_id,quantity,invoice_reference)
                VALUES('r1','t1','po2','l1',25,'INV-OLD'),('r2','t1','po2','l2',15,NULL),('r3','t1','po3','l3',20,NULL);
            INSERT INTO journal_entries(id,tenant_id,reference,description,actor,created_at)
                VALUES('j1','t1','INV-OLD','Three-way matched supplier invoice for PO-OLD-2','Legacy','2026-02-10 10:00:00+05:30');
            INSERT INTO journal_lines(id,tenant_id,entry_id,account,debit,credit)
                VALUES('jl1','t1','j1','GRNI',250,0),('jl2','t1','j1','ACCOUNTS_PAYABLE',0,250);
            """;

    @Test
    void existingSingleItemOrdersBecomeAHeaderWithOneLine() throws Exception {
        String schema = "procurement_v6_" + id().replace("-", "");
        var folder = Files.createTempDirectory("dexlanka-v6-migration-");
        try {
            Files.writeString(folder.resolve("V5_1__legacy_purchases.sql"), LEGACY.formatted(schema));
            Flyway.configure()
                    .dataSource(s.db().getDataSource())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration", "filesystem:" + folder)
                    .target("6")
                    .load()
                    .migrate();
            String q = schema + ".";

            var line = row("SELECT * FROM " + q + "purchase_order_lines WHERE purchase_order_id='po2'");
            assertThat(line.get("line_no")).isEqualTo(1);
            assertThat(line).containsEntry("item_id", "rm").containsEntry("unit", "kg");
            assertThat(dec(line.get("quantity"))).isEqualByComparingTo("100");
            assertThat(dec(line.get("factor"))).isEqualByComparingTo("1");
            assertThat(dec(line.get("base_quantity"))).isEqualByComparingTo("100");
            assertThat(dec(line.get("unit_cost"))).isEqualByComparingTo("10");
            assertThat(dec(line.get("base_unit_cost"))).isEqualByComparingTo("10");
            assertThat(dec(line.get("line_total"))).isEqualByComparingTo("1000");
            assertThat(dec(line.get("received"))).isEqualByComparingTo("40");
            assertThat(dec(line.get("returned"))).isZero();
            assertThat(count("SELECT COUNT(*) FROM " + q + "purchase_order_lines"))
                    .isEqualTo(3);

            // DRAFT meant "awaiting approval": it is SUBMITTED now. Other statuses are unchanged.
            var draft = row("SELECT * FROM " + q + "purchase_orders WHERE id='po1'");
            assertThat(draft).containsEntry("status", "SUBMITTED").containsEntry("submitted_by", "u1");
            assertThat(dec(draft.get("total"))).isEqualByComparingTo("625");
            assertThat(draft).doesNotContainKeys("item_id", "quantity", "unit_cost", "received");
            var partial = row("SELECT * FROM " + q + "purchase_orders WHERE id='po2'");
            assertThat(partial).containsEntry("status", "PARTIAL").containsEntry("number", "PO-OLD-2");
            assertThat(dec(partial.get("total"))).isEqualByComparingTo("1000");

            // Receipts keep their lot and quantity and gain the line, their posted value and the invoiced quantity.
            var r1 = row("SELECT * FROM " + q + "receipts WHERE id='r1'");
            assertThat(r1.get("purchase_order_line_id")).isEqualTo(line.get("id"));
            assertThat(str(r1, "number")).startsWith("GRN-");
            assertThat(r1).containsEntry("lot_id", "l1").containsEntry("entered_unit", "kg");
            assertThat(dec(r1.get("value_amount"))).isEqualByComparingTo("250");
            assertThat(dec(r1.get("unit_cost"))).isEqualByComparingTo("10");
            assertThat(dec(r1.get("invoiced"))).isEqualByComparingTo("25");
            assertThat(dec(row("SELECT invoiced FROM " + q + "receipts WHERE id='r2'")
                            .get("invoiced")))
                    .isZero();
            assertThat(dec(row("SELECT value_amount FROM " + q + "receipts WHERE id='r3'")
                            .get("value_amount")))
                    .isEqualByComparingTo("200"); // the GOODS_RECEIPT row, not the QC reversal

            // The matched receipt became a supplier invoice dated by its journal entry.
            var invoice = row("SELECT * FROM " + q + "supplier_invoices WHERE purchase_order_id='po2'");
            assertThat(invoice)
                    .containsEntry("supplier_reference", "INV-OLD")
                    .containsEntry("status", "MATCHED")
                    .containsEntry("supplier_id", "s1");
            assertThat(dec(invoice.get("amount"))).isEqualByComparingTo("250");
            assertThat(dec(invoice.get("price_variance"))).isZero();
            assertThat(invoice.get("invoice_date").toString()).isEqualTo("2026-02-10");
            var invoiceLine =
                    row("SELECT * FROM " + q + "supplier_invoice_lines WHERE invoice_id=?", invoice.get("id"));
            assertThat(invoiceLine).containsEntry("receipt_id", "r1");
            assertThat(dec(invoiceLine.get("quantity"))).isEqualByComparingTo("25");
            assertThat(count("SELECT COUNT(*) FROM " + q + "supplier_invoices")).isEqualTo(1);

            // Open supply as MRP reads it: 60 kg on PO-OLD-2 plus the reopened 20 kg of PO-OLD-3.
            assertThat(decimal("SELECT SUM(l.base_quantity-l.received) FROM " + q + "purchase_order_lines l JOIN " + q
                            + "purchase_orders po ON po.tenant_id=l.tenant_id AND po.id=l.purchase_order_id"
                            + " WHERE po.status IN ('APPROVED','PARTIAL')"))
                    .isEqualByComparingTo("80");
            assertThat(count("SELECT COUNT(*) FROM " + q + "stock_ledger")).isEqualTo(4);
            assertThat(count("SELECT COUNT(*) FROM " + q + "journal_entries")).isEqualTo(1);

            // New statuses are allowed, unknown ones are not; a receipt cannot point at another order's line and only
            // drafts may lack a supplier.
            update("UPDATE " + q + "purchase_orders SET status='CLOSED' WHERE id='po3'");
            assertThatThrownBy(() -> update("UPDATE " + q + "purchase_orders SET status='SHIPPED' WHERE id='po3'"))
                    .hasMessageContaining("purchase_orders_status_check");
            String otherLine =
                    str(row("SELECT id FROM " + q + "purchase_order_lines WHERE purchase_order_id='po3'"), "id");
            assertThatThrownBy(() ->
                            update("UPDATE " + q + "receipts SET purchase_order_line_id=? WHERE id='r2'", otherLine))
                    .hasMessageContaining("receipts_purchase_order_line_fk");
            assertThatThrownBy(() -> update("UPDATE " + q + "purchase_orders SET supplier_id=NULL WHERE id='po1'"))
                    .hasMessageContaining("purchase_orders_supplier_required");
            assertThatThrownBy(() -> update("UPDATE " + q + "receipts SET invoiced=26 WHERE id='r1'"))
                    .hasMessageContaining("receipts_quantities_check");
        } finally {
            update("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            try (var files = Files.list(folder)) {
                for (var file : files.toList()) Files.delete(file);
            }
            Files.delete(folder);
        }
    }
}
