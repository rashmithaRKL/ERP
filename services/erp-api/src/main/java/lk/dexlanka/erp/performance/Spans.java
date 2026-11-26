package lk.dexlanka.erp.performance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * An immutable set of half-open time spans {@code [start, end)} in epoch milliseconds, kept sorted, disjoint and
 * merged (touching spans are joined). The OEE calculator classifies every instant of a work-center day with union,
 * intersection and difference of these sets, so overlapping records are never counted twice.
 */
final class Spans {
    static final Spans EMPTY = new Spans(new long[0]);

    /** start0, end0, start1, end1, ... (sorted, disjoint, non-touching, non-empty). */
    private final long[] bounds;

    private Spans(long[] bounds) {
        this.bounds = bounds;
    }

    static Spans of(Instant start, Instant end) {
        return of(start.toEpochMilli(), end.toEpochMilli());
    }

    static Spans of(long start, long end) {
        return end > start ? new Spans(new long[] {start, end}) : EMPTY;
    }

    /** Normalizes arbitrary (possibly overlapping, unsorted or empty) spans {@code {start, end}}. */
    static Spans union(List<long[]> spans) {
        var sorted = new ArrayList<long[]>();
        for (var s : spans) if (s[1] > s[0]) sorted.add(s);
        sorted.sort(Comparator.<long[]>comparingLong(s -> s[0]).thenComparingLong(s -> s[1]));
        var out = new ArrayList<Long>();
        long start = 0, end = 0;
        boolean open = false;
        for (var s : sorted) {
            if (open && s[0] <= end) {
                end = Math.max(end, s[1]);
            } else {
                if (open) {
                    out.add(start);
                    out.add(end);
                }
                start = s[0];
                end = s[1];
                open = true;
            }
        }
        if (open) {
            out.add(start);
            out.add(end);
        }
        long[] b = new long[out.size()];
        for (int i = 0; i < b.length; i++) b[i] = out.get(i);
        return new Spans(b);
    }

    Spans union(Spans other) {
        var all = new ArrayList<long[]>(pairs());
        all.addAll(other.pairs());
        return union(all);
    }

    Spans intersect(Spans other) {
        var out = new ArrayList<long[]>();
        int i = 0, j = 0;
        while (i < bounds.length && j < other.bounds.length) {
            long start = Math.max(bounds[i], other.bounds[j]);
            long end = Math.min(bounds[i + 1], other.bounds[j + 1]);
            if (end > start) out.add(new long[] {start, end});
            if (bounds[i + 1] < other.bounds[j + 1]) i += 2;
            else j += 2;
        }
        return union(out);
    }

    Spans minus(Spans other) {
        var out = new ArrayList<long[]>();
        int j = 0;
        for (int i = 0; i < bounds.length; i += 2) {
            long start = bounds[i];
            long end = bounds[i + 1];
            while (j < other.bounds.length && other.bounds[j + 1] <= start) j += 2;
            int k = j;
            while (k < other.bounds.length && other.bounds[k] < end) {
                if (other.bounds[k] > start) out.add(new long[] {start, other.bounds[k]});
                start = Math.max(start, other.bounds[k + 1]);
                if (start >= end) break;
                k += 2;
            }
            if (end > start) out.add(new long[] {start, end});
        }
        return union(out);
    }

    boolean isEmpty() {
        return bounds.length == 0;
    }

    /** Total length in milliseconds. */
    long millis() {
        long total = 0;
        for (int i = 0; i < bounds.length; i += 2) total += bounds[i + 1] - bounds[i];
        return total;
    }

    double minutes() {
        return millis() / 60000.0;
    }

    List<long[]> pairs() {
        var out = new ArrayList<long[]>();
        for (int i = 0; i < bounds.length; i += 2) out.add(new long[] {bounds[i], bounds[i + 1]});
        return out;
    }
}
