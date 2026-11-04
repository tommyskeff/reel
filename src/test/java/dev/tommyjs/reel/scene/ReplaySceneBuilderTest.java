package dev.tommyjs.reel.scene;

import dev.tommyjs.reel.TestFixtures;
import dev.tommyjs.reel.TestFixtures.IntState;
import dev.tommyjs.reel.recorder.EntityRecorder;
import dev.tommyjs.reel.recorder.ReelRecorder;
import dev.tommyjs.reel.replay.update.ReplayUpdate;
import dev.tommyjs.reel.storage.writer.LayerStrategy;
import dev.tommyjs.reel.track.TrackRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.tommyjs.reel.TestFixtures.PLAYER;
import static dev.tommyjs.reel.TestFixtures.POSITION;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplaySceneBuilderTest {

    private static final LayerStrategy STRATEGY = new LayerStrategy(new long[]{1, 4});

    @TempDir
    Path tmp;

    private TrackRegistry registry;
    private File file;

    @BeforeEach
    void record() throws Exception {
        registry = TestFixtures.registry();
        file = tmp.resolve("scene.reel").toFile();
        try (ReelRecorder recorder = ReelRecorder.builder()
            .setWriter(w -> w.setFile(file).setLayerStrategy(STRATEGY))
            .setTrackRegistry(registry)
            .build()) {
            EntityRecorder player = recorder.createEntity(PLAYER);
            player.recordState(POSITION, new IntState(0));
            for (int i = 0; i < 4; i++) {
                recorder.advanceFrame();
            }
        }
    }

    @Test
    void dispatchesTheFirstFrameWhenBuiltByDefault() {
        List<SceneTransition> transitions = new ArrayList<>();
        ReplayScene.builder().setTrackRegistry(registry).setReader(r -> r.setFile(file)).addHandler(recording(transitions)).build();

        assertEquals(1, transitions.size());
        assertEquals(0L, transitions.get(0).to());
    }

    @Test
    void dispatchesNothingUntilSeekedWhenTheInitialSeekIsOff() {
        List<SceneTransition> transitions = new ArrayList<>();
        ReplaySceneImpl scene = ReplayScene.builder()
            .setTrackRegistry(registry)
            .setReader(r -> r.setFile(file))
            .addHandler(recording(transitions))
            .setSeekToStart(false)
            .build();

        assertEquals(List.of(), transitions);

        scene.seek(2);

        assertEquals(1, transitions.size());
        assertEquals(2L, transitions.get(0).to());
    }

    private static SceneHandler recording(List<SceneTransition> transitions) {
        return new SceneHandler() {
            @Override
            public void load(SceneHandlerContext context) {
            }

            @Override
            public void run(ReplayUpdate update, SceneTransition transition, ReplayScene scene) {
                transitions.add(transition);
            }

            @Override
            public void stop(ReplayScene scene) {
            }
        };
    }

}
