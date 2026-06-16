package dev.tommyjs.reel.scene;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

public interface SceneResourceManager {

    <K, V> void publish(@NotNull SceneResourceKey<K, V> key, @NotNull K id, @NotNull V value);

    <K, V> void unpublish(@NotNull SceneResourceKey<K, V> key, @NotNull K id);

    <K, V> @Nullable V find(@NotNull SceneResourceKey<K, V> key, @NotNull K id);

    default <K, V> @NotNull V require(@NotNull SceneResourceKey<K, V> key, @NotNull K id) {
        V value = find(key, id);
        if (value == null) {
            throw new IllegalStateException("no resource published for key " + key + " and id " + id);
        }
        return value;
    }

    <K, V> @NotNull Collection<V> getResources(@NotNull SceneResourceKey<K, V> key);

}
