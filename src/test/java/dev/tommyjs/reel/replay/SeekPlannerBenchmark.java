package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.storage.model.TrackSnapshot;
import dev.tommyjs.reel.storage.model.TrackSpan;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Run with:
 * mvn -DskipTests test-compile exec:java
 *   -Dexec.mainClass=org.openjdk.jmh.Main
 *   -Dexec.classpathScope=test
 *   -Dexec.args='SeekPlannerBenchmark.*'
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SeekPlannerBenchmark {

    @State(Scope.Benchmark)
    public static class DataState {

        @Param({"recording", "dijkstra", "hierarchical", "tiered"})
        public String plannerType;

        @Param({"1000", "10000", "50000"})
        public int trackLength;

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
            TrackData trackData = switch (profile) {
                case "dense" -> dense(trackLength);
                case "bursty" -> bursty(trackLength);
                case "snapshotHeavy" -> snapshotHeavy(trackLength);
                default -> throw new IllegalArgumentException("Unknown profile: " + profile);
            };

            this.spans = trackData.spans;
            this.snapshots = trackData.snapshots;
            this.queries = buildQueries(trackData.reachableFrames, trackLength, 256);

            this.planner = createPlanner(plannerType);
            this.preparedPlan = planner.prepare(spans, snapshots, reversible);
            this.cursor = 0;
        }

        private ReelSeekPlanner createPlanner(String type) {
            return switch (type) {
                case "recording" -> new RecordingSeekPlanner();
                case "dijkstra" -> new DijkstraSeekPlanner();
                case "hierarchical" -> new HierarchicalSeekPlanner();
                case "tiered" -> new TieredSeekPlanner();
                default -> throw new IllegalArgumentException("Unknown planner: " + type);
            };
        }

        private Query nextQuery() {
            Query query = queries.get(cursor);
            cursor = (cursor + 1) % queries.size();
            return query;
        }
    }

    @Benchmark
    public void seekPreparedPlan(DataState state, Blackhole blackhole) {
        Query query = state.nextQuery();
        List<SeekStep> steps = state.preparedPlan.seek(query.from, query.to);
        blackhole.consume(steps);
    }

    @Benchmark
    public void prepareOnly(DataState state, Blackhole blackhole) {
        ReelSeekPlanner.TrackPlan plan = state.planner.prepare(state.spans, state.snapshots, state.reversible);
        blackhole.consume(plan);
    }

    private static List<Query> buildQueries(List<Long> reachableFrames, int trackLength, int count) {
        if (reachableFrames.isEmpty()) {
            return List.of(new Query(-1, trackLength));
        }

        Random random = new Random(12345L);
        List<Query> queries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long from = reachableFrames.get(random.nextInt(reachableFrames.size()));
            long to;
            if (i % 5 == 0) {
                to = trackLength + random.nextInt(Math.max(1, trackLength / 10));
            } else {
                to = reachableFrames.get(random.nextInt(reachableFrames.size()));
            }
            queries.add(new Query(from, to));
        }

        return queries;
    }

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

        for (int i = 0; i + 10 <= length; i += 10) {
            spans.add(new TrackSpan(ref++, i, i + 10, 2));
        }
        for (int i = 0; i + 100 <= length; i += 100) {
            spans.add(new TrackSpan(ref++, i, i + 100, 4));
        }
        for (int i = 0; i <= length; i += Math.max(1, length / 10)) {
            snapshots.add(new TrackSnapshot(ref++, i, 8));
        }

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

        int start = 0;
        int previousEnd = -1;
        for (int b = 0; b < bursts && start < length; b++) {
            int end = Math.min(length, start + burstSize);
            if (previousEnd >= 0 && previousEnd < start) {
                spans.add(new TrackSpan(ref++, previousEnd, start, 7));
            }

            for (int i = start; i < end; i++) {
                spans.add(new TrackSpan(ref++, i, i + 1, 1));
                reachable.add((long) i);
            }
            reachable.add((long) end);

            for (int i = start; i + 10 <= end; i += 10) {
                spans.add(new TrackSpan(ref++, i, i + 10, 2));
            }
            if (start + 1 <= end) {
                snapshots.add(new TrackSnapshot(ref++, start, 5));
            }

            previousEnd = end;
            start += gap;
        }

        if (snapshots.isEmpty()) {
            snapshots.add(new TrackSnapshot(ref, 0, 5));
        }

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

        for (int i = 0; i + (step * 10) <= length; i += (step * 10)) {
            spans.add(new TrackSpan(ref++, i, i + (step * 10), 3));
        }

        return new TrackData(spans, snapshots, reachable);
    }

    private record Query(long from, long to) {
    }

    private record TrackData(List<TrackSpan> spans, List<TrackSnapshot> snapshots, List<Long> reachableFrames) {
    }
}
