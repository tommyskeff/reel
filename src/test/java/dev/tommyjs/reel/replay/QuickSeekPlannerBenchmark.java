package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.storage.model.TrackSnapshot;
import dev.tommyjs.reel.storage.model.TrackSpan;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * A stripped-down version of {@link SeekPlannerBenchmark} intended for rapid iteration
 * on {@link RecordingSeekPlanner}. Runs in ~2 minutes.
 *
 * <p>Scope decisions vs. the full benchmark:
 * <ul>
 *   <li>Only {@code recording} and {@code tiered} (the latter as a fixed baseline).</li>
 *   <li>Single track length of 10 000 — enough to expose scaling issues without being slow.</li>
 *   <li>All three data profiles kept (dense / bursty / snapshotHeavy) — each stresses a
 *       different code path.</li>
 *   <li>Reversible on/off kept — backward seek uses a separate structure, so it matters.</li>
 *   <li>2 warmup + 3 measurement iterations × 1s each instead of 3 + 5.</li>
 * </ul>
 *
 * <p>Run with:
 * <pre>
 * mvn -DskipTests test-compile exec:java
 *   -Dexec.mainClass=org.openjdk.jmh.Main
 *   -Dexec.classpathScope=test
 *   -Dexec.args='QuickSeekPlannerBenchmark.*'
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class QuickSeekPlannerBenchmark {

    private static final int TRACK_LENGTH = 10_000;

    @State(Scope.Benchmark)
    public static class DataState {

        @Param({"recording", "tiered"})
        public String plannerType;

        @Param({"dense", "bursty", "snapshotHeavy"})
        public String profile;

        @Param({"false", "true"})
        public boolean reversible;

        private List<TrackSpan> spans;
        private List<TrackSnapshot> snapshots;
        private List<Query> queries;

        private ReelSeekPlanner planner;
        private ReelSeekPlanner.TrackPlan preparedPlan;
        private int cursor;

        @Setup(Level.Trial)
        public void setup() {
            TrackData data = switch (profile) {
                case "dense" -> dense(TRACK_LENGTH);
                case "bursty" -> bursty(TRACK_LENGTH);
                case "snapshotHeavy" -> snapshotHeavy(TRACK_LENGTH);
                default -> throw new IllegalArgumentException("Unknown profile: " + profile);
            };
            this.spans = data.spans;
            this.snapshots = data.snapshots;
            this.queries = buildQueries(data.reachableFrames, TRACK_LENGTH, 128);

            this.planner = switch (plannerType) {
                case "recording" -> new RecordingSeekPlanner();
                case "tiered" -> new TieredSeekPlanner();
                default -> throw new IllegalArgumentException("Unknown planner: " + plannerType);
            };
            this.preparedPlan = planner.prepare(spans, snapshots, reversible);
            this.cursor = 0;
        }

        private Query nextQuery() {
            Query q = queries.get(cursor);
            cursor = (cursor + 1) % queries.size();
            return q;
        }
    }

    @Benchmark
    public void prepareOnly(DataState state, Blackhole blackhole) {
        blackhole.consume(state.planner.prepare(state.spans, state.snapshots, state.reversible));
    }

    @Benchmark
    public void seekPreparedPlan(DataState state, Blackhole blackhole) {
        Query q = state.nextQuery();
        blackhole.consume(state.preparedPlan.seek(q.from, q.to));
    }

    // --- Generators (shrunken copies from SeekPlannerBenchmark; kept local to avoid coupling). ---

    private static TrackData dense(int length) {
        List<TrackSpan> spans = new ArrayList<>();
        List<TrackSnapshot> snapshots = new ArrayList<>();
        List<Long> reachable = new ArrayList<>();
        long ref = 1;
        for (int i = 0; i < length; i++) {
            spans.add(new TrackSpan(ref++, i, i + 1, 1));
            reachable.add((long) i);
        }
        reachable.add((long) length);
        for (int i = 0; i + 10 <= length; i += 10) spans.add(new TrackSpan(ref++, i, i + 10, 2));
        for (int i = 0; i + 100 <= length; i += 100) spans.add(new TrackSpan(ref++, i, i + 100, 4));
        for (int i = 0; i <= length; i += Math.max(1, length / 10))
            snapshots.add(new TrackSnapshot(ref++, i, 8));
        return new TrackData(spans, snapshots, reachable);
    }

    private static TrackData bursty(int length) {
        List<TrackSpan> spans = new ArrayList<>();
        List<TrackSnapshot> snapshots = new ArrayList<>();
        List<Long> reachable = new ArrayList<>();
        long ref = 1;
        int bursts = 20;
        int burstSize = Math.max(10, length / (bursts * 4));
        int gap = Math.max(20, length / bursts);
        int start = 0, previousEnd = -1;
        for (int b = 0; b < bursts && start < length; b++) {
            int end = Math.min(length, start + burstSize);
            if (previousEnd >= 0 && previousEnd < start)
                spans.add(new TrackSpan(ref++, previousEnd, start, 7));
            for (int i = start; i < end; i++) {
                spans.add(new TrackSpan(ref++, i, i + 1, 1));
                reachable.add((long) i);
            }
            reachable.add((long) end);
            for (int i = start; i + 10 <= end; i += 10)
                spans.add(new TrackSpan(ref++, i, i + 10, 2));
            if (start + 1 <= end) snapshots.add(new TrackSnapshot(ref++, start, 5));
            previousEnd = end;
            start += gap;
        }
        if (snapshots.isEmpty()) snapshots.add(new TrackSnapshot(ref, 0, 5));
        return new TrackData(spans, snapshots, reachable);
    }

    private static TrackData snapshotHeavy(int length) {
        List<TrackSpan> spans = new ArrayList<>();
        List<TrackSnapshot> snapshots = new ArrayList<>();
        List<Long> reachable = new ArrayList<>();
        long ref = 1;
        int step = Math.max(2, length / 1000);
        for (int i = 0; i + step <= length; i += step) {
            spans.add(new TrackSpan(ref++, i, i + step, 2));
            reachable.add((long) i);
            snapshots.add(new TrackSnapshot(ref++, i, 2));
        }
        snapshots.add(new TrackSnapshot(ref++, length, 2));
        reachable.add((long) length);
        for (int i = 0; i + (step * 10) <= length; i += (step * 10))
            spans.add(new TrackSpan(ref++, i, i + (step * 10), 3));
        return new TrackData(spans, snapshots, reachable);
    }

    private static List<Query> buildQueries(List<Long> reachable, int trackLength, int count) {
        if (reachable.isEmpty()) return List.of(new Query(-1, trackLength));
        Random r = new Random(12345L);
        List<Query> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long from = reachable.get(r.nextInt(reachable.size()));
            long to = (i % 5 == 0)
                    ? trackLength + r.nextInt(Math.max(1, trackLength / 10))
                    : reachable.get(r.nextInt(reachable.size()));
            out.add(new Query(from, to));
        }
        return out;
    }

    private record Query(long from, long to) {}
    private record TrackData(List<TrackSpan> spans, List<TrackSnapshot> snapshots, List<Long> reachableFrames) {}
}
