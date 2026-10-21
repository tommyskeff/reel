package dev.tommyjs.reel.scene.player;

import dev.tommyjs.reel.scene.ReplayScene;
import dev.tommyjs.reel.scene.ReplaySceneImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

abstract class AbstractScenePlayer implements ScenePlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractScenePlayer.class);
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private static volatile ScheduledExecutorService defaultExecutor;

    protected final ReplaySceneImpl scene;
    protected final int baseFps;

    private final CopyOnWriteArrayList<PlaybackListener> listeners;

    protected double speed;
    protected boolean looping;
    protected PlaybackState state;
    protected double position;
    protected long current;

    private Closeable tickDriver;

    AbstractScenePlayer(@NotNull ReplaySceneImpl scene, int baseFps, double speed, boolean looping,
                        @NotNull List<PlaybackListener> listeners) {
        this.scene = scene;
        this.baseFps = baseFps;
        this.speed = speed;
        this.looping = looping;
        this.listeners = new CopyOnWriteArrayList<>(listeners);
        this.state = PlaybackState.PAUSED;
    }

    void initialise(boolean autoSchedule, @Nullable ScheduledExecutorService executor) {
        rawSeek(initialFrame());
        if (autoSchedule) {
            this.tickDriver = schedule(executor != null ? executor : defaultExecutor());
        }
    }

    protected abstract long limitFrame();

    protected abstract long initialFrame();

    @Override
    public void play() {
        if (state == PlaybackState.PLAYING) {
            return;
        }

        if (state == PlaybackState.ENDED) {
            rawSeek(speed < 0 ? scene.getTotalFrames() - 1 : 0);
        }

        setState(PlaybackState.PLAYING);
    }

    @Override
    public void pause() {
        if (state != PlaybackState.ENDED) {
            setState(PlaybackState.PAUSED);
        }
    }

    @Override
    public void toggle() {
        if (state == PlaybackState.PLAYING) {
            pause();
        } else {
            play();
        }
    }

    @Override
    public void stop() {
        rawSeek(0);
        setState(PlaybackState.PAUSED);
    }

    @Override
    public void restart() {
        rawSeek(speed < 0 ? scene.getTotalFrames() - 1 : 0);
        setState(PlaybackState.PAUSED);
        play();
    }

    @Override
    public void seek(long frame) {
        rawSeek(frame);
        if (state == PlaybackState.ENDED) {
            setState(PlaybackState.PAUSED);
        }
    }

    @Override
    public void seekToProgress(double progress) {
        seek(Math.round(progress * (scene.getTotalFrames() - 1)));
    }

    @Override
    public void seekToTime(@NotNull Duration time) {
        seek(durationToFrame(time));
    }

    @Override
    public void setSpeed(double speed) {
        this.speed = speed;
    }

    @Override
    public double getSpeed() {
        return speed;
    }

    @Override
    public void setLooping(boolean looping) {
        this.looping = looping;
    }

    @Override
    public boolean isLooping() {
        return looping;
    }

    @Override
    public @NotNull Closeable schedule() {
        return schedule(defaultExecutor());
    }

    @Override
    public @NotNull Closeable schedule(@NotNull ScheduledExecutorService executor) {
        long periodMillis = Math.max(1, Math.round(1000.0 / baseFps));
        long[] last = {System.nanoTime()};
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            Duration elapsed = Duration.ofNanos(now - last[0]);
            last[0] = now;
            try {
                tick(elapsed);
            } catch (Throwable t) {
                LOGGER.warn("Exception caught whilst ticking scene player", t);
            }
        }, 0, periodMillis, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public @NotNull PlaybackSubscription subscribe(@NotNull PlaybackListener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public @NotNull PlaybackState getState() {
        return state;
    }

    @Override
    public boolean isPlaying() {
        return state == PlaybackState.PLAYING;
    }

    @Override
    public long getCurrentFrame() {
        return current;
    }

    @Override
    public long getTotalFrames() {
        return scene.getTotalFrames();
    }

    @Override
    public double getProgress() {
        long total = scene.getTotalFrames();
        return total <= 1 ? 0.0 : (double) current / (total - 1);
    }

    @Override
    public @NotNull Duration getCurrentTime() {
        return frameToDuration(current);
    }

    @Override
    public @NotNull Duration getTotalTime() {
        return frameToDuration(scene.getTotalFrames());
    }

    @Override
    public int getBaseFps() {
        return baseFps;
    }

    @Override
    public @NotNull ReplayScene getScene() {
        return scene;
    }

    @Override
    public void close() {
        if (tickDriver != null) {
            try {
                tickDriver.close();
            } catch (IOException ignored) {
            }
            tickDriver = null;
        }
    }

    protected long advancePosition(@NotNull Duration elapsed) {
        position += baseFps * speed * (elapsed.toNanos() / NANOS_PER_SECOND);
        return (long) Math.floor(position);
    }

    protected void advanceTo(long target) {
        target = Math.max(0, Math.min(target, limitFrame()));
        if (target != current) {
            scene.seek(target, true);
            current = target;
            fireFrame(target);
        }
    }

    protected void rawSeek(long frame) {
        frame = Math.max(0, Math.min(frame, limitFrame()));
        scene.seek(frame);
        current = frame;
        position = frame;
    }

    protected void finishEnded() {
        setState(PlaybackState.ENDED);
        fireEnd();
    }

    protected void setState(@NotNull PlaybackState next) {
        if (next != state) {
            state = next;
            fireStateChange(next);
        }
    }

    protected @NotNull Duration frameToDuration(long frames) {
        return Duration.ofNanos(Math.round(frames * NANOS_PER_SECOND / baseFps));
    }

    protected long durationToFrame(@NotNull Duration time) {
        return Math.round(time.toNanos() / NANOS_PER_SECOND * baseFps);
    }

    private void fireFrame(long frame) {
        for (PlaybackListener listener : listeners) {
            try {
                listener.onFrame(frame);
            } catch (Throwable t) {
                LOGGER.warn("Exception caught whilst dispatching onFrame to playback listener", t);
            }
        }
    }

    private void fireStateChange(PlaybackState next) {
        for (PlaybackListener listener : listeners) {
            try {
                listener.onState(next);
            } catch (Throwable t) {
                LOGGER.warn("Exception caught whilst dispatching onState to playback listener", t);
            }
        }
    }

    private void fireEnd() {
        for (PlaybackListener listener : listeners) {
            try {
                listener.onEnd();
            } catch (Throwable t) {
                LOGGER.warn("Exception caught whilst dispatching onEnd to playback listener", t);
            }
        }
    }

    private static ScheduledExecutorService defaultExecutor() {
        ScheduledExecutorService executor = defaultExecutor;
        if (executor == null) {
            synchronized (AbstractScenePlayer.class) {
                executor = defaultExecutor;
                if (executor == null) {
                    executor = Executors.newSingleThreadScheduledExecutor();
                    defaultExecutor = executor;
                }
            }
        }

        return executor;
    }

}
