package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.replay.update.ReplayUpdate;
import org.jetbrains.annotations.NotNull;

@FunctionalInterface
public interface ReplayListener {

    void onUpdate(@NotNull ReplayUpdate update);

}
