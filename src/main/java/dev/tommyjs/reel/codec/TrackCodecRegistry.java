package dev.tommyjs.reel.codec;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class TrackCodecRegistry {

    private final Map<String, TrackEntry<?, ?>> trackEntries;
    private final Map<String, EntityTypeEntry<?>> entityTypeEntries;

    private TrackCodecRegistry(Map<String, TrackEntry<?, ?>> trackEntries,
                               Map<String, EntityTypeEntry<?>> entityTypeEntries) {
        this.trackEntries = Collections.unmodifiableMap(new LinkedHashMap<>(trackEntries));
        this.entityTypeEntries = Collections.unmodifiableMap(new LinkedHashMap<>(entityTypeEntries));
    }

    @SuppressWarnings("unchecked")
    public <S, D> @Nullable TrackCodec<S, D> get(@NotNull TrackHandle<S, D> handle) {
        TrackEntry<?, ?> entry = trackEntries.get(handle.trackId());
        return entry == null ? null : (TrackCodec<S, D>) entry.codec;
    }

    @SuppressWarnings("unchecked")
    public <S, D> @Nullable TrackCodec<S, D> get(@NotNull String trackId) {
        TrackEntry<?, ?> entry = trackEntries.get(trackId);
        return entry == null ? null : (TrackCodec<S, D>) entry.codec;
    }

    public @NotNull Set<String> trackIds() {
        return trackEntries.keySet();
    }

    public int size() {
        return trackEntries.size();
    }

    public @Nullable TrackHandle<?, ?> getHandle(@NotNull String trackId) {
        TrackEntry<?, ?> entry = trackEntries.get(trackId);
        return entry == null ? null : entry.handle;
    }

    @SuppressWarnings("unchecked")
    public <T> @Nullable EntityMetadataCodec<T> getEntityCodec(@NotNull String typeId) {
        EntityTypeEntry<?> entry = entityTypeEntries.get(typeId);
        return entry == null ? null : (EntityMetadataCodec<T>) entry.codec;
    }

    public <T> @Nullable EntityMetadataCodec<T> getEntityCodec(@NotNull EntityTypeHandle<T> handle) {
        return getEntityCodec(handle.typeId());
    }

    public @Nullable EntityTypeHandle<?> getEntityTypeHandle(@NotNull String typeId) {
        EntityTypeEntry<?> entry = entityTypeEntries.get(typeId);
        return entry == null ? null : entry.handle;
    }

    public @NotNull Set<String> entityTypeIds() {
        return entityTypeEntries.keySet();
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    record TrackEntry<S, D>(TrackHandle<S, D> handle, TrackCodec<S, D> codec) {
    }

    record EntityTypeEntry<T>(EntityTypeHandle<T> handle, EntityMetadataCodec<T> codec) {
    }

    public static final class Builder {

        private final Map<String, TrackEntry<?, ?>> trackEntries;
        private final Map<String, EntityTypeEntry<?>> entityTypeEntries;

        private Builder() {
            this.trackEntries = new LinkedHashMap<>();
            this.entityTypeEntries = new LinkedHashMap<>();
        }

        public <S, D> @NotNull Builder register(@NotNull TrackHandle<S, D> handle, @NotNull TrackCodec<S, D> codec) {
            String trackId = handle.trackId();
            if (trackEntries.containsKey(trackId)) {
                throw new IllegalArgumentException("Track ID \"" + trackId + "\" is already registered");
            }

            trackEntries.put(trackId, new TrackEntry<>(handle, codec));
            return this;
        }

        public <T> @NotNull Builder registerEntityType(@NotNull EntityTypeHandle<T> handle,
                                                       @NotNull EntityMetadataCodec<T> codec) {
            String typeId = handle.typeId();
            if (entityTypeEntries.containsKey(typeId)) {
                throw new IllegalArgumentException("Entity type \"" + typeId + "\" is already registered");
            }

            entityTypeEntries.put(typeId, new EntityTypeEntry<>(handle, codec));
            return this;
        }

        public @NotNull TrackCodecRegistry build() {
            return new TrackCodecRegistry(trackEntries, entityTypeEntries);
        }

    }

}
