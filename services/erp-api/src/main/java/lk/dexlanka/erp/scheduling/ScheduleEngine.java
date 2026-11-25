package lk.dexlanka.erp.scheduling;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.routing.CalendarService;

/**
 * The forward finite-capacity scheduler (V13): a pure function of its {@link Input} (no I/O, no clock), so the same
 * input always gives the same schedule. All times are epoch minutes; durations are whole working minutes.
 *
 * <p>Rules, in the order they are applied:
 *
 * <ol>
 *   <li><b>Capacity</b>: a work center works only inside its calendar's working intervals ({@code
 *       routing.CalendarService}) minus blocked time (maintenance windows, open downtime). An operation may be split
 *       across breaks, shift ends, days and blocked time, but once started nothing else runs on that work center
 *       until it ends: a placement occupies the wall-clock span from its start to its end. One operation at a time
 *       per work center.
 *   <li><b>Running work first</b>: IN_PROGRESS operations (and running orders without a routing) stay on their work
 *       center and continue from "now" for their remaining minutes (planned − actual, never negative).
 *   <li><b>Pins</b>: an active pin books its operation at the pinned work center and start, before any other
 *       operation, when that start is still working time, after "now", free and inside the horizon. When the
 *       operation is reached in sequence and its predecessors (earlier operations, child orders) end after the pinned
 *       start, the pin is released (CONFLICT) and the operation is placed by the normal rules.
 *   <li><b>Sequence</b>: orders by priority (URGENT, HIGH, NORMAL), due date, creation time and id; a child order
 *       inherits a more urgent priority and an earlier due date of its parent and is always scheduled before it.
 *       Within an order, operations in sequence: each starts at or after the end of the previous one; the first
 *       operation of a parent starts at or after the end of every scheduled child order.
 *   <li><b>Placement</b>: the earliest start at or after the operation's release where its whole duration fits in the
 *       free working time of the work center without overlapping another placement (earlier gaps may be filled by
 *       later orders when the operation fits entirely). With alternates enabled, a not-yet-started routed operation
 *       goes to the work center among its current, routing and alternate work centers that finishes it earliest
 *       (ties: the current work center, then by code).
 *   <li><b>Horizon</b>: an operation that cannot finish before the horizon end is not placed, nor are the later
 *       operations of its order (BEYOND_HORIZON).
 * </ol>
 */
final class ScheduleEngine {
    static final String SCHEDULED = "SCHEDULED";
    static final String NO_ROUTING = "NO_ROUTING";
    static final String BEYOND_HORIZON = "BEYOND_HORIZON";
    static final String NO_OPEN_WORK = "NO_OPEN_WORK";
    static final String HONOURED = "HONOURED";
    static final String CONFLICT = "CONFLICT";

    private ScheduleEngine() {}

    // ------------------------------------------------------------------ input

    /** A half-open interval {@code [start, end)} in epoch minutes. */
    record Span(long start, long end) {
        long minutes() {
            return end - start;
        }
    }

    /** A work center: id, code (tie-breaks) and its merged calendar working intervals inside the horizon. */
    record Machine(String id, String code, List<Span> working) {}

    /** Blocked time of a work center (maintenance window or downtime). */
    record Block(
            String machineId, String kind, String reference, String label, long start, long end, boolean assumedEnd) {}

    /**
     * An open operation. {@code id} is null for the implicit operation of an order without a routing. {@code
     * candidates} lists the work centers it may run on, its current work center first; {@code remaining} is the
     * remaining work in minutes (fractional; placed rounded up); {@code running} = IN_PROGRESS now.
     */
    record Operation(
            String id,
            int seq,
            String name,
            String status,
            String machineId,
            String plannedMachineId,
            List<String> candidates,
            BigDecimal remaining,
            BigDecimal planned,
            BigDecimal actual,
            boolean running) {
        boolean open() {
            return !status.equals("DONE") && !status.equals("CANCELLED");
        }
    }

    /**
     * A manufacturing order in scope. {@code priorityRank} 0 = URGENT, 1 = HIGH, 2 = NORMAL; {@code dueEnd} is the end
     * of the due date; {@code created} orders equal keys; {@code notes} are warnings found while loading the input.
     */
    record Order(
            String id,
            String number,
            String status,
            int priorityRank,
            String priority,
            LocalDate dueDate,
            long dueEnd,
            long created,
            String parentId,
            boolean routed,
            BigDecimal estimate,
            List<Operation> operations,
            List<String> notes) {}

    /** An active pin: the operation (null id = the implicit operation of {@code orderId}) at a work center and start. */
    record Pin(String id, String orderId, String operationId, String machineId, long start) {
        String key() {
            return ScheduleEngine.key(orderId, operationId);
        }
    }

    record Input(
            long now,
            long horizonEnd,
            boolean useAlternates,
            List<Machine> machines,
            List<Block> blocks,
            List<Order> orders,
            List<Pin> pins) {}

    // ------------------------------------------------------------------ output

    /** A placed operation; {@code segments} are the working intervals it runs in. */
    record Placed(
            Order order,
            Operation operation,
            String machineId,
            long start,
            long end,
            long minutes,
            List<Span> segments,
            boolean late,
            Pin pin,
            String pinStatus,
            String pinNote) {
        boolean alternate() {
            return !machineId.equals(operation.machineId());
        }
    }

    record OrderResult(
            Order order,
            int sequence,
            String status,
            Long start,
            Long end,
            boolean late,
            long latenessMinutes,
            long remainingMinutes,
            List<String> warnings) {}

    record PinOutcome(Pin pin, String status, String note) {}

    record Result(List<OrderResult> orders, List<Placed> operations, Map<String, PinOutcome> pins) {
        OrderResult order(String id) {
            return orders.stream().filter(o -> o.order().id().equals(id)).findFirst().orElse(null);
        }

        List<Placed> placed(String orderId) {
            return operations.stream()
                    .filter(p -> p.order().id().equals(orderId))
                    .toList();
        }
    }

    static String key(String orderId, String operationId) {
        return orderId + ":" + (operationId == null ? "" : operationId);
    }

    /** Whole minutes of work, rounded up. */
    static long minutes(BigDecimal remaining) {
        return remaining.signum() <= 0
                ? 0
                : remaining.setScale(0, RoundingMode.CEILING).longValueExact();
    }

    // ------------------------------------------------------------------ work-center timeline

    /** Free working time (calendar minus blocks) and the placements booked on one work center. */
    static final class Timeline {
        final String machineId;
        final String code;
        final List<Span> free;
        final List<Booking> booked = new ArrayList<>();

        Timeline(Machine machine, List<Block> blocks) {
            this.machineId = machine.id();
            this.code = machine.code();
            this.free = subtract(
                    machine.working(),
                    blocks.stream()
                            .map(b -> new Span(b.start(), b.end()))
                            .toList());
        }

        /** The earliest free working minute at or after {@code t}, or null when there is none in the horizon. */
        Long nextWorking(long t) {
            for (var span : free) if (span.end() > t) return Math.max(span.start(), t);
            return null;
        }

        /** Consumes {@code minutes} of working time from {@code start}; null when the horizon ends first. */
        List<Span> consume(long start, long minutes) {
            var segments = new ArrayList<Span>();
            long rest = minutes;
            if (rest == 0) return segments;
            for (var span : free) {
                if (span.end() <= start) continue;
                long from = Math.max(span.start(), start);
                long take = Math.min(span.end() - from, rest);
                segments.add(new Span(from, from + take));
                rest -= take;
                if (rest == 0) return segments;
            }
            return null;
        }

        /** The first booking (by start) overlapping {@code [start, end)}; zero-length placements never block. */
        Booking conflict(long start, long end) {
            for (var b : booked) {
                if (b.end <= b.start) continue;
                boolean overlaps = end > start ? b.start < end && start < b.end : b.start < start && start < b.end;
                if (overlaps) return b;
            }
            return null;
        }

        void book(Booking b) {
            booked.add(b);
            booked.sort(Comparator.comparingLong((Booking x) -> x.start).thenComparing(x -> x.key));
        }

        void release(Booking b) {
            booked.remove(b);
        }

        /** Earliest placement of {@code minutes} at or after {@code release}; null beyond the horizon. */
        Placement place(long release, long minutes) {
            long t = release;
            while (true) {
                Long start = nextWorking(t);
                if (start == null) return null;
                var segments = consume(start, minutes);
                if (segments == null) return null;
                long end = segments.isEmpty() ? start : segments.getLast().end();
                var clash = conflict(start, end);
                if (clash == null) return new Placement(machineId, code, start, end, segments);
                t = clash.end;
            }
        }
    }

    record Placement(String machineId, String code, long start, long end, List<Span> segments) {}

    static final class Booking {
        final String key;
        final long start;
        final long end;

        Booking(String key, long start, long end) {
            this.key = key;
            this.start = start;
            this.end = end;
        }
    }

    /** {@code intervals} minus {@code holes}; both may be unsorted; the result is sorted and disjoint. */
    static List<Span> subtract(List<Span> intervals, List<Span> holes) {
        var sortedHoles = holes.stream()
                .filter(h -> h.end() > h.start())
                .sorted(Comparator.comparingLong(Span::start))
                .toList();
        var out = new ArrayList<Span>();
        for (var span : intervals.stream()
                .sorted(Comparator.comparingLong(Span::start))
                .toList()) {
            long cursor = span.start();
            for (var hole : sortedHoles) {
                if (hole.end() <= cursor || hole.start() >= span.end()) continue;
                if (hole.start() > cursor) out.add(new Span(cursor, hole.start()));
                cursor = Math.max(cursor, hole.end());
                if (cursor >= span.end()) break;
            }
            if (cursor < span.end()) out.add(new Span(cursor, span.end()));
        }
        return out;
    }

    // ------------------------------------------------------------------ the scheduler

    static Result schedule(Input in) {
        return new Run(in).execute();
    }

    private static final class Run {
        final Input in;
        final Map<String, Timeline> timelines = new LinkedHashMap<>();
        final Map<String, Order> orders = new LinkedHashMap<>();
        final Map<String, List<Order>> children = new HashMap<>();
        final Map<String, Placed> running = new HashMap<>();
        final Map<String, Booking> pinBookings = new HashMap<>();
        final Map<String, Placement> pinPlacements = new HashMap<>();
        final Map<String, Pin> pinByKey = new HashMap<>();
        final Map<String, PinOutcome> pinOutcomes = new LinkedHashMap<>();
        final Map<String, OrderResult> results = new LinkedHashMap<>();
        final List<Placed> placed = new ArrayList<>();
        final Map<String, String> runningFailures = new HashMap<>();

        Run(Input in) {
            this.in = in;
            var blocks = new HashMap<String, List<Block>>();
            for (var b : in.blocks())
                blocks.computeIfAbsent(b.machineId(), k -> new ArrayList<>()).add(b);
            for (var m : in.machines())
                timelines.put(
                        m.id(),
                        new Timeline(
                                new Machine(m.id(), m.code(), clip(m.working(), in.now(), in.horizonEnd())),
                                blocks.getOrDefault(m.id(), List.of())));
            for (var o : in.orders()) orders.put(o.id(), o);
            for (var o : in.orders())
                if (o.parentId() != null && orders.containsKey(o.parentId()))
                    children.computeIfAbsent(o.parentId(), k -> new ArrayList<>()).add(o);
        }

        Result execute() {
            var sequence = sequence();
            placeRunning(sequence);
            bookPins();
            int n = 0;
            for (var order : sequence) schedule(order, ++n);
            var ordered = new ArrayList<OrderResult>();
            for (var order : sequence) ordered.add(results.get(order.id()));
            for (var pin : in.pins())
                pinOutcomes.putIfAbsent(
                        pin.id(), new PinOutcome(pin, CONFLICT, "The operation is no longer open for scheduling"));
            return new Result(ordered, placed, pinOutcomes);
        }

        // -------------------------------------------------------------- order sequence

        private final Map<String, int[]> rankMemo = new HashMap<>();

        /** Effective {priority rank, due epoch day}: the more urgent of the order's own and its parent's. */
        int[] effective(Order o) {
            var memo = rankMemo.get(o.id());
            if (memo != null) return memo;
            int[] own = {o.priorityRank(), (int) o.dueDate().toEpochDay()};
            var parent = o.parentId() == null ? null : orders.get(o.parentId());
            if (parent != null) {
                int[] p = effective(parent);
                own = new int[] {Math.min(own[0], p[0]), Math.min(own[1], p[1])};
            }
            rankMemo.put(o.id(), own);
            return own;
        }

        Comparator<Order> byKey() {
            return Comparator.<Order>comparingInt(o -> effective(o)[0])
                    .thenComparingInt(o -> effective(o)[1])
                    .thenComparingInt(Order::priorityRank)
                    .thenComparing(Order::dueDate)
                    .thenComparingLong(Order::created)
                    .thenComparing(Order::id);
        }

        /** Orders by key, every child (recursively) before its parent. */
        List<Order> sequence() {
            var sorted = new ArrayList<>(orders.values());
            sorted.sort(byKey());
            var out = new ArrayList<Order>();
            var seen = new HashSet<String>();
            for (var o : sorted) visit(o, seen, out);
            return out;
        }

        private void visit(Order o, Set<String> seen, List<Order> out) {
            if (!seen.add(o.id())) return;
            var kids = new ArrayList<>(children.getOrDefault(o.id(), List.of()));
            kids.sort(byKey());
            for (var child : kids) visit(child, seen, out);
            out.add(o);
        }

        // -------------------------------------------------------------- running work and pins

        void placeRunning(List<Order> sequence) {
            for (var order : sequence)
                for (var op : order.operations()) {
                    if (!op.running()) continue;
                    var timeline = timelines.get(op.machineId());
                    long minutes = minutes(op.remaining());
                    var p = timeline == null ? null : timeline.place(in.now(), minutes);
                    String k = key(order.id(), op.id());
                    if (p == null) {
                        runningFailures.put(k, "does not finish before the horizon end");
                        continue;
                    }
                    timeline.book(new Booking(k, p.start(), p.end()));
                    running.put(
                            k,
                            new Placed(
                                    order,
                                    op,
                                    p.machineId(),
                                    p.start(),
                                    p.end(),
                                    minutes,
                                    p.segments(),
                                    p.end() > order.dueEnd(),
                                    null,
                                    null,
                                    ""));
                }
        }

        void bookPins() {
            var ops = new HashMap<String, Operation>();
            for (var o : orders.values()) for (var op : o.operations()) ops.put(key(o.id(), op.id()), op);
            var pins = new ArrayList<>(in.pins());
            pins.sort(Comparator.comparingLong(Pin::start).thenComparing(Pin::id));
            for (var pin : pins) {
                var op = ops.get(pin.key());
                if (op == null || !op.open() || op.running() || !op.status().matches("PENDING|READY")) {
                    pinOutcomes.put(
                            pin.id(), new PinOutcome(pin, CONFLICT, "The operation has started or is no longer open"));
                    continue;
                }
                String problem = null;
                var timeline = timelines.get(pin.machineId());
                Placement p = null;
                if (!op.candidates().contains(pin.machineId()) || timeline == null)
                    problem = "The pinned work center can no longer run this operation";
                else if (pin.start() < in.now()) problem = "The pinned start has passed";
                else if (pin.start() >= in.horizonEnd()) problem = "The pinned start is after the horizon end";
                else {
                    Long first = timeline.nextWorking(pin.start());
                    if (first == null || first != pin.start())
                        problem = "The pinned start is not working time of " + timeline.code;
                    else {
                        var segments = timeline.consume(pin.start(), minutes(op.remaining()));
                        if (segments == null) problem = "The pinned operation does not finish before the horizon end";
                        else {
                            long end = segments.isEmpty() ? pin.start() : segments.getLast().end();
                            var clash = timeline.conflict(pin.start(), end);
                            if (clash != null) problem = "The pinned time overlaps " + describe(clash.key);
                            else p = new Placement(timeline.machineId, timeline.code, pin.start(), end, segments);
                        }
                    }
                }
                if (problem != null) {
                    pinOutcomes.put(pin.id(), new PinOutcome(pin, CONFLICT, problem));
                    pinByKey.put(pin.key(), pin);
                    continue;
                }
                var booking = new Booking(pin.key(), p.start(), p.end());
                timeline.book(booking);
                pinBookings.put(pin.key(), booking);
                pinPlacements.put(pin.key(), p);
                pinByKey.put(pin.key(), pin);
            }
        }

        private String describe(String bookingKey) {
            String orderId = bookingKey.substring(0, bookingKey.indexOf(':'));
            String opId = bookingKey.substring(bookingKey.indexOf(':') + 1);
            var order = orders.get(orderId);
            if (order == null) return "another operation";
            for (var op : order.operations())
                if (opId.equals(op.id() == null ? "" : op.id()))
                    return order.number() + (op.id() == null ? "" : " operation " + op.seq());
            return order.number();
        }

        // -------------------------------------------------------------- one order

        void schedule(Order order, int sequence) {
            var warnings = new ArrayList<>(order.notes());
            long release = in.now();
            boolean blocked = false;
            for (var child : children.getOrDefault(order.id(), List.of())) {
                var r = results.get(child.id());
                if (r == null) continue;
                switch (r.status()) {
                    case SCHEDULED -> release = Math.max(release, r.end());
                    case NO_ROUTING ->
                        warnings.add("Child order " + child.number()
                                + " has no routing or estimate; its completion is not considered");
                    case BEYOND_HORIZON -> {
                        blocked = true;
                        warnings.add("Waits for child order " + child.number() + ", which ends beyond the horizon");
                    }
                    default -> {}
                }
            }
            var open = order.operations().stream().filter(Operation::open).toList();
            if (!order.routed() && order.operations().isEmpty()) {
                long lateness = Math.max(0, in.now() - order.dueEnd());
                results.put(
                        order.id(),
                        new OrderResult(
                                order, sequence, NO_ROUTING, null, null, lateness > 0, lateness, 0, warnings));
                return;
            }
            if (open.isEmpty()) {
                long lateness = Math.max(0, in.now() - order.dueEnd());
                results.put(
                        order.id(),
                        new OrderResult(
                                order, sequence, NO_OPEN_WORK, null, null, lateness > 0, lateness, 0, warnings));
                return;
            }
            Long first = null;
            long remaining = 0;
            String status = SCHEDULED;
            for (var op : open) {
                String k = key(order.id(), op.id());
                long minutes = minutes(op.remaining());
                if (op.running()) {
                    var r = running.get(k);
                    if (r == null) {
                        status = BEYOND_HORIZON;
                        warnings.add(label(op) + " " + runningFailures.getOrDefault(k, "cannot be placed"));
                        break;
                    }
                    if (op.remaining().signum() <= 0)
                        warnings.add(label(op) + " has used its planned minutes; shown as finishing now");
                    placed.add(r);
                    first = first == null ? r.start() : Math.min(first, r.start());
                    release = Math.max(release, r.end());
                    remaining += minutes;
                    continue;
                }
                if (blocked) {
                    status = BEYOND_HORIZON;
                    break;
                }
                var pin = pinByKey.get(k);
                var booking = pinBookings.remove(k);
                String pinStatus = null;
                String pinNote = "";
                if (booking != null) {
                    if (booking.start >= release) {
                        var p = pinPlacements.get(k);
                        add(order, op, p, minutes, pin, HONOURED, "");
                        pinOutcomes.put(pin.id(), new PinOutcome(pin, HONOURED, ""));
                        first = first == null ? p.start() : Math.min(first, p.start());
                        release = p.end();
                        remaining += minutes;
                        continue;
                    }
                    timelines.get(pin.machineId()).release(booking);
                    pinStatus = CONFLICT;
                    pinNote = "The previous step ends at " + local(release) + ", after the pinned start";
                    pinOutcomes.put(pin.id(), new PinOutcome(pin, CONFLICT, pinNote));
                } else if (pin != null) {
                    pinStatus = CONFLICT;
                    pinNote = pinOutcomes.get(pin.id()).note();
                }
                var best = best(op, release, minutes);
                if (best == null) {
                    status = BEYOND_HORIZON;
                    warnings.add(label(op) + " does not fit before the horizon end");
                    break;
                }
                timelines.get(best.machineId()).book(new Booking(k, best.start(), best.end()));
                add(order, op, best, minutes, pinStatus == null ? null : pin, pinStatus, pinNote);
                first = first == null ? best.start() : Math.min(first, best.start());
                release = best.end();
                remaining += minutes;
            }
            if (status.equals(BEYOND_HORIZON)) {
                // Pins of operations that were not reached free their capacity for later orders.
                for (var op : open) {
                    String k = key(order.id(), op.id());
                    var booking = pinBookings.remove(k);
                    if (booking == null) continue;
                    var pin = pinByKey.get(k);
                    timelines.get(pin.machineId()).release(booking);
                    pinOutcomes.put(
                            pin.id(),
                            new PinOutcome(pin, CONFLICT, "An earlier step of the order does not fit in the horizon"));
                }
                long lateness = Math.max(0, in.horizonEnd() - order.dueEnd());
                results.put(
                        order.id(),
                        new OrderResult(
                                order, sequence, status, first, null, lateness > 0, lateness, remaining, warnings));
                return;
            }
            long lateness = Math.max(0, release - order.dueEnd());
            results.put(
                    order.id(),
                    new OrderResult(
                            order, sequence, SCHEDULED, first, release, lateness > 0, lateness, remaining, warnings));
        }

        private void add(Order order, Operation op, Placement p, long minutes, Pin pin, String status, String note) {
            placed.add(new Placed(
                    order,
                    op,
                    p.machineId(),
                    p.start(),
                    p.end(),
                    minutes,
                    p.segments(),
                    p.end() > order.dueEnd(),
                    pin,
                    status,
                    note));
        }

        /** The candidate work center that finishes the operation earliest (ties: candidate order). */
        private Placement best(Operation op, long release, long minutes) {
            var candidates = in.useAlternates() && op.status().matches("PENDING|READY")
                    ? op.candidates()
                    : List.of(op.machineId());
            Placement best = null;
            for (String machine : candidates) {
                var timeline = timelines.get(machine);
                if (timeline == null) continue;
                var p = timeline.place(release, minutes);
                if (p != null && (best == null || p.end() < best.end())) best = p;
            }
            return best;
        }

        private static String label(Operation op) {
            return op.id() == null ? "The order run" : "Operation " + op.seq();
        }
    }

    /** Epoch minute as local business time, {@code yyyy-MM-dd HH:mm}. */
    static String local(long epochMinute) {
        return Instant.ofEpochSecond(epochMinute * 60)
                .atZone(CalendarService.ZONE)
                .toLocalDateTime()
                .toString()
                .replace('T', ' ');
    }

    static List<Span> clip(List<Span> spans, long from, long to) {
        var out = new ArrayList<Span>();
        for (var s : spans) {
            long a = Math.max(s.start(), from);
            long b = Math.min(s.end(), to);
            if (b > a) out.add(new Span(a, b));
        }
        return out;
    }
}
