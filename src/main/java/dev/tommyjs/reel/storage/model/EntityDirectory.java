package dev.tommyjs.reel.storage.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface EntityDirectory {

    @NotNull List<String> getEntityTypes();

    @NotNull List<EntityEntry> getEntities();

}
