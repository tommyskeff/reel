package dev.tommyjs.reel.track;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface TrackModel<S, D> {

    default @NotNull TrackKind kind() {
        return TrackKind.DELTA;
    }

    @NotNull S applyDelta(@NotNull S state, @NotNull D delta);

    @NotNull S cloneState(@NotNull S state);

    default @NotNull List<D> condense(@NotNull S referenceState, @NotNull List<D> deltas) {
        return deltas;
    }

    @NotNull D reverse(@NotNull D delta);

}
