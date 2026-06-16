package dev.tommyjs.reel.scene.player;

import org.jetbrains.annotations.NotNull;

public interface PlaybackListener {

    default void onFrame(long frame) {
    }

    default void onState(@NotNull PlaybackState state) {
    }

    default void onEnd() {
    }

}
