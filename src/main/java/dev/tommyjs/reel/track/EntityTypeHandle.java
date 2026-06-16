package dev.tommyjs.reel.track;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class EntityTypeHandle {

    private final String identifier;

    private EntityTypeHandle(@NotNull String identifier) {
        this.identifier = identifier;
    }

    public static @NotNull EntityTypeHandle of(@NotNull String identifier) {
        return new EntityTypeHandle(identifier);
    }

    public @NotNull String identifier() {
        return identifier;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EntityTypeHandle that)) {
            return false;
        }
        return Objects.equals(identifier, that.identifier);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(identifier);
    }

    @Override
    public String toString() {
        return "EntityTypeHandle[" + identifier + "]";
    }

}
