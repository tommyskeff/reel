package dev.tommyjs.reel;

import dev.tommyjs.reel.TestFixtures.IntModel;
import dev.tommyjs.reel.TestFixtures.IntDelta;
import dev.tommyjs.reel.TestFixtures.IntState;
import dev.tommyjs.reel.replay.update.TrackUpdate;
import dev.tommyjs.reel.storage.writer.LayerStrategy;
import dev.tommyjs.reel.track.TrackRegistry;
import dev.tommyjs.reel.recorder.EntityRecorder;
import dev.tommyjs.reel.recorder.ReelRecorder;
import dev.tommyjs.reel.replay.update.EntityUpdate;
import dev.tommyjs.reel.replay.ReplayCursor;
import dev.tommyjs.reel.replay.update.ReplayUpdate;
import dev.tommyjs.reel.storage.reader.ReelReader;
import dev.tommyjs.reel.storage.reader.ReelReaderBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static dev.tommyjs.reel.TestFixtures.PLAYER;
import static dev.tommyjs.reel.TestFixtures.POSITION;
import static dev.tommyjs.reel.TestFixtures.SIGNAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoundTripReplayTest {

    @TempDir
    Path tmp;

    @Test
    void recordsAndReplaysCumulativeState() throws IOException {
        File file = tmp.resolve("rt.reel").toFile();
        TrackRegistry registry = TestFixtures.registry();
        IntModel trackModel = TestFixtures.intModel();

        long total;
        long entityId;
        try (ReelRecorder recorder = ReelRecorder.builder()
            .setWriter(w -> w.setFile(file).setLayerStrategy(new LayerStrategy(new long[]{1, 4})))
            .setTrackRegistry(registry)
            .setSingleSegment()
            .setIdleThreshold(0)
            .build()) {

            EntityRecorder e = recorder.createEntity(PLAYER);
            entityId = e.entityId();

            e.recordState(POSITION, new IntState(0));
            recorder.advanceFrame();
            e.recordDelta(POSITION, new IntDelta(5));
            recorder.advanceFrame();
            e.recordDelta(POSITION, new IntDelta(7));
            recorder.advanceFrame();
            e.recordDelta(POSITION, new IntDelta(-2));
            recorder.advanceFrame();

            total = recorder.currentFrame() + 1;
        }

        Map<Long, IntState> model = new HashMap<>();
        AtomicReference<ReplayUpdate> last = new AtomicReference<>();

        try (ReplayCursor cursor = ReplayCursor.fromFile(file, registry)) {
            cursor.addListener(update -> applyModel(model, trackModel, last, update));

            assertEquals(-1L, cursor.currentFrame());
            assertEquals(total, cursor.totalFrames());

            cursor.seek(cursor.totalFrames() - 1);
            assertEquals(-1L, last.get().getFromFrame());
            assertEquals(PLAYER, last.get().getEntity(entityId).getEntityType());
            assertEquals(new IntState(10), model.get(entityId));

            cursor.seek(0);
            assertEquals(new IntState(0), model.get(entityId));
        }

        assertTrue(total > 0);
    }

    @Test
    void recordsAndReplaysEvents() throws IOException {
        File file = tmp.resolve("rt-events.reel").toFile();
        TrackRegistry registry = TestFixtures.eventRegistry();

        long entityId;
        try (ReelRecorder recorder = ReelRecorder.builder()
            .setWriter(w -> w.setFile(file).setLayerStrategy(new LayerStrategy(new long[]{1, 4})))
            .setTrackRegistry(registry)
            .setSingleSegment()
            .setIdleThreshold(0)
            .build()) {

            EntityRecorder e = recorder.createEntity(PLAYER);
            entityId = e.entityId();

            e.recordState(POSITION, new IntState(0));
            for (int cf = 1; cf <= 7; cf++) {
                recorder.advanceFrame();
                e.recordDelta(POSITION, new IntDelta(1));
                if (cf == 2) {
                    e.recordEvent(SIGNAL, new TestFixtures.Signal(2));
                }
                if (cf == 5) {
                    e.recordEvent(SIGNAL, new TestFixtures.Signal(50));
                    e.recordEvent(SIGNAL, new TestFixtures.Signal(51));
                }
            }
        }

        AtomicReference<ReplayUpdate> last = new AtomicReference<>();
        try (ReplayCursor cursor = ReplayCursor.fromFile(file, registry)) {
            cursor.addListener(last::set);

            Map<Long, List<Integer>> byFrame = new HashMap<>();
            for (long frame = 0; frame <= 7; frame++) {
                last.set(null);
                cursor.seek(frame);
                byFrame.put(frame, last.get() == null ? List.of() : signals(last.get(), entityId));
            }

            assertEquals(List.of(2), byFrame.get(2L));
            assertEquals(List.of(50, 51), byFrame.get(5L));
            assertEquals(List.of(), byFrame.get(3L));
        }

        try (ReplayCursor cursor = ReplayCursor.fromFile(file, registry)) {
            cursor.addListener(last::set);
            cursor.seek(7);
            assertEquals(List.of(2, 50, 51), signals(last.get(), entityId));
        }
    }

    @Test
    void recordsAndReplaysAcrossMultipleSegments() throws IOException {
        File file = tmp.resolve("rt-multi.reel").toFile();
        TrackRegistry registry = TestFixtures.registry();
        IntModel trackModel = TestFixtures.intModel();

        long entityId;
        try (ReelRecorder recorder = ReelRecorder.builder()
            .setWriter(w -> w.setFile(file).setLayerStrategy(new LayerStrategy(new long[]{1, 4})))
            .setTrackRegistry(registry)
            .setSegmentLength(4)
            .setIdleThreshold(0)
            .build()) {

            EntityRecorder e = recorder.createEntity(PLAYER);
            entityId = e.entityId();

            e.recordState(POSITION, new IntState(0));
            recorder.advanceFrame();
            for (int frame = 1; frame <= 11; frame++) {
                e.recordDelta(POSITION, new IntDelta(1));
                recorder.advanceFrame();
            }
        }

        try (ReelReader reader = new ReelReaderBuilder().setFile(file).build()) {
            assertTrue(reader.readSegmentDirectory().size() >= 3);
        }

        Map<Long, IntState> model = new HashMap<>();
        AtomicReference<ReplayUpdate> last = new AtomicReference<>();

        try (ReplayCursor cursor = ReplayCursor.fromFile(file, registry)) {
            cursor.addListener(update -> applyModel(model, trackModel, last, update));

            cursor.seek(cursor.totalFrames() - 1);
            assertEquals(-1L, last.get().getFromFrame());
            assertEquals(PLAYER, last.get().getEntity(entityId).getEntityType());
            assertEquals(new IntState(11), model.get(entityId));

            cursor.seek(9);
            assertEquals(new IntState(9), model.get(entityId));

            cursor.seek(3);
            assertEquals(new IntState(3), model.get(entityId));

            cursor.seek(0);
            assertEquals(new IntState(0), model.get(entityId));
        }
    }

    @Test
    void recordsAnchorSegmentsAndReplaysFromAnchor() throws IOException {
        File file = tmp.resolve("rt-anchor.reel").toFile();
        TrackRegistry registry = TestFixtures.registry();
        IntModel trackModel = TestFixtures.intModel();

        long entityId;
        try (ReelRecorder recorder = ReelRecorder.builder()
            .setWriter(w -> w.setFile(file).setLayerStrategy(new LayerStrategy(new long[]{1, 4})))
            .setTrackRegistry(registry)
            .setSegmentLength(4)
            .setAnchorInterval(4)
            .setIdleThreshold(0)
            .build()) {

            EntityRecorder e = recorder.createEntity(PLAYER);
            entityId = e.entityId();

            e.recordState(POSITION, new IntState(0));
            recorder.advanceFrame();
            for (int frame = 1; frame <= 11; frame++) {
                e.recordDelta(POSITION, new IntDelta(1));
                recorder.advanceFrame();
            }
        }

        try (ReelReader reader = new ReelReaderBuilder().setFile(file).build()) {
            assertTrue(reader.readSegmentDirectory().segments().size() >= 3);
            assertTrue(reader.readSegmentDirectory().segments().get(0).anchor());
            assertTrue(reader.readSegmentDirectory().segments().get(1).anchor());
        }

        Map<Long, IntState> model = new HashMap<>();
        AtomicReference<ReplayUpdate> last = new AtomicReference<>();

        try (ReplayCursor cursor = ReplayCursor.fromFile(file, registry)) {
            cursor.addListener(update -> applyModel(model, trackModel, last, update));

            cursor.seek(cursor.totalFrames() - 1);
            assertEquals(new IntState(11), model.get(entityId));

            cursor.seek(9);
            assertEquals(new IntState(9), model.get(entityId));

            cursor.seek(5);
            assertEquals(new IntState(5), model.get(entityId));

            cursor.seek(0);
            assertEquals(new IntState(0), model.get(entityId));
        }
    }

    @Test
    void closedEntityIsRemovedOnForwardSeek() throws IOException {
        File file = tmp.resolve("rt-close.reel").toFile();
        TrackRegistry registry = TestFixtures.registry();
        IntModel trackModel = TestFixtures.intModel();

        long pId;
        long qId;
        try (ReelRecorder recorder = ReelRecorder.builder()
            .setWriter(w -> w.setFile(file).setLayerStrategy(new LayerStrategy(new long[]{1, 4})))
            .setTrackRegistry(registry)
            .setSingleSegment()
            .setIdleThreshold(0)
            .build()) {

            EntityRecorder p = recorder.createEntity(PLAYER);
            EntityRecorder q = recorder.createEntity(PLAYER);
            pId = p.entityId();
            qId = q.entityId();

            p.recordState(POSITION, new IntState(0));
            q.recordState(POSITION, new IntState(100));
            recorder.advanceFrame();
            p.recordDelta(POSITION, new IntDelta(1));
            q.recordDelta(POSITION, new IntDelta(1));
            recorder.advanceFrame();
            p.recordDelta(POSITION, new IntDelta(1));
            q.recordDelta(POSITION, new IntDelta(1));
            recorder.advanceFrame();
            q.close();
            recorder.advanceFrame();
            p.recordDelta(POSITION, new IntDelta(1));
            recorder.advanceFrame();
        }

        Map<Long, IntState> model = new HashMap<>();
        AtomicReference<ReplayUpdate> last = new AtomicReference<>();

        try (ReplayCursor cursor = ReplayCursor.fromFile(file, registry)) {
            cursor.addListener(update -> applyModel(model, trackModel, last, update));

            cursor.seek(2);
            assertEquals(new IntState(2), model.get(pId));
            assertEquals(new IntState(102), model.get(qId));

            cursor.seek(4);
            assertEquals(new IntState(3), model.get(pId));
            assertFalse(model.containsKey(qId));
            assertTrue(last.get().getRemoved().contains(qId));

            cursor.seek(2);
            assertEquals(new IntState(2), model.get(pId));
            assertEquals(new IntState(102), model.get(qId));
        }
    }

    @Test
    void entityClosedBeforeAnchorIsRemovedOnReSeek() throws IOException {
        File file = tmp.resolve("rt-reseed-close.reel").toFile();
        TrackRegistry registry = TestFixtures.registry();
        IntModel trackModel = TestFixtures.intModel();

        long pId;
        long qId;
        try (ReelRecorder recorder = ReelRecorder.builder()
            .setWriter(w -> w.setFile(file).setLayerStrategy(new LayerStrategy(new long[]{1, 4})))
            .setTrackRegistry(registry)
            .setSegmentLength(4)
            .setAnchorInterval(4)
            .setIdleThreshold(0)
            .build()) {

            EntityRecorder p = recorder.createEntity(PLAYER);
            EntityRecorder q = recorder.createEntity(PLAYER);
            pId = p.entityId();
            qId = q.entityId();

            p.recordState(POSITION, new IntState(0));
            q.recordState(POSITION, new IntState(100));
            recorder.advanceFrame();
            p.recordDelta(POSITION, new IntDelta(1));
            q.recordDelta(POSITION, new IntDelta(1));
            recorder.advanceFrame();
            q.close();
            for (int frame = 2; frame <= 8; frame++) {
                p.recordDelta(POSITION, new IntDelta(1));
                recorder.advanceFrame();
            }
        }

        Map<Long, IntState> model = new HashMap<>();
        AtomicReference<ReplayUpdate> last = new AtomicReference<>();

        try (ReplayCursor cursor = ReplayCursor.fromFile(file, registry)) {
            cursor.addListener(update -> applyModel(model, trackModel, last, update));

            cursor.seek(1);
            assertEquals(new IntState(101), model.get(qId));

            cursor.seek(8);
            assertEquals(new IntState(8), model.get(pId));
            assertFalse(model.containsKey(qId));
            assertTrue(last.get().getRemoved().contains(qId));
        }
    }

    @Test
    void backwardSeekOverCoarseCloseWindowRestoresState() throws IOException {
        File file = tmp.resolve("rt-coarse-close.reel").toFile();
        TrackRegistry registry = TestFixtures.registry();
        IntModel trackModel = TestFixtures.intModel();

        long pId;
        long qId;
        try (ReelRecorder recorder = ReelRecorder.builder()
            .setWriter(w -> w.setFile(file).setLayerStrategy(new LayerStrategy(new long[]{1, 4})))
            .setTrackRegistry(registry)
            .setSingleSegment()
            .setIdleThreshold(0)
            .build()) {

            EntityRecorder p = recorder.createEntity(PLAYER);
            EntityRecorder q = recorder.createEntity(PLAYER);
            pId = p.entityId();
            qId = q.entityId();

            p.recordState(POSITION, new IntState(0));
            q.recordState(POSITION, new IntState(100));
            recorder.advanceFrame();
            for (int frame = 1; frame <= 7; frame++) {
                p.recordDelta(POSITION, new IntDelta(1));
                recorder.advanceFrame();
            }
            p.recordDelta(POSITION, new IntDelta(1));
            q.recordDelta(POSITION, new IntDelta(1));
            recorder.advanceFrame();
            q.close();
            recorder.advanceFrame();
            p.recordDelta(POSITION, new IntDelta(1));
            recorder.advanceFrame();
            p.recordDelta(POSITION, new IntDelta(1));
            recorder.advanceFrame();
        }

        Map<Long, IntState> model = new HashMap<>();
        AtomicReference<ReplayUpdate> last = new AtomicReference<>();

        try (ReplayCursor cursor = ReplayCursor.fromFile(file, registry)) {
            cursor.addListener(update -> applyModel(model, trackModel, last, update));

            cursor.seek(11);
            assertFalse(model.containsKey(qId));

            cursor.seek(7);
            assertEquals(new IntState(7), model.get(pId));
            assertEquals(new IntState(100), model.get(qId));
        }
    }

    @Test
    void recordsAndReplaysUncompressibleMultiDeltaWindows() throws IOException {
        File file = tmp.resolve("rt-chat.reel").toFile();
        TrackRegistry registry = TestFixtures.chatRegistry();
        TestFixtures.ChatModel trackModel = TestFixtures.chatModel();

        long entityId;
        try (ReelRecorder recorder = ReelRecorder.builder()
            .setWriter(w -> w.setFile(file).setLayerStrategy(new LayerStrategy(new long[]{1, 4})))
            .setTrackRegistry(registry)
            .setSingleSegment()
            .setIdleThreshold(0)
            .build()) {

            EntityRecorder e = recorder.createEntity(PLAYER);
            entityId = e.entityId();

            e.recordState(TestFixtures.CHAT, new TestFixtures.ChatState(new ArrayList<>()));
            recorder.advanceFrame();
            e.recordDelta(TestFixtures.CHAT, new TestFixtures.ChatDelta("hello"));
            e.recordDelta(TestFixtures.CHAT, new TestFixtures.ChatDelta("world"));
            recorder.advanceFrame();
            e.recordDelta(TestFixtures.CHAT, new TestFixtures.ChatDelta("again"));
            recorder.advanceFrame();
        }

        Map<Long, TestFixtures.ChatState> model = new HashMap<>();

        try (ReplayCursor cursor = ReplayCursor.fromFile(file, registry)) {
            cursor.addListener(update -> applyChatModel(model, trackModel, update));

            cursor.seek(cursor.totalFrames() - 1);
            assertEquals(List.of("hello", "world", "again"), model.get(entityId).messages());

            cursor.seek(1);
            assertEquals(List.of("hello", "world"), model.get(entityId).messages());
            cursor.seek(0);
            assertEquals(List.of(), model.get(entityId).messages());

            cursor.seek(cursor.totalFrames() - 1);
            assertEquals(List.of("hello", "world", "again"), model.get(entityId).messages());
        }
    }

    private static void applyChatModel(Map<Long, TestFixtures.ChatState> model, TestFixtures.ChatModel trackModel,
                                       ReplayUpdate update) {
        for (EntityUpdate entity : update.getEntities()) {
            TestFixtures.ChatState state =
                model.getOrDefault(entity.getEntityId(), new TestFixtures.ChatState(new ArrayList<>()));
            for (TrackUpdate<TestFixtures.ChatState, TestFixtures.ChatDelta> step
                : entity.getUpdatesFor(TestFixtures.CHAT)) {
                if (step instanceof TrackUpdate.Snapshot<TestFixtures.ChatState, TestFixtures.ChatDelta> snapshot) {
                    state = snapshot.state();
                } else if (step instanceof TrackUpdate.Delta<TestFixtures.ChatState, TestFixtures.ChatDelta> delta) {
                    state = trackModel.applyDelta(state, delta.delta());
                }
            }
            model.put(entity.getEntityId(), state);
        }
        for (Long removed : update.getRemoved()) {
            model.remove(removed);
        }
    }

    private static void applyModel(Map<Long, IntState> model, IntModel trackModel, AtomicReference<ReplayUpdate> last,
                                   ReplayUpdate update) {
        last.set(update);
        for (EntityUpdate entity : update.getEntities()) {
            IntState state = model.getOrDefault(entity.getEntityId(), new IntState(0));
            for (TrackUpdate<IntState, IntDelta> step : entity.getUpdatesFor(POSITION)) {
                if (step instanceof TrackUpdate.Snapshot<IntState, IntDelta> snapshot) {
                    state = snapshot.state();
                } else if (step instanceof TrackUpdate.Delta<IntState, IntDelta> delta) {
                    state = trackModel.applyDelta(state, delta.delta());
                }
            }
            model.put(entity.getEntityId(), state);
        }
        for (Long removed : update.getRemoved()) {
            model.remove(removed);
        }
    }

    private static List<Integer> signals(ReplayUpdate update, long entityId) {
        EntityUpdate entity = update.getEntity(entityId);
        if (entity == null) {
            return List.of();
        }
        List<Integer> codes = new ArrayList<>();
        for (TrackUpdate<Void, TestFixtures.Signal> step : entity.getUpdatesFor(SIGNAL)) {
            if (step instanceof TrackUpdate.Event<Void, TestFixtures.Signal> event) {
                codes.add(event.event().code());
            }
        }
        return codes;
    }

}
