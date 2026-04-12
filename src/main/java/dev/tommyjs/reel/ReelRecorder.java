package dev.tommyjs.reel;

import dev.tommyjs.reel.codec.EntityTypeHandle;
import dev.tommyjs.reel.codec.TrackCodecRegistry;
import dev.tommyjs.reel.storage.ReelWriter;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;

public interface ReelRecorder extends Closeable {

    static @NotNull ReelRecorder create(@NotNull ReelWriter writer, @NotNull TrackCodecRegistry registry) {
        return new ReelRecorderImpl(writer, registry);
    }

    <T> @NotNull EntityHandle createEntity(@NotNull EntityTypeHandle<T> type, @NotNull T metadata);

    void spawnEntity(@NotNull EntityHandle handle);

    void advanceFrame();

    long currentFrame();

}
