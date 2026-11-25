package lk.dexlanka.erp.routing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request bodies for shift calendars (V10). Times are local Asia/Colombo {@code HH:mm}; weekdays are ISO numbers (1 =
 * Monday ... 7 = Sunday).
 *
 * <p>Bean Validation annotations are the input contract (HTTP 400). Component names are the JSON field names and,
 * serialized in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class CalendarCommands {
    private CalendarCommands() {}

    static final String TIME = "([01]\\d|2[0-3]):[0-5]\\d";
    static final String CODE = "[A-Za-z0-9][A-Za-z0-9-]{0,19}";

    /**
     * {@code POST /api/calendars/patterns}: one shift, e.g. Day 06:00–14:00 with a 30-minute break at 10:00 on Monday to
     * Saturday. {@code endTime <= startTime} = the shift ends the next day. {@code breakStart} empty = the break is
     * centred in the shift.
     */
    public record ShiftPattern(
            @NotBlank @Pattern(regexp = CODE) String code,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = TIME) String startTime,
            @NotBlank @Pattern(regexp = TIME) String endTime,
            @Pattern(regexp = TIME) String breakStart,
            @NotNull @Min(0) @Max(720) Integer breakMinutes,
            @NotEmpty @Size(max = 7) List<@NotNull @Min(1) @Max(7) Integer> weekdays) {}

    /** {@code POST /api/calendars/patterns/{id}}: changes a pattern (its code stays). */
    public record ShiftPatternUpdate(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = TIME) String startTime,
            @NotBlank @Pattern(regexp = TIME) String endTime,
            @Pattern(regexp = TIME) String breakStart,
            @NotNull @Min(0) @Max(720) Integer breakMinutes,
            @NotEmpty @Size(max = 7) List<@NotNull @Min(1) @Max(7) Integer> weekdays) {}

    /** {@code POST /api/calendars}: a calendar made of shift patterns; {@code plantDefault} replaces the current one. */
    public record Calendar(
            @NotBlank @Pattern(regexp = CODE) String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 20) List<@NotBlank @Size(max = 64) String> patternIds,
            Boolean plantDefault) {}

    /** {@code POST /api/calendars/{id}}: name, the full set of patterns and the plant-default flag. */
    public record CalendarUpdate(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 20) List<@NotBlank @Size(max = 64) String> patternIds,
            Boolean plantDefault) {}

    /**
     * {@code POST /api/calendars/{id}/exceptions}: {@code CLOSED} (holiday: no shift starts on the date) or {@code
     * OVERTIME} (extra working time from {@code startTime} to {@code endTime}, ending the next day when not after the
     * start).
     */
    public record DateException(
            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
            String date,

            @NotBlank @Pattern(regexp = "CLOSED|OVERTIME") String kind,
            @Pattern(regexp = TIME) String startTime,
            @Pattern(regexp = TIME) String endTime,
            @NotBlank @Size(max = 200) String reason) {}

    /**
     * {@code POST /api/calendars/assignments}: the calendar of a work center; an empty {@code calendarId} returns it to
     * the plant default calendar.
     */
    public record Assignment(
            @NotBlank @Size(max = 64) String machineId,
            @Size(max = 64) String calendarId) {}
}
