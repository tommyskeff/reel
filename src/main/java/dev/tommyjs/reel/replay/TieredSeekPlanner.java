package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.storage.model.TrackSnapshot;
import dev.tommyjs.reel.storage.model.TrackSpan;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * A seek planner that classifies spans into resolution tiers by frame range width,
 * then runs a single Dijkstra where lower-tier (narrower) edges are only eligible
 * near the source and target. The top tier acts as a "highway" for the bulk of
 * the journey, while lower tiers handle the first/last mile.
 *
 * <p>This is a heuristic — it may not always find the globally optimal path, but
 * avoids exploring narrow spans in the middle of the graph where they are unlikely
 * to be useful.</p>
 */
public class TieredSeekPlanner implements ReelSeekPlanner {

    /** Classifies spans into tiers and builds the adjacency graph and frame index. */
    @Override
    public @NotNull TrackPlan prepare(@NotNull List<TrackSpan> spans,
                                      @NotNull List<TrackSnapshot> snapshots,
                                      boolean reversible) {
        List<TierData> tiers = classifySpans(spans);
        Map<Long, List<TieredEdge>> adjacency = buildAdjacency(tiers, reversible);
        TreeSet<Long> reachableFrames = buildReachableFrames(adjacency, snapshots);
        return new TieredTrackPlan(adjacency, snapshots, reachableFrames);
    }

    /** Collects all node frames from the adjacency graph and snapshots into a sorted set for O(log n) target resolution. */
    private TreeSet<Long> buildReachableFrames(Map<Long, List<TieredEdge>> adjacency,
                                               List<TrackSnapshot> snapshots) {
        TreeSet<Long> frames = new TreeSet<>();
        for (var entry : adjacency.entrySet()) {
            frames.add(entry.getKey());
            for (TieredEdge edge : entry.getValue()) {
                frames.add(edge.to);
            }
        }
        for (TrackSnapshot snap : snapshots) {
            frames.add(snap.frame());
        }
        return frames;
    }

    /**
     * Sorts spans by absolute frame range width and splits them into tiers at points
     * where the width jumps by more than 3x. Each tier is assigned a radius equal to
     * the next tier up's max span width, so low-tier spans can reach the nearest
     * on-ramp to the highway. The top tier has unlimited radius.
     */
    private List<TierData> classifySpans(List<TrackSpan> spans) {
        if (spans.isEmpty()) {
            return List.of();
        }

        List<TrackSpan> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparingLong(s -> Math.abs(s.targetFrame() - s.referenceFrame())));

        List<List<TrackSpan>> groups = new ArrayList<>();
        List<TrackSpan> currentGroup = new ArrayList<>();
        long prevRange = Math.max(1, Math.abs(sorted.get(0).targetFrame() - sorted.get(0).referenceFrame()));

        for (TrackSpan span : sorted) {
            long range = Math.max(1, Math.abs(span.targetFrame() - span.referenceFrame()));
            if (!currentGroup.isEmpty() && range > prevRange * 3) {
                groups.add(currentGroup);
                currentGroup = new ArrayList<>();
            }
            currentGroup.add(span);
            prevRange = range;
        }
        groups.add(currentGroup);

        // Compute max span width per group for radius assignment
        long[] maxRanges = new long[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            for (TrackSpan span : groups.get(i)) {
                maxRanges[i] = Math.max(maxRanges[i], Math.abs(span.targetFrame() - span.referenceFrame()));
            }
        }

        int topTier = groups.size() - 1;
        List<TierData> tiers = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            // Top tier has unlimited radius; lower tiers use the next tier up's max span
            // width as their radius, so they can reach the nearest on-ramp to the highway.
            long radius = (i >= topTier) ? Long.MAX_VALUE : maxRanges[i + 1];
            tiers.add(new TierData(groups.get(i), radius));
        }

        return tiers;
    }

    /** Builds the full adjacency map from all tiers, tagging each edge with its tier's radius. */
    private Map<Long, List<TieredEdge>> buildAdjacency(List<TierData> tiers, boolean reversible) {
        Map<Long, List<TieredEdge>> adj = new HashMap<>();
        for (TierData tier : tiers) {
            for (TrackSpan span : tier.spans) {
                adj.computeIfAbsent(span.referenceFrame(), k -> new ArrayList<>())
                        .add(new TieredEdge(span.targetFrame(), span.cost(),
                                new SeekStep.Forward(span), tier.radius));
                if (reversible) {
                    adj.computeIfAbsent(span.targetFrame(), k -> new ArrayList<>())
                            .add(new TieredEdge(span.referenceFrame(), span.cost(),
                                    new SeekStep.Backward(span), tier.radius));
                }
            }
        }
        return adj;
    }

    private static class TieredTrackPlan implements TrackPlan {

        private final Map<Long, List<TieredEdge>> adjacency;
        private final List<TrackSnapshot> snapshots;
        private final TreeSet<Long> reachableFrames;

        TieredTrackPlan(Map<Long, List<TieredEdge>> adjacency,
                        List<TrackSnapshot> snapshots,
                        TreeSet<Long> reachableFrames) {
            this.adjacency = adjacency;
            this.snapshots = snapshots;
            this.reachableFrames = reachableFrames;
        }

        /** Resolves the target frame and runs a tier-filtered Dijkstra from reference to it. */
        @Override
        public @NotNull List<SeekStep> seek(long reference, long toFrame) {
            if (reference == toFrame) return List.of();
            if (adjacency.isEmpty() && snapshots.isEmpty()) return List.of();

            long target = resolveTarget(toFrame);
            if (target < 0) return List.of();
            if (reference == target) return List.of();

            return search(reference, target);
        }

        /**
         * Runs Dijkstra over the full adjacency graph, skipping edges that fail the tier
         * eligibility check. If the search exhausts the PQ without reaching the target,
         * doubles the radius multiplier and re-enqueues all settled nodes to explore
         * newly-eligible edges, reusing prior dist/prev state. Repeats until a path is
         * found or all edges are eligible.
         */
        private List<SeekStep> search(long from, long target) {
            Map<Long, Long> dist = new HashMap<>();
            Map<Long, Predecessor> prev = new HashMap<>();
            PriorityQueue<long[]> pq = new PriorityQueue<>(Comparator.comparingLong(a -> a[0]));

            dist.put(from, 0L);
            pq.add(new long[]{0, from});

            for (TrackSnapshot snap : snapshots) {
                long cost = snap.cost();
                if (cost < dist.getOrDefault(snap.frame(), Long.MAX_VALUE)) {
                    dist.put(snap.frame(), cost);
                    prev.put(snap.frame(), new Predecessor(-1, new SeekStep.Snapshot(snap)));
                    pq.add(new long[]{cost, snap.frame()});
                }
            }

            long radiusMultiplier = 1;

            while (true) {
                boolean found = runDijkstra(pq, dist, prev, from, target, radiusMultiplier);
                if (found || dist.containsKey(target)) break;

                // All edges already eligible — no path exists
                if (radiusMultiplier == Long.MAX_VALUE) break;

                // Double the radius and re-enqueue all settled nodes to explore newly-eligible edges
                long nextMultiplier = radiusMultiplier <= Long.MAX_VALUE / 2 ? radiusMultiplier * 2 : Long.MAX_VALUE;
                if (nextMultiplier == radiusMultiplier) break;
                radiusMultiplier = nextMultiplier;

                for (var entry : dist.entrySet()) {
                    pq.add(new long[]{entry.getValue(), entry.getKey()});
                }
            }

            if (!dist.containsKey(target)) return List.of();

            return reconstructPath(prev, from, target);
        }

        /** Drains the PQ using the given radius multiplier. Returns true if target was reached. */
        private boolean runDijkstra(PriorityQueue<long[]> pq, Map<Long, Long> dist,
                                    Map<Long, Predecessor> prev, long from, long target,
                                    long radiusMultiplier) {
            while (!pq.isEmpty()) {
                long[] entry = pq.poll();
                long cost = entry[0];
                long frame = entry[1];

                if (cost > dist.getOrDefault(frame, Long.MAX_VALUE)) continue;
                if (frame == target) return true;

                List<TieredEdge> edges = adjacency.getOrDefault(frame, List.of());
                for (TieredEdge edge : edges) {
                    if (!isEligible(edge, frame, from, target, radiusMultiplier)) continue;

                    long newCost = cost + edge.cost;
                    if (newCost < dist.getOrDefault(edge.to, Long.MAX_VALUE)) {
                        dist.put(edge.to, newCost);
                        prev.put(edge.to, new Predecessor(frame, edge.step));
                        pq.add(new long[]{newCost, edge.to});
                    }
                }
            }
            return false;
        }

        /** Returns true if the edge's source or destination is within the scaled tier radius of either endpoint. */
        private boolean isEligible(TieredEdge edge, long currentFrame, long from, long target,
                                   long radiusMultiplier) {
            if (edge.radius == Long.MAX_VALUE) return true;
            long scaledRadius = (radiusMultiplier <= Long.MAX_VALUE / edge.radius)
                    ? edge.radius * radiusMultiplier : Long.MAX_VALUE;
            return Math.abs(currentFrame - from) <= scaledRadius
                    || Math.abs(currentFrame - target) <= scaledRadius
                    || Math.abs(edge.to - from) <= scaledRadius
                    || Math.abs(edge.to - target) <= scaledRadius;
        }

        /** Returns the largest reachable frame &le; toFrame, or -1 if none exists. */
        private long resolveTarget(long toFrame) {
            Long target = reachableFrames.floor(toFrame);
            return target == null ? -1 : target;
        }

        /** Walks the predecessor map backwards from target to source and reverses to produce the step list. */
        private List<SeekStep> reconstructPath(Map<Long, Predecessor> prev, long from, long target) {
            List<SeekStep> steps = new ArrayList<>();
            long current = target;
            while (current != from) {
                Predecessor p = prev.get(current);
                if (p == null) break;
                steps.add(p.step);
                if (p.fromFrame == -1) break;
                current = p.fromFrame;
            }
            Collections.reverse(steps);
            return steps;
        }

    }

    private record TierData(List<TrackSpan> spans, long radius) {}

    private record TieredEdge(long to, int cost, SeekStep step, long radius) {}

    private record Predecessor(long fromFrame, SeekStep step) {}

}
