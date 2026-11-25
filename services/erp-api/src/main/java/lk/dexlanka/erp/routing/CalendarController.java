package lk.dexlanka.erp.routing;

import jakarta.validation.Valid;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Shift calendars (V10): patterns, calendars, exceptions, work-center assignment and available minutes. */
@RestController
@RequestMapping("/api")
class CalendarController {
    private final ShiftCalendarService calendars;
    private final CalendarQuery query;

    CalendarController(ShiftCalendarService calendars, CalendarQuery query) {
        this.calendars = calendars;
        this.query = query;
    }

    @GetMapping("/calendars")
    Map<String, Object> overview(Authentication a) {
        return query.overview(Actor.of(a));
    }

    @GetMapping("/calendars/available-minutes")
    Map<String, Object> availability(
            Authentication a,
            @RequestParam(required = false) String machineId,
            @RequestParam String from,
            @RequestParam String to) {
        return query.availability(Actor.of(a), machineId, from, to);
    }

    @GetMapping("/calendars/{id}")
    Map<String, Object> calendar(
            Authentication a,
            @PathVariable String id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return query.calendar(Actor.of(a), id, from, to);
    }

    @PostMapping("/calendars/patterns")
    Map<String, Object> createPattern(
            Authentication a,
            @Valid @RequestBody CalendarCommands.ShiftPattern c,
            @RequestHeader("Idempotency-Key") String k) {
        return calendars.createPattern(Actor.of(a), c, k);
    }

    @PostMapping("/calendars/patterns/{id}")
    Map<String, Object> updatePattern(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody CalendarCommands.ShiftPatternUpdate c,
            @RequestHeader("Idempotency-Key") String k) {
        return calendars.updatePattern(Actor.of(a), id, c, k);
    }

    @PostMapping("/calendars/assignments")
    Map<String, Object> assign(
            Authentication a,
            @Valid @RequestBody CalendarCommands.Assignment c,
            @RequestHeader("Idempotency-Key") String k) {
        return calendars.assign(Actor.of(a), c, k);
    }

    @PostMapping("/calendars")
    Map<String, Object> createCalendar(
            Authentication a,
            @Valid @RequestBody CalendarCommands.Calendar c,
            @RequestHeader("Idempotency-Key") String k) {
        return calendars.createCalendar(Actor.of(a), c, k);
    }

    @PostMapping("/calendars/{id}")
    Map<String, Object> updateCalendar(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody CalendarCommands.CalendarUpdate c,
            @RequestHeader("Idempotency-Key") String k) {
        return calendars.updateCalendar(Actor.of(a), id, c, k);
    }

    @PostMapping("/calendars/{id}/exceptions")
    Map<String, Object> addException(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody CalendarCommands.DateException c,
            @RequestHeader("Idempotency-Key") String k) {
        return calendars.addException(Actor.of(a), id, c, k);
    }

    @PostMapping("/calendars/{id}/exceptions/{exceptionId}/remove")
    Map<String, Object> removeException(
            Authentication a,
            @PathVariable String id,
            @PathVariable String exceptionId,
            @RequestHeader("Idempotency-Key") String k) {
        return calendars.removeException(Actor.of(a), id, exceptionId, k);
    }
}
