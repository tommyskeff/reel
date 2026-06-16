package dev.tommyjs.reel.storage.writer.registry;

import dev.tommyjs.reel.storage.model.EntityTypeDefinition;
import dev.tommyjs.reel.storage.model.EntityTypeDirectory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EntityTypeRegistry {

    private final Map<Integer, EntityTypeDefinition> ids;
    private final Map<String, EntityTypeDefinition> identifiers;

    public EntityTypeRegistry() {
        this.ids = new LinkedHashMap<>();
        this.identifiers = new LinkedHashMap<>();
    }

    public void register(@NotNull String identifier, int id) {
        if (ids.containsKey(id)) {
            throw new IllegalArgumentException("duplicate entity_type_id: " + id);
        }

        if (identifiers.containsKey(identifier)) {
            throw new IllegalArgumentException("duplicate entity_type_identifier: " + identifier);
        }

        EntityTypeDefinition def = new EntityTypeDefinition(id, identifier);
        ids.put(id, def);
        identifiers.put(identifier, def);
    }

    public @Nullable EntityTypeDefinition get(int id) {
        return ids.get(id);
    }

    public @NotNull EntityTypeDirectory build() {
        return new EntityTypeDirectory(List.copyOf(ids.values()), new LinkedHashMap<>(ids),
            new LinkedHashMap<>(identifiers));
    }

}
