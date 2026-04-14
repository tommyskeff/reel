# Recording Seek Algorithm

## Definitions

- **Track**: A recordable entity with state that changes over time.
- **Frame**: A point in time in the recording (real frame space).
- **Delta**: A partial state update between two frames. Has a start frame, end frame, span (end - start), cost, and byte offset.
- **Snapshot**: A full state capture at a particular frame. Has a frame, cost, and byte offset.
- **Frame-by-frame delta**: A delta with span = 1 (between consecutive active frames in squashed space).
- **Compressed delta**: Any delta with span > 1.
- **Active frame**: A frame in which the track’s state actually changed.
- **Squashed frame index**: Index into the sorted array of active frames, eliminating idle frames.

## Precomputation (at load time, per track)

### 1. Separate frame-by-frame deltas and build the active frame index

Partition all deltas into two groups:

- **Frame-by-frame** (span = 1): these form the fixed bottom tier.
- **Compressed** (span > 1): these get partitioned into tiers.

Derive the **active frame index** from the frame-by-frame deltas: take the start frame of the very first delta, then the end frame of every frame-by-frame delta. Sort into a flat array. This is the **squashed frame space**. Seeking from real frame A uses binary search on this array to find the nearest active frame.

Any compressed delta whose start or end frame references an intermediate frame not present in the active frame index must be discarded — its endpoints don’t align with the bottom tier, so it could create gaps where no path exists. Well-behaved recorders should never produce these.

### 2. Build tiers from compressed deltas

Choose a branching factor **N** (e.g. 20).

Sort compressed deltas by span width descending. Partition into tiers:

- Tier 0: the N widest compressed deltas.
- Tier 1: the next N² widest.
- Tier 2: the next N³ widest.
- Continue until all compressed deltas are assigned.
- Bottom tier (implicit): frame-by-frame deltas.

Number of tiers: `ceil(log_N(compressed_delta_count)) + 1` (the +1 is the frame-by-frame tier).

Within each tier, sort deltas by start frame for binary search.

### 3. Build the snapshot index

Sort snapshots by frame number into a flat array. Precompute for fast “nearest snapshot to frame X” lookups via binary search.

## Seek (at query time)

Called with real frame numbers A (current) and B (target), executed independently per track.

### Step 0: Direction

If B = A + 1 (advance one frame): bypass all of this. Apply the next frame-by-frame delta directly. This is the hot path.

If B > A (forward seek): proceed to step 1.

If B < A (rewind):

- **Reversible track**: the track supports reverse deltas. Apply the same tiered traversal logic in reverse — traverse from A backward toward B, using reverse deltas. Snapshots, compressed deltas, and frame-by-frame deltas all work symmetrically.
- **Non-reversible track**: find the nearest snapshot at or before B via the snapshot index. If there are no active frames between that snapshot and the current position A (i.e. the track had no state changes in that range), the state at B is identical to the snapshot — apply the snapshot and skip the forward seek entirely. Otherwise, set A to the snapshot’s frame and proceed with a forward seek from A to B, with the snapshot as the first step in the path.

### Step 1: Snapshot candidate

Find the nearest snapshot to B (before or after, but prefer before to avoid overshooting). Estimate its cost:

```
snapshot_cost = snapshot.cost + estimated cost to walk from snapshot.frame to B using low tiers
```

The walk estimate can be rough — count the number of active frames between the snapshot and B, multiply by the average frame-by-frame delta cost for this track. Hold this as a candidate.

### Step 2: Tiered traversal

Start at the current position (A in squashed space). Work down from tier 0.

At each tier:

1. Binary search for the first delta in this tier whose start frame is at or after the current position.
1. If this delta’s end frame advances toward B without overshooting, add it to the path. Update current position to its end frame.
1. Repeat within the same tier: scan forward for the next delta that continues from the new position.
1. When no more deltas in this tier advance toward B, drop to the next tier.

At the bottom tier, fill any remaining gap with frame-by-frame deltas.

### Step 3: Compare and choose

Sum the cost of the tiered path from step 2. Compare against the snapshot candidate from step 1. Choose whichever is cheaper.

### Step 4: Load and apply

Using the byte offsets from the chosen path’s deltas/snapshots, read the data from disk. Apply in frame order to reconstruct the state at B.

## Complexity

|Phase |Cost |
|-----------------------------------|------------------------------------------------------|
|Real frame → squashed index |O(log F) where F = active frames in track |
|Per tier traversal |O(log D + K) where D = deltas in tier, K = deltas used|
|Total pathfinding per track |O(T × (log D + K)) where T = number of tiers |
|Snapshot lookup |O(log S) where S = snapshots in track |
|Total pathfinding across all tracks|Sum of the above per track |

With N = 20 and typical recordings, total comparisons across all tracks are in the low thousands. Pathfinding time is negligible relative to disk I/O and delta application.

## Tuning

**Branching factor N** is the single tuning knob. Higher N means more comparisons per tier but fewer tiers and potentially better paths. Lower N means faster seeks but possibly suboptimal paths. Benchmark against a full greedy traversal to find the sweet spot for your recording profile.

## Worked Example: Minecraft Recording

### Track count

- 100 chunks × 16 sub-chunks × 2 tracks (blocks, lighting) = **3,200 sub-chunk tracks**
- 40 entities × 5 tracks each = **200 entity tracks**
- **Total: 3,400 tracks**

### Recording parameters

1 hour at 20 ticks/sec = 72,000 frames.

### Delta density per track (after squashing)

- Static sub-chunk block tracks (~2,800): ~10 active frames, ~5 compressed deltas.
- Static sub-chunk lighting tracks (~2,800): ~5 active frames, ~2 compressed deltas.
- Active sub-chunk block tracks (~200): ~500 active frames, ~50 compressed deltas.
- Active sub-chunk lighting tracks (~200): ~100 active frames, ~20 compressed deltas.
- Stationary entity tracks (150 = 30 entities × 5): ~10 active frames, ~5 compressed deltas.
- Moving entity tracks (50 = 10 entities × 5): varies by track type. Position track: ~24,000 active frames (update every ~3 ticks), ~200 compressed deltas. Other tracks (health, inventory, metadata, rotation): ~100–500 active frames, ~20–50 compressed deltas.

### Seek comparisons per track (N = 20)

- Static sub-chunk tracks (~5,600): 2 compressed deltas → 1 tier + bottom. ~3 comparisons each = **~16,800**
- Active sub-chunk block tracks (~200): 50 compressed → 2 tiers + bottom. ~30 comparisons each = **~6,000**
- Active sub-chunk lighting tracks (~200): 20 compressed → 1 tier + bottom. ~15 comparisons each = **~3,000**
- Stationary entity tracks (~150): ~5 compressed → 1 tier + bottom. ~5 comparisons each = **~750**
- Moving entity position tracks (~10): 200 compressed → 2 tiers + bottom. ~80 comparisons each = **~800**
- Moving entity other tracks (~40): ~30 compressed → 2 tiers + bottom. ~25 comparisons each = **~1,000**

**Total: ~28,350 comparisons.** Integer comparisons and array lookups on cache-friendly sorted arrays. Estimated **~200–400 microseconds** for pathfinding.

### Disk I/O

Most static tracks need no data loaded (no state change between A and B for a typical seek). Estimated ~100–200 tracks need data, averaging ~2KB each = ~200–400KB. SSD: **~1–2ms**. Spinning disk: **~10–30ms**.

### Delta application

Applying chunk snapshots is heaviest. ~20–40 chunk tracks needing snapshots at ~4KB each, plus entity state reconstruction. Estimated **~2–5ms**.

### Total seek time

|Phase |Time |
|-----------------|----------|
|Pathfinding |~0.2–0.4ms|
|Disk I/O (SSD) |~1–2ms |
|Delta application|~2–5ms |
|**Total (SSD)** |**~3–7ms**|

Comfortably under a single tick (50ms at 20 tps). Pathfinding is a small fraction of total cost — I/O and application dominate.
