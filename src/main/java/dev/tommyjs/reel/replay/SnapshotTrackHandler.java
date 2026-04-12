package dev.tommyjs.reel.replay;

import org.jetbrains.annotations.NotNull;

public interface SnapshotTrackHandler<S, D> {

    void onSnapshot(long entityId, @NotNull S state);

}
