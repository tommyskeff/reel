package dev.tommyjs.reel.storage.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface EntityEntry {

    long getEntityId();

    @NotNull String getEntityType();

    byte @NotNull [] getMetadata();

    @NotNull List<EntitySpawnEntry> getSpawns();

}
