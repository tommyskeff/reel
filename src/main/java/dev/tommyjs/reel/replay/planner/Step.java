package dev.tommyjs.reel.replay.planner;

import dev.tommyjs.reel.storage.model.WindowEntry;
import dev.tommyjs.reel.track.TrackHandle;
import dev.tommyjs.reel.track.TrackKind;
import org.jetbrains.annotations.NotNull;

public record Step(@NotNull TrackHandle<?, ?> handle, int codecVersion, @NotNull TrackKind kind,
                   @NotNull WindowEntry entry, boolean reversed) {
}
