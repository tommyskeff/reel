package dev.tommyjs.reel.storage.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record TrackDirectory(int layerCount, long @NotNull [] layerStrides, @NotNull List<TrackDefinition> trackTypes,
                             @NotNull Map<Integer, TrackDefinition> ids,
                             @NotNull Map<String, TrackDefinition> identifiers) {

    public TrackDirectory {
        layerStrides = layerStrides.clone();
    }

    public static @NotNull TrackDirectory of(int layerCount, long @NotNull [] layerStrides,
                                             @NotNull List<TrackDefinition> trackTypes) {
        Map<Integer, TrackDefinition> ids = new HashMap<>();
        Map<String, TrackDefinition> identifiers = new HashMap<>();
        for (TrackDefinition def : trackTypes) {
            ids.put(def.id(), def);
            identifiers.put(def.identifier(), def);
        }
        return new TrackDirectory(layerCount, layerStrides, List.copyOf(trackTypes), ids, identifiers);
    }

    @Override
    public long @NotNull [] layerStrides() {
        return layerStrides.clone();
    }

    public @Nullable TrackDefinition get(int typeId) {
        return ids.get(typeId);
    }

    public @Nullable TrackDefinition get(@NotNull String typeIdentifier) {
        return identifiers.get(typeIdentifier);
    }

}
