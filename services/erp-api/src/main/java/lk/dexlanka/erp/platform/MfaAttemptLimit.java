package lk.dexlanka.erp.platform;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Attempt limiting for two-step sign-in codes (V17): at most {@value #MAX_FAILURES} wrong authenticator or recovery
 * codes per account within {@value #WINDOW_MINUTES} minutes; further attempts (even with the right code) are refused with
 * HTTP 429 until the oldest failure leaves the window. A correct code clears the account's failures. Like
 * {@link PasswordAttemptLimit} it is a sliding window kept in memory per process.
 */
@Component
class MfaAttemptLimit {
    static final int MAX_FAILURES = 5;
    static final long WINDOW_MINUTES = 15;
    private static final long WINDOW_MILLIS = WINDOW_MINUTES * 60_000;

    private final Map<String, ArrayDeque<Long>> failures = new ConcurrentHashMap<>();
    private LongSupplier clock = System::currentTimeMillis;

    /** 429 while the account has {@link #MAX_FAILURES} failed attempts inside the window. */
    void check(String userId) {
        long now = clock.getAsLong();
        synchronized (failures) {
            var times = failures.get(userId);
            if (times == null) return;
            while (!times.isEmpty() && times.getFirst() <= now - WINDOW_MILLIS) times.removeFirst();
            if (times.isEmpty()) {
                failures.remove(userId);
                return;
            }
            if (times.size() >= MAX_FAILURES) {
                long minutes = Math.max(1, (times.getFirst() + WINDOW_MILLIS - now + 59_999) / 60_000);
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Too many incorrect codes. Try again in " + minutes
                                + (minutes == 1 ? " minute." : " minutes."));
            }
        }
    }

    /** Records a wrong code. */
    void failed(String userId) {
        long now = clock.getAsLong();
        synchronized (failures) {
            failures.entrySet()
                    .removeIf(e -> e.getValue().isEmpty() || e.getValue().getLast() <= now - WINDOW_MILLIS);
            failures.computeIfAbsent(userId, k -> new ArrayDeque<>()).add(now);
        }
    }

    /** A successful code starts the account afresh. */
    void succeeded(String userId) {
        synchronized (failures) {
            failures.remove(userId);
        }
    }

    /** Tests only: a controllable clock ({@code null} restores the system clock). */
    void clock(LongSupplier clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }
}
