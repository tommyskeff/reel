package dev.tommyjs.reel.track;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface EventTrackModel<E> extends TrackModel<Void, E> {

    @Override
    default @NotNull TrackKind kind() {
        return TrackKind.EVENT;
    }

    @Override
    default @NotNull Void applyDelta(@NotNull Void state, @NotNull E delta) {
        throw new UnsupportedOperationException("event track model does not support state");
    }

    @Override
    default @NotNull Void cloneState(@NotNull Void state) {
        throw new UnsupportedOperationException("event track model does not support state");
    }

    @Override
    default @NotNull List<E> condense(@NotNull Void referenceState, @NotNull List<E> deltas) {
        throw new UnsupportedOperationException("event track model does not support condensing");
    }

    @Override
    default @NotNull E reverse(@NotNull E delta) {
        throw new UnsupportedOperationException("event track model does not support reversal");
    }

}
