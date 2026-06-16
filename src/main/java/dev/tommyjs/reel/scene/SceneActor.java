package dev.tommyjs.reel.scene;

import dev.tommyjs.reel.replay.update.EntityUpdate;
import org.jetbrains.annotations.NotNull;

public interface SceneActor {

    void apply(@NotNull EntityUpdate update, @NotNull SceneTransition transition, @NotNull ReplayScene scene);

    default void destroy(@NotNull ReplayScene scene) {
    }

}
