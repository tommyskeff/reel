package dev.tommyjs.reel.storage.writer;

import dev.tommyjs.reel.storage.model.RawPayload;
import dev.tommyjs.reel.track.TrackKind;
import org.jetbrains.annotations.NotNull;

public interface ReelWriterListener {

    default void onEntityType(@NotNull String typeIdentifier, int typeId) {
    }

    default void onTrackType(@NotNull String typeIdentifier, int typeId, int codecVersion, @NotNull TrackKind kind) {
    }

    default void onSegmentBegin(long startFrame, boolean anchor) {
    }

    default void onPayload(long entityId, int entityTypeId, int trackTypeId, int layer, long windowStartFrame,
                           @NotNull RawPayload payload) {
    }

    default void onSegmentEnd(long startFrame, long endFrame, boolean anchor) {
    }

    default void onFrameAdvanced(long frame) {
    }

    default void onFinish(long totalFrames) {
    }

}
