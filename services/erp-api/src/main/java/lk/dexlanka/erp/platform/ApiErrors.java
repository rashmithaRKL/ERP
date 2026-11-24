package lk.dexlanka.erp.platform;

import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Error bodies for every controller. Note: with {@code spring.mvc.problemdetails.enabled=true}, Spring Boot's
 * {@code ProblemDetailsExceptionHandler} has a higher precedence and answers {@link MethodArgumentNotValidException} and
 * {@link ResponseStatusException} (all {@code Store.require}/{@code Store.one} failures) with {@code
 * application/problem+json} ({@code detail} carries the message). Business rules raised by database triggers ({@code
 * RAISE EXCEPTION}, SQLSTATE {@code P0001}) are answered here as 409 problem responses carrying the trigger's message;
 * {@link DataIntegrityViolationException} (constraint violations, 409) and {@link AccessDeniedException} (403) are
 * answered with {@code {"message": ...}}. Keep exactly one advice.
 */
@RestControllerAdvice
class ApiErrors {
    /** SQLSTATE of a PL/pgSQL {@code RAISE EXCEPTION} without an explicit {@code ERRCODE} ({@code raise_exception}). */
    static final String RAISE_EXCEPTION = "P0001";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest()
                .body(Map.of(
                        "message",
                        e.getBindingResult().getFieldErrors().stream()
                                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                                .findFirst()
                                .orElse("Invalid input")));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(409)
                .body(
                        Map.of(
                                "message",
                                "This transaction violates a record, quantity or accounting constraint. Check for duplicate references and valid units."));
    }

    /**
     * Trigger-raised business rules (V1 immutable history and ledger checks, V2 approved BOMs, V4 item identity, V8 last
     * administrator...) surface as an uncategorized SQL exception, or as a commit failure for the deferred journal
     * balance trigger. They become 409 with the trigger's message; anything else stays a 500 without SQL details.
     */
    @ExceptionHandler({DataAccessException.class, TransactionSystemException.class})
    ResponseEntity<ProblemDetail> database(Exception e) {
        String rule = businessRule(e);
        var problem = rule == null
                ? ProblemDetail.forStatusAndDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "The request could not be completed. Try again or contact your administrator.")
                : ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, rule);
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("message", Objects.toString(e.getReason(), "Request could not be completed")));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<?> denied(Exception e) {
        return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
    }

    /** The message of the first {@code P0001} database error in {@code e}'s causes, or null. */
    static String businessRule(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql)
                for (SQLException x = sql; x != null; x = x.getNextException())
                    if (RAISE_EXCEPTION.equals(x.getSQLState())) return serverMessage(x.getMessage());
            if (t.getCause() == t) break;
        }
        return null;
    }

    /** {@code "ERROR: Posted history is immutable…\n  Where: PL/pgSQL function…"} → the first line, without severity. */
    static String serverMessage(String message) {
        String first =
                Objects.toString(message, "").lines().findFirst().orElse("").strip();
        first = first.replaceFirst("^\\p{Lu}+:\\s*", "");
        return first.isEmpty() ? "This change violates a business rule" : first;
    }
}
