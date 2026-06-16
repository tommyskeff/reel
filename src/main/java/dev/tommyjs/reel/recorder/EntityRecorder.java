package dev.tommyjs.reel.recorder;

import dev.tommyjs.reel.track.TrackHandle;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;

public interface EntityRecorder extends Closeable {

    long entityId();

    <S, D> void recordDelta(@NotNull TrackHandle<S, D> track, @NotNull D delta);

    <S, D> void recordState(@NotNull TrackHandle<S, D> track, @NotNull S state);

    <E> void recordEvent(@NotNull TrackHandle<Void, E> track, @NotNull E event);

    @Override
    void close();

}
