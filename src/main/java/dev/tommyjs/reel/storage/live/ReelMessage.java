package dev.tommyjs.reel.storage.live;

import dev.tommyjs.reel.track.TrackKind;
import org.jetbrains.annotations.NotNull;

public sealed interface ReelMessage permits ReelFragment, ReelMessage.EntityTypeRegistered,
    ReelMessage.TrackTypeRegistered, ReelMessage.SegmentBegun, ReelMessage.SegmentSealed, ReelMessage.Complete {

    record EntityTypeRegistered(@NotNull String identifier, int typeId) implements ReelMessage {
    }

    record TrackTypeRegistered(@NotNull String identifier, int typeId, int codecVersion,
                               @NotNull TrackKind kind) implements ReelMessage {
    }

    record SegmentBegun(long startFrame, boolean anchor) implements ReelMessage {
    }

    record SegmentSealed(long startFrame, long endFrame, boolean anchor) implements ReelMessage {
    }

    record Complete(long totalFrames) implements ReelMessage {
    }

}
