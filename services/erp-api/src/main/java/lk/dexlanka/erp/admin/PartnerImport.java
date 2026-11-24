package lk.dexlanka.erp.admin;

import static lk.dexlanka.erp.admin.ImportKind.choice;
import static lk.dexlanka.erp.admin.ImportKind.decimal;
import static lk.dexlanka.erp.admin.ImportKind.integer;
import static lk.dexlanka.erp.admin.ImportKind.text;

import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.dexlanka.erp.catalog.CatalogCommands;
import lk.dexlanka.erp.catalog.CatalogService;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/**
 * Customers and suppliers through {@link CatalogService#partner} (the same rules, idempotency and audit as {@code POST
 * /api/partners}). A partner whose name already exists for the same type is a row error, so a re-import never creates
 * duplicates.
 */
@Component
class PartnerImport implements ImportKind {
    private static final Map<String, String> COLUMNS = Map.of(
            "name", "name",
            "type", "type",
            "email", "email",
            "paymentDays", "payment_days",
            "phone", "phone",
            "billingAddress", "billing_address",
            "shippingAddress", "shipping_address",
            "taxId", "tax_id",
            "creditLimit", "credit_limit");

    private final Store s;
    private final CatalogService catalog;
    private final Validator validator;

    PartnerImport(Store s, CatalogService catalog, Validator validator) {
        this.s = s;
        this.catalog = catalog;
        this.validator = validator;
    }

    @Override
    public String kind() {
        return "PARTNERS";
    }

    @Override
    public List<String> required() {
        return List.of("type", "name");
    }

    @Override
    public List<String> optional() {
        return List.of(
                "email", "phone", "payment_days", "credit_limit", "tax_id", "billing_address", "shipping_address");
    }

    @Override
    public String template() {
        return """
                type,name,email,phone,payment_days,credit_limit,tax_id,billing_address,shipping_address
                CUSTOMER,Example Beverages (Pvt) Ltd,orders@example-beverages.test,+94 11 200 0000,30,2500000,VAT-0001,"12 Example Road, Colombo 03","Warehouse 4, Example Estate, Kelaniya"
                SUPPLIER,Example Polymers Lanka,sales@example-polymers.test,+94 11 300 0000,45,,,"8 Sample Lane, Ja-Ela",
                """;
    }

    @Override
    public Map<String, Object> row(Context c, CsvFile.Row row) {
        var a = c.actor;
        String type = choice(row, "type", List.of("CUSTOMER", "SUPPLIER"), null);
        String name = text(row, "name", true, 160);
        String email = text(row, "email", false, 160);
        String phone = text(row, "phone", false, 40);
        Integer days = integer(row, "payment_days", 0, 365);
        BigDecimal credit = decimal(row, "credit_limit", false, BigDecimal.ZERO, false);
        String taxId = text(row, "tax_id", false, 40);
        String billing = text(row, "billing_address", false, 400);
        String shipping = text(row, "shipping_address", false, 400);
        if (credit != null && type.equals("SUPPLIER"))
            throw new RowError("credit_limit", "Credit limits apply to customers only");
        String label = type.equals("CUSTOMER") ? "customer" : "supplier";
        if (!c.seen.add(type + ":" + name.toLowerCase(Locale.ROOT)))
            throw new RowError("name", "The " + label + " " + name + " appears more than once in this file");
        if (!s.list(
                        "SELECT 1 FROM partners WHERE tenant_id=? AND type=? AND lower(name)=lower(?)",
                        a.tenant(),
                        type,
                        name)
                .isEmpty()) throw new RowError("name", "A " + label + " named " + name + " already exists");
        var command = new CatalogCommands.Partner(
                name,
                type,
                email.isEmpty() ? null : email,
                days == null ? 30 : days,
                phone.isEmpty() ? null : phone,
                billing.isEmpty() ? null : billing,
                shipping.isEmpty() ? null : shipping,
                taxId.isEmpty() ? null : taxId,
                credit);
        for (var v : validator.validate(command))
            throw new RowError(COLUMNS.get(v.getPropertyPath().toString()), v.getMessage());
        var created = catalog.partner(a, command, c.rowKey(row));
        var preview = new LinkedHashMap<String, Object>();
        preview.put("id", created.get("id"));
        preview.put("type", type);
        preview.put("name", name);
        preview.put("email", email.isEmpty() ? null : email);
        preview.put("payment_days", days == null ? 30 : days);
        preview.put("credit_limit", credit);
        return preview;
    }
}
