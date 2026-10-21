package dev.tommyjs.reel.storage.live;

import dev.tommyjs.reel.storage.model.EntityTypeDirectory;
import dev.tommyjs.reel.storage.model.SegmentDirectory;
import dev.tommyjs.reel.storage.model.TrackDirectory;
import org.jetbrains.annotations.NotNull;

public record ReelHeadState(@NotNull EntityTypeDirectory types, @NotNull TrackDirectory tracks,
                            @NotNull SegmentDirectory segments, long headFrame, boolean complete) {
}
