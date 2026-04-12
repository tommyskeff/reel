package dev.tommyjs.reel.replay;

import org.jetbrains.annotations.NotNull;

public interface EntityHandler<T> {

    void onSpawn(long entityId, @NotNull T spawnData);

    void onDespawn(long entityId);

}
