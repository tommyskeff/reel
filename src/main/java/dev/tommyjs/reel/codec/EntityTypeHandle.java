package dev.tommyjs.reel.codec;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class EntityTypeHandle<T> {

    private final String typeId;
    private final Class<T> dataClass;

    private EntityTypeHandle(String typeId, Class<T> dataClass) {
        this.typeId = Objects.requireNonNull(typeId);
        this.dataClass = Objects.requireNonNull(dataClass);
    }

    public static <T> @NotNull EntityTypeHandle<T> of(@NotNull String typeId, @NotNull Class<T> dataClass) {
        return new EntityTypeHandle<>(typeId, dataClass);
    }

    public @NotNull String typeId() {
        return typeId;
    }

    public @NotNull Class<T> dataClass() {
        return dataClass;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EntityTypeHandle<?> that)) return false;
        return typeId.equals(that.typeId);
    }

    @Override
    public int hashCode() {
        return typeId.hashCode();
    }

    @Override
    public String toString() {
        return "EntityTypeHandle[" + typeId + "]";
    }

}
