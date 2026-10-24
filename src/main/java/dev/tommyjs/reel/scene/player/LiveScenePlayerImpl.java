package dev.tommyjs.reel.scene.player;

import dev.tommyjs.reel.scene.ReplaySceneImpl;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;

final class LiveScenePlayerImpl extends AbstractScenePlayer implements LiveScenePlayer {

    private long delayFrames;
    private Duration sinceComplete = Duration.ZERO;

    LiveScenePlayerImpl(@NotNull ReplaySceneImpl scene, int baseFps, double speed, @NotNull Duration liveDelay,
                        @NotNull List<PlaybackListener> listeners) {
        super(scene, baseFps, speed, false, listeners);
        this.delayFrames = durationToFrame(liveDelay);
    }

    @Override
    protected long limitFrame() {
        long last = scene.getTotalFrames() - 1;
        long behind = delayFrames;
        if (scene.isComplete()) {
            behind = Math.max(0, delayFrames - durationToFrame(sinceComplete));
        }
        return Math.max(0, last - behind);
    }

    @Override
    protected long initialFrame() {
        return limitFrame();
    }

    @Override
    public @NotNull Duration getLiveDelay() {
        return frameToDuration(delayFrames);
    }

    @Override
    public void setLiveDelay(@NotNull Duration delay) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("live delay must not be negative");
        }
        this.delayFrames = durationToFrame(delay);
    }

    @Override
    public long getLiveTargetFrame() {
        return limitFrame();
    }

    @Override
    public void tick(@NotNull Duration elapsed) {
        if (scene.isComplete()) {
            sinceComplete = sinceComplete.plus(elapsed);
        }
        if (state != PlaybackState.PLAYING) {
            return;
        }

        long target = advancePosition(elapsed);
        long limit = limitFrame();

        if (target > limit) {
            advanceTo(limit);
            position = limit;
            if (scene.isComplete()) {
                finishEnded();
            }
            return;
        }

        advanceTo(target);
    }

    @Override
    public void stop() {
        throw new IllegalStateException("stop is not supported while following live");
    }

    @Override
    public void restart() {
        throw new IllegalStateException("restart is not supported while following live");
    }

    @Override
    public void setSpeed(double speed) {
        if (speed < 0) {
            throw new IllegalStateException("negative speed is not supported while following live");
        }
        super.setSpeed(speed);
    }

    @Override
    public void setLooping(boolean looping) {
        if (looping) {
            throw new IllegalStateException("looping is not supported while following live");
        }
        super.setLooping(looping);
    }

    @Override
    public double getProgress() {
        return Double.NaN;
    }

}
