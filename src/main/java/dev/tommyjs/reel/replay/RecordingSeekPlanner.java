package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.storage.model.TrackSnapshot;
import dev.tommyjs.reel.storage.model.TrackSpan;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tiered greedy seek planner. Compressed deltas (span &gt; 1) are bucketed by width into
 * N, N², N³, ... tiers; frame-by-frame deltas (span = 1) form the implicit bottom tier.
 * A seek greedily consumes edges from wider tiers first, dropping to narrower tiers
 * only when no edge in the current tier advances toward the target, and compares the
 * result against a nearest-snapshot candidate.
 *
 * <p>See {@code SEEK_ALGORITHM.md} for the full algorithm description.
 */
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

    private enum Direction { FORWARD, BACKWARD }

    private static final class RecordingTrackPlan implements TrackPlan {

        private final boolean reversible;

        // Tier 0 is widest; last tier is the frame-by-frame bottom.
        private final Tier[] forwardTiers;
        private final Tier[] backwardTiers;

        // Sorted primitive arrays — no Long boxing, no TreeSet/TreeMap overhead.
        // `activeFrames` is used for "is any state change between X and Y?" checks.
        private final long[] activeFrames;
        // `reachableFrames` = activeFrames ∪ snapshotFrames. Targets are snapped onto it so
        // a seek request for an arbitrary frame resolves to one we can actually land on.
        private final long[] reachableFrames;
        // Parallel arrays for snapshot lookup-by-frame.
        private final long[] snapshotFrames;
        private final TrackSnapshot[] snapshotsByIndex;

        RecordingTrackPlan(List<TrackSpan> spans, List<TrackSnapshot> snapshots,
                           boolean reversible, int branchingFactor) {
            this.reversible = reversible;

            // 1. Partition spans by width.
            //    Width 1 → bottom tier (frame-by-frame).
            //    Width > 1 → compressed tiers.
            //    Width 0 or negative → skipped.
            List<TrackSpan> frameByFrame = new ArrayList<>();
            List<TrackSpan> compressed = new ArrayList<>();
            for (TrackSpan span : spans) {
                long width = span.targetFrame() - span.referenceFrame();
                if (width == 1) frameByFrame.add(span);
                else if (width > 1) compressed.add(span);
            }
            frameByFrame.sort(Comparator.comparingLong(TrackSpan::referenceFrame));

            // 2. Canonical active frames come from the frame-by-frame chain. If there is
            //    no frame-by-frame tier, fall back to using every endpoint of every span.
            long[] canonical = buildCanonicalActiveFrames(frameByFrame);
            List<TrackSpan> aligned;
            if (canonical.length == 0) {
                aligned = compressed;
                this.activeFrames = collectEndpoints(spans);
            } else {
                // Compressed deltas whose endpoints are not in the canonical set are dropped:
                // they could otherwise introduce paths that can't chain with the bottom tier.
                aligned = new ArrayList<>(compressed.size());
                for (TrackSpan span : compressed) {
                    if (contains(canonical, span.referenceFrame())
                            && contains(canonical, span.targetFrame())) {
                        aligned.add(span);
                    }
                }
                this.activeFrames = canonical;
            }

            // 3. Partition compressed deltas into geometric tiers (widest first).
            aligned.sort(Comparator
                    .comparingLong((TrackSpan s) -> s.targetFrame() - s.referenceFrame())
                    .reversed());
            List<List<TrackSpan>> compressedTiers = partitionTiers(aligned, branchingFactor);

            // 4. Append frame-by-frame as the implicit bottom tier.
            List<List<TrackSpan>> allTiers = new ArrayList<>(compressedTiers.size() + 1);
            allTiers.addAll(compressedTiers);
            allTiers.add(frameByFrame);

            this.forwardTiers = buildTiers(allTiers, Direction.FORWARD);
            this.backwardTiers = reversible ? buildTiers(allTiers, Direction.BACKWARD) : new Tier[0];

            // 5. Build the snapshot index, keeping only the cheapest snapshot per frame.
            TreeMap<Long, TrackSnapshot> snapMap = new TreeMap<>();
            for (TrackSnapshot s : snapshots) {
                TrackSnapshot prev = snapMap.get(s.frame());
                if (prev == null || s.cost() < prev.cost()) snapMap.put(s.frame(), s);
            }
            this.snapshotFrames = new long[snapMap.size()];
            this.snapshotsByIndex = new TrackSnapshot[snapMap.size()];
            int i = 0;
            for (Map.Entry<Long, TrackSnapshot> e : snapMap.entrySet()) {
                snapshotFrames[i] = e.getKey();
                snapshotsByIndex[i] = e.getValue();
                i++;
            }

            // 6. reachableFrames = sorted merge of activeFrames and snapshot frames.
            this.reachableFrames = union(this.activeFrames, this.snapshotFrames);
        }

        @Override
        public @NotNull List<SeekStep> seek(long reference, long target) {
            if (reference == target) return List.of();
            if (reachableFrames.length == 0) return List.of();

            // Snap the caller's target to the largest reachable frame ≤ target.
            int tIdx = floorIndex(reachableFrames, target);
            if (tIdx < 0) return List.of();
            long resolved = reachableFrames[tIdx];
            if (reference == resolved) return List.of();

            if (resolved > reference) return forwardSeek(reference, resolved);
            if (reversible) return backwardSeek(reference, resolved);
            return nonReversibleRewind(reference, resolved);
        }

        /** Forward: compare the direct tiered walk against the best snapshot candidate. */
        private List<SeekStep> forwardSeek(long from, long to) {
            List<SeekStep> direct = buildTieredPath(from, to, Direction.FORWARD);
            // Optimization: when the direct walk already lands at `to` and the nearest
            // snapshot is at-or-before `from`, that snapshot's tail would walk the same
            // ground as `direct` plus extra — it cannot win. Skip the wasted work.
            if (!direct.isEmpty() && nearestSnapshotFrame(to) <= from) return direct;
            List<SeekStep> snap = buildSnapshotCandidate(to);
            return chooseCheaper(direct, snap);
        }

        /** Backward: mirror of forward. */
        private List<SeekStep> backwardSeek(long from, long to) {
            List<SeekStep> direct = buildTieredPath(from, to, Direction.BACKWARD);
            if (!direct.isEmpty() && nearestSnapshotFrame(to) >= from) return direct;
            List<SeekStep> snap = buildSnapshotCandidate(to);
            return chooseCheaper(direct, snap);
        }

        /** Frame of the snapshot nearest `target`, or {@code Long.MIN_VALUE} if none. */
        private long nearestSnapshotFrame(long target) {
            int idx = nearestSnapshotIndex(target);
            return idx < 0 ? Long.MIN_VALUE : snapshotFrames[idx];
        }

        private int nearestSnapshotIndex(long target) {
            if (snapshotFrames.length == 0) return -1;
            int floor = floorIndex(snapshotFrames, target);
            int ceil = ceilingIndex(snapshotFrames, target);
            if (floor < 0) return ceil;
            if (ceil < 0) return floor;
            long floorDist = target - snapshotFrames[floor];
            long ceilDist = snapshotFrames[ceil] - target;
            return floorDist <= ceilDist ? floor : ceil; // prefer before on ties
        }

        /**
         * Rewind on a non-reversible track: jump to the latest snapshot at-or-before the
         * target, then walk forward to the target. If there have been no state changes
         * between the snapshot and the current frame, the snapshot alone suffices.
         */
        private List<SeekStep> nonReversibleRewind(long from, long to) {
            int sIdx = floorIndex(snapshotFrames, to);
            if (sIdx < 0) return List.of();
            TrackSnapshot snap = snapshotsByIndex[sIdx];

            if (!hasActiveFrameIn(snap.frame(), from)) {
                // State at `from` == state at snap.frame == state at `to` (no changes between).
                return List.of(new SeekStep.Snapshot(snap));
            }
            if (snap.frame() == to) {
                return List.of(new SeekStep.Snapshot(snap));
            }

            List<SeekStep> tail = buildTieredPath(snap.frame(), to, Direction.FORWARD);
            // Explicit failure: never return a truncated path that lands at the wrong frame.
            if (tail.isEmpty()) return List.of();
            List<SeekStep> out = new ArrayList<>(tail.size() + 1);
            out.add(new SeekStep.Snapshot(snap));
            out.addAll(tail);
            return out;
        }

        /**
         * Candidate path via the nearest snapshot to {@code target}: apply the snapshot,
         * then walk from there to target. Walk direction follows snapshot position —
         * forward if snapshot ≤ target, backward otherwise (backward requires reversible).
         */
        private List<SeekStep> buildSnapshotCandidate(long target) {
            int idx = nearestSnapshotIndex(target);
            if (idx < 0) return List.of();
            long frame = snapshotFrames[idx];
            TrackSnapshot snap = snapshotsByIndex[idx];
            if (frame == target) return List.of(new SeekStep.Snapshot(snap));

            List<SeekStep> tail;
            if (frame < target) {
                tail = buildTieredPath(frame, target, Direction.FORWARD);
            } else if (reversible) {
                tail = buildTieredPath(frame, target, Direction.BACKWARD);
            } else {
                return List.of();
            }
            if (tail.isEmpty()) return List.of();
            List<SeekStep> out = new ArrayList<>(tail.size() + 1);
            out.add(new SeekStep.Snapshot(snap));
            out.addAll(tail);
            return out;
        }

        private List<SeekStep> chooseCheaper(List<SeekStep> a, List<SeekStep> b) {
            if (a.isEmpty()) return b;
            if (b.isEmpty()) return a;
            return cost(a) <= cost(b) ? a : b;
        }

        private long cost(List<SeekStep> path) {
            long total = 0;
            for (SeekStep s : path) {
                if (s instanceof SeekStep.Snapshot x) total += x.snapshot().cost();
                else if (s instanceof SeekStep.Forward f) total += f.span().cost();
                else if (s instanceof SeekStep.Backward b) total += b.span().cost();
            }
            return total;
        }

        /**
         * Greedy tiered walk with tier re-ascension. At every step, probe tiers widest
         * to narrowest and take the first edge starting at the current frame that
         * advances toward the target. After taking an edge, start again from the widest
         * tier — so a short detour on a narrow tier can re-enter a wider "highway" as
         * soon as the walker lands on an aligned frame. Contrast with the simpler
         * "drop once" strategy, which walks the bottom tier all the way to target once
         * it's stuck there, even when compressed deltas are available one step ahead.
         *
         * <p>Returns an empty list if the path can't be completed — callers must not
         * silently ignore this.
         */
        private List<SeekStep> buildTieredPath(long from, long target, Direction dir) {
            if (from == target) return List.of();
            Tier[] tiers = dir == Direction.FORWARD ? forwardTiers : backwardTiers;
            if (tiers.length == 0) return List.of();

            long current = from;
            List<SeekStep> steps = new ArrayList<>();
            outer:
            while (current != target) {
                for (Tier tier : tiers) {
                    int idx = tier.findNext(current, target, dir);
                    if (idx < 0) continue;
                    steps.add(tier.steps[idx]);
                    current = tier.ends[idx];
                    continue outer; // restart at widest tier from the new frame
                }
                return List.of(); // no tier has an edge starting at `current`
            }
            return steps;
        }

        /** True iff any active frame X satisfies startExclusive &lt; X ≤ endInclusive. */
        private boolean hasActiveFrameIn(long startExclusive, long endInclusive) {
            if (endInclusive <= startExclusive) return false;
            int idx = upperIndex(activeFrames, startExclusive);
            return idx >= 0 && activeFrames[idx] <= endInclusive;
        }

        // --- prepare-time helpers ---------------------------------------------------

        private static long[] buildCanonicalActiveFrames(List<TrackSpan> frameByFrame) {
            if (frameByFrame.isEmpty()) return new long[0];
            // Start frame of the first delta + end frame of every delta, sorted, deduped.
            long[] tmp = new long[frameByFrame.size() + 1];
            int n = 0;
            tmp[n++] = frameByFrame.get(0).referenceFrame();
            for (TrackSpan s : frameByFrame) tmp[n++] = s.targetFrame();
            Arrays.sort(tmp, 0, n);
            int m = 1;
            for (int i = 1; i < n; i++) if (tmp[i] != tmp[m - 1]) tmp[m++] = tmp[i];
            return Arrays.copyOf(tmp, m);
        }

        private static long[] collectEndpoints(List<TrackSpan> spans) {
            if (spans.isEmpty()) return new long[0];
            long[] tmp = new long[spans.size() * 2];
            int n = 0;
            for (TrackSpan s : spans) { tmp[n++] = s.referenceFrame(); tmp[n++] = s.targetFrame(); }
            Arrays.sort(tmp, 0, n);
            int m = 0;
            for (int i = 0; i < n; i++) if (m == 0 || tmp[i] != tmp[m - 1]) tmp[m++] = tmp[i];
            return Arrays.copyOf(tmp, m);
        }

        private static long[] union(long[] a, long[] b) {
            if (a.length == 0) return b.clone();
            if (b.length == 0) return a.clone();
            long[] tmp = new long[a.length + b.length];
            int i = 0, j = 0, n = 0;
            while (i < a.length && j < b.length) {
                long av = a[i], bv = b[j];
                if (av < bv) { tmp[n++] = av; i++; }
                else if (av > bv) { tmp[n++] = bv; j++; }
                else { tmp[n++] = av; i++; j++; }
            }
            while (i < a.length) tmp[n++] = a[i++];
            while (j < b.length) tmp[n++] = b[j++];
            return Arrays.copyOf(tmp, n);
        }

        private static boolean contains(long[] sorted, long value) {
            return Arrays.binarySearch(sorted, value) >= 0;
        }

        /** Greatest index with a[i] ≤ key, or -1 if none. */
        private static int floorIndex(long[] a, long key) {
            int idx = Arrays.binarySearch(a, key);
            if (idx >= 0) return idx;
            return (-idx - 1) - 1;
        }

        /** Smallest index with a[i] ≥ key, or -1 if none. */
        private static int ceilingIndex(long[] a, long key) {
            int idx = Arrays.binarySearch(a, key);
            if (idx >= 0) return idx;
            int ins = -idx - 1;
            return ins < a.length ? ins : -1;
        }

        /** Smallest index with a[i] &gt; key, or -1 if none. */
        private static int upperIndex(long[] a, long key) {
            int lo = 0, hi = a.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (a[mid] <= key) lo = mid + 1; else hi = mid;
            }
            return lo < a.length ? lo : -1;
        }

        private static List<List<TrackSpan>> partitionTiers(List<TrackSpan> sorted, int branching) {
            if (sorted.isEmpty()) return List.of();
            List<List<TrackSpan>> tiers = new ArrayList<>();
            int index = 0;
            long tierSize = branching; // tier k holds branching^(k+1) elements
            while (index < sorted.size()) {
                int take = (int) Math.min(tierSize, sorted.size() - index);
                tiers.add(new ArrayList<>(sorted.subList(index, index + take)));
                index += take;
                tierSize = (tierSize > Integer.MAX_VALUE / (long) branching)
                        ? Integer.MAX_VALUE
                        : tierSize * branching;
            }
            return tiers;
        }

        /**
         * Materialize each tier into parallel primitive arrays sorted by edge start.
         * For FORWARD: start = referenceFrame, end = targetFrame.
         * For BACKWARD: start = targetFrame, end = referenceFrame (edge walked in reverse).
         */
        private static Tier[] buildTiers(List<List<TrackSpan>> src, Direction dir) {
            Tier[] out = new Tier[src.size()];
            for (int t = 0; t < src.size(); t++) {
                List<TrackSpan> tierSpans = src.get(t);
                TempEdge[] tmp = new TempEdge[tierSpans.size()];
                int n = 0;
                for (TrackSpan span : tierSpans) {
                    if (span.targetFrame() <= span.referenceFrame()) continue;
                    if (dir == Direction.FORWARD) {
                        tmp[n++] = new TempEdge(span.referenceFrame(), span.targetFrame(),
                                new SeekStep.Forward(span), span.cost());
                    } else {
                        tmp[n++] = new TempEdge(span.targetFrame(), span.referenceFrame(),
                                new SeekStep.Backward(span), span.cost());
                    }
                }
                Arrays.sort(tmp, 0, n, Comparator.comparingLong(TempEdge::start));

                long[] starts = new long[n];
                long[] ends = new long[n];
                SeekStep[] steps = new SeekStep[n];
                int[] costs = new int[n];
                for (int k = 0; k < n; k++) {
                    starts[k] = tmp[k].start;
                    ends[k] = tmp[k].end;
                    steps[k] = tmp[k].step;
                    costs[k] = tmp[k].cost;
                }
                out[t] = new Tier(starts, ends, steps, costs);
            }
            return out;
        }
    }

    /** Intermediate sortable edge — only used during prepare. */
    private record TempEdge(long start, long end, SeekStep step, int cost) {}

    /** Tier data stored as parallel primitive arrays sorted by start. */
    private static final class Tier {
        final long[] starts;
        final long[] ends;
        final SeekStep[] steps;
        final int[] costs;

        Tier(long[] starts, long[] ends, SeekStep[] steps, int[] costs) {
            this.starts = starts;
            this.ends = ends;
            this.steps = steps;
            this.costs = costs;
        }

        /**
         * Among edges whose start equals {@code current}, return the index of the one
         * whose end advances furthest toward {@code target} without overshooting (ties
         * broken by lower cost). Returns -1 if none qualify.
         */
        int findNext(long current, long target, Direction dir) {
            if (starts.length == 0) return -1;
            int best = -1;
            long bestEnd = 0;
            int bestCost = 0;
            // Binary search to the first edge with start >= current; walk while start == current.
            for (int i = lowerBound(starts, current); i < starts.length && starts[i] == current; i++) {
                long end = ends[i];
                int cost = costs[i];
                if (dir == Direction.FORWARD) {
                    if (end <= current || end > target) continue;
                    if (best < 0 || end > bestEnd || (end == bestEnd && cost < bestCost)) {
                        best = i; bestEnd = end; bestCost = cost;
                    }
                } else {
                    if (end >= current || end < target) continue;
                    if (best < 0 || end < bestEnd || (end == bestEnd && cost < bestCost)) {
                        best = i; bestEnd = end; bestCost = cost;
                    }
                }
            }
            return best;
        }

        private static int lowerBound(long[] a, long key) {
            int lo = 0, hi = a.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (a[mid] < key) lo = mid + 1; else hi = mid;
            }
            return lo;
        }
    }
}
