package dev.tommyjs.reel;

import dev.tommyjs.reel.TestFixtures.IntDelta;
import dev.tommyjs.reel.TestFixtures.IntModel;
import dev.tommyjs.reel.TestFixtures.IntState;
import dev.tommyjs.reel.recorder.EntityRecorder;
import dev.tommyjs.reel.recorder.ReelRecorder;
import dev.tommyjs.reel.replay.ReplayCursor;
import dev.tommyjs.reel.storage.live.ReelFragment;
import dev.tommyjs.reel.storage.live.ReelLivePublisher;
import dev.tommyjs.reel.storage.live.ReelLiveReader;
import dev.tommyjs.reel.storage.live.ReelMessage;
import dev.tommyjs.reel.storage.live.ReelMessageCodec;
import dev.tommyjs.reel.storage.model.SegmentEntry;
import dev.tommyjs.reel.storage.reader.ReelFileReader;
import dev.tommyjs.reel.storage.writer.LayerStrategy;
import dev.tommyjs.reel.track.TrackRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.tommyjs.reel.TestFixtures.NPC;
import static dev.tommyjs.reel.TestFixtures.PLAYER;
import static dev.tommyjs.reel.TestFixtures.POSITION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveCompactionTest {

    private static final LayerStrategy STRATEGY = new LayerStrategy(new long[]{1, 4});
    private static final int FRAMES = 29;

    @TempDir
    Path tmp;

    @Test
    void publishesEachSegmentStoredAfterItIsSealed() throws IOException {
        List<ReelMessage> messages = decodeAll(record());

        int stored = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof ReelMessage.SegmentStored m) {
                ReelMessage.SegmentSealed sealed = (ReelMessage.SegmentSealed) messages.get(i - 1);
                assertEquals(sealed.startFrame(), m.startFrame());
                assertEquals(sealed.endFrame(), m.endFrame());
                assertEquals(sealed.anchor(), m.anchor());
                stored++;
            }
        }
        assertEquals(3, stored);
    }

    @Test
    void aJoinerReadingStoredSegmentsSeesWhatAFollowerSaw() throws IOException {
        List<byte[]> recorded = record();

        try (ReelLiveReader follower = reader(); ReelLiveReader joiner = reader()) {
            for (ReelMessage message : decodeAll(recorded)) {
                follower.accept(message);
            }
            joiner.acceptAll(compact(decodeAll(recorded)));

            assertSameFrames(follower, joiner);
        }
    }

    @Test
    void aStoredSegmentReplacesAPartlyReceivedTail() throws IOException {
        List<ReelMessage> messages = decodeAll(record());
        int cut = indexOfSegmentBegun(messages, 8) + 3;

        try (ReelLiveReader follower = reader(); ReelLiveReader joiner = reader()) {
            for (ReelMessage message : decodeAll(record())) {
                follower.accept(message);
            }
            joiner.acceptAll(messages.subList(0, cut));
            joiner.acceptAll(compact(messages.subList(cut, messages.size())));

            assertSameFrames(follower, joiner);
        }
    }

    @Test
    void aReaderLoadedFromTheFinishedFileSeesWhatAFollowerSaw() throws IOException {
        File file = tmp.resolve("finished.reel").toFile();
        List<byte[]> recorded = new ArrayList<>();
        record(file, recorded);

        try (ReelLiveReader follower = reader(); ReelLiveReader loaded = reader();
             ReelFileReader finished = new ReelFileReader(file, ByteBufAllocator.DEFAULT)) {
            for (ReelMessage message : decodeAll(recorded)) {
                follower.accept(message);
            }
            loaded.acceptDirectories(finished.readTypeDirectory(), finished.readTrackDirectory());
            List<SegmentEntry> segments = finished.readSegmentDirectory().segments();
            for (int index = 0; index < segments.size(); index++) {
                SegmentEntry segment = segments.get(index);
                ByteBuf compressed = finished.readCompressedSegment(index);
                try {
                    loaded.accept(new ReelMessage.SegmentStored(segment.startFrame(), segment.endFrame(), segment.anchor(),
                        ByteBufUtil.getBytes(compressed)));
                } finally {
                    compressed.release();
                }
            }
            loaded.accept(new ReelMessage.Complete(finished.getTotalFrames()));

            assertTrue(follower.isComplete());
            assertSameFrames(follower, loaded);
        }
    }

    private void assertSameFrames(ReelLiveReader expected, ReelLiveReader actual) {
        TrackRegistry registry = TestFixtures.registry();
        assertEquals(expected.getHeadFrame(), actual.getHeadFrame());
        assertEquals(expected.readSegmentDirectory().size(), actual.readSegmentDirectory().size());
        assertTrue(expected.getHeadFrame() >= FRAMES - 1);
        for (long frame = 0; frame <= expected.getHeadFrame(); frame++) {
            assertEquals(stateAt(expected, registry, frame), stateAt(actual, registry, frame), "frame " + frame);
        }
    }

    private List<byte[]> record() {
        return record(tmp.resolve("compaction-" + System.nanoTime() + ".reel").toFile(), new ArrayList<>());
    }

    private List<byte[]> record(File file, List<byte[]> recorded) {
        ReelLivePublisher publisher = new ReelLivePublisher(STRATEGY, message -> recorded.add(encode(message)));
        TrackRegistry registry = TestFixtures.registry();
        List<byte[]> snapshot;
        try (ReelRecorder recorder = ReelRecorder.builder()
            .setWriter(w -> w.setFile(file)
                .setLayerStrategy(STRATEGY)
                .addListener(publisher))
            .setTrackRegistry(registry)
            .setSegmentLength(8)
            .setAnchorInterval(16)
            .setIdleThreshold(0)
            .build()) {
            EntityRecorder player = recorder.createEntity(PLAYER);
            EntityRecorder npc = recorder.createEntity(NPC);
            player.recordState(POSITION, new IntState(0));
            npc.recordState(POSITION, new IntState(100));
            publisher.flush();
            for (int frame = 1; frame <= FRAMES; frame++) {
                recorder.advanceFrame();
                player.recordDelta(POSITION, new IntDelta(1));
                if (frame % 3 == 0) {
                    npc.recordDelta(POSITION, new IntDelta(-2));
                }
                publisher.flush();
            }
            snapshot = List.copyOf(recorded);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            publisher.release();
        }
        return snapshot;
    }

    private static List<ReelMessage> compact(List<ReelMessage> messages) {
        List<ReelMessage.SegmentStored> stored = new ArrayList<>();
        for (ReelMessage message : messages) {
            if (message instanceof ReelMessage.SegmentStored m) {
                stored.add(m);
            }
        }
        List<ReelMessage> kept = new ArrayList<>();
        for (ReelMessage message : messages) {
            long frame = segmentFrame(message);
            boolean covered = frame >= 0 && stored.stream().anyMatch(s -> frame >= s.startFrame() && frame < s.endFrame());
            if (covered && message instanceof ReelFragment fragment) {
                fragment.release();
            } else if (!covered) {
                kept.add(message);
            }
        }
        return kept;
    }

    private static long segmentFrame(ReelMessage message) {
        if (message instanceof ReelMessage.SegmentBegun m) {
            return m.startFrame();
        }
        if (message instanceof ReelMessage.SegmentSealed m) {
            return m.startFrame();
        }
        if (message instanceof ReelFragment m) {
            return m.startFrame();
        }
        return -1;
    }

    private static int indexOfSegmentBegun(List<ReelMessage> messages, long startFrame) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof ReelMessage.SegmentBegun m && m.startFrame() == startFrame) {
                return i;
            }
        }
        throw new IllegalStateException("no segment begins at " + startFrame);
    }

    private static ReelLiveReader reader() {
        return ReelLiveReader.builder().setLayerStrategy(STRATEGY).build();
    }

    private static byte[] encode(ReelMessage message) {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelMessageCodec.encode(buf, message);
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    private static List<ReelMessage> decodeAll(List<byte[]> recorded) throws IOException {
        List<ReelMessage> messages = new ArrayList<>(recorded.size());
        for (byte[] bytes : recorded) {
            messages.add(ReelMessageCodec.decode(Unpooled.wrappedBuffer(bytes)));
        }
        return messages;
    }

    private static Map<Long, IntState> stateAt(ReelLiveReader reader, TrackRegistry registry, long frame) {
        Map<Long, IntState> model = new HashMap<>();
        IntModel trackModel = TestFixtures.intModel();
        try (ReplayCursor cursor = ReplayCursor.builder().setTrackRegistry(registry).setReader(reader).build()) {
            cursor.addListener(update -> TestFixtures.applyIntModel(model, trackModel, update));
            cursor.seek(frame);
        }
        return model;
    }

}
