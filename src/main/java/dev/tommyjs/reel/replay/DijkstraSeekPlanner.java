package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.storage.model.TrackSnapshot;
import dev.tommyjs.reel.storage.model.TrackSpan;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class DijkstraSeekPlanner implements ReelSeekPlanner {

    @Override
    public @NotNull TrackPlan prepare(@NotNull List<TrackSpan> spans,
                                      @NotNull List<TrackSnapshot> snapshots,
                                      boolean reversible) {
        Map<Long, List<Edge>> adjacency = buildAdjacency(spans, reversible);
        return new DijkstraTrackPlan(adjacency, snapshots);
    }

    private Map<Long, List<Edge>> buildAdjacency(List<TrackSpan> spans, boolean reversible) {
        Map<Long, List<Edge>> adj = new HashMap<>();
        for (TrackSpan span : spans) {
            adj.computeIfAbsent(span.referenceFrame(), k -> new ArrayList<>())
                    .add(new Edge(span.targetFrame(), span.cost(), new SeekStep.Forward(span)));
            if (reversible) {
                adj.computeIfAbsent(span.targetFrame(), k -> new ArrayList<>())
                        .add(new Edge(span.referenceFrame(), span.cost(), new SeekStep.Backward(span)));
            }
        }
        return adj;
    }

    private static class DijkstraTrackPlan implements TrackPlan {

        private final Map<Long, List<Edge>> adjacency;
        private final List<TrackSnapshot> snapshots;

        DijkstraTrackPlan(Map<Long, List<Edge>> adjacency, List<TrackSnapshot> snapshots) {
            this.adjacency = adjacency;
            this.snapshots = snapshots;
        }

        @Override
        public @NotNull List<SeekStep> seek(long reference, long toFrame) {
            if (reference == toFrame) return List.of();
            if (adjacency.isEmpty() && snapshots.isEmpty()) return List.of();

            long target = resolveTarget(toFrame);
            if (target < 0) return List.of();
            if (reference == target) return List.of();

            return search(reference, target);
        }

        private long resolveTarget(long toFrame) {
            long best = Long.MIN_VALUE;
            for (var edges : adjacency.entrySet()) {
                long frame = edges.getKey();
                if (frame <= toFrame) best = Math.max(best, frame);
                for (Edge edge : edges.getValue()) {
                    if (edge.to <= toFrame) best = Math.max(best, edge.to);
                }
            }
            for (TrackSnapshot snap : snapshots) {
                if (snap.frame() <= toFrame) best = Math.max(best, snap.frame());
            }
            return best == Long.MIN_VALUE ? -1 : best;
        }

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
