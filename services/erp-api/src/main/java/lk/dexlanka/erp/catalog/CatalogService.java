package lk.dexlanka.erp.catalog;

import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Organization, user accounts and master data: partners, items, work centers and BOM revisions. */
@Service
public class CatalogService {
    private final Store s;
    private final PasswordEncoder passwords;
    private final ItemService items;
    private final BomService bomRules;

    CatalogService(Store s, PasswordEncoder passwords, ItemService items, BomService bomRules) {
        this.s = s;
        this.passwords = passwords;
        this.items = items;
        this.bomRules = bomRules;
    }

    @Transactional
    public Map<String, Object> user(Actor a, CatalogCommands.User c, String key) {
        a.allow();
        return s.once(
                a,
                key,
                "user",
                Map.of(
                        "email",
                        c.email(),
                        "name",
                        c.name(),
                        "role",
                        c.role(),
                        "passwordFingerprint",
                        fingerprint(c.password())),
                () -> {
                    String id = id();
                    s.db().update(
                                    "INSERT INTO users(id,tenant_id,email,name,password_hash,role) VALUES(?,?,?,?,?,?)",
                                    id,
                                    a.tenant(),
                                    c.email().toLowerCase(Locale.ROOT),
                                    c.name(),
                                    passwords.encode(c.password()),
                                    c.role());
                    s.audit(a, "USER_CREATED", c.email(), "Role " + c.role());
                    return Map.of("id", id);
                });
    }

    String fingerprint(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public Map<String, Object> bom(Actor a, CatalogCommands.Bom c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "bom", c, () -> {
            // Finished goods and semi-finished intermediates (single level: a semi-finished BOM consumes materials).
            var product = s.one(
                    "SELECT id,active FROM items WHERE tenant_id=? AND id=? AND type IN ('FINISHED_GOOD','SEMI_FINISHED')",
                    a.tenant(),
                    c.productId());
            require(Boolean.TRUE.equals(product.get("active")), "This product is inactive; reactivate it first");
            s.quantity("pcs", c.outputQty());
            String id = id();
            s.db().update(
                            "INSERT INTO boms(id,tenant_id,product_id,revision,status,output_qty,yield_factor) VALUES(?,?,?,?,'DRAFT',?,?)",
                            id,
                            a.tenant(),
                            c.productId(),
                            c.revision(),
                            c.outputQty(),
                            c.yieldFactor());
            var seen = new HashSet<String>();
            for (var l : c.lines()) {
                require(seen.add(l.itemId()), "A material can appear only once per BOM");
                require(!l.itemId().equals(c.productId()), "A product cannot be a component of its own BOM");
                var material = s.one(
                        "SELECT * FROM items WHERE tenant_id=? AND id=? AND type<>'FINISHED_GOOD'",
                        a.tenant(),
                        l.itemId());
                require(
                        Boolean.TRUE.equals(material.get("active")),
                        "Material " + str(material, "sku") + " is inactive and cannot be used on new BOMs");
                s.quantity(str(material, "uom"), l.quantity());
                s.db().update(
                                "INSERT INTO bom_lines(id,tenant_id,bom_id,item_id,quantity) VALUES(?,?,?,?,?)",
                                id(),
                                a.tenant(),
                                id,
                                l.itemId(),
                                l.quantity());
            }
            s.audit(a, "BOM_DRAFT_CREATED", c.revision(), "New immutable-on-approval material formula");
            return Map.of("id", id, "status", "DRAFT");
        });
    }

    @Transactional
    public Map<String, Object> approveBom(Actor a, String id, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "approve-bom:" + id, Map.of(), () -> {
            var b = s.one("SELECT * FROM boms WHERE tenant_id=? AND id=?", a.tenant(), id);
            require(b.get("status").equals("DRAFT"), "Only draft BOM revisions can be approved");
            require(
                    !s.list("SELECT id FROM bom_lines WHERE tenant_id=? AND bom_id=?", a.tenant(), id)
                            .isEmpty(),
                    "A BOM requires at least one component");
            var inactive = s.list(
                    "SELECT i.sku FROM items i WHERE i.tenant_id=? AND i.active=false AND (i.id=? OR i.id IN (SELECT item_id FROM bom_lines WHERE tenant_id=? AND bom_id=?)) ORDER BY i.sku",
                    a.tenant(),
                    b.get("product_id"),
                    a.tenant(),
                    id);
            require(
                    inactive.isEmpty(),
                    "Inactive items cannot be approved into a BOM: "
                            + String.join(
                                    ", ",
                                    inactive.stream().map(r -> str(r, "sku")).toList()));
            bomRules.checkApprovable(a, b); // V7: sub-BOMs approved first, no cycles across levels
            s.db().update("UPDATE boms SET status='APPROVED' WHERE tenant_id=? AND id=?", a.tenant(), id);
            s.audit(a, "BOM_APPROVED", str(b, "revision"), "Material formula frozen for manufacturing");
            return Map.of("id", id, "status", "APPROVED");
        });
    }

    @Transactional
    public Map<String, Object> partner(Actor a, CatalogCommands.Partner c, String key) {
        a.allow("PLANNER", "SALES", "WAREHOUSE");
        if (a.role().equals("SALES")) require(c.type().equals("CUSTOMER"), "Sales role can create customers only");
        if (a.role().equals("WAREHOUSE"))
            require(c.type().equals("SUPPLIER"), "Warehouse role can create suppliers only");
        return s.once(a, key, "partner", c, () -> {
            require(c.creditLimit() == null || c.type().equals("CUSTOMER"), "Credit limits apply to customers only");
            String id = id();
            s.db().update(
                            "INSERT INTO partners(id,tenant_id,name,type,email,payment_days,phone,billing_address,shipping_address,tax_id,credit_limit) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                            id,
                            a.tenant(),
                            c.name().strip(),
                            c.type(),
                            blankToNull(c.email()),
                            c.paymentDays(),
                            blankToNull(c.phone()),
                            blankToNull(c.billingAddress()),
                            blankToNull(c.shippingAddress()),
                            blankToNull(c.taxId()),
                            c.creditLimit());
            s.audit(a, "PARTNER_CREATED", c.name().strip(), c.type());
            return Map.of("id", id);
        });
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    @Transactional
    public Map<String, Object> machine(Actor a, CatalogCommands.Machine c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "machine", c, () -> {
            String id = id();
            var plant = s.one("SELECT id FROM factories WHERE tenant_id=? ORDER BY id LIMIT 1", a.tenant());
            s.db().update(
                            "INSERT INTO machines(id,tenant_id,factory_id,code,name,kind,status,hourly_rate) VALUES(?,?,?,?,?,?,'IDLE',?)",
                            id,
                            a.tenant(),
                            plant.get("id"),
                            c.code(),
                            c.name(),
                            c.kind(),
                            c.hourlyRate());
            s.audit(a, "WORK_CENTER_CREATED", c.code(), c.name());
            return Map.of("id", id);
        });
    }

    @Transactional
    public Map<String, Object> organization(Actor a, CatalogCommands.Organization c, String key) {
        a.allow();
        return s.once(a, key, "organization", c, () -> {
            s.db().update("UPDATE tenants SET name=? WHERE id=?", c.name(), a.tenant());
            s.db().update(
                            "UPDATE factories SET name=?,location=? WHERE tenant_id=?",
                            c.factoryName(),
                            c.location(),
                            a.tenant());
            s.audit(a, "ORGANIZATION_UPDATED", c.name(), c.factoryName());
            return Map.of("status", "UPDATED");
        });
    }

    /** Item creation lives with the rest of the item master in {@link ItemService#create}. */
    @Transactional
    public Map<String, Object> item(Actor a, CatalogCommands.Item c, String key) {
        return items.create(a, c, key);
    }
}
