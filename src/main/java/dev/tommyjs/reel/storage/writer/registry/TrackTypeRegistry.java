package dev.tommyjs.reel.storage.writer.registry;

import dev.tommyjs.reel.storage.model.TrackDefinition;
import dev.tommyjs.reel.storage.model.TrackDirectory;
import dev.tommyjs.reel.storage.writer.LayerStrategy;
import dev.tommyjs.reel.track.TrackKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TrackTypeRegistry {

    private final Map<Integer, TrackDefinition> ids;
    private final Map<String, TrackDefinition> identifiers;

    public TrackTypeRegistry() {
        this.ids = new LinkedHashMap<>();
        this.identifiers = new LinkedHashMap<>();
    }

    public void register(@NotNull String identifier, int id, int codecVersion, @NotNull TrackKind kind) {
        if (ids.containsKey(id)) {
            throw new IllegalArgumentException("duplicate type_id: " + id);
        }

        if (identifiers.containsKey(identifier)) {
            throw new IllegalArgumentException("duplicate type_identifier: " + identifier);
        }

        TrackDefinition track = new TrackDefinition(id, identifier, codecVersion, kind);
        ids.put(id, track);
        identifiers.put(identifier, track);
    }

    public @Nullable TrackDefinition get(int id) {
        return ids.get(id);
    }

    public @NotNull TrackDirectory build(@NotNull LayerStrategy layerStrategy) {
        long[] strides = layerStrategy.strides();
        return new TrackDirectory(strides.length, strides, List.copyOf(ids.values()), new LinkedHashMap<>(ids),
            new LinkedHashMap<>(identifiers));
    }

}
