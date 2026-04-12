package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.codec.TrackCodecRegistry;
import dev.tommyjs.reel.codec.TrackHandle;
import dev.tommyjs.reel.storage.ReelReader;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;

public interface ReelSandbox extends Closeable {

    static @NotNull ReelSandbox open(@NotNull ReelReader reader,
                                     @NotNull TrackCodecRegistry registry,
                                     @NotNull EntityHandlerRegistry entityHandlers,
                                     @NotNull ReelSeekPlanner planner) {
        return new ReelSandboxImpl(reader, registry, entityHandlers, planner);
    }

    static @NotNull ReelSandbox open(@NotNull ReelReader reader,
                                     @NotNull TrackCodecRegistry registry,
                                     @NotNull EntityHandlerRegistry entityHandlers) {
        return open(reader, registry, entityHandlers, new DijkstraSeekPlanner());
    }

    <S, D> void registerHandler(@NotNull TrackHandle<S, D> track, @NotNull SnapshotTrackHandler<S, D> handler);

    <S, D> void registerDeltaHandler(@NotNull TrackHandle<S, D> track, @NotNull DeltaTrackHandler<S, D> handler);

    void seekTo(long frame);

    long currentFrame();

    long totalFrames();

}
