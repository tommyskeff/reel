package dev.tommyjs.reel.recorder;

import dev.tommyjs.reel.track.EntityTypeHandle;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;

public interface ReelRecorder extends Closeable {

    static @NotNull ReelRecorderBuilder builder() {
        return new ReelRecorderBuilder();
    }

    @NotNull EntityRecorder createEntity(@NotNull EntityTypeHandle type);

    void advanceFrame();

    long currentFrame();

}
