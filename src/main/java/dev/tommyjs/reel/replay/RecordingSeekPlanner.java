package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.storage.model.TrackSnapshot;
import dev.tommyjs.reel.storage.model.TrackSpan;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class RecordingSeekPlanner implements ReelSeekPlanner {

    private static final int DEFAULT_BRANCHING_FACTOR = 20;

    private final int branchingFactor;

    public RecordingSeekPlanner() {
        this(DEFAULT_BRANCHING_FACTOR);
    }

    public RecordingSeekPlanner(int branchingFactor) {
        this.branchingFactor = Math.max(2, branchingFactor);
    }

    @Override
    public @NotNull TrackPlan prepare(@NotNull List<TrackSpan> spans,
                                      @NotNull List<TrackSnapshot> snapshots,
                                      boolean reversible) {
        return new RecordingTrackPlan(spans, snapshots, reversible, branchingFactor);
    }

    private enum Direction {
        FORWARD,
        BACKWARD
    }

    private static final class RecordingTrackPlan implements TrackPlan {

        private final boolean reversible;
        private final List<Tier> forwardTiers;
        private final List<Tier> backwardTiers;
        private final NavigableMap<Long, TrackSnapshot> snapshotsByFrame;
        private final NavigableSet<Long> activeFrames;
        private final NavigableSet<Long> reachableFrames;

        RecordingTrackPlan(List<TrackSpan> spans, List<TrackSnapshot> snapshots,
                           boolean reversible, int branchingFactor) {
            this.reversible = reversible;

            List<TrackSpan> frameByFrame = new ArrayList<>();
            List<TrackSpan> compressed = new ArrayList<>();
            for (TrackSpan span : spans) {
                long width = Math.abs(span.targetFrame() - span.referenceFrame());
                if (width == 1 && span.targetFrame() > span.referenceFrame()) {
                    frameByFrame.add(span);
                } else if (width > 1) {
                    compressed.add(span);
                }
            }

            frameByFrame.sort(Comparator.comparingLong(TrackSpan::referenceFrame));

            TreeSet<Long> canonicalActiveFrames = new TreeSet<>();
            if (!frameByFrame.isEmpty()) {
                canonicalActiveFrames.add(frameByFrame.get(0).referenceFrame());
                for (TrackSpan span : frameByFrame) {
                    canonicalActiveFrames.add(span.targetFrame());
                }
            }

            TreeSet<Long> active = new TreeSet<>(canonicalActiveFrames);
            if (active.isEmpty()) {
                for (TrackSpan span : spans) {
                    active.add(span.referenceFrame());
                    active.add(span.targetFrame());
                }
            }
            this.activeFrames = Collections.unmodifiableNavigableSet(active);

            List<TrackSpan> alignedCompressed = new ArrayList<>();
            if (canonicalActiveFrames.isEmpty()) {
                alignedCompressed.addAll(compressed);
            } else {
                for (TrackSpan span : compressed) {
                    if (canonicalActiveFrames.contains(span.referenceFrame())
                            && canonicalActiveFrames.contains(span.targetFrame())) {
                        alignedCompressed.add(span);
                    }
                }
            }

            List<TrackSpan> sortedCompressed = new ArrayList<>(alignedCompressed);
            sortedCompressed.sort(Comparator
                    .comparingLong((TrackSpan s) -> Math.abs(s.targetFrame() - s.referenceFrame()))
                    .reversed());

            List<List<TrackSpan>> compressedTiers = partitionCompressedTiers(sortedCompressed, branchingFactor);

            List<List<TrackSpan>> allTiers = new ArrayList<>(compressedTiers.size() + 1);
            allTiers.addAll(compressedTiers);
            allTiers.add(frameByFrame);

            this.forwardTiers = buildTiers(allTiers, Direction.FORWARD);
            this.backwardTiers = reversible ? buildTiers(allTiers, Direction.BACKWARD) : List.of();

            TreeMap<Long, TrackSnapshot> snapshotsMap = new TreeMap<>();
            for (TrackSnapshot snapshot : snapshots) {
                TrackSnapshot existing = snapshotsMap.get(snapshot.frame());
                if (existing == null || snapshot.cost() < existing.cost()) {
                    snapshotsMap.put(snapshot.frame(), snapshot);
                }
            }
            this.snapshotsByFrame = Collections.unmodifiableNavigableMap(snapshotsMap);

            TreeSet<Long> reachable = new TreeSet<>();
            for (TrackSpan span : spans) {
                reachable.add(span.referenceFrame());
                reachable.add(span.targetFrame());
            }
            reachable.addAll(this.snapshotsByFrame.keySet());
            this.reachableFrames = Collections.unmodifiableNavigableSet(reachable);
        }

        @Override
        public @NotNull List<SeekStep> seek(long reference, long toFrame) {
            if (reference == toFrame) return List.of();
            if (reachableFrames.isEmpty()) return List.of();

            Long resolved = reachableFrames.floor(toFrame);
            if (resolved == null) return List.of();
            long target = resolved;
            if (reference == target) return List.of();

            if (target > reference) {
                if (target == reference + 1) {
                    List<SeekStep> hot = buildTieredPath(reference, target, Direction.FORWARD);
                    if (!hot.isEmpty()) return hot;
                }
                List<SeekStep> direct = buildTieredPath(reference, target, Direction.FORWARD);
                List<SeekStep> snapshotCandidate = buildSnapshotCandidate(target);
                return chooseCheaper(direct, snapshotCandidate);
            }

            if (!reversible) {
                return nonReversibleRewind(reference, target);
            }

            if (target == reference - 1) {
                List<SeekStep> hot = buildTieredPath(reference, target, Direction.BACKWARD);
                if (!hot.isEmpty()) return hot;
            }

            List<SeekStep> directBackward = buildTieredPath(reference, target, Direction.BACKWARD);
            List<SeekStep> snapshotCandidate = buildSnapshotCandidate(target);
            return chooseCheaper(directBackward, snapshotCandidate);
        }

        private List<SeekStep> nonReversibleRewind(long reference, long target) {
            Map.Entry<Long, TrackSnapshot> floor = snapshotsByFrame.floorEntry(target);
            if (floor == null) return List.of();

            TrackSnapshot snapshot = floor.getValue();
            if (!hasActiveFrameInRange(snapshot.frame(), reference)) {
                return List.of(new SeekStep.Snapshot(snapshot));
            }

            if (snapshot.frame() == target) {
                return List.of(new SeekStep.Snapshot(snapshot));
            }

            List<SeekStep> tail = buildTieredPath(snapshot.frame(), target, Direction.FORWARD);
            if (tail.isEmpty()) return List.of(new SeekStep.Snapshot(snapshot));

            List<SeekStep> result = new ArrayList<>(tail.size() + 1);
            result.add(new SeekStep.Snapshot(snapshot));
            result.addAll(tail);
            return result;
        }

        private List<SeekStep> buildSnapshotCandidate(long target) {
            if (snapshotsByFrame.isEmpty()) return List.of();

            TrackSnapshot before = Optional.ofNullable(snapshotsByFrame.floorEntry(target))
                    .map(Map.Entry::getValue)
                    .orElse(null);
            TrackSnapshot after = Optional.ofNullable(snapshotsByFrame.ceilingEntry(target))
                    .map(Map.Entry::getValue)
                    .orElse(null);

            TrackSnapshot chosen = nearestSnapshot(before, after, target);
            if (chosen == null) return List.of();

            if (chosen.frame() == target) {
                return List.of(new SeekStep.Snapshot(chosen));
            }

            List<SeekStep> tail;
            if (chosen.frame() < target) {
                tail = buildTieredPath(chosen.frame(), target, Direction.FORWARD);
            } else if (reversible) {
                tail = buildTieredPath(chosen.frame(), target, Direction.BACKWARD);
            } else {
                return List.of();
            }

            if (tail.isEmpty()) return List.of();

            List<SeekStep> result = new ArrayList<>(tail.size() + 1);
            result.add(new SeekStep.Snapshot(chosen));
            result.addAll(tail);
            return result;
        }

        private TrackSnapshot nearestSnapshot(TrackSnapshot before, TrackSnapshot after, long target) {
            if (before == null) return after;
            if (after == null) return before;
            long beforeDistance = Math.abs(target - before.frame());
            long afterDistance = Math.abs(after.frame() - target);
            return beforeDistance <= afterDistance ? before : after;
        }

        private boolean hasActiveFrameInRange(long startInclusive, long endInclusive) {
            if (endInclusive < startInclusive) return false;
            Long next = activeFrames.higher(startInclusive);
            return next != null && next <= endInclusive;
        }

        private List<SeekStep> chooseCheaper(List<SeekStep> first, List<SeekStep> second) {
            if (first.isEmpty()) return second;
            if (second.isEmpty()) return first;
            long firstCost = cost(first);
            long secondCost = cost(second);
            return firstCost <= secondCost ? first : second;
        }

        private long cost(List<SeekStep> path) {
            long total = 0L;
            for (SeekStep step : path) {
                if (step instanceof SeekStep.Snapshot s) {
                    total += s.snapshot().cost();
                } else if (step instanceof SeekStep.Forward f) {
                    total += f.span().cost();
                } else if (step instanceof SeekStep.Backward b) {
                    total += b.span().cost();
                }
            }
            return total;
        }

        private List<SeekStep> buildTieredPath(long from, long target, Direction direction) {
            if (from == target) return List.of();
            List<Tier> tiers = direction == Direction.FORWARD ? forwardTiers : backwardTiers;
            if (tiers.isEmpty()) return List.of();

            long current = from;
            List<SeekStep> steps = new ArrayList<>();
            for (Tier tier : tiers) {
                while (true) {
                    Edge next = tier.findNext(current, target, direction);
                    if (next == null) break;
                    steps.add(next.step);
                    current = next.end;
                    if (current == target) {
                        return steps;
                    }
                }
            }

            return List.of();
        }

        private List<List<TrackSpan>> partitionCompressedTiers(List<TrackSpan> sortedCompressed, int branchingFactor) {
            if (sortedCompressed.isEmpty()) return List.of();

            List<List<TrackSpan>> tiers = new ArrayList<>();
            int index = 0;
            long tierSize = branchingFactor;

            while (index < sortedCompressed.size()) {
                int remaining = sortedCompressed.size() - index;
                int take = (int) Math.min(tierSize, remaining);
                List<TrackSpan> tier = new ArrayList<>(sortedCompressed.subList(index, index + take));
                tiers.add(tier);
                index += take;
                if (tierSize > Integer.MAX_VALUE / (long) branchingFactor) {
                    tierSize = Integer.MAX_VALUE;
                } else {
                    tierSize *= branchingFactor;
                }
            }

            return tiers;
        }

        private List<Tier> buildTiers(List<List<TrackSpan>> allTiers, Direction direction) {
            List<Tier> result = new ArrayList<>(allTiers.size());
            for (List<TrackSpan> tierSpans : allTiers) {
                List<Edge> edges = new ArrayList<>(tierSpans.size());
                for (TrackSpan span : tierSpans) {
                    if (direction == Direction.FORWARD) {
                        if (span.targetFrame() > span.referenceFrame()) {
                            edges.add(new Edge(span.referenceFrame(), span.targetFrame(), new SeekStep.Forward(span), span.cost()));
                        }
                    } else {
                        if (span.targetFrame() > span.referenceFrame()) {
                            edges.add(new Edge(span.targetFrame(), span.referenceFrame(), new SeekStep.Backward(span), span.cost()));
                        }
                    }
                }
                edges.sort(Comparator.comparingLong(e -> e.start));
                result.add(new Tier(edges));
            }
            return result;
        }
    }

    private record Tier(List<Edge> edges) {
        private Edge findNext(long current, long target, Direction direction) {
            if (edges.isEmpty()) return null;

            int idx = lowerBound(edges, current);
            Edge best = null;
            for (int i = idx; i < edges.size(); i++) {
                Edge candidate = edges.get(i);
                if (candidate.start != current) {
                    if (candidate.start > current) break;
                    continue;
                }

                if (direction == Direction.FORWARD) {
                    if (candidate.end <= current || candidate.end > target) continue;
                    if (best == null || candidate.end > best.end
                            || (candidate.end == best.end && candidate.cost < best.cost)) {
                        best = candidate;
                    }
                } else {
                    if (candidate.end >= current || candidate.end < target) continue;
                    if (best == null || candidate.end < best.end
                            || (candidate.end == best.end && candidate.cost < best.cost)) {
                        best = candidate;
                    }
                }
            }
            return best;
        }

        private static int lowerBound(List<Edge> edges, long frame) {
            int lo = 0;
            int hi = edges.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (edges.get(mid).start < frame) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }
    }

    private record Edge(long start, long end, SeekStep step, int cost) {
    }
}
