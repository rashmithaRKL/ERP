package lk.dexlanka.erp.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * CSV imports: dry-run validation with row-level errors and a preview, commit through the domain services, opening
 * stock postings (lots, ledger, balanced journal against OPENING_EQUITY, reconciliation), idempotency per file content,
 * limits, permissions and tenant isolation.
 */
class CsvImportTest extends AdminTestSupport {
    private Map<String, Object> validate(String kind, String fileName, String content) throws Exception {
        return json(command(
                        admin,
                        "/api/admin/imports/" + kind + "/validate",
                        new AdminCommands.ImportFile(fileName, content))
                .andExpect(status().isOk()));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    @Test
    void itemFileIsValidatedRowByRowThenImportedThroughTheItemService() throws Exception {
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost) VALUES(?,?,'RM-OLD','Existing','RAW_MATERIAL','kg',1)",
                id(),
                t);
        update("INSERT INTO item_categories(id,tenant_id,name) VALUES(?,?,'Resins')", id(), t);
        String broken = """
                SKU,Name,Type,UOM,Standard cost,Reorder level,Category
                RM-1,PP resin,RAW_MATERIAL,kg,410,100,resins
                RM-2,Bad type,METAL,kg,1,0,
                RM-1,Duplicate,RAW_MATERIAL,kg,1,0,
                RM-3,Unknown unit,RAW_MATERIAL,furlong,1,0,
                RM-4,Negative,RAW_MATERIAL,kg,-5,0,
                RM-OLD,Existing,RAW_MATERIAL,kg,1,0,
                RM-5,No category,RAW_MATERIAL,kg,1,0,Metals
                FG-1,"Bottle, 1 L",FINISHED_GOOD,pcs,24.5,0,
                """;
        var dry = validate("items", "items.csv", broken);
        assertThat(dry.get("valid")).isEqualTo(false);
        assertThat(dry.get("rows")).isEqualTo(8);
        assertThat(dry.get("validRows")).isEqualTo(2);
        assertThat(list(dry, "errors"))
                .extracting(e -> e.get("row") + ":" + e.get("column"))
                .containsExactly("3:type", "4:sku", "5:null", "6:standard_cost", "7:null", "8:category");
        assertThat(list(dry, "errors").get(4).get("message").toString()).contains("RM-OLD");
        assertThat(list(dry, "preview")).extracting(p -> p.get("sku")).containsExactly("RM-1", "FG-1");
        assertThat(list(dry, "preview").get(1).get("name")).isEqualTo("Bottle, 1 L");
        // A dry run leaves no trace.
        assertThat(count("SELECT COUNT(*) FROM items WHERE tenant_id=?", t)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='ITEM_CREATED'", t))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id=?", t))
                .isZero();
        // Committing a file with errors imports nothing.
        command(admin, "/api/admin/imports/items/commit", new AdminCommands.ImportFile("items.csv", broken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Row 3: type — Use one of")));
        assertThat(count("SELECT COUNT(*) FROM items WHERE tenant_id=?", t)).isEqualTo(1);

        String good = """
                sku,name,type,uom,standard_cost,reorder_level,category,lead_time_days
                RM-1,PP resin,RAW_MATERIAL,kg,410,100,Resins,14
                FG-1,"Bottle, 1 L",FINISHED_GOOD,pcs,24.5,0,,
                """;
        var clean = validate("items", "items.csv", good);
        assertThat(clean.get("valid")).isEqualTo(true);
        assertThat(clean.get("alreadyImported")).isNull();
        String key = id();
        var batch = json(
                command(admin, "/api/admin/imports/items/commit", new AdminCommands.ImportFile("items.csv", good), key)
                        .andExpect(status().isOk()));
        assertThat(batch.get("duplicate")).isEqualTo(false);
        assertThat(batch.get("row_count")).isEqualTo(2);
        assertThat(batch.get("number").toString()).startsWith("IMP-");
        var item = row("SELECT * FROM items WHERE tenant_id=? AND sku='RM-1'", t);
        assertThat(item.get("name")).isEqualTo("PP resin");
        assertThat(dec(item.get("standard_cost"))).isEqualByComparingTo("410");
        assertThat(item.get("lead_time_days")).isEqualTo(14);
        assertThat(item.get("category_id")).isNotNull();
        assertThat(rows("SELECT action FROM audit_events WHERE tenant_id=?", t))
                .extracting(r -> r.get("action"))
                .containsExactlyInAnyOrder("ITEM_CREATED", "ITEM_CREATED", "IMPORT_COMMITTED");

        // Idempotent per file content: same key replays, a re-upload (even with a BOM and CRLF) creates nothing.
        command(admin, "/api/admin/imports/items/commit", new AdminCommands.ImportFile("items.csv", good), key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(batch.get("id")));
        String sameContent = "﻿" + good.replace("\n", "\r\n");
        assertThat(validate("items", "copy.csv", sameContent).get("alreadyImported"))
                .isNotNull();
        command(admin, "/api/admin/imports/items/commit", new AdminCommands.ImportFile("copy.csv", sameContent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.id").value(batch.get("id")));
        assertThat(count("SELECT COUNT(*) FROM items WHERE tenant_id=?", t)).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM import_batches WHERE tenant_id=?", t))
                .isEqualTo(1);
        command(admin, "/api/admin/imports/items/commit", new AdminCommands.ImportFile("other.csv", broken), key)
                .andExpect(status().isConflict());
    }

    @Test
    void partnerFileCreatesCustomersAndSuppliers() throws Exception {
        update("INSERT INTO partners(id,tenant_id,name,type) VALUES(?,?,'Existing Ltd','CUSTOMER')", id(), t);
        String file = """
                type,name,email,phone,payment_days,credit_limit,tax_id,billing_address
                customer,Ceylon Drinks,orders@ceylon.test,+94 11 1,30,250000,VAT-1,"1 Main St, Colombo"
                SUPPLIER,Lanka Polymers,not-an-email,,45,,,
                SUPPLIER,Resin House,,,45,1000,,
                CUSTOMER,Existing Ltd,,,30,,,
                CUSTOMER,Serendib Foods,,,400,,,
                """;
        var dry = validate("partners", "partners.csv", file);
        assertThat(list(dry, "errors"))
                .extracting(e -> e.get("row") + ":" + e.get("column"))
                .containsExactly("3:email", "4:credit_limit", "5:name", "6:payment_days");
        String good = """
                type,name,email,payment_days,credit_limit,billing_address
                customer,Ceylon Drinks,orders@ceylon.test,30,250000,"1 Main St, Colombo"
                SUPPLIER,Lanka Polymers,,45,,
                """;
        assertThat(validate("partners", "partners.csv", good).get("valid")).isEqualTo(true);
        command(admin, "/api/admin/imports/partners/commit", new AdminCommands.ImportFile("partners.csv", good))
                .andExpect(status().isOk());
        var customer = row("SELECT * FROM partners WHERE tenant_id=? AND name='Ceylon Drinks'", t);
        assertThat(customer.get("type")).isEqualTo("CUSTOMER");
        assertThat(dec(customer.get("credit_limit"))).isEqualByComparingTo("250000");
        assertThat(customer.get("billing_address")).isEqualTo("1 Main St, Colombo");
        assertThat(row("SELECT payment_days FROM partners WHERE tenant_id=? AND name='Lanka Polymers'", t)
                        .get("payment_days"))
                .isEqualTo(45);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='PARTNER_CREATED'", t))
                .isEqualTo(2);
    }

    @Test
    void openingStockPostsLotsLedgerAndABalancedReconciledJournal() throws Exception {
        String resin = id();
        String bottle = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost) VALUES(?,?,'RM-1','Resin','RAW_MATERIAL','kg',10),(?,?,'FG-1','Bottle','FINISHED_GOOD','pcs',5)",
                resin,
                t,
                bottle,
                t);
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,active) VALUES(?,?,'RM-X','Old','RAW_MATERIAL','kg',1,false)",
                id(),
                t);
        update(
                "INSERT INTO warehouses(id,tenant_id,factory_id,name,kind,code,active) VALUES(?,?,?,'Closed store','RAW','OLD',false)",
                id(),
                t,
                tenant.plant());
        update(
                "INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost) VALUES(?,?,'TAKEN',?,?,'RELEASED','OPENING','X',1)",
                id(),
                t,
                resin,
                tenant.rawWarehouse());
        String broken = """
                item_sku,warehouse_code,lot_number,quantity,unit_cost,quality_status
                NOPE,RM,,1,1,
                RM-X,RM,,1,1,
                RM-1,OLD,,1,1,
                FG-1,FG,,2.5,1,
                RM-1,RM,TAKEN,1,1,
                RM-1,RM,,0,1,
                RM-1,RM,,1,1,HOLD
                """;
        assertThat(list(validate("opening-stock", "stock.csv", broken), "errors"))
                .extracting(e -> e.get("row") + ":" + e.get("column"))
                .containsExactly(
                        "2:item_sku",
                        "3:item_sku",
                        "4:warehouse_code",
                        "5:quantity",
                        "6:lot_number",
                        "7:quantity",
                        "8:quality_status");

        String file = """
                item_sku,warehouse_code,lot_number,quantity,unit_cost,quality_status
                RM-1,rm,OPEN-A,100.5,10,
                FG-1,FG,,20,5,QUARANTINE
                """;
        var dry = validate("opening-stock", "stock.csv", file);
        assertThat(dry.get("valid")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) dry.get("summary");
        assertThat(dec(summary.get("totalValue"))).isEqualByComparingTo("1105");
        assertThat(summary.get("creditAccount")).isEqualTo("OPENING_EQUITY");
        assertThat(list(dry, "preview"))
                .extracting(p -> p.get("account"))
                .containsExactly("RAW_INVENTORY", "FG_INVENTORY");
        assertThat(count("SELECT COUNT(*) FROM lots WHERE tenant_id=? AND number='OPEN-A'", t))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM stock_ledger WHERE tenant_id=? AND reason='OPENING_STOCK_IMPORT'", t))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE tenant_id=?", t))
                .isZero();

        var batch = json(command(
                        admin,
                        "/api/admin/imports/opening-stock/commit",
                        new AdminCommands.ImportFile("stock.csv", file))
                .andExpect(status().isOk()));
        String number = batch.get("number").toString();
        assertThat(dec(batch.get("total_value"))).isEqualByComparingTo("1105");

        var lotA = row(
                "SELECT l.*,b.quantity,b.value_amount FROM lots l JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id WHERE l.tenant_id=? AND l.number='OPEN-A'",
                t);
        assertThat(lotA.get("status")).isEqualTo("RELEASED");
        assertThat(lotA.get("source_type")).isEqualTo("OPENING");
        assertThat(lotA.get("source_id")).isEqualTo(number);
        assertThat(dec(lotA.get("quantity"))).isEqualByComparingTo("100.5");
        assertThat(dec(lotA.get("value_amount"))).isEqualByComparingTo("1005");
        var held = row("SELECT * FROM lots WHERE tenant_id=? AND item_id=? AND source_id=?", t, bottle, number);
        assertThat(held.get("status")).isEqualTo("QUARANTINE");
        assertThat(held.get("number").toString()).startsWith("OPN-");
        assertThat(row("SELECT * FROM inspections WHERE tenant_id=? AND lot_id=?", t, held.get("id")))
                .containsEntry("type", "OPENING")
                .containsEntry("status", "PENDING");
        assertThat(count(
                        "SELECT COUNT(*) FROM stock_ledger WHERE tenant_id=? AND reference=? AND reason='OPENING_STOCK_IMPORT'",
                        t,
                        number))
                .isEqualTo(2);

        // One balanced entry per inventory account, credited to opening equity, reconciling with the stock value.
        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE tenant_id=? AND reference=?", t, number))
                .isEqualTo(2);
        assertThat(count(
                        "SELECT COUNT(*) FROM (SELECT entry_id FROM journal_lines WHERE tenant_id=? GROUP BY entry_id HAVING SUM(debit)<>SUM(credit)) x",
                        t))
                .isZero();
        assertThat(account(number, "RAW_INVENTORY")).isEqualByComparingTo("1005");
        assertThat(account(number, "FG_INVENTORY")).isEqualByComparingTo("100");
        assertThat(account(number, "OPENING_EQUITY")).isEqualByComparingTo("-1105");
        assertThat(decimal(
                        "SELECT SUM(b.value_amount) FROM stock_balances b JOIN lots l ON l.tenant_id=b.tenant_id AND l.id=b.lot_id WHERE l.tenant_id=? AND l.source_id=?",
                        t,
                        number))
                .isEqualByComparingTo(account(number, "RAW_INVENTORY").add(account(number, "FG_INVENTORY")));
        assertThat(row("SELECT detail FROM audit_events WHERE tenant_id=? AND action='IMPORT_COMMITTED'", t)
                        .get("detail"))
                .isEqualTo("Opening stock · 2 rows from stock.csv · Rs. 1105.00");

        // A closed period blocks the import like any other posting.
        update("INSERT INTO financial_periods(tenant_id,end_date,closed) VALUES(?,CURRENT_DATE,true)", t);
        String later = "item_sku,warehouse_code,quantity,unit_cost\nRM-1,RM,1,10\n";
        assertThat(list(validate("opening-stock", "later.csv", later), "errors"))
                .extracting(e -> e.get("message"))
                .containsExactly("The financial period is closed");
        command(admin, "/api/admin/imports/opening-stock/commit", new AdminCommands.ImportFile("later.csv", later))
                .andExpect(status().isConflict());
    }

    @Test
    void commitRevalidatesAndRollsBackEverythingWhenARowNoLongerFits() throws Exception {
        String file = "sku,name,type,uom,standard_cost\nRM-7,Seven,RAW_MATERIAL,kg,1\nRM-8,Eight,RAW_MATERIAL,kg,1\n";
        assertThat(validate("items", "items.csv", file).get("valid")).isEqualTo(true);
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost) VALUES(?,?,'RM-8','Meanwhile','RAW_MATERIAL','kg',1)",
                id(),
                t);
        command(admin, "/api/admin/imports/items/commit", new AdminCommands.ImportFile("items.csv", file))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Row 3")));
        assertThat(count("SELECT COUNT(*) FROM items WHERE tenant_id=? AND sku='RM-7'", t))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM import_batches WHERE tenant_id=?", t))
                .isZero();
    }

    @Test
    void fileLimitsColumnsTemplatesHistoryAndPermissions() throws Exception {
        var many = new StringBuilder("sku,name,type,uom,standard_cost\n");
        for (int i = 0; i <= ImportService.MAX_ROWS; i++)
            many.append("S").append(i).append(",N,RAW_MATERIAL,kg,1\n");
        assertThat(list(validate("items", "big.csv", many.toString()), "errors"))
                .extracting(e -> e.get("message"))
                .containsExactly("A file can contain at most 2000 data rows; split the file");
        assertThat(list(validate("items", "x.csv", "sku,name\nA,B\n"), "errors"))
                .extracting(e -> e.get("message"))
                .containsExactly("Missing required column(s): type, uom, standard_cost");
        assertThat(list(
                        validate(
                                "items",
                                "x.csv",
                                "sku,name,type,uom,standard_cost,colour\nA,B,RAW_MATERIAL,kg,1,red\n"),
                        "errors"))
                .extracting(e -> e.get("message").toString())
                .singleElement()
                .satisfies(m -> assertThat(m).startsWith("Unknown column(s): colour"));
        assertThat(list(validate("items", "x.csv", "sku,name,type,uom,standard_cost\n\"A,B\n"), "errors"))
                .extracting(e -> e.get("message"))
                .containsExactly("Row 2 has an opening quote without a closing quote");
        command(
                        admin,
                        "/api/admin/imports/items/validate",
                        new AdminCommands.ImportFile("x.csv", "x".repeat(AdminCommands.MAX_FILE_CHARS + 1)))
                .andExpect(status().isBadRequest());
        command(admin, "/api/admin/imports/boms/validate", new AdminCommands.ImportFile("x.csv", "a\n1"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/admin/imports/opening-stock/template").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("Content-Disposition", "attachment; filename=\"opening-stock-template.csv\""))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.containsString(
                                "item_sku,warehouse_code,lot_number,quantity,unit_cost,quality_status")));

        String file = "type,name\nCUSTOMER,History Ltd\n";
        command(admin, "/api/admin/imports/partners/commit", new AdminCommands.ImportFile("p.csv", file))
                .andExpect(status().isOk());
        var history = json(query(admin, "/api/admin/imports?limit=10"));
        assertThat(list(history, "items")).extracting(b -> b.get("file_name")).containsExactly("p.csv");
        assertThat(list(history, "items").getFirst().get("created_by")).isEqualTo("ADMIN");

        // Another tenant can import the same content: idempotency is per tenant.
        var other = createTenant("Other");
        var otherAdmin = createUser(other.id(), "ADMIN");
        command(otherAdmin, "/api/admin/imports/partners/commit", new AdminCommands.ImportFile("p.csv", file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));
        assertThat(list(json(query(otherAdmin, "/api/admin/imports")), "items")).hasSize(1);

        command(planner, "/api/admin/imports/items/validate", new AdminCommands.ImportFile("x.csv", file))
                .andExpect(status().isForbidden());
        command(warehouse, "/api/admin/imports/opening-stock/commit", new AdminCommands.ImportFile("x.csv", file))
                .andExpect(status().isForbidden());
        query(planner, "/api/admin/imports").andExpect(status().isForbidden());
        query(planner, "/api/admin/imports/items/template").andExpect(status().isForbidden());
    }

    private java.math.BigDecimal account(String reference, String account) {
        return decimal(
                "SELECT COALESCE(SUM(l.debit-l.credit),0) FROM journal_lines l JOIN journal_entries e ON e.tenant_id=l.tenant_id AND e.id=l.entry_id WHERE e.tenant_id=? AND e.reference=? AND l.account=?",
                t,
                reference,
                account);
    }
}
