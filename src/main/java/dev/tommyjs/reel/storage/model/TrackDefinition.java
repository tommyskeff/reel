package dev.tommyjs.reel.storage.model;

import dev.tommyjs.reel.track.TrackKind;
import org.jetbrains.annotations.NotNull;

public record TrackDefinition(int id, @NotNull String identifier, int codecVersion, @NotNull TrackKind kind) {
}
