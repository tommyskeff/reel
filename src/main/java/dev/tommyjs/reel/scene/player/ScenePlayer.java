package dev.tommyjs.reel.scene.player;

import dev.tommyjs.reel.scene.ReplayScene;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;

public interface ScenePlayer extends Closeable {

    static @NotNull ScenePlayerBuilder builder() {
        return new ScenePlayerBuilder();
    }

    void play();

    void pause();

    void toggle();

    void stop();

    void restart();

    void seek(long frame);

    void seekToProgress(double progress);

    void seekToTime(@NotNull Duration time);

    void setSpeed(double speed);

    double getSpeed();

    void setLooping(boolean looping);

    boolean isLooping();

    void tick(@NotNull Duration elapsed);

    @NotNull Closeable schedule();

    @NotNull Closeable schedule(@NotNull ScheduledExecutorService executor);

    @NotNull PlaybackSubscription subscribe(@NotNull PlaybackListener listener);

    @NotNull PlaybackState getState();

    boolean isPlaying();

    long getCurrentFrame();

    long getTotalFrames();

    double getProgress();

    @NotNull Duration getCurrentTime();

    @NotNull Duration getTotalTime();

    int getBaseFps();

    @NotNull ReplayScene getScene();

    @Override
    void close();

}
