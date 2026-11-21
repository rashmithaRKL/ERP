package lk.dexlanka.erp.platform;

import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Error bodies for every controller. Note: with {@code spring.mvc.problemdetails.enabled=true}, Spring Boot's
 * {@code ProblemDetailsExceptionHandler} has a higher precedence and answers {@link MethodArgumentNotValidException} and
 * {@link ResponseStatusException} (all {@code Store.require}/{@code Store.one} failures) with {@code
 * application/problem+json} ({@code detail} carries the message). {@link DataIntegrityViolationException} (409) and
 * {@link AccessDeniedException} (403) are answered here with {@code {"message": ...}}. Keep exactly one advice.
 */
@RestControllerAdvice
class ApiErrors {
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

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("message", Objects.toString(e.getReason(), "Request could not be completed")));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<?> denied(Exception e) {
        return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
    }
}
