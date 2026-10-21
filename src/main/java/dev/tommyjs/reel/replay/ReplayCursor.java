package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.track.TrackRegistry;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.File;
import java.nio.file.Path;

public interface ReplayCursor extends Closeable {

    static @NotNull ReplayCursorBuilder builder() {
        return new ReplayCursorBuilder();
    }

    static @NotNull ReplayCursor fromFile(@NotNull File file, @NotNull TrackRegistry registry) {
        return builder().setReader(r -> r.setFile(file)).setTrackRegistry(registry).build();
    }

    static @NotNull ReplayCursor fromFile(@NotNull Path path, @NotNull TrackRegistry registry) {
        return fromFile(path.toFile(), registry);
    }

    long currentFrame();

    long totalFrames();

    boolean isComplete();

    void addListener(@NotNull ReplayListener listener);

    void removeListener(@NotNull ReplayListener listener);

    void seek(long targetFrame);

    @Override
    void close();

}
