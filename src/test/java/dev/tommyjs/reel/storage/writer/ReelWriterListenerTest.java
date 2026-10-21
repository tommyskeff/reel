package dev.tommyjs.reel.storage.writer;

import dev.tommyjs.reel.TestFixtures;
import dev.tommyjs.reel.TestFixtures.IntDelta;
import dev.tommyjs.reel.TestFixtures.IntState;
import dev.tommyjs.reel.recorder.EntityRecorder;
import dev.tommyjs.reel.recorder.ReelRecorder;
import dev.tommyjs.reel.storage.model.RawPayload;
import dev.tommyjs.reel.track.TrackKind;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.tommyjs.reel.TestFixtures.PLAYER;
import static dev.tommyjs.reel.TestFixtures.POSITION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReelWriterListenerTest {

    @TempDir
    Path tmp;

    @Test
    void observesRegistrationsSegmentsAndPayloads() throws IOException {
        Capture capture = new Capture();

        try (ReelRecorder recorder = ReelRecorder.builder()
            .setWriter(w -> w.setFile(tmp.resolve("listener.reel").toFile())
                .setLayerStrategy(new LayerStrategy(new long[]{1, 4}))
                .addListener(capture))
            .setTrackRegistry(TestFixtures.registry())
            .setSegmentLength(4)
            .setIdleThreshold(0)
            .build()) {

            EntityRecorder entity = recorder.createEntity(PLAYER);
            entity.recordState(POSITION, new IntState(0));
            for (int i = 0; i < 8; i++) {
                recorder.advanceFrame();
                entity.recordDelta(POSITION, new IntDelta(1));
            }
        }

        assertEquals(List.of("player"), capture.entityTypes);
        assertEquals(List.of("position"), capture.trackTypes);
        assertEquals(TrackKind.DELTA, capture.trackKinds.get(0));

        assertFalse(capture.payloads.isEmpty());
        assertTrue(capture.segmentBegins.size() > 1);
        assertEquals(capture.segmentBegins.size(), capture.segmentEnds.size());
        assertTrue(capture.finished);
    }

    @Test
    void marksTheFirstSegmentAsAnAnchor() throws IOException {
        Capture capture = new Capture();

        try (ReelRecorder recorder = ReelRecorder.builder()
            .setWriter(w -> w.setFile(tmp.resolve("anchor.reel").toFile())
                .setLayerStrategy(new LayerStrategy(new long[]{1, 4}))
                .addListener(capture))
            .setTrackRegistry(TestFixtures.registry())
            .setSegmentLength(4)
            .setAnchorInterval(8)
            .setIdleThreshold(0)
            .build()) {

            EntityRecorder entity = recorder.createEntity(PLAYER);
            entity.recordState(POSITION, new IntState(0));
            for (int i = 0; i < 12; i++) {
                recorder.advanceFrame();
                entity.recordDelta(POSITION, new IntDelta(1));
            }
        }

        assertTrue(capture.segmentBegins.get(0).anchor());
        assertEquals(0L, capture.segmentBegins.get(0).startFrame());
        assertTrue(capture.segmentBegins.stream().anyMatch(s -> !s.anchor()));
    }

    @Test
    void reportsContiguousSegmentSpans() throws IOException {
        Capture capture = new Capture();

        try (ReelRecorder recorder = ReelRecorder.builder()
            .setWriter(w -> w.setFile(tmp.resolve("spans.reel").toFile())
                .setLayerStrategy(new LayerStrategy(new long[]{1, 4}))
                .addListener(capture))
            .setTrackRegistry(TestFixtures.registry())
            .setSegmentLength(4)
            .setIdleThreshold(0)
            .build()) {

            EntityRecorder entity = recorder.createEntity(PLAYER);
            entity.recordState(POSITION, new IntState(0));
            for (int i = 0; i < 12; i++) {
                recorder.advanceFrame();
                entity.recordDelta(POSITION, new IntDelta(1));
            }
        }

        long previousEnd = -1;
        for (Span span : capture.segmentEnds) {
            if (previousEnd >= 0) {
                assertEquals(previousEnd, span.startFrame());
            }
            assertTrue(span.endFrame() > span.startFrame());
            previousEnd = span.endFrame();
        }
    }

    @Test
    void payloadsCarryUsableBodies() throws IOException {
        List<Integer> sizes = new ArrayList<>();
        ReelWriterListener listener = new ReelWriterListener() {
            @Override
            public void onPayload(long entityId, int entityTypeId, int trackTypeId, int layer, long windowStartFrame,
                                  @NotNull RawPayload payload) {
                sizes.add(payload.body().readableBytes());
            }
        };

        try (ReelRecorder recorder = ReelRecorder.builder()
            .setWriter(w -> w.setFile(tmp.resolve("bodies.reel").toFile())
                .setLayerStrategy(new LayerStrategy(new long[]{1, 4}))
                .addListener(listener))
            .setTrackRegistry(TestFixtures.registry())
            .setSingleSegment()
            .setIdleThreshold(0)
            .build()) {

            EntityRecorder entity = recorder.createEntity(PLAYER);
            entity.recordState(POSITION, new IntState(0));
            recorder.advanceFrame();
            entity.recordDelta(POSITION, new IntDelta(3));
            recorder.advanceFrame();
        }

        assertFalse(sizes.isEmpty());
        assertTrue(sizes.stream().allMatch(size -> size > 0));
    }

    @Test
    void stillWritesAReadableFileWhenAListenerThrows() throws IOException {
        ReelWriterListener broken = new ReelWriterListener() {
            @Override
            public void onPayload(long entityId, int entityTypeId, int trackTypeId, int layer, long windowStartFrame,
                                  @NotNull RawPayload payload) {
                throw new IllegalStateException("boom");
            }
        };

        Path file = tmp.resolve("broken.reel");
        try (ReelRecorder recorder = ReelRecorder.builder()
            .setWriter(w -> w.setFile(file.toFile())
                .setLayerStrategy(new LayerStrategy(new long[]{1, 4}))
                .addListener(broken))
            .setTrackRegistry(TestFixtures.registry())
            .setSingleSegment()
            .setIdleThreshold(0)
            .build()) {

            EntityRecorder entity = recorder.createEntity(PLAYER);
            entity.recordState(POSITION, new IntState(0));
            recorder.advanceFrame();
            entity.recordDelta(POSITION, new IntDelta(1));
            recorder.advanceFrame();
        }

        assertTrue(file.toFile().length() > 0);
    }

    private record Span(long startFrame, long endFrame, boolean anchor) {
    }

    private static final class Capture implements ReelWriterListener {

        private final List<String> entityTypes = new ArrayList<>();
        private final List<String> trackTypes = new ArrayList<>();
        private final List<TrackKind> trackKinds = new ArrayList<>();
        private final List<Span> segmentBegins = new ArrayList<>();
        private final List<Span> segmentEnds = new ArrayList<>();
        private final List<Long> payloads = new ArrayList<>();
        private boolean finished;

        @Override
        public void onEntityType(@NotNull String typeIdentifier, int typeId) {
            entityTypes.add(typeIdentifier);
        }

        @Override
        public void onTrackType(@NotNull String typeIdentifier, int typeId, int codecVersion,
                                @NotNull TrackKind kind) {
            trackTypes.add(typeIdentifier);
            trackKinds.add(kind);
        }

        @Override
        public void onSegmentBegin(long startFrame, boolean anchor) {
            segmentBegins.add(new Span(startFrame, -1, anchor));
        }

        @Override
        public void onPayload(long entityId, int entityTypeId, int trackTypeId, int layer, long windowStartFrame,
                              @NotNull RawPayload payload) {
            payloads.add(windowStartFrame);
        }

        @Override
        public void onSegmentEnd(long startFrame, long endFrame, boolean anchor) {
            segmentEnds.add(new Span(startFrame, endFrame, anchor));
        }

        @Override
        public void onFinish(long totalFrames) {
            finished = true;
        }

    }

}
