package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.codec.EntityTypeHandle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class EntityHandlerRegistry {

    private final Map<String, Entry<?>> entries;

    private EntityHandlerRegistry(Map<String, Entry<?>> entries) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    @SuppressWarnings("unchecked")
    <T> @Nullable EntityHandler<T> get(@NotNull String typeId) {
        Entry<?> entry = entries.get(typeId);
        return entry == null ? null : (EntityHandler<T>) entry.handler;
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    private record Entry<T>(EntityTypeHandle<T> handle, EntityHandler<T> handler) {
    }

    public static final class Builder {

        private final Map<String, Entry<?>> entries;

        private Builder() {
            this.entries = new LinkedHashMap<>();
        }

        public <T> @NotNull Builder register(@NotNull EntityTypeHandle<T> handle,
                                              @NotNull EntityHandler<T> handler) {
            String typeId = handle.typeId();
            if (entries.containsKey(typeId)) {
                throw new IllegalArgumentException("Entity type \"" + typeId + "\" is already registered");
            }
            entries.put(typeId, new Entry<>(handle, handler));
            return this;
        }

        public @NotNull EntityHandlerRegistry build() {
            return new EntityHandlerRegistry(entries);
        }

    }

}
