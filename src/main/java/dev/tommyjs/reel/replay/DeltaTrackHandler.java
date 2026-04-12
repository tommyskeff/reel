package dev.tommyjs.reel.replay;

import org.jetbrains.annotations.NotNull;

public interface DeltaTrackHandler<S, D> extends SnapshotTrackHandler<S, D> {

    void onDelta(long entityId, @NotNull D delta);

}
