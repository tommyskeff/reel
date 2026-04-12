package dev.tommyjs.reel.codec;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class TrackHandle<S, D> {

    private final String trackId;
    private final Class<S> stateClass;
    private final Class<D> deltaClass;
    private final int codecVersion;

    private TrackHandle(String trackId, Class<S> stateClass, Class<D> deltaClass, int codecVersion) {
        this.trackId = Objects.requireNonNull(trackId);
        this.stateClass = Objects.requireNonNull(stateClass);
        this.deltaClass = Objects.requireNonNull(deltaClass);
        this.codecVersion = codecVersion;
    }

    public static <S, D> @NotNull TrackHandle<S, D> of(@NotNull String trackId, @NotNull Class<S> stateClass,
                                                       @NotNull Class<D> deltaClass, int codecVersion) {
        return new TrackHandle<>(trackId, stateClass, deltaClass, codecVersion);
    }

    public static <S, D> @NotNull TrackHandle<S, D> of(@NotNull String trackId, @NotNull Class<S> stateClass,
                                                       @NotNull Class<D> deltaClass) {
        return new TrackHandle<>(trackId, stateClass, deltaClass, 1);
    }

    public @NotNull String trackId() {
        return trackId;
    }

    public @NotNull Class<S> stateClass() {
        return stateClass;
    }

    public @NotNull Class<D> deltaClass() {
        return deltaClass;
    }

    public int codecVersion() {
        return codecVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TrackHandle<?, ?> that)) return false;
        return trackId.equals(that.trackId);
    }

    @Override
    public int hashCode() {
        return trackId.hashCode();
    }

    @Override
    public String toString() {
        return "TrackHandle[" + trackId + "]";
    }

}
