package dev.tommyjs.reel.track;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface SnapshotTrackModel<S> extends TrackModel<S, Void> {

    @Override
    default @NotNull TrackKind kind() {
        return TrackKind.SNAPSHOT;
    }

    @Override
    default @NotNull S cloneState(@NotNull S state) {
        return state;
    }

    @Override
    default @NotNull S applyDelta(@NotNull S state, @NotNull Void delta) {
        throw new UnsupportedOperationException("snapshot track model does not support deltas");
    }

    @Override
    default @NotNull List<Void> condense(@NotNull S referenceState, @NotNull List<Void> deltas) {
        throw new UnsupportedOperationException("snapshot track model does not support deltas");
    }

    @Override
    default @NotNull Void reverse(@NotNull Void delta) {
        throw new UnsupportedOperationException("snapshot track model does not support deltas");
    }

}
