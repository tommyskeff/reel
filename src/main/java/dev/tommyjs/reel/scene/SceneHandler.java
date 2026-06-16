package dev.tommyjs.reel.scene;

import dev.tommyjs.reel.replay.update.ReplayUpdate;
import org.jetbrains.annotations.NotNull;

public interface SceneHandler {

    void load(@NotNull SceneHandlerContext ctx);

    void run(@NotNull ReplayUpdate update, @NotNull SceneTransition transition, @NotNull ReplayScene scene);

    void stop(@NotNull ReplayScene scene);

}
