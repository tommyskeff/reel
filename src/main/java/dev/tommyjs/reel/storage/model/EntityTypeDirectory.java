package dev.tommyjs.reel.storage.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

public record EntityTypeDirectory(@NotNull List<EntityTypeDefinition> types,
                                  @NotNull Map<Integer, EntityTypeDefinition> ids,
                                  @NotNull Map<String, EntityTypeDefinition> identifiers) {

    public @Nullable EntityTypeDefinition get(int id) {
        return ids.get(id);
    }

    public @Nullable EntityTypeDefinition get(@NotNull String identifier) {
        return identifiers.get(identifier);
    }

}
