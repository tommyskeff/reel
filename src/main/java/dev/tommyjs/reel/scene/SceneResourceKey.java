package dev.tommyjs.reel.scene;

import org.jetbrains.annotations.NotNull;

public final class SceneResourceKey<K, V> {

    private final String name;

    private SceneResourceKey(@NotNull String name) {
        this.name = name;
    }

    public static <K, V> @NotNull SceneResourceKey<K, V> of(@NotNull String name) {
        return new SceneResourceKey<>(name);
    }

    public @NotNull String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "SceneResourceKey[" + name + "]";
    }

}
