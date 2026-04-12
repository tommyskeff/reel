package dev.tommyjs.reel.codec;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Defines how a track type encodes, decodes, and transforms its state and deltas.
 *
 * <p>The recorder uses this interface to manage multi-resolution layers: it maintains
 * the current state {@code S} by applying deltas, accumulates deltas per layer, and
 * calls {@link #condense} when a layer's cost threshold is exceeded. The replayer uses
 * it to decode snapshots and spans, and optionally reverse spans for bidirectional seeking.</p>
 *
 * @param <S> the state type (full track state at a point in time)
 * @param <D> the delta type (a change between two states)
 */
public interface TrackCodec<S, D> {

    /**
     * Returns the layer strategy for this codec, defining how many layers
     * the recorder should maintain and when to emit condensed spans and snapshots.
     */
    @NotNull LayerStrategy layerStrategy();

    /**
     * Applies a delta to the current state, returning the new state.
     * The implementation may mutate {@code state} in place and return it,
     * or return a new instance.
     */
    @NotNull S applyDelta(@NotNull S state, @NotNull D delta);

    /**
     * Creates a deep copy of the state. Called when a layer emits, to snapshot
     * the reference state for the next accumulation window.
     */
    @NotNull S cloneState(@NotNull S state);

    /**
     * Condenses a list of deltas into a single equivalent delta.
     *
     * <p>The reference state is the state at the start of the accumulation window
     * (before any of the deltas were applied). This is necessary for data structures
     * where condensation depends on the actual state (e.g., list removals by index).</p>
     *
     * <p>The returned delta, when applied to {@code referenceState}, must produce the
     * same result as applying all {@code deltas} in order.</p>
     *
     * @param referenceState the state at the start of the accumulation window
     * @param deltas         the deltas to condense, in application order
     * @return a single condensed delta equivalent to applying all deltas in sequence
     */
    @NotNull D condense(@NotNull S referenceState, @NotNull List<D> deltas);

    /**
     * Returns whether this codec supports reversing spans for bidirectional seeking.
     * If true, {@link #reverse} must be implemented.
     */
    boolean isReversible();

    /**
     * Produces a delta that undoes {@code delta}. The delta must encode enough
     * information for reversal without external state (e.g., a position delta
     * {@code (+3, -2)} reverses to {@code (-3, +2)}).
     *
     * @param delta the delta to reverse
     * @return a delta that undoes {@code delta}
     * @throws UnsupportedOperationException if {@link #isReversible()} returns false
     */
    @NotNull D reverse(@NotNull D delta);

    /**
     * Returns the application cost of a delta. Used by the recorder to track
     * accumulated cost per layer and by the seek planner as edge weight.
     */
    int spanCost(@NotNull D delta);

    /**
     * Returns the application cost of restoring from a snapshot. Used by the
     * seek planner to weigh snapshot jumps against chaining spans.
     */
    int snapshotCost(@NotNull S state);

    /**
     * Encodes a full state into bytes for storage as a snapshot.
     */
    byte @NotNull [] encodeSnapshot(@NotNull S state);

    /**
     * Decodes a snapshot from bytes.
     */
    @NotNull S decodeSnapshot(byte @NotNull [] data);

    /**
     * Encodes a delta into bytes for storage as a span.
     */
    byte @NotNull [] encodeSpan(@NotNull D delta);

    /**
     * Decodes a span from bytes.
     */
    @NotNull D decodeSpan(byte @NotNull [] data);

}
