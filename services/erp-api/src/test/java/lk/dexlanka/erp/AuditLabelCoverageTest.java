package lk.dexlanka.erp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every audit action code the API writes ({@code Store.audit(actor, "CODE", ...)}) has a readable label in the web
 * client's {@code features/audit/auditLabel.ts} (V9). A plain unit test over the sources: no Spring context.
 */
class AuditLabelCoverageTest {
    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path LABELS = Path.of("../../apps/erp-web/src/features/audit/auditLabel.ts");

    /**
     * Codes chosen at runtime: {@code "MO_" + action}, {@code "QC_" + result}, the partner status change and the
     * adjustment posting (direct or approved).
     */
    private static final Set<String> DYNAMIC = Set.of(
            "MO_APPROVE",
            "MO_RESERVE",
            "MO_ISSUE",
            "MO_START",
            "MO_PAUSE",
            "MO_RESUME",
            "MO_CLOSE",
            "MO_CANCEL",
            "QC_PASSED",
            "QC_FAILED",
            "PARTNER_UPDATED",
            "PARTNER_DEACTIVATED",
            "PARTNER_REACTIVATED",
            "ADJUSTMENT_POSTED",
            "ADJUSTMENT_APPROVED");

    /** {@code x.audit(actor, "CODE",}: a plain literal code (not a prefix such as {@code "MO_" + action}). */
    private static final Pattern LITERAL = Pattern.compile("\\.audit\\(\\s*\\w+\\s*,\\s*\"([A-Z][A-Z0-9_]*)\"\\s*,");

    private static final Pattern CALL = Pattern.compile("\\.audit\\(\\s*\\w+\\s*,");

    @Test
    void everyAuditActionHasAReadableLabel() throws IOException {
        var codes = new TreeSet<>(DYNAMIC);
        int computed = 0;
        try (var files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                var literal = LITERAL.matcher(source);
                while (literal.find()) {
                    codes.add(literal.group(1));
                    computed--;
                }
                var call = CALL.matcher(source);
                while (call.find()) computed++;
            }
        }
        // The four computed call sites are covered by DYNAMIC; a new one must be added there (and labelled).
        assertThat(computed).isEqualTo(4);
        assertThat(codes).contains("PO_AUTO_APPROVED", "MO_AUTO_APPROVED", "COUNT_STARTED", "SO_CLOSED_SHORT");

        String labels = Files.readString(LABELS);
        var missing = codes.stream()
                .filter(code -> !Pattern.compile("(?m)^\\s*" + code + ":")
                        .matcher(labels)
                        .find())
                .toList();
        assertThat(missing).as("audit codes without a label in auditLabel.ts").isEmpty();
    }
}
