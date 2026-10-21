package dev.tommyjs.reel.scene.player;

import dev.tommyjs.reel.scene.ReplaySceneImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

public final class ScenePlayerBuilder {

    ReplaySceneImpl scene;
    int baseFps;
    double speed = 1.0;
    boolean looping;
    Duration liveDelay = Duration.ZERO;
    final List<PlaybackListener> listeners = new ArrayList<>();
    boolean autoSchedule;
    ScheduledExecutorService executor;

    ScenePlayerBuilder() {
    }

    public @NotNull ScenePlayerBuilder setScene(@NotNull ReplaySceneImpl scene) {
        this.scene = scene;
        return this;
    }

    public @NotNull ScenePlayerBuilder setBaseFps(int baseFps) {
        this.baseFps = baseFps;
        return this;
    }

    public @NotNull ScenePlayerBuilder setSpeed(double speed) {
        this.speed = speed;
        return this;
    }

    public @NotNull ScenePlayerBuilder setLooping(boolean looping) {
        this.looping = looping;
        return this;
    }

    public @NotNull ScenePlayerBuilder setLiveDelay(@NotNull Duration liveDelay) {
        this.liveDelay = liveDelay;
        return this;
    }

    public @NotNull ScenePlayerBuilder addListener(@NotNull PlaybackListener listener) {
        this.listeners.add(listener);
        return this;
    }

    public @NotNull ScenePlayerBuilder schedule() {
        return schedule(null);
    }

    public @NotNull ScenePlayerBuilder schedule(@Nullable ScheduledExecutorService executor) {
        this.autoSchedule = true;
        this.executor = executor;
        return this;
    }

    public @NotNull ScenePlayer build() {
        validate();
        ScenePlayerImpl player = new ScenePlayerImpl(scene, baseFps, speed, looping, listeners);
        player.initialise(autoSchedule, executor);
        return player;
    }

    public @NotNull LiveScenePlayer buildLive() {
        validate();
        if (speed < 0) {
            throw new IllegalStateException("negative speed is not supported while following live");
        }
        if (looping) {
            throw new IllegalStateException("looping is not supported while following live");
        }
        if (liveDelay.isNegative()) {
            throw new IllegalStateException("live delay must not be negative");
        }

        LiveScenePlayerImpl player = new LiveScenePlayerImpl(scene, baseFps, speed, liveDelay, listeners);
        player.initialise(autoSchedule, executor);
        return player;
    }

    private void validate() {
        if (scene == null) {
            throw new IllegalStateException("scene must be provided");
        }
        if (baseFps <= 0) {
            throw new IllegalStateException("base fps must be positive");
        }
    }

}
