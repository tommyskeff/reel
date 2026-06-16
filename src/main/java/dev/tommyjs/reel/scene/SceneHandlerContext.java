package dev.tommyjs.reel.scene;

import org.jetbrains.annotations.NotNull;

public interface SceneHandlerContext {

    void registerProvider(@NotNull SceneResourceKey<?, ?> key);

    void registerConsumer(@NotNull SceneResourceKey<?, ?> key);

}
