package lk.dexlanka.erp.catalog;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Partner maintenance: contact details, addresses, tax ID, payment terms, customer credit limit and active state.
 * Inactive partners cannot be used on new documents (enforced by the sales service and a database trigger on sales and
 * purchase orders); existing documents keep working.
 */
@Service
public class PartnerService {
    private final Store s;

    PartnerService(Store s) {
        this.s = s;
    }

    @Transactional
    public Map<String, Object> update(Actor a, String id, PartnerCommands.Update c, String key) {
        a.allow("PLANNER", "SALES", "WAREHOUSE");
        return s.once(a, key, "partner-update:" + id, c, () -> {
            var p = s.one("SELECT * FROM partners WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
            String type = str(p, "type");
            if (a.role().equals("SALES")) require(type.equals("CUSTOMER"), "Sales role can edit customers only");
            if (a.role().equals("WAREHOUSE"))
                require(type.equals("SUPPLIER"), "Warehouse role can edit suppliers only");
            require(c.creditLimit() == null || type.equals("CUSTOMER"), "Credit limits apply to customers only");
            var next = new Object[] {
                c.name().strip(),
                clean(c.email()),
                clean(c.phone()),
                clean(c.billingAddress()),
                clean(c.shippingAddress()),
                clean(c.taxId()),
                c.paymentDays(),
                c.creditLimit()
            };
            String[] columns = {
                "name",
                "email",
                "phone",
                "billing_address",
                "shipping_address",
                "tax_id",
                "payment_days",
                "credit_limit"
            };
            String[] labels = {
                "name",
                "email",
                "phone",
                "billing address",
                "shipping address",
                "tax ID",
                "payment terms",
                "credit limit"
            };
            var changed = new ArrayList<String>();
            for (int i = 0; i < columns.length; i++) if (!same(p.get(columns[i]), next[i])) changed.add(labels[i]);
            boolean wasActive = Boolean.TRUE.equals(p.get("active"));
            s.db().update(
                            "UPDATE partners SET name=?,email=?,phone=?,billing_address=?,shipping_address=?,tax_id=?,payment_days=?,credit_limit=?,active=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            next[0],
                            next[1],
                            next[2],
                            next[3],
                            next[4],
                            next[5],
                            next[6],
                            next[7],
                            c.active(),
                            a.tenant(),
                            id);
            String action = wasActive == c.active()
                    ? "PARTNER_UPDATED"
                    : c.active() ? "PARTNER_REACTIVATED" : "PARTNER_DEACTIVATED";
            String detail = type.charAt(0)
                    + type.substring(1).toLowerCase(java.util.Locale.ROOT)
                    + (changed.isEmpty() ? "" : " · changed " + String.join(", ", changed))
                    + (changed.contains("name") ? " (was " + p.get("name") + ")" : "");
            s.audit(a, action, c.name().strip(), detail);
            return s.one("SELECT * FROM partners WHERE tenant_id=? AND id=?", a.tenant(), id);
        });
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static boolean same(Object current, Object next) {
        if (current instanceof BigDecimal || next instanceof BigDecimal)
            return current != null && next != null
                    ? dec(current).compareTo(dec(next)) == 0
                    : current == null && next == null;
        if (current instanceof Number || next instanceof Number)
            return Objects.equals(
                    current == null ? null : ((Number) current).longValue(),
                    next == null ? null : ((Number) next).longValue());
        Object a = current instanceof String text && text.isBlank() ? null : current;
        return Objects.equals(a, next);
    }
}
