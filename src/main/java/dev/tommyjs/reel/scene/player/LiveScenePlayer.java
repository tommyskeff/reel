package dev.tommyjs.reel.scene.player;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;

public interface LiveScenePlayer extends ScenePlayer {

    @NotNull Duration getLiveDelay();

    void setLiveDelay(@NotNull Duration delay);

    long getLiveTargetFrame();

}
