package dev.tommyjs.reel.storage.live;

import dev.tommyjs.reel.storage.model.RawPayload;
import org.jetbrains.annotations.NotNull;

public record FragmentEntry(long entityId, int entityTypeId, int trackTypeId, int layer, long windowStartFrame,
                            @NotNull RawPayload payload) {
}
