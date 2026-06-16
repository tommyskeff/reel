package dev.tommyjs.reel.scene;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unchecked")
final class SceneResourceManagerImpl implements SceneResourceManager {

    private final Map<SceneResourceKey<?, ?>, Map<Object, Object>> resources = new IdentityHashMap<>();

    @Override
    public <K, V> void publish(@NotNull SceneResourceKey<K, V> key, @NotNull K id, @NotNull V value) {
        ((Map<K, V>) resources.computeIfAbsent(key, k -> new LinkedHashMap<>())).put(id, value);
    }

    @Override
    public <K, V> void unpublish(@NotNull SceneResourceKey<K, V> key, @NotNull K id) {
        Map<Object, Object> values = resources.get(key);
        if (values != null) {
            values.remove(id);
        }
    }

    @Override
    public <K, V> @Nullable V find(@NotNull SceneResourceKey<K, V> key, @NotNull K id) {
        Map<Object, Object> values = resources.get(key);
        return values == null ? null : (V) values.get(id);
    }

    @Override
    public <K, V> @NotNull Collection<V> getResources(@NotNull SceneResourceKey<K, V> key) {
        Map<Object, Object> values = resources.get(key);
        if (values == null) {
            return List.of();
        }
        return new ArrayList<>((Collection<V>) values.values());
    }

    void clear() {
        resources.clear();
    }

}
