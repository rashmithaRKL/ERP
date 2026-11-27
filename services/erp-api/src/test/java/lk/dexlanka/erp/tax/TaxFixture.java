package lk.dexlanka.erp.tax;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.platform.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A tenant with administrator, sales, planner and viewer users, a finished good (bottle, pcs), a raw material (resin,
 * kg), a customer and a supplier. Helpers create tax codes through the real commands and record tax lines through
 * {@link TaxService#record} inside a transaction (as V26/V27 will).
 */
abstract class TaxFixture extends IntegrationTestBase {
    @Autowired
    TaxService tax;

    @Autowired
    TaxAdminService admin;

    @Autowired
    TaxQuery query;

    @Autowired
    PlatformTransactionManager transactions;

    String t, bottle, resin, customer, supplier;
    Actor adminUser, salesRep, planner, viewer;

    @BeforeEach
    void taxFixture() {
        var tenant = createTenant("Tax fixture");
        t = tenant.id();
        adminUser = createUser(t, "ADMIN");
        salesRep = createUser(t, "SALES");
        planner = createUser(t, "PLANNER");
        viewer = createUser(t, "VIEWER");
        bottle = id();
        resin = id();
        customer = id();
        supplier = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,'FG-B','Bottle','FINISHED_GOOD','pcs',10,0),(?,?,'RM','Resin','RAW_MATERIAL','kg',10,0)",
                bottle,
                t,
                resin,
                t);
        update(
                "INSERT INTO partners(id,tenant_id,name,type) VALUES(?,?,'Customer Co','CUSTOMER'),(?,?,'Supplier Co','SUPPLIER')",
                customer,
                t,
                supplier,
                t);
    }

    /** A standard code with one component per {@code "name:rate:from[:compound]"}. */
    String standardCode(String code, String appliesTo, String... components) {
        var list = Arrays.stream(components)
                .map(spec -> {
                    var p = spec.split(":");
                    return new TaxCommands.Component(p[0], p.length > 3, p[2], new BigDecimal(p[1]));
                })
                .toList();
        return str(
                admin.createCode(
                        adminUser,
                        new TaxCommands.Code(code, code + " tax", appliesTo, "STANDARD", null, null, list),
                        id()),
                "id");
    }

    String plainCode(String code, String kind) {
        return str(
                admin.createCode(
                        adminUser, new TaxCommands.Code(code, code + " tax", "BOTH", kind, null, null, null), id()),
                "id");
    }

    String component(String codeId, int seq) {
        return str(
                row("SELECT id FROM tax_components WHERE tenant_id=? AND tax_code_id=? AND seq=?", t, codeId, seq),
                "id");
    }

    void settings(boolean inclusive, String rounding, String sales, String purchase, String exempt) {
        admin.updateSettings(adminUser, new TaxCommands.Settings(inclusive, rounding, sales, purchase, exempt), id());
    }

    static TaxService.LineInput line(int no, String item, String qty, String price, String code) {
        return new TaxService.LineInput(no, item, bd(qty), bd(price), null, code);
    }

    TaxService.Result calculate(String direction, String date, String partner, TaxService.LineInput... lines) {
        return tax.calculate(
                adminUser, new TaxService.Document(direction, LocalDate.parse(date), partner, null, List.of(lines)));
    }

    /** Calculates and records a document in one transaction, like a posting command of V26/V27. */
    List<String> record(
            String type, String number, String direction, String date, String partner, TaxService.LineInput... lines) {
        return new TransactionTemplate(transactions).execute(status -> {
            var result = calculate(direction, date, partner, lines);
            return tax.record(adminUser, new TaxService.Source(type, id(), number, LocalDate.parse(date), result));
        });
    }

    static String key() {
        return "k-" + Long.toHexString(Double.doubleToLongBits(Math.random()));
    }

    static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.getFirst();
    }
}
