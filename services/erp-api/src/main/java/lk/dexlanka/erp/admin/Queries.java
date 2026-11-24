package lk.dexlanka.erp.admin;

import java.util.List;
import java.util.Map;

/** Small helpers shared by the administration read endpoints. */
final class Queries {
    private Queries() {}

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** {@code %text%} for ILIKE with {@code %}, {@code _} and {@code \} escaped, or null for no filter. */
    static String like(String q) {
        String text = blankToNull(q);
        if (text == null) return null;
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** Keyset page: rows were fetched with {@code limit + 1}; {@code next} is the last returned id, or "". */
    static Map<String, Object> page(List<Map<String, Object>> rows, int limit) {
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        return Map.of("items", items, "next", more ? items.getLast().get("id") : "");
    }
}
