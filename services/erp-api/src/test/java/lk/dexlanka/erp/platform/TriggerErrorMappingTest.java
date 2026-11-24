package lk.dexlanka.erp.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import lk.dexlanka.erp.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Business rules raised by database triggers (PL/pgSQL {@code RAISE EXCEPTION}, SQLSTATE P0001) are answered as 409
 * problem responses carrying the trigger's message instead of HTTP 500; other database failures stay 500 without SQL
 * details.
 */
class TriggerErrorMappingTest extends IntegrationTestBase {
    @Autowired
    PlatformTransactionManager transactions;

    /**
     * Performs real statements that the V1 triggers reject, through MVC and the real {@link ApiErrors} advice. A nested
     * class of a test class, so the shared context's component scan skips it (Spring Boot's TestTypeExcludeFilter);
     * only the standalone MockMvc below registers it.
     */
    @RestController
    static class Probe {
        private final Store s;

        Probe(Store s) {
            this.s = s;
        }

        @PostMapping("/probe/immutable-history/{tenant}")
        void rewriteHistory(@PathVariable String tenant) {
            s.db().update("UPDATE audit_events SET detail='rewritten' WHERE tenant_id=?", tenant);
        }

        @PostMapping("/probe/missing-table")
        void missingTable() {
            s.db().update("UPDATE no_such_table SET x=1");
        }
    }

    @Test
    void triggerViolationsBecomeConflictProblemsWithTheTriggerMessage() throws Exception {
        var tenant = createTenant();
        var admin = createUser(tenant.id(), "ADMIN");
        s.audit(admin, "TEST_EVENT", "REF", "Original");
        var http = MockMvcBuilders.standaloneSetup(new Probe(s))
                .setControllerAdvice(new ApiErrors())
                .build();

        http.perform(post("/probe/immutable-history/" + tenant.id()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Posted history is immutable; use a compensating transaction"));
        http.perform(post("/probe/missing-table"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail")
                        .value("The request could not be completed. Try again or contact your administrator."));
    }

    @Test
    void theDeferredJournalBalanceCheckAtCommitIsAConflictToo() {
        var tenant = createTenant();
        var failure = catchThrowable(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String entry = Store.id();
            update(
                    "INSERT INTO journal_entries(id,tenant_id,reference,description,actor) VALUES(?,?,'UNBALANCED','Test','Test')",
                    entry,
                    tenant.id());
            update(
                    "INSERT INTO journal_lines(id,tenant_id,entry_id,account,debit,credit) VALUES(?,?,?,'RAW_INVENTORY',?,0)",
                    Store.id(),
                    tenant.id(),
                    entry,
                    BigDecimal.TEN);
        }));
        assertThat(failure).isNotNull();
        var response = new ApiErrors().database((Exception) failure);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(((ProblemDetail) response.getBody()).getDetail()).isEqualTo("Journal entry must balance");
        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE tenant_id=?", tenant.id()))
                .isZero();
    }

    @Test
    void severityPrefixesAndContextLinesAreRemovedFromTheMessage() {
        assertThat(
                        ApiErrors.serverMessage(
                                "ERROR: Approved BOM lines are immutable\n  Where: PL/pgSQL function protect_approved_bom_line()"))
                .isEqualTo("Approved BOM lines are immutable");
        assertThat(ApiErrors.serverMessage("")).isEqualTo("This change violates a business rule");
    }
}
