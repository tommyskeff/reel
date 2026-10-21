package dev.tommyjs.reel;

import dev.tommyjs.reel.TestFixtures.IntDelta;
import dev.tommyjs.reel.TestFixtures.IntModel;
import dev.tommyjs.reel.TestFixtures.IntState;
import dev.tommyjs.reel.recorder.EntityRecorder;
import dev.tommyjs.reel.recorder.ReelRecorder;
import dev.tommyjs.reel.replay.ReplayCursor;
import dev.tommyjs.reel.replay.update.EntityUpdate;
import dev.tommyjs.reel.replay.update.ReplayUpdate;
import dev.tommyjs.reel.replay.update.TrackUpdate;
import dev.tommyjs.reel.storage.live.LocalLiveReel;
import dev.tommyjs.reel.storage.live.ReelLiveReader;
import dev.tommyjs.reel.storage.writer.LayerStrategy;
import dev.tommyjs.reel.track.TrackRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static dev.tommyjs.reel.TestFixtures.NPC;
import static dev.tommyjs.reel.TestFixtures.PLAYER;
import static dev.tommyjs.reel.TestFixtures.POSITION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveEquivalenceTest {

    private static final LayerStrategy STRATEGY = new LayerStrategy(new long[]{1, 4});
    private static final int FRAMES = 40;

    @TempDir
    Path tmp;

    @Test
    void liveReplayMatchesTheFinishedFileAtEveryFrame() throws IOException {
        File file = tmp.resolve("equivalence.reel").toFile();
        TrackRegistry registry = TestFixtures.registry();

        try (LocalLiveReel live = new LocalLiveReel(STRATEGY)) {
            record(file, registry, live, FRAMES);

            assertTrue(live.reader().isComplete());
            assertEquals(FRAMES + 1, live.reader().getTotalFrames());

            for (long frame = 0; frame <= FRAMES; frame++) {
                assertEquals(stateFromFile(file, registry, frame), stateFromLive(live.reader(), registry, frame),
                    "frame " + frame);
            }
        }
    }

    @Test
    void liveReaderTracksTheHeadWhileRecording() throws IOException {
        File file = tmp.resolve("following.reel").toFile();
        TrackRegistry registry = TestFixtures.registry();

        try (LocalLiveReel live = new LocalLiveReel(STRATEGY)) {
            try (ReelRecorder recorder = recorder(file, registry, live)) {
                EntityRecorder player = recorder.createEntity(PLAYER);
                long entityId = player.entityId();
                player.recordState(POSITION, new IntState(0));
                live.flush();

                for (int frame = 1; frame <= 20; frame++) {
                    recorder.advanceFrame();
                    player.recordDelta(POSITION, new IntDelta(1));
                    live.flush();

                    assertFalse(live.reader().isComplete());
                    assertTrue(live.reader().getHeadFrame() >= frame - 1);

                    long head = live.reader().getHeadFrame();
                    assertEquals(new IntState((int) head),
                        stateFromLive(live.reader(), registry, head).get(entityId), "head " + head);
                }
            }
        }
    }

    @Test
    void oneLiveReaderServesIndependentCursors() throws IOException {
        File file = tmp.resolve("branching.reel").toFile();
        TrackRegistry registry = TestFixtures.registry();
        IntModel trackModel = TestFixtures.intModel();

        try (LocalLiveReel live = new LocalLiveReel(STRATEGY)) {
            long entityId = record(file, registry, live, FRAMES);

            Map<Long, IntState> early = new HashMap<>();
            Map<Long, IntState> late = new HashMap<>();

            try (ReplayCursor first = cursor(registry, live.reader());
                 ReplayCursor second = cursor(registry, live.reader())) {

                first.addListener(update -> TestFixtures.applyIntModel(early, trackModel, update));
                second.addListener(update -> TestFixtures.applyIntModel(late, trackModel, update));

                first.seek(5);
                second.seek(30);

                assertEquals(new IntState(5), early.get(entityId));
                assertEquals(new IntState(30), late.get(entityId));
            }

            assertFalse(live.reader().readSegmentDirectory().segments().isEmpty());
            assertEquals(FRAMES + 1, live.reader().getTotalFrames());
        }
    }

    @Test
    void liveReplaySupportsBackwardSeeks() throws IOException {
        File file = tmp.resolve("rewind.reel").toFile();
        TrackRegistry registry = TestFixtures.registry();
        IntModel trackModel = TestFixtures.intModel();

        try (LocalLiveReel live = new LocalLiveReel(STRATEGY)) {
            long entityId = record(file, registry, live, FRAMES);

            Map<Long, IntState> model = new HashMap<>();
            try (ReplayCursor cursor = cursor(registry, live.reader())) {
                cursor.addListener(update -> TestFixtures.applyIntModel(model, trackModel, update));

                cursor.seek(FRAMES);
                assertEquals(new IntState(FRAMES), model.get(entityId));

                cursor.seek(12);
                assertEquals(new IntState(12), model.get(entityId));

                cursor.seek(33);
                assertEquals(new IntState(33), model.get(entityId));

                cursor.seek(0);
                assertEquals(new IntState(0), model.get(entityId));
            }
        }
    }

    @Test
    void liveSegmentBoundariesMatchTheFile() throws IOException {
        File file = tmp.resolve("segments.reel").toFile();
        TrackRegistry registry = TestFixtures.registry();

        try (LocalLiveReel live = new LocalLiveReel(STRATEGY)) {
            record(file, registry, live, FRAMES);

            try (ReplayCursor fileCursor = ReplayCursor.fromFile(file, registry)) {
                assertEquals(fileCursor.totalFrames(), live.reader().getTotalFrames());
            }

            assertEquals(live.publisher().headState().segments().size(),
                live.reader().readSegmentDirectory().size());
        }
    }

    private static long record(File file, TrackRegistry registry, LocalLiveReel live, int frames)
        throws IOException {
        try (ReelRecorder recorder = recorder(file, registry, live)) {
            EntityRecorder player = recorder.createEntity(PLAYER);
            EntityRecorder npc = recorder.createEntity(NPC);

            player.recordState(POSITION, new IntState(0));
            npc.recordState(POSITION, new IntState(100));
            live.flush();

            for (int frame = 1; frame <= frames; frame++) {
                recorder.advanceFrame();
                player.recordDelta(POSITION, new IntDelta(1));
                if (frame % 3 == 0) {
                    npc.recordDelta(POSITION, new IntDelta(-2));
                }
                live.flush();
            }

            return player.entityId();
        }
    }

    private static ReelRecorder recorder(File file, TrackRegistry registry, LocalLiveReel live) {
        return ReelRecorder.builder()
            .setWriter(w -> w.setFile(file).setLayerStrategy(STRATEGY).addListener(live.publisher()))
            .setTrackRegistry(registry)
            .setSegmentLength(8)
            .setAnchorInterval(16)
            .setIdleThreshold(0)
            .build();
    }

    private static ReplayCursor cursor(TrackRegistry registry, ReelLiveReader reader) {
        return ReplayCursor.builder().setTrackRegistry(registry).setReader(reader).build();
    }

    private static Map<Long, IntState> stateFromFile(File file, TrackRegistry registry, long frame) {
        Map<Long, IntState> model = new HashMap<>();
        IntModel trackModel = TestFixtures.intModel();
        try (ReplayCursor cursor = ReplayCursor.fromFile(file, registry)) {
            cursor.addListener(update -> TestFixtures.applyIntModel(model, trackModel, update));
            cursor.seek(frame);
        }
        return model;
    }

    private static Map<Long, IntState> stateFromLive(ReelLiveReader reader, TrackRegistry registry, long frame) {
        Map<Long, IntState> model = new HashMap<>();
        IntModel trackModel = TestFixtures.intModel();
        try (ReplayCursor cursor = cursor(registry, reader)) {
            cursor.addListener(update -> TestFixtures.applyIntModel(model, trackModel, update));
            cursor.seek(frame);
        }
        return model;
    }


}
