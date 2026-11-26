package lk.dexlanka.erp.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Interval arithmetic behind the OEE time classification (plain unit test, no Spring context). */
class SpansTest {
    static Spans spans(long... bounds) {
        var pairs = new java.util.ArrayList<long[]>();
        for (int i = 0; i < bounds.length; i += 2) pairs.add(new long[] {bounds[i], bounds[i + 1]});
        return Spans.union(pairs);
    }

    static List<List<Long>> of(Spans s) {
        return s.pairs().stream().map(p -> List.of(p[0], p[1])).toList();
    }

    @Test
    void unionMergesOverlappingAndTouchingSpansAndDropsEmptyOnes() {
        var s = spans(30, 40, 0, 10, 10, 20, 35, 50, 60, 60);
        assertThat(of(s)).containsExactly(List.of(0L, 20L), List.of(30L, 50L));
        assertThat(s.millis()).isEqualTo(40);
    }

    @Test
    void intersectAndMinusPartitionASet() {
        var a = spans(0, 100, 200, 300);
        var b = spans(50, 250, 280, 400);
        assertThat(of(a.intersect(b))).containsExactly(List.of(50L, 100L), List.of(200L, 250L), List.of(280L, 300L));
        assertThat(of(a.minus(b))).containsExactly(List.of(0L, 50L), List.of(250L, 280L));
        assertThat(a.intersect(b).millis() + a.minus(b).millis()).isEqualTo(a.millis());
        assertThat(of(b.minus(a))).containsExactly(List.of(100L, 200L), List.of(300L, 400L));
        assertThat(a.minus(Spans.EMPTY).millis()).isEqualTo(200);
        assertThat(Spans.EMPTY.minus(a).isEmpty()).isTrue();
    }

    @Test
    void minusHandlesSpansCoveringSeveralTargets() {
        var a = spans(0, 10, 20, 30, 40, 50);
        assertThat(of(a.minus(spans(5, 45)))).containsExactly(List.of(0L, 5L), List.of(45L, 50L));
        assertThat(a.minus(spans(-5, 60)).isEmpty()).isTrue();
        assertThat(Spans.of(10, 10).isEmpty()).isTrue();
        assertThat(Spans.of(0, 60_000).minutes()).isEqualTo(1.0);
    }
}
