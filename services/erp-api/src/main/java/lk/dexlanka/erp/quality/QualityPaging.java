package lk.dexlanka.erp.quality;

import static lk.dexlanka.erp.platform.Store.require;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;

/**
 * Keyset paging for the V12 quality lists: {@code select + from} filtered by {@code where} (with its arguments), ordered
 * by {@code created_at DESC, id DESC} of {@code alias}, cursor = the last row's id. Returns {@code {items, next, total}}.
 */
final class QualityPaging {
    private QualityPaging() {}

    static Map<String, Object> page(
            Store s,
            String select,
            String from,
            String alias,
            String table,
            List<Object> filterArgs,
            int limit,
            String before) {
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
        String cursor = before == null || before.isBlank() ? null : before;
        var args = new ArrayList<>(filterArgs);
        args.add(cursor);
        args.add(cursor);
        args.add(limit + 1);
        var rows = s.list(
                select + from + " AND (?::text IS NULL OR (" + alias + ".created_at," + alias
                        + ".id) < (SELECT c.created_at,c.id FROM "
                        + table + " c WHERE c.tenant_id=" + alias + ".tenant_id AND c.id=?))"
                        + " ORDER BY " + alias + ".created_at DESC," + alias + ".id DESC LIMIT ?",
                args.toArray());
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        long total = s.db().queryForObject("SELECT COUNT(*) " + from, Long.class, filterArgs.toArray());
        var out = new LinkedHashMap<String, Object>();
        out.put("items", items);
        out.put("next", more ? items.getLast().get("id") : "");
        out.put("total", total);
        return out;
    }

    /** {@code %text%} for ILIKE with wildcards escaped, or null for a blank search. */
    static String like(String q) {
        return q == null || q.isBlank()
                ? null
                : "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** An upper-case code from {@code allowed}, or null for blank / {@code all}. */
    static String code(String value, List<String> allowed, String message) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("all")) return null;
        String code = value.toUpperCase(Locale.ROOT);
        require(allowed.contains(code), message);
        return code;
    }

    static List<Object> args(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
