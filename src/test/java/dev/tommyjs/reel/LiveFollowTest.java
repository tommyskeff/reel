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
import dev.tommyjs.reel.storage.live.ReelMessage;
import dev.tommyjs.reel.storage.writer.LayerStrategy;
import dev.tommyjs.reel.track.EntityTypeHandle;
import dev.tommyjs.reel.track.TrackKind;
import dev.tommyjs.reel.track.TrackRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static dev.tommyjs.reel.TestFixtures.NPC;
import static dev.tommyjs.reel.TestFixtures.PLAYER;
import static dev.tommyjs.reel.TestFixtures.POSITION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveFollowTest {

    private static final LayerStrategy STRATEGY = new LayerStrategy(new long[]{1, 4});

    @TempDir
    Path tmp;

    @Test
    void aSingleCursorFollowsAGrowingReel() throws IOException {
        TrackRegistry registry = TestFixtures.registry();
        IntModel trackModel = TestFixtures.intModel();
        Map<Long, IntState> model = new HashMap<>();

        try (LocalLiveReel live = new LocalLiveReel(STRATEGY)) {
            try (ReelRecorder recorder = recorder(registry, live, "follow.reel");
                 ReplayCursor cursor = ReplayCursor.builder()
                     .setTrackRegistry(registry)
                     .setReader(live.reader())
                     .build()) {

                cursor.addListener(update -> TestFixtures.applyIntModel(model, trackModel, update));

                EntityRecorder player = recorder.createEntity(PLAYER);
                long playerId = player.entityId();
                player.recordState(POSITION, new IntState(0));
                live.flush();

                for (int frame = 1; frame <= 30; frame++) {
                    recorder.advanceFrame();
                    player.recordDelta(POSITION, new IntDelta(1));
                    live.flush();

                    long head = live.reader().getHeadFrame();
                    cursor.seek(head);

                    assertEquals(head, cursor.currentFrame());
                    assertEquals(new IntState((int) head), model.get(playerId), "head " + head);
                }
            }
        }
    }

    @Test
    void seeksIntoEvictedFramesWithoutTouchingReleasedSegments() throws IOException {
        TrackRegistry registry = TestFixtures.registry();
        IntModel trackModel = TestFixtures.intModel();
        Map<Long, IntState> model = new HashMap<>();

        try (LocalLiveReel live = new LocalLiveReel(STRATEGY)) {
            live.reader().setRetainedFrames(16);
            try (ReelRecorder recorder = recorder(registry, live, "retained.reel");
                 ReplayCursor cursor = ReplayCursor.builder()
                     .setTrackRegistry(registry)
                     .setReader(live.reader())
                     .build()) {

                cursor.addListener(update -> TestFixtures.applyIntModel(model, trackModel, update));

                EntityRecorder player = recorder.createEntity(PLAYER);
                long playerId = player.entityId();
                player.recordState(POSITION, new IntState(0));
                for (int frame = 1; frame <= 64; frame++) {
                    recorder.advanceFrame();
                    player.recordDelta(POSITION, new IntDelta(1));
                    live.flush();
                    if (frame == 4) {
                        cursor.seek(2);
                    }
                }

                assertEquals(0, live.reader().readSegment(0).getWindowCount(0));
                cursor.seek(3);

                long head = live.reader().getHeadFrame();
                cursor.seek(head);
                assertEquals(new IntState((int) head), model.get(playerId));
            }
        }
    }

    @Test
    void keepsRejectingATrackWhoseKindDisagreesWithTheRegistry() throws IOException {
        TrackRegistry registry = TestFixtures.registry();

        try (ReelLiveReader reader = ReelLiveReader.builder().setLayerStrategy(STRATEGY).build();
             ReplayCursor cursor = ReplayCursor.builder().setTrackRegistry(registry).setReader(reader).build()) {
            reader.accept(new ReelMessage.TrackTypeRegistered("position", 0, 1, TrackKind.EVENT));

            assertThrows(IllegalStateException.class, () -> cursor.seek(0));
            assertThrows(IllegalStateException.class, () -> cursor.seek(0));
        }
    }

    @Test
    void picksUpAnEntityTypeRegisteredMidRecording() throws IOException {
        TrackRegistry registry = TestFixtures.registry();
        IntModel trackModel = TestFixtures.intModel();
        Map<Long, IntState> model = new HashMap<>();

        try (LocalLiveReel live = new LocalLiveReel(STRATEGY)) {
            try (ReelRecorder recorder = recorder(registry, live, "late-type.reel");
                 ReplayCursor cursor = ReplayCursor.builder()
                     .setTrackRegistry(registry)
                     .setReader(live.reader())
                     .build()) {

                cursor.addListener(update -> TestFixtures.applyIntModel(model, trackModel, update));

                EntityRecorder player = recorder.createEntity(PLAYER);
                player.recordState(POSITION, new IntState(0));
                live.flush();

                for (int frame = 1; frame <= 12; frame++) {
                    recorder.advanceFrame();
                    player.recordDelta(POSITION, new IntDelta(1));
                    live.flush();
                    cursor.seek(live.reader().getHeadFrame());
                }

                assertNull(live.reader().readTypeDirectory().get("npc"));

                EntityRecorder npc = recorder.createEntity(NPC);
                long npcId = npc.entityId();
                npc.recordState(POSITION, new IntState(500));
                live.flush();

                for (int frame = 13; frame <= 30; frame++) {
                    recorder.advanceFrame();
                    player.recordDelta(POSITION, new IntDelta(1));
                    npc.recordDelta(POSITION, new IntDelta(-5));
                    live.flush();
                    cursor.seek(live.reader().getHeadFrame());
                }

                assertNotNull(live.reader().readTypeDirectory().get("npc"));
                assertEquals(NPC, lastEntityTypeOf(cursor, live, registry, npcId));
                assertNotNull(model.get(npcId), "the late-registered entity must not be dropped");
            }
        }
    }

    @Test
    void reportsAGrowingTotalToTheCursor() throws IOException {
        TrackRegistry registry = TestFixtures.registry();

        try (LocalLiveReel live = new LocalLiveReel(STRATEGY)) {
            try (ReelRecorder recorder = recorder(registry, live, "growing.reel");
                 ReplayCursor cursor = ReplayCursor.builder()
                     .setTrackRegistry(registry)
                     .setReader(live.reader())
                     .build()) {

                EntityRecorder player = recorder.createEntity(PLAYER);
                player.recordState(POSITION, new IntState(0));
                live.flush();

                long first = cursor.totalFrames();

                for (int frame = 1; frame <= 20; frame++) {
                    recorder.advanceFrame();
                    player.recordDelta(POSITION, new IntDelta(1));
                    live.flush();
                }

                assertTrue(cursor.totalFrames() > first);
                assertFalse(cursor.isComplete());
            }
        }
    }

    private static EntityTypeHandle lastEntityTypeOf(ReplayCursor cursor, LocalLiveReel live,
                                                     TrackRegistry registry, long entityId) {
        Map<Long, EntityTypeHandle> types = new HashMap<>();
        try (ReplayCursor probe = ReplayCursor.builder()
            .setTrackRegistry(registry)
            .setReader(live.reader())
            .build()) {

            probe.addListener(update -> {
                for (EntityUpdate entity : update.getEntities()) {
                    types.put(entity.getEntityId(), entity.getEntityType());
                }
            });
            probe.seek(cursor.currentFrame());
        }
        return types.get(entityId);
    }

    private ReelRecorder recorder(TrackRegistry registry, LocalLiveReel live, String name) {
        return ReelRecorder.builder()
            .setWriter(w -> w.setFile(tmp.resolve(name).toFile())
                .setLayerStrategy(STRATEGY)
                .addListener(live.publisher()))
            .setTrackRegistry(registry)
            .setSegmentLength(8)
            .setAnchorInterval(16)
            .setIdleThreshold(0)
            .build();
    }


}
