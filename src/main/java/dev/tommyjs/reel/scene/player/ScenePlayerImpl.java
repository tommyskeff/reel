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

final class ScenePlayerImpl implements ScenePlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScenePlayerImpl.class);
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private static volatile ScheduledExecutorService defaultExecutor;

    private final ReplaySceneImpl scene;
    private final int baseFps;
    private final CopyOnWriteArrayList<PlaybackListener> listeners;

    private double speed;
    private boolean looping;

    private PlaybackState state;
    private double position;
    private long current;

    private Closeable tickDriver;

    ScenePlayerImpl(@NotNull ReplaySceneImpl scene, int baseFps, double speed, boolean looping,
                    @NotNull List<PlaybackListener> listeners, boolean autoSchedule,
                    @Nullable ScheduledExecutorService executor) {
        this.scene = scene;
        this.baseFps = baseFps;
        this.speed = speed;
        this.looping = looping;
        this.state = PlaybackState.PAUSED;
        this.listeners = new CopyOnWriteArrayList<>(listeners);

        rawSeek(0);
        if (autoSchedule) {
            this.tickDriver = schedule(executor != null ? executor : defaultExecutor());
        }
    }

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
        long total = scene.getTotalFrames();
        seek(Math.round(progress * (total - 1)));
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
    public void tick(@NotNull Duration elapsed) {
        if (state != PlaybackState.PLAYING) {
            return;
        }

        long total = scene.getTotalFrames();
        position += baseFps * speed * (elapsed.toNanos() / NANOS_PER_SECOND);
        long target = (long) Math.floor(position);

        if (speed >= 0) {
            if (target > total - 1) {
                if (looping) {
                    position -= total;
                    advanceTo((long) Math.floor(position));
                } else {
                    advanceTo(total - 1);
                    position = total - 1;
                    finishEnded();
                }
                return;
            }
        } else if (target < 0) {
            if (looping) {
                position += total;
                advanceTo((long) Math.floor(position));
            } else {
                advanceTo(0);
                position = 0;
                finishEnded();
            }
            return;
        }

        advanceTo(target);
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

    private void advanceTo(long target) {
        long total = scene.getTotalFrames();
        target = Math.max(0, Math.min(target, total - 1));
        if (target != current) {
            scene.seek(target, true);
            current = target;
            fireFrame(target);
        }
    }

    private void rawSeek(long frame) {
        long total = scene.getTotalFrames();
        frame = Math.max(0, Math.min(frame, total - 1));
        scene.seek(frame);
        current = frame;
        position = frame;
    }

    private void finishEnded() {
        setState(PlaybackState.ENDED);
        fireEnd();
    }

    private void setState(PlaybackState next) {
        if (next != state) {
            state = next;
            fireStateChange(next);
        }
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

    private Duration frameToDuration(long frames) {
        return Duration.ofNanos(Math.round(frames * NANOS_PER_SECOND / baseFps));
    }

    private long durationToFrame(Duration time) {
        return Math.round(time.toNanos() / NANOS_PER_SECOND * baseFps);
    }

    private static ScheduledExecutorService defaultExecutor() {
        ScheduledExecutorService executor = defaultExecutor;
        if (executor == null) {
            synchronized (ScenePlayerImpl.class) {
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
