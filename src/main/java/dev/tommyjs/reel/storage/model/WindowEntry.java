package dev.tommyjs.reel.storage.model;

import org.jetbrains.annotations.NotNull;

public record WindowEntry(int trackRefIndex, @NotNull RawPayload payload) {
}
