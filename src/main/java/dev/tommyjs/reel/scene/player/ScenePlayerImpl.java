package dev.tommyjs.reel.scene.player;

import dev.tommyjs.reel.scene.ReplaySceneImpl;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;

final class ScenePlayerImpl extends AbstractScenePlayer {

    ScenePlayerImpl(@NotNull ReplaySceneImpl scene, int baseFps, double speed, boolean looping,
                    @NotNull List<PlaybackListener> listeners) {
        super(scene, baseFps, speed, looping, listeners);
    }

    @Override
    protected long limitFrame() {
        return scene.getTotalFrames() - 1;
    }

    @Override
    protected long initialFrame() {
        return 0;
    }

    @Override
    public void tick(@NotNull Duration elapsed) {
        if (state != PlaybackState.PLAYING) {
            return;
        }

        long total = scene.getTotalFrames();
        long target = advancePosition(elapsed);

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

}
