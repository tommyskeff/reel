package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.storage.model.TrackSnapshot;
import dev.tommyjs.reel.storage.model.TrackSpan;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SeekPlannerTest {

    static Stream<ReelSeekPlanner> planners() {
        return Stream.of(new DijkstraSeekPlanner(), new HierarchicalSeekPlanner(), new TieredSeekPlanner());
    }

    private List<SeekStep> plan(ReelSeekPlanner planner, List<TrackSpan> spans,
                                List<TrackSnapshot> snapshots, boolean reversible,
                                long from, long to) {
        return planner.prepare(spans, snapshots, reversible).seek(from, to);
    }

    // --- basic cases ---

    @ParameterizedTest
    @MethodSource("planners")
    void sameFrameReturnsEmpty(ReelSeekPlanner planner) {
        var steps = plan(planner, List.of(span(0, 5, 1)), List.of(), false, 3, 3);
        assertTrue(steps.isEmpty());
    }

    @ParameterizedTest
    @MethodSource("planners")
    void noDataReturnsEmpty(ReelSeekPlanner planner) {
        var steps = plan(planner, List.of(), List.of(), false, 0, 5);
        assertTrue(steps.isEmpty());
    }

    @ParameterizedTest
    @MethodSource("planners")
    void singleSpanForward(ReelSeekPlanner planner) {
        var steps = plan(planner, List.of(span(0, 5, 1)), List.of(), false, 0, 5);
        assertEquals(1, steps.size());
        assertForward(steps.get(0), 0, 5);
    }

    @ParameterizedTest
    @MethodSource("planners")
    void chainedSpansForward(ReelSeekPlanner planner) {
        var steps = plan(planner,
                List.of(span(0, 3, 1), span(3, 7, 1), span(7, 10, 1)),
                List.of(), false, 0, 10);
        assertEquals(3, steps.size());
        assertForward(steps.get(0), 0, 3);
        assertForward(steps.get(1), 3, 7);
        assertForward(steps.get(2), 7, 10);
    }

    // --- snapshots ---

    @ParameterizedTest
    @MethodSource("planners")
    void snapshotCheaperThanSpans(ReelSeekPlanner planner) {
        var steps = plan(planner,
                List.of(span(0, 5, 10), span(5, 10, 10)),
                List.of(snapshot(10, 3)),
                false, 0, 10);
        assertEquals(1, steps.size());
        assertSnapshot(steps.get(0), 10);
    }

    @ParameterizedTest
    @MethodSource("planners")
    void spansCheaperThanSnapshot(ReelSeekPlanner planner) {
        var steps = plan(planner,
                List.of(span(0, 5, 1), span(5, 10, 1)),
                List.of(snapshot(10, 100)),
                false, 0, 10);
        assertEquals(2, steps.size());
        assertForward(steps.get(0), 0, 5);
        assertForward(steps.get(1), 5, 10);
    }

    @ParameterizedTest
    @MethodSource("planners")
    void snapshotPlusSpan(ReelSeekPlanner planner) {
        var steps = plan(planner,
                List.of(span(0, 5, 10), span(5, 10, 1)),
                List.of(snapshot(5, 2)),
                false, 0, 10);
        assertEquals(2, steps.size());
        assertSnapshot(steps.get(0), 5);
        assertForward(steps.get(1), 5, 10);
    }

    @ParameterizedTest
    @MethodSource("planners")
    void multipleSnapshots(ReelSeekPlanner planner) {
        var steps = plan(planner,
                List.of(span(5, 10, 1)),
                List.of(snapshot(0, 100), snapshot(5, 2)),
                false, -1, 10);
        assertEquals(2, steps.size());
        assertSnapshot(steps.get(0), 5);
        assertForward(steps.get(1), 5, 10);
    }

    // --- backward seeking ---

    @ParameterizedTest
    @MethodSource("planners")
    void backwardWithReversible(ReelSeekPlanner planner) {
        var steps = plan(planner,
                List.of(span(0, 5, 1)),
                List.of(),
                true, 5, 0);
        assertEquals(1, steps.size());
        assertBackward(steps.get(0), 0, 5);
    }

    @ParameterizedTest
    @MethodSource("planners")
    void backwardWithoutReversibleUsesSnapshot(ReelSeekPlanner planner) {
        var steps = plan(planner,
                List.of(span(0, 5, 1)),
                List.of(snapshot(0, 2)),
                false, 5, 0);
        assertEquals(1, steps.size());
        assertSnapshot(steps.get(0), 0);
    }

    @ParameterizedTest
    @MethodSource("planners")
    void backwardWithoutReversibleNoSnapshotReturnsEmpty(ReelSeekPlanner planner) {
        var steps = plan(planner,
                List.of(span(0, 5, 1)),
                List.of(),
                false, 5, 0);
        assertTrue(steps.isEmpty());
    }

    // --- target resolution ---

    @ParameterizedTest
    @MethodSource("planners")
    void targetResolvesToLargestReachableFrame(ReelSeekPlanner planner) {
        var steps = plan(planner,
                List.of(span(0, 3, 1), span(3, 7, 1)),
                List.of(),
                false, 0, 100);
        assertEquals(2, steps.size());
        assertForward(steps.get(1), 3, 7);
    }

    @ParameterizedTest
    @MethodSource("planners")
    void targetBeyondAllDataResolvesToLast(ReelSeekPlanner planner) {
        var steps = plan(planner,
                List.of(),
                List.of(snapshot(5, 1)),
                false, -1, 999);
        assertEquals(1, steps.size());
        assertSnapshot(steps.get(0), 5);
    }

    // --- from frame not in graph ---

    @ParameterizedTest
    @MethodSource("planners")
    void fromFrameNotInGraphUsesSnapshot(ReelSeekPlanner planner) {
        var steps = plan(planner,
                List.of(span(5, 10, 1)),
                List.of(snapshot(5, 2)),
                false, -1, 10);
        assertEquals(2, steps.size());
        assertSnapshot(steps.get(0), 5);
        assertForward(steps.get(1), 5, 10);
    }

    // --- complex graph ---

    @ParameterizedTest
    @MethodSource("planners")
    void cheapestPathThroughComplexGraph(ReelSeekPlanner planner) {
        // Two paths: 0→5→10 costs 2+2=4, or snapshot@10 costs 100
        var steps = plan(planner,
                List.of(span(0, 5, 2), span(5, 10, 2)),
                List.of(snapshot(10, 100)),
                false, 0, 10);
        assertEquals(2, steps.size());
        assertForward(steps.get(0), 0, 5);
        assertForward(steps.get(1), 5, 10);
    }

    @ParameterizedTest
    @MethodSource("planners")
    void cheapestPathPrefersShortcut(ReelSeekPlanner planner) {
        // Two paths: 0→5→10 costs 10+10=20, or 0→10 costs 5
        var steps = plan(planner,
                List.of(span(0, 5, 10), span(5, 10, 10), span(0, 10, 5)),
                List.of(),
                false, 0, 10);
        assertEquals(1, steps.size());
        assertForward(steps.get(0), 0, 10);
    }

    // --- zero-cost edges ---

    @ParameterizedTest
    @MethodSource("planners")
    void zeroCostEdges(ReelSeekPlanner planner) {
        var steps = plan(planner,
                List.of(span(0, 5, 0), span(5, 10, 0)),
                List.of(),
                false, 0, 10);
        assertEquals(2, steps.size());
    }

    // --- prepare reuse ---

    @ParameterizedTest
    @MethodSource("planners")
    void preparedPlanCanBeReusedForMultipleSeeks(ReelSeekPlanner planner) {
        var plan = planner.prepare(
                List.of(span(0, 5, 1), span(5, 10, 1)),
                List.of(snapshot(0, 2)),
                true);

        var forward = plan.seek(0, 10);
        assertEquals(2, forward.size());

        var backward = plan.seek(10, 0);
        assertFalse(backward.isEmpty());

        var sameFrame = plan.seek(5, 5);
        assertTrue(sameFrame.isEmpty());
    }

    // --- complex realistic scenario ---

    @ParameterizedTest
    @MethodSource("planners")
    void burstyRecordingWithGaps(ReelSeekPlanner planner) {
        // Simulate an entity that emits dense bursts of activity separated by long quiet periods.
        // Burst 1: frames 0-50, one span per frame (base layer)
        // Gap: frames 50-500, no activity
        // Burst 2: frames 500-530
        // Gap: frames 530-2000
        // Burst 3: frames 2000-2080
        // Gap: frames 2080-5000
        // Burst 4: frames 5000-5020
        //
        // Higher-layer condensed spans bridge the bursts.
        // Snapshots placed at burst boundaries.

        List<TrackSpan> spans = new ArrayList<>();
        List<TrackSnapshot> snapshots = new ArrayList<>();

        long refIdCounter = 0;

        // Burst 1: dense base spans, frames 0-50
        for (int i = 0; i < 50; i++) {
            spans.add(new TrackSpan(refIdCounter++, i, i + 1, 1));
        }
        // Condensed L1 spans covering burst 1 in chunks of 10
        for (int i = 0; i < 50; i += 10) {
            spans.add(new TrackSpan(refIdCounter++, i, i + 10, 3));
        }
        // Condensed L2 span covering all of burst 1
        spans.add(new TrackSpan(refIdCounter++, 0, 50, 8));

        snapshots.add(new TrackSnapshot(refIdCounter++, 0, 10));

        // Burst 2: dense base spans, frames 500-530
        for (int i = 500; i < 530; i++) {
            spans.add(new TrackSpan(refIdCounter++, i, i + 1, 1));
        }
        // Condensed L1 spans
        for (int i = 500; i < 530; i += 10) {
            spans.add(new TrackSpan(refIdCounter++, i, i + 10, 3));
        }
        // Condensed L2 span
        spans.add(new TrackSpan(refIdCounter++, 500, 530, 7));

        snapshots.add(new TrackSnapshot(refIdCounter++, 500, 10));

        // Bridge span from burst 1 to burst 2 (high layer)
        spans.add(new TrackSpan(refIdCounter++, 50, 500, 15));

        // Burst 3: dense base spans, frames 2000-2080
        for (int i = 2000; i < 2080; i++) {
            spans.add(new TrackSpan(refIdCounter++, i, i + 1, 1));
        }
        for (int i = 2000; i < 2080; i += 10) {
            spans.add(new TrackSpan(refIdCounter++, i, i + 10, 3));
        }
        spans.add(new TrackSpan(refIdCounter++, 2000, 2080, 12));

        snapshots.add(new TrackSnapshot(refIdCounter++, 2000, 10));

        // Bridge from burst 2 to burst 3
        spans.add(new TrackSpan(refIdCounter++, 530, 2000, 20));

        // Burst 4: dense base spans, frames 5000-5020
        for (int i = 5000; i < 5020; i++) {
            spans.add(new TrackSpan(refIdCounter++, i, i + 1, 1));
        }
        spans.add(new TrackSpan(refIdCounter++, 5000, 5010, 3));
        spans.add(new TrackSpan(refIdCounter++, 5010, 5020, 3));
        spans.add(new TrackSpan(refIdCounter++, 5000, 5020, 6));

        snapshots.add(new TrackSnapshot(refIdCounter++, 5000, 10));

        // Bridge from burst 3 to burst 4
        spans.add(new TrackSpan(refIdCounter++, 2080, 5000, 25));

        // --- seek tests ---

        var plan = planner.prepare(spans, snapshots, false);

        // Forward across entire recording
        var fullForward = plan.seek(0, 5020);
        assertValidPath(fullForward, 0, 5020);

        // Forward within a single burst
        var withinBurst = plan.seek(505, 525);
        assertValidPath(withinBurst, 505, 525);

        // Forward across a gap
        var acrossGap = plan.seek(40, 510);
        assertValidPath(acrossGap, 40, 510);

        // From snapshot mid-recording
        var fromSnapshot = plan.seek(-1, 2050);
        assertValidPath(fromSnapshot, -1, 2050);

        // Seek to frame in a gap (should resolve to end of previous burst)
        var intoGap = plan.seek(0, 300);
        assertFalse(intoGap.isEmpty(), "Should find a path to nearest reachable frame");
        assertValidPathReachable(intoGap, 0);
    }

    @ParameterizedTest
    @MethodSource("planners")
    void burstyRecordingReversible(ReelSeekPlanner planner) {
        List<TrackSpan> spans = new ArrayList<>();
        List<TrackSnapshot> snapshots = new ArrayList<>();
        long refIdCounter = 0;

        // Two bursts with a bridge
        for (int i = 0; i < 30; i++) {
            spans.add(new TrackSpan(refIdCounter++, i, i + 1, 1));
        }
        spans.add(new TrackSpan(refIdCounter++, 0, 30, 8));
        snapshots.add(new TrackSnapshot(refIdCounter++, 0, 10));

        spans.add(new TrackSpan(refIdCounter++, 30, 200, 15));

        for (int i = 200; i < 240; i++) {
            spans.add(new TrackSpan(refIdCounter++, i, i + 1, 1));
        }
        spans.add(new TrackSpan(refIdCounter++, 200, 240, 10));
        snapshots.add(new TrackSnapshot(refIdCounter++, 200, 10));

        var plan = planner.prepare(spans, snapshots, true);

        // Backward across the full recording
        var backward = plan.seek(240, 0);
        assertValidPath(backward, 240, 0);

        // Backward within a burst
        var withinBurst = plan.seek(220, 205);
        assertValidPath(withinBurst, 220, 205);

        // Backward across a gap
        var acrossGap = plan.seek(210, 10);
        assertValidPath(acrossGap, 210, 10);
    }

    /**
     * Asserts the step sequence forms a valid path from {@code expectedStart} to {@code expectedEnd}.
     * Each step must chain: the target of step N must equal the source of step N+1.
     */
    private void assertValidPath(List<SeekStep> steps, long expectedStart, long expectedEnd) {
        assertFalse(steps.isEmpty(), "Expected non-empty path from " + expectedStart + " to " + expectedEnd);

        long current = expectedStart;
        for (int i = 0; i < steps.size(); i++) {
            SeekStep step = steps.get(i);
            if (step instanceof SeekStep.Snapshot s) {
                // Snapshots can teleport — just update current
                current = s.snapshot().frame();
            } else if (step instanceof SeekStep.Forward f) {
                assertEquals(current, f.span().referenceFrame(),
                        "Step " + i + " forward span doesn't chain from current frame " + current);
                current = f.span().targetFrame();
            } else if (step instanceof SeekStep.Backward b) {
                assertEquals(current, b.span().targetFrame(),
                        "Step " + i + " backward span doesn't chain from current frame " + current);
                current = b.span().referenceFrame();
            }
        }

        assertEquals(expectedEnd, current,
                "Path ended at frame " + current + " but expected " + expectedEnd);
    }

    /**
     * Asserts the steps form a valid chain from the given start, ending at any reachable frame.
     */
    private void assertValidPathReachable(List<SeekStep> steps, long expectedStart) {
        assertFalse(steps.isEmpty());
        long current = expectedStart;
        for (int i = 0; i < steps.size(); i++) {
            SeekStep step = steps.get(i);
            if (step instanceof SeekStep.Snapshot s) {
                current = s.snapshot().frame();
            } else if (step instanceof SeekStep.Forward f) {
                assertEquals(current, f.span().referenceFrame(),
                        "Step " + i + " forward span doesn't chain from current frame " + current);
                current = f.span().targetFrame();
            } else if (step instanceof SeekStep.Backward b) {
                assertEquals(current, b.span().targetFrame(),
                        "Step " + i + " backward span doesn't chain from current frame " + current);
                current = b.span().referenceFrame();
            }
        }
    }

    // --- helpers ---

    private static TrackSpan span(long from, long to, int cost) {
        return new TrackSpan(from * 100 + to, from, to, cost);
    }

    private static TrackSnapshot snapshot(long frame, int cost) {
        return new TrackSnapshot(frame * 1000, frame, cost);
    }

    private void assertForward(SeekStep step, long from, long to) {
        assertTrue(step instanceof SeekStep.Forward, "Expected Forward, got " + step);
        SeekStep.Forward f = (SeekStep.Forward) step;
        assertEquals(from, f.span().referenceFrame());
        assertEquals(to, f.span().targetFrame());
    }

    private void assertBackward(SeekStep step, long from, long to) {
        assertTrue(step instanceof SeekStep.Backward, "Expected Backward, got " + step);
        SeekStep.Backward b = (SeekStep.Backward) step;
        assertEquals(from, b.span().referenceFrame());
        assertEquals(to, b.span().targetFrame());
    }

    private void assertSnapshot(SeekStep step, long frame) {
        assertTrue(step instanceof SeekStep.Snapshot, "Expected Snapshot, got " + step);
        SeekStep.Snapshot s = (SeekStep.Snapshot) step;
        assertEquals(frame, s.snapshot().frame());
    }

}
