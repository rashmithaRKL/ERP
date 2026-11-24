package lk.dexlanka.erp.admin;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lk.dexlanka.erp.platform.Actor;

/**
 * One importable file type. {@link ImportService} runs {@link #begin}, {@link #row} for every data row (each in its
 * own savepoint) and {@link #finish} inside one transaction; a dry run rolls the transaction back.
 */
interface ImportKind {
    /** {@code ITEMS}, {@code PARTNERS} or {@code OPENING_STOCK}. */
    String kind();

    List<String> required();

    List<String> optional();

    /** A downloadable CSV template: the header and fictional example rows. */
    String template();

    default void begin(Context c) {}

    /** Creates the row's record(s); throws {@link RowError} or a {@code ResponseStatusException} for a row error. */
    Map<String, Object> row(Context c, CsvFile.Row row);

    default void finish(Context c) {}

    /** State of one import run (dry run or commit). */
    final class Context {
        final Actor actor;
        final String batchId;
        final String batchNumber;
        final String fileName;
        final String contentHash;
        /** Debit totals per inventory account (opening stock). */
        final Map<String, BigDecimal> accounts = new TreeMap<>();

        final Set<String> seen = new HashSet<>();
        BigDecimal totalValue = BigDecimal.ZERO;

        Context(Actor actor, String batchId, String batchNumber, String fileName, String contentHash) {
            this.actor = actor;
            this.batchId = batchId;
            this.batchNumber = batchNumber;
            this.fileName = fileName;
            this.contentHash = contentHash;
        }

        /**
         * A per-row Idempotency-Key for the domain command a row calls, unique per batch and row (the batch id is new on
         * every run; a committed file is never run again because its content hash is unique).
         */
        String rowKey(CsvFile.Row row) {
            return "import-" + batchId.replace("-", "").substring(0, 24) + "-" + row.line();
        }
    }

    /** A validation error in one cell (or the whole row when {@code column} is null). */
    final class RowError extends RuntimeException {
        final String column;

        RowError(String column, String message) {
            super(message, null, false, false);
            this.column = column;
        }
    }

    // ------------------------------------------------------------------ cell parsing helpers

    static String text(CsvFile.Row row, String column, boolean required, int max) {
        String v = row.get(column);
        if (required && v.isEmpty()) throw new RowError(column, "Required");
        if (v.length() > max) throw new RowError(column, "At most " + max + " characters");
        return v;
    }

    static BigDecimal decimal(CsvFile.Row row, String column, boolean required, BigDecimal min, boolean exclusive) {
        String v = row.get(column).replace(",", "");
        if (v.isEmpty()) {
            if (required) throw new RowError(column, "Required");
            return null;
        }
        BigDecimal d;
        try {
            d = new BigDecimal(v);
        } catch (NumberFormatException e) {
            throw new RowError(column, "Enter a number, e.g. 1250.50");
        }
        if (exclusive ? d.compareTo(min) <= 0 : d.compareTo(min) < 0)
            throw new RowError(column, "Must be " + (exclusive ? "greater than " : "at least ") + min.toPlainString());
        var stripped = d.stripTrailingZeros();
        if (stripped.scale() > 6 || stripped.precision() - stripped.scale() > 12)
            throw new RowError(column, "Use at most 12 digits before and 6 after the decimal point");
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    static Integer integer(CsvFile.Row row, String column, int min, int max) {
        String v = row.get(column);
        if (v.isEmpty()) return null;
        try {
            int n = Integer.parseInt(v);
            if (n < min || n > max) throw new RowError(column, "Use a whole number from " + min + " to " + max);
            return n;
        } catch (NumberFormatException e) {
            throw new RowError(column, "Use a whole number from " + min + " to " + max);
        }
    }

    static String choice(CsvFile.Row row, String column, List<String> allowed, String fallback) {
        String v = row.get(column).toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
        if (v.isEmpty()) {
            if (fallback != null) return fallback;
            throw new RowError(column, "Required: one of " + String.join(", ", allowed));
        }
        if (!allowed.contains(v)) throw new RowError(column, "Use one of " + String.join(", ", allowed));
        return v;
    }
}
