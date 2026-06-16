package dev.tommyjs.reel.track;

import dev.tommyjs.reel.track.codec.Codec;
import dev.tommyjs.reel.track.codec.VoidCodec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class TrackRegistry {

    private final Map<String, RegisteredTrack<?, ?>> tracks;

    private TrackRegistry(Map<String, RegisteredTrack<?, ?>> tracks) {
        this.tracks = Collections.unmodifiableMap(new LinkedHashMap<>(tracks));
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    @SuppressWarnings("unchecked")
    public <S, D> @Nullable RegisteredTrack<S, D> get(@NotNull TrackHandle<S, D> handle) {
        return (RegisteredTrack<S, D>) tracks.get(handle.identifier());
    }

    public @Nullable RegisteredTrack<?, ?> get(@NotNull String trackId) {
        return tracks.get(trackId);
    }

    public <S, D> @Nullable TrackModel<S, D> getModel(@NotNull TrackHandle<S, D> handle) {
        RegisteredTrack<S, D> track = get(handle);
        return track == null ? null : track.model();
    }

    public @Nullable TrackHandle<?, ?> getHandle(@NotNull String trackId) {
        RegisteredTrack<?, ?> track = tracks.get(trackId);
        return track == null ? null : track.handle();
    }

    public @NotNull Set<String> trackIds() {
        return tracks.keySet();
    }

    public int size() {
        return tracks.size();
    }

    public static final class Builder {

        private final Map<String, RegisteredTrack<?, ?>> tracks;

        private Builder() {
            this.tracks = new LinkedHashMap<>();
        }

        public <S, D> @NotNull Builder register(@NotNull TrackHandle<S, D> handle, @NotNull TrackModel<S, D> model,
                                                @NotNull Codec<S> stateCodec, @NotNull Codec<D> deltaCodec) {
            return register(handle, 1, model, stateCodec, deltaCodec);
        }

        @SuppressWarnings("unchecked")
        public <S, D> @NotNull Builder register(@NotNull TrackHandle<S, D> handle, int version,
                                                @NotNull TrackModel<S, D> model, @NotNull Codec<S> stateCodec,
                                                @NotNull Codec<D> deltaCodec) {
            RegisteredTrack<S, D> track = (RegisteredTrack<S, D>) tracks.computeIfAbsent(handle.identifier(),
                id -> new RegisteredTrack<>(handle));
            track.register(version, model, stateCodec, deltaCodec);
            return this;
        }

        public <E> @NotNull Builder register(@NotNull TrackHandle<Void, E> handle, @NotNull EventTrackModel<E> model,
                                             @NotNull Codec<E> eventCodec) {
            return register(handle, 1, model, eventCodec);
        }

        public <E> @NotNull Builder register(@NotNull TrackHandle<Void, E> handle, int version,
                                             @NotNull EventTrackModel<E> model, @NotNull Codec<E> eventCodec) {
            return register(handle, version, model, VoidCodec.INSTANCE, eventCodec);
        }

        public <S> @NotNull Builder register(@NotNull TrackHandle<S, Void> handle,
                                             @NotNull SnapshotTrackModel<S> model, @NotNull Codec<S> stateCodec) {
            return register(handle, 1, model, stateCodec);
        }

        public <S> @NotNull Builder register(@NotNull TrackHandle<S, Void> handle, int version,
                                             @NotNull SnapshotTrackModel<S> model, @NotNull Codec<S> stateCodec) {
            return register(handle, version, model, stateCodec, VoidCodec.INSTANCE);
        }

        public @NotNull TrackRegistry build() {
            return new TrackRegistry(tracks);
        }

    }

}
