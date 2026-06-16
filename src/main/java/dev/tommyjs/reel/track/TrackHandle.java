package dev.tommyjs.reel.track;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class TrackHandle<S, D> {

    private final String identifier;
    private final Class<S> stateClass;
    private final Class<D> deltaClass;

    private TrackHandle(String identifier, Class<S> stateClass, Class<D> deltaClass) {
        this.identifier = Objects.requireNonNull(identifier);
        this.stateClass = Objects.requireNonNull(stateClass);
        this.deltaClass = Objects.requireNonNull(deltaClass);
    }

    public static <S, D> @NotNull TrackHandle<S, D> of(@NotNull String trackId, @NotNull Class<S> stateClass,
                                                       @NotNull Class<D> deltaClass) {
        return new TrackHandle<>(trackId, stateClass, deltaClass);
    }

    public @NotNull String identifier() {
        return identifier;
    }

    public @NotNull Class<S> stateClass() {
        return stateClass;
    }

    public @NotNull Class<D> deltaClass() {
        return deltaClass;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TrackHandle<?, ?> that)) {
            return false;
        }
        return identifier.equals(that.identifier);
    }

    @Override
    public int hashCode() {
        return identifier.hashCode();
    }

    @Override
    public String toString() {
        return "TrackHandle[" + identifier + "]";
    }

}
