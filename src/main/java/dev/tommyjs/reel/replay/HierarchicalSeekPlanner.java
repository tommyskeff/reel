package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.storage.model.TrackSnapshot;
import dev.tommyjs.reel.storage.model.TrackSpan;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * A seek planner that classifies spans by their frame range width into resolution
 * tiers, then uses the widest-range spans ("highway") for coarse seeking and
 * progressively includes narrower spans for precision near endpoints.
 *
 * <p>If a search at a given tier fails to find a path, the planner merges in the
 * next tier down and retries, eventually falling back to full Dijkstra.</p>
 */
public class HierarchicalSeekPlanner implements ReelSeekPlanner {

    @Override
    public @NotNull TrackPlan prepare(@NotNull List<TrackSpan> spans,
                                      @NotNull List<TrackSnapshot> snapshots,
                                      boolean reversible) {
        List<List<TrackSpan>> tiers = classifySpans(spans);
        return new HierarchicalTrackPlan(tiers, snapshots, reversible);
    }

    /**
     * Classifies spans into resolution tiers based on frame range width.
     * Sorts spans by range width, then cuts at points where the width jumps
     * by more than 3x, producing natural tier boundaries.
     */
    private List<List<TrackSpan>> classifySpans(List<TrackSpan> spans) {
        if (spans.isEmpty()) {
            return List.of();
        }

        List<TrackSpan> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparingLong(s -> Math.abs(s.targetFrame() - s.referenceFrame())));

        List<List<TrackSpan>> tiers = new ArrayList<>();
        List<TrackSpan> currentTier = new ArrayList<>();
        long prevRange = Math.max(1, Math.abs(sorted.get(0).targetFrame() - sorted.get(0).referenceFrame()));

        for (TrackSpan span : sorted) {
            long range = Math.max(1, Math.abs(span.targetFrame() - span.referenceFrame()));
            if (!currentTier.isEmpty() && range > prevRange * 3) {
                tiers.add(currentTier);
                currentTier = new ArrayList<>();
            }
            currentTier.add(span);
            prevRange = range;
        }
        tiers.add(currentTier);

        return tiers;
    }

    private static class HierarchicalTrackPlan implements TrackPlan {

        private final List<List<TrackSpan>> tiers;
        private final List<TrackSnapshot> snapshots;
        private final boolean reversible;

        HierarchicalTrackPlan(List<List<TrackSpan>> tiers, List<TrackSnapshot> snapshots,
                              boolean reversible) {
            this.tiers = tiers;
            this.snapshots = snapshots;
            this.reversible = reversible;
        }

        @Override
        public @NotNull List<SeekStep> seek(long reference, long toFrame) {
            if (reference == toFrame) return List.of();
            if (snapshots.isEmpty() && allTiersEmpty()) return List.of();

            long target = resolveTarget(toFrame);
            if (target < 0) return List.of();
            if (reference == target) return List.of();

            // Single tier — just run full Dijkstra
            if (tiers.size() <= 1) {
                return fullDijkstra(reference, target);
            }

            // Start with top tier only, progressively include lower tiers
            Map<Long, List<Edge>> adj = new HashMap<>();
            for (int tier = tiers.size() - 1; tier >= 0; tier--) {
                addSpansToAdjacency(adj, tiers.get(tier));
                List<SeekStep> result = dijkstra(adj, snapshots, reference, target);
                if (!result.isEmpty()) return result;
            }

            return List.of();
        }

        private List<SeekStep> fullDijkstra(long fromFrame, long target) {
            Map<Long, List<Edge>> adj = new HashMap<>();
            for (List<TrackSpan> tier : tiers) {
                addSpansToAdjacency(adj, tier);
            }
            return dijkstra(adj, snapshots, fromFrame, target);
        }

        private void addSpansToAdjacency(Map<Long, List<Edge>> adj, List<TrackSpan> spans) {
            for (TrackSpan span : spans) {
                adj.computeIfAbsent(span.referenceFrame(), k -> new ArrayList<>())
                        .add(new Edge(span.targetFrame(), span.cost(), new SeekStep.Forward(span)));
                if (reversible) {
                    adj.computeIfAbsent(span.targetFrame(), k -> new ArrayList<>())
                            .add(new Edge(span.referenceFrame(), span.cost(), new SeekStep.Backward(span)));
                }
            }
        }

        private List<SeekStep> dijkstra(Map<Long, List<Edge>> adjacency,
                                        List<TrackSnapshot> snapshots,
                                        long from, long target) {
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

            while (!pq.isEmpty()) {
                long[] entry = pq.poll();
                long cost = entry[0];
                long frame = entry[1];

                if (cost > dist.getOrDefault(frame, Long.MAX_VALUE)) continue;
                if (frame == target) break;

                List<Edge> edges = adjacency.getOrDefault(frame, List.of());
                for (Edge edge : edges) {
                    long newCost = cost + edge.cost;
                    if (newCost < dist.getOrDefault(edge.to, Long.MAX_VALUE)) {
                        dist.put(edge.to, newCost);
                        prev.put(edge.to, new Predecessor(frame, edge.step));
                        pq.add(new long[]{newCost, edge.to});
                    }
                }
            }

            if (!dist.containsKey(target)) return List.of();

            return reconstructPath(prev, from, target);
        }

        private long resolveTarget(long toFrame) {
            long best = Long.MIN_VALUE;
            for (List<TrackSpan> tier : tiers) {
                for (TrackSpan span : tier) {
                    if (span.referenceFrame() <= toFrame) best = Math.max(best, span.referenceFrame());
                    if (span.targetFrame() <= toFrame) best = Math.max(best, span.targetFrame());
                }
            }
            for (TrackSnapshot snap : snapshots) {
                if (snap.frame() <= toFrame) best = Math.max(best, snap.frame());
            }
            return best == Long.MIN_VALUE ? -1 : best;
        }

        private boolean allTiersEmpty() {
            for (List<TrackSpan> tier : tiers) {
                if (!tier.isEmpty()) return false;
            }
            return true;
        }

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

    private record Edge(long to, int cost, SeekStep step) {}

    private record Predecessor(long fromFrame, SeekStep step) {}

}
