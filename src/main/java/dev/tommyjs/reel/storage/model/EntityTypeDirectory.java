package dev.tommyjs.reel.storage.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record EntityTypeDirectory(@NotNull List<EntityTypeDefinition> types,
                                  @NotNull Map<Integer, EntityTypeDefinition> ids,
                                  @NotNull Map<String, EntityTypeDefinition> identifiers) {

    public static @NotNull EntityTypeDirectory of(@NotNull List<EntityTypeDefinition> types) {
        Map<Integer, EntityTypeDefinition> ids = new HashMap<>();
        Map<String, EntityTypeDefinition> identifiers = new HashMap<>();
        for (EntityTypeDefinition def : types) {
            ids.put(def.id(), def);
            identifiers.put(def.identifier(), def);
        }
        return new EntityTypeDirectory(List.copyOf(types), ids, identifiers);
    }

    public @Nullable EntityTypeDefinition get(int id) {
        return ids.get(id);
    }

    public @Nullable EntityTypeDefinition get(@NotNull String identifier) {
        return identifiers.get(identifier);
    }

}
