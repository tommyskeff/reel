package dev.tommyjs.reel;

import dev.tommyjs.reel.codec.TrackHandle;
import org.jetbrains.annotations.NotNull;

public interface EntityHandle {

    long entityId();

    <S, D> void recordDelta(@NotNull TrackHandle<S, D> track, @NotNull D delta);

    <S, D> void recordSnapshot(@NotNull TrackHandle<S, D> track, @NotNull S state);

    <S, D> void recordSnapshot(@NotNull TrackHandle<S, D> track);

    void despawn();

}
