package lk.dexlanka.erp.admin;

import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.server.ResponseStatusException;

/**
 * CSV imports of items, customers/suppliers and opening stock (ADMIN only), in two steps:
 *
 * <ol>
 *   <li><b>Validate</b> ({@link #preview}) runs the complete import — the same domain commands, ledger and journal
 *       postings — inside a transaction that is always rolled back, each row in its own savepoint, and returns row-level
 *       errors plus a preview of what would be created.
 *   <li><b>Import</b> ({@link #commit}) runs it again in one transaction and commits only when every row succeeds.
 * </ol>
 *
 * <p>Imports are idempotent per file content: the SHA-256 of the normalized text (no byte-order mark, LF line ends) is
 * unique per tenant and kind ({@code import_batches}), so re-uploading an imported file creates nothing and returns the
 * original batch. Limits: {@value #MAX_ROWS} data rows and {@value AdminCommands#MAX_FILE_CHARS} characters per file.
 */
@Service
public class ImportService {
    public static final int MAX_ROWS = 2000;
    static final int PREVIEW_ROWS = 200;
    static final int MAX_ERRORS = 500;

    private final Store s;
    private final Map<String, ImportKind> kinds;

    ImportService(Store s, List<ImportKind> kinds) {
        this.s = s;
        this.kinds = kinds.stream().collect(Collectors.toMap(ImportKind::kind, Function.identity()));
    }

    /** Idempotency payload of a commit: the file is identified by its content hash, not stored in the key table. */
    private record Payload(String kind, String fileName, String contentHash) {}

    private record Run(
            String batchId,
            String batchNumber,
            int rows,
            List<Map<String, Object>> created,
            List<Map<String, Object>> errors,
            ImportKind.Context context) {}

    /** Dry run: validates and simulates the whole file, then rolls everything back. */
    @Transactional
    public Map<String, Object> preview(Actor a, String kind, AdminCommands.ImportFile f) {
        a.allow();
        var k = kind(kind);
        var status = TransactionAspectSupport.currentTransactionStatus();
        status.setRollbackOnly();
        s.lock(a);
        String text = CsvFile.normalize(f.content());
        String hash = CsvFile.sha256(text);
        var result = new LinkedHashMap<String, Object>();
        result.put("kind", k.kind());
        result.put("fileName", f.fileName());
        result.put("contentHash", hash);
        result.put("alreadyImported", existing(a, k.kind(), hash));
        result.put("columns", Map.of("required", k.required(), "optional", k.optional()));
        result.put("limits", Map.of("maxRows", MAX_ROWS, "maxChars", AdminCommands.MAX_FILE_CHARS));
        Run run;
        try {
            run = run(a, k, f.fileName(), text, hash, status);
        } catch (ResponseStatusException e) {
            result.put("rows", 0);
            result.put("validRows", 0);
            result.put("valid", false);
            result.put("errors", List.of(error(null, null, Objects.toString(e.getReason(), "Invalid file"))));
            result.put("preview", List.of());
            result.put("summary", Map.of());
            return result;
        }
        result.put("rows", run.rows());
        result.put(
                "validRows",
                run.rows()
                        - (int) run.errors().stream()
                                .map(e -> e.get("row"))
                                .distinct()
                                .count());
        result.put("valid", run.errors().isEmpty());
        result.put("errors", run.errors());
        result.put("preview", run.created());
        result.put("summary", summary(run));
        return result;
    }

    /**
     * Imports the file in one transaction. 409 when any row fails (validate again to see every error); an already
     * imported file returns its original batch with {@code duplicate: true} and creates nothing.
     */
    @Transactional
    public Map<String, Object> commit(Actor a, String kind, AdminCommands.ImportFile f, String key) {
        a.allow();
        var k = kind(kind);
        String text = CsvFile.normalize(f.content());
        String hash = CsvFile.sha256(text);
        return s.once(a, key, "import:" + k.kind(), new Payload(k.kind(), f.fileName(), hash), () -> {
            var existing = existing(a, k.kind(), hash);
            if (existing != null) {
                var replay = new LinkedHashMap<>(existing);
                replay.put("duplicate", true);
                return replay;
            }
            var run = run(a, k, f.fileName(), text, hash, TransactionAspectSupport.currentTransactionStatus());
            if (!run.errors().isEmpty()) {
                var first = run.errors().getFirst();
                require(
                        false,
                        (first.get("row") == null ? "" : "Row " + first.get("row") + ": ")
                                + (first.get("column") == null ? "" : first.get("column") + " — ")
                                + first.get("message")
                                + (run.errors().size() > 1
                                        ? " (and " + (run.errors().size() - 1) + " more errors)"
                                        : "")
                                + ". Nothing was imported; validate the file again.");
            }
            var summary = summary(run);
            s.db().update(
                            "INSERT INTO import_batches(id,tenant_id,number,kind,file_name,content_hash,row_count,total_value,summary,created_by) VALUES(?,?,?,?,?,?,?,?,?,?)",
                            run.batchId(),
                            a.tenant(),
                            run.batchNumber(),
                            k.kind(),
                            f.fileName().strip(),
                            hash,
                            run.rows(),
                            run.context().totalValue,
                            s.encode(summary),
                            a.id());
            s.audit(
                    a,
                    "IMPORT_COMMITTED",
                    run.batchNumber(),
                    label(k.kind()) + " · " + run.rows() + (run.rows() == 1 ? " row" : " rows") + " from "
                            + f.fileName().strip()
                            + (k.kind().equals("OPENING_STOCK")
                                    ? " · Rs. " + ApprovalPolicyService.rupees(run.context().totalValue)
                                    : ""));
            var batch = batch(a, run.batchId());
            batch.put("duplicate", false);
            return batch;
        });
    }

    /** Committed import batches, newest first (keyset on {@code created_at, id}). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> history(Actor a, int limit, String before) {
        a.allow();
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
        String cursor = Queries.blankToNull(before);
        var rows = s.list(BATCH_COLUMNS + """
                 WHERE b.tenant_id=?
                  AND (?::text IS NULL OR (b.created_at,b.id) <
                       (SELECT c.created_at,c.id FROM import_batches c WHERE c.tenant_id=b.tenant_id AND c.id=?))
                ORDER BY b.created_at DESC,b.id DESC
                LIMIT ?""", a.tenant(), cursor, cursor, limit + 1);
        return Queries.page(rows, limit);
    }

    /** The CSV template of a kind (header plus fictional example rows). */
    public String template(Actor a, String kind) {
        a.allow();
        return kind(kind).template();
    }

    // ------------------------------------------------------------------ internals

    private static final String BATCH_COLUMNS = """
            SELECT b.id,b.number,b.kind,b.file_name,b.content_hash,b.row_count,b.total_value,b.summary,b.created_at,
                   u.name AS created_by
            FROM import_batches b JOIN users u ON u.id=b.created_by""";

    private ImportKind kind(String kind) {
        var k = kinds.get(kind);
        if (k == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown import type");
        return k;
    }

    private Map<String, Object> existing(Actor a, String kind, String hash) {
        var rows = s.list(
                BATCH_COLUMNS + " WHERE b.tenant_id=? AND b.kind=? AND b.content_hash=?", a.tenant(), kind, hash);
        return rows.isEmpty() ? null : withSummary(rows.getFirst());
    }

    private Map<String, Object> batch(Actor a, String id) {
        return withSummary(s.one(BATCH_COLUMNS + " WHERE b.tenant_id=? AND b.id=?", a.tenant(), id));
    }

    private Map<String, Object> withSummary(Map<String, Object> row) {
        var out = new LinkedHashMap<>(row);
        out.put("summary", s.decode(Objects.toString(row.get("summary"), "{}")));
        return out;
    }

    /** Parses, checks the columns and runs every row in a savepoint. File-level problems are a 409. */
    private Run run(Actor a, ImportKind k, String fileName, String text, String hash, TransactionStatus status) {
        var csv = CsvFile.parse(text, MAX_ROWS);
        var missing =
                k.required().stream().filter(c -> !csv.headers().contains(c)).toList();
        require(missing.isEmpty(), "Missing required column(s): " + String.join(", ", missing));
        var known = new ArrayList<>(k.required());
        known.addAll(k.optional());
        var unknown = csv.headers().stream().filter(c -> !known.contains(c)).toList();
        require(
                unknown.isEmpty(),
                "Unknown column(s): " + String.join(", ", unknown) + ". Expected: " + String.join(", ", known));
        String batchId = id();
        String number = batchNumber(a);
        var context = new ImportKind.Context(a, batchId, number, fileName.strip(), hash);
        k.begin(context);
        var created = new ArrayList<Map<String, Object>>();
        var errors = new ArrayList<Map<String, Object>>();
        for (var row : csv.rows()) {
            Object savepoint = status.createSavepoint();
            try {
                var result = new LinkedHashMap<>(k.row(context, row));
                status.releaseSavepoint(savepoint);
                if (created.size() < PREVIEW_ROWS) {
                    result.put("row", row.line());
                    created.add(result);
                }
            } catch (ImportKind.RowError e) {
                status.rollbackToSavepoint(savepoint);
                addError(errors, error(row.line(), e.column, e.getMessage()));
            } catch (ResponseStatusException e) {
                status.rollbackToSavepoint(savepoint);
                addError(errors, error(row.line(), null, Objects.toString(e.getReason(), "Rejected")));
            } catch (AccessDeniedException e) {
                status.rollbackToSavepoint(savepoint);
                addError(errors, error(row.line(), null, e.getMessage()));
            } catch (DataAccessException e) {
                status.rollbackToSavepoint(savepoint);
                addError(errors, error(row.line(), null, databaseMessage(e)));
            }
        }
        if (errors.isEmpty()) k.finish(context);
        return new Run(batchId, number, csv.rows().size(), created, errors, context);
    }

    private static void addError(List<Map<String, Object>> errors, Map<String, Object> error) {
        if (errors.size() < MAX_ERRORS) errors.add(error);
    }

    private static Map<String, Object> error(Integer row, String column, String message) {
        var e = new LinkedHashMap<String, Object>();
        e.put("row", row);
        e.put("column", column);
        e.put("message", message);
        return e;
    }

    private static Map<String, Object> summary(Run run) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("rows", run.rows());
        summary.put("errors", run.errors().size());
        if (!run.context().accounts.isEmpty() || run.context().totalValue.signum() != 0) {
            summary.put("totalValue", run.context().totalValue);
            summary.put("accounts", run.context().accounts);
            summary.put("creditAccount", OpeningStockImport.EQUITY);
        }
        return summary;
    }

    private String batchNumber(Actor a) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = s.number(a, "IMP");
            if (s.list("SELECT 1 FROM import_batches WHERE tenant_id=? AND number=?", a.tenant(), candidate)
                    .isEmpty()) return candidate;
        }
        throw new IllegalStateException("Could not allocate a unique import number");
    }

    /** A trigger's business-rule message (SQLSTATE P0001) or a generic constraint message. */
    static String databaseMessage(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof SQLException sql && "P0001".equals(sql.getSQLState()))
                return Objects.toString(sql.getMessage(), "")
                        .lines()
                        .findFirst()
                        .orElse("")
                        .replaceFirst("^\\p{Lu}+:\\s*", "")
                        .strip();
        return "This row violates a record, quantity or accounting constraint";
    }

    static String label(String kind) {
        return switch (kind) {
            case "ITEMS" -> "Items";
            case "PARTNERS" -> "Customers and suppliers";
            default -> "Opening stock";
        };
    }
}
