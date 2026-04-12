package dev.tommyjs.reel.storage;

import org.jetbrains.annotations.NotNull;

import java.io.Closeable;

public interface ReelWriter extends Closeable {

    void registerEntityType(@NotNull String typeId);

    void registerTrack(@NotNull String trackId, int codecVersion);

    void writeEntityCreate(long entityId, @NotNull String entityType, byte @NotNull [] metadata);

    void writeEntitySpawn(long entityId, long frame);

    void writeEntityDespawn(long entityId, long frame);

    void writeTrackSpan(long entityId, @NotNull String trackId, long referenceFrame, long targetFrame, int cost, byte @NotNull [] data);

    void writeTrackSnapshot(long entityId, @NotNull String trackId, long frame, int cost, byte @NotNull [] data);

    void finish(long totalFrames);

}
