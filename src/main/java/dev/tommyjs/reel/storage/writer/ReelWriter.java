package dev.tommyjs.reel.storage.writer;

import dev.tommyjs.reel.track.TrackKind;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;

public interface ReelWriter extends Closeable {

    @NotNull LayerStrategy getLayerStrategy();

    @NotNull ByteBufAllocator getAllocator();

    void registerEntityType(@NotNull String typeIdentifier, int typeId);

    void registerTrackType(@NotNull String typeIdentifier, int typeId, int codecVersion, @NotNull TrackKind kind);

    void beginSegment(long startFrame, boolean anchor);

    default void beginSegment(long startFrame) {
        beginSegment(startFrame, false);
    }

    void endSegment(long endFrame);

    default void advanceFrame(long frame) {
    }

    void writeDelta(long entityId, int entityTypeId, int trackTypeId, int layer,
                    long windowStartFrame, @NotNull ByteBuf deltaBody);

    void writeState(long entityId, int entityTypeId, int trackTypeId, int layer,
                    long windowStartFrame, @Nullable ByteBuf previousBody, @NotNull ByteBuf currentBody);

    void writeClose(long entityId, int entityTypeId, int trackTypeId, int layer, long windowStartFrame,
                    @NotNull ByteBuf previousBody);

    void writeEvent(long entityId, int entityTypeId, int trackTypeId, int layer, long windowStartFrame,
                    @NotNull ByteBuf eventBody);

    void finish(long totalFrames);

}
