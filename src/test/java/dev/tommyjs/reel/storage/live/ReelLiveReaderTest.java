package dev.tommyjs.reel.storage.live;

import dev.tommyjs.reel.LiveTestFixtures;
import dev.tommyjs.reel.storage.ReelCompressor;
import dev.tommyjs.reel.storage.codec.SegmentCodec;
import dev.tommyjs.reel.storage.model.*;
import dev.tommyjs.reel.storage.writer.LayerStrategy;
import dev.tommyjs.reel.track.TrackKind;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReelLiveReaderTest {

    private static final LayerStrategy STRATEGY = new LayerStrategy(new long[]{1, 4});

    private static ReelLiveReader reader() {
        return ReelLiveReader.builder().setLayerStrategy(STRATEGY).build();
    }

    @Test
    void reportsAnEmptyButUsableTimeline() {
        try (ReelLiveReader reader = reader()) {
            assertEquals(1L, reader.getTotalFrames());
            assertEquals(0L, reader.getHeadFrame());
            assertFalse(reader.isComplete());
            assertTrue(reader.ownsSegments());
            assertEquals(0, reader.readSegmentDirectory().size());
            assertArrayEquals(STRATEGY.strides(), reader.readTrackDirectory().layerStrides());
        }
    }

    @Test
    void growsDirectoriesAndBumpsRevision() {
        try (ReelLiveReader reader = reader()) {
            long before = reader.getRevision();

            reader.accept(new ReelMessage.EntityTypeRegistered("player", 0));
            reader.accept(new ReelMessage.TrackTypeRegistered("position", 0, 1, TrackKind.DELTA));

            assertTrue(reader.getRevision() > before);
            assertEquals("player", reader.readTypeDirectory().get(0).identifier());
            assertEquals("position", reader.readTrackDirectory().get(0).identifier());
            assertEquals(TrackKind.DELTA, reader.readTrackDirectory().get(0).kind());
        }
    }

    @Test
    void ignoresDuplicateRegistrations() {
        try (ReelLiveReader reader = reader()) {
            reader.accept(new ReelMessage.EntityTypeRegistered("player", 0));
            reader.accept(new ReelMessage.EntityTypeRegistered("player", 0));

            assertEquals(1, reader.readTypeDirectory().types().size());
        }
    }

    @Test
    void appendsFragmentsIntoAGrowingTailSegment() {
        try (ReelLiveReader reader = reader()) {
            registerTypes(reader);
            reader.accept(new ReelMessage.SegmentBegun(0, true));

            reader.accept(LiveTestFixtures.fragment(0, 1,
                LiveTestFixtures.entry(1L, 0L, LiveTestFixtures.statePayload(5))));

            assertEquals(1, reader.readSegmentDirectory().size());
            assertEquals(0L, reader.getHeadFrame());
            assertEquals(1L, reader.getTotalFrames());

            reader.accept(LiveTestFixtures.fragment(1, 2,
                LiveTestFixtures.entry(1L, 1L, LiveTestFixtures.deltaPayload(2))));

            assertEquals(1L, reader.getHeadFrame());
            assertEquals(2L, reader.getTotalFrames());

            SegmentView tail = reader.readSegment(0);
            assertEquals(0L, tail.getStartFrame());
            assertEquals(2L, tail.getEndFrame());
            assertEquals(1, tail.getWindow(0, 0).size());
            assertEquals(1, tail.getWindow(0, 1).size());
        }
    }

    @Test
    void exposesTheOpenTailAsAnAnchorInTheDirectory() {
        try (ReelLiveReader reader = reader()) {
            registerTypes(reader);
            reader.accept(new ReelMessage.SegmentBegun(0, true));
            reader.accept(LiveTestFixtures.fragment(0, 1,
                LiveTestFixtures.entry(1L, 0L, LiveTestFixtures.statePayload(1))));

            assertEquals(0L, reader.readSegmentDirectory().floorAnchorStart(0));
        }
    }

    @Test
    void sealsTheTailIntoAnIndexedSegment() {
        try (ReelLiveReader reader = reader()) {
            registerTypes(reader);
            reader.accept(new ReelMessage.SegmentBegun(0, true));
            reader.accept(LiveTestFixtures.fragment(0, 1,
                LiveTestFixtures.entry(1L, 0L, LiveTestFixtures.statePayload(5))));
            reader.accept(new ReelMessage.SegmentSealed(0, 4, true));

            assertEquals(1, reader.readSegmentDirectory().size());
            SegmentEntry entry = reader.readSegmentDirectory().segments().get(0);
            assertEquals(0L, entry.startFrame());
            assertEquals(4L, entry.endFrame());
            assertTrue(entry.anchor());
            assertEquals(3L, reader.getHeadFrame());

            reader.accept(new ReelMessage.SegmentBegun(4, false));
            reader.accept(LiveTestFixtures.fragment(4, 5,
                LiveTestFixtures.entry(1L, 4L, LiveTestFixtures.deltaPayload(1))));

            assertEquals(2, reader.readSegmentDirectory().size());
            assertFalse(reader.readSegmentDirectory().segments().get(1).anchor());
            assertEquals(1, reader.readSegment(1).getWindow(0, 0).size());
        }
    }

    @Test
    void holdsFragmentsReferencingUnknownTypesUntilRegistered() {
        try (ReelLiveReader reader = reader()) {
            reader.accept(new ReelMessage.SegmentBegun(0, true));
            reader.accept(LiveTestFixtures.fragment(0, 1,
                LiveTestFixtures.entry(1L, 0L, LiveTestFixtures.statePayload(5))));

            assertEquals(1, reader.getPendingMessageCount());
            assertEquals(0L, reader.getHeadFrame());

            reader.accept(new ReelMessage.EntityTypeRegistered("player", 0));
            assertEquals(1, reader.getPendingMessageCount());

            reader.accept(new ReelMessage.TrackTypeRegistered("position", 0, 1, TrackKind.DELTA));

            assertEquals(0, reader.getPendingMessageCount());
            assertEquals(1, reader.readSegment(0).getWindow(0, 0).size());
        }
    }

    @Test
    void preservesOrderWhileBlocked() {
        try (ReelLiveReader reader = reader()) {
            reader.accept(new ReelMessage.SegmentBegun(0, true));
            reader.accept(LiveTestFixtures.fragment(0, 1,
                LiveTestFixtures.entry(1L, 0L, LiveTestFixtures.statePayload(5))));
            reader.accept(new ReelMessage.SegmentSealed(0, 4, true));
            reader.accept(new ReelMessage.SegmentBegun(4, false));

            assertEquals(3, reader.getPendingMessageCount());

            registerTypes(reader);

            assertEquals(0, reader.getPendingMessageCount());
            assertEquals(1, reader.readSegmentDirectory().segments().size() - 1);
            assertEquals(4L, reader.readSegmentDirectory().segments().get(1).startFrame());
        }
    }

    @Test
    void dropsTheOldestPendingMessageWhenTheQueueOverflows() {
        try (ReelLiveReader reader = ReelLiveReader.builder()
            .setLayerStrategy(STRATEGY)
            .setMaxPendingMessages(2)
            .build()) {

            for (int i = 0; i < 5; i++) {
                reader.accept(LiveTestFixtures.fragment(i, i + 1,
                    LiveTestFixtures.entry(1L, i, LiveTestFixtures.deltaPayload(1))));
            }

            assertEquals(2, reader.getPendingMessageCount());
        }
    }

    @Test
    void marksCompletionAndFreezesTheTotal() {
        try (ReelLiveReader reader = reader()) {
            registerTypes(reader);
            reader.accept(new ReelMessage.SegmentBegun(0, true));
            reader.accept(LiveTestFixtures.fragment(0, 2,
                LiveTestFixtures.entry(1L, 0L, LiveTestFixtures.statePayload(5))));
            reader.accept(new ReelMessage.Complete(8));

            assertTrue(reader.isComplete());
            assertEquals(8L, reader.getTotalFrames());
        }
    }

    @Test
    void ingestsBootstrapSegmentBytes() throws IOException {
        try (ReelLiveReader reader = reader()) {
            registerTypes(reader);

            ByteBuf compressed = Unpooled.buffer();
            SegmentCodec.encodeCompressed(compressed, bootstrapSegment(), ReelCompressor.LZ4);
            reader.acceptSegment(0, 4, true, compressed);

            assertEquals(1, reader.readSegmentDirectory().size());
            assertEquals(3L, reader.getHeadFrame());
            assertTrue(reader.readSegmentDirectory().segments().get(0).anchor());
            assertEquals(1, reader.readSegment(0).getWindow(0, 0).size());
        }
    }

    @Test
    void rejectsOutOfOrderBootstrapSegments() throws IOException {
        try (ReelLiveReader reader = reader()) {
            registerTypes(reader);

            ByteBuf first = Unpooled.buffer();
            SegmentCodec.encodeCompressed(first, bootstrapSegment(), ReelCompressor.LZ4);
            reader.acceptSegment(4, 8, true, first);

            ByteBuf second = Unpooled.buffer();
            SegmentCodec.encodeCompressed(second, bootstrapSegment(), ReelCompressor.LZ4);

            assertThrows(IOException.class, () -> reader.acceptSegment(0, 4, false, second));
        }
    }

    @Test
    void evictsSegmentsOutsideTheRetentionWindow() {
        try (ReelLiveReader reader = reader()) {
            registerTypes(reader);
            reader.setRetainedFrames(4);

            for (int segment = 0; segment < 4; segment++) {
                long start = segment * 4L;
                reader.accept(new ReelMessage.SegmentBegun(start, true));
                reader.accept(LiveTestFixtures.fragment(start, start + 1,
                    LiveTestFixtures.entry(1L, start, LiveTestFixtures.statePayload(segment))));
                reader.accept(new ReelMessage.SegmentSealed(start, start + 4, true));
            }

            assertEquals(4, reader.readSegmentDirectory().size());
            assertEquals(0, reader.readSegment(0).getWindowCount(0));
            assertEquals(4, reader.readSegment(3).getWindowCount(0));
        }
    }

    @Test
    void retainsTheAnchorThatRetainedSegmentsDependOn() {
        try (ReelLiveReader reader = reader()) {
            registerTypes(reader);
            reader.setRetainedFrames(4);

            for (int segment = 0; segment < 4; segment++) {
                long start = segment * 4L;
                reader.accept(new ReelMessage.SegmentBegun(start, segment % 2 == 0));
                reader.accept(LiveTestFixtures.fragment(start, start + 1,
                    LiveTestFixtures.entry(1L, start, LiveTestFixtures.statePayload(segment))));
                reader.accept(new ReelMessage.SegmentSealed(start, start + 4, segment % 2 == 0));
            }

            assertEquals(0, reader.readSegment(0).getWindowCount(0));
            assertEquals(0, reader.readSegment(1).getWindowCount(0));
            assertEquals(4, reader.readSegment(2).getWindowCount(0));
        }
    }

    @Test
    void dropsFragmentsRatherThanStructuralMessagesOnOverflow() {
        try (ReelLiveReader reader = ReelLiveReader.builder().setLayerStrategy(STRATEGY).setMaxPendingMessages(1)
            .build()) {
            reader.accept(new ReelMessage.SegmentBegun(0, true));
            reader.accept(LiveTestFixtures.fragment(0, 1,
                LiveTestFixtures.entry(1L, 0L, LiveTestFixtures.statePayload(5))));
            reader.accept(new ReelMessage.Complete(8));
            reader.accept(LiveTestFixtures.fragment(1, 2,
                LiveTestFixtures.entry(1L, 1L, LiveTestFixtures.deltaPayload(1))));

            reader.acceptDirectories(LiveTestFixtures.playerTypes(),
                LiveTestFixtures.positionTracks(STRATEGY.strides()));

            assertEquals(0, reader.getPendingMessageCount());
            assertTrue(reader.isComplete());
            assertEquals(8L, reader.getTotalFrames());
        }
    }

    @Test
    void acceptsDirectoriesInBulk() {
        try (ReelLiveReader reader = reader()) {
            reader.accept(new ReelMessage.SegmentBegun(0, true));
            reader.accept(LiveTestFixtures.fragment(0, 1,
                LiveTestFixtures.entry(1L, 0L, LiveTestFixtures.statePayload(5))));

            assertEquals(1, reader.getPendingMessageCount());

            reader.acceptDirectories(LiveTestFixtures.playerTypes(),
                LiveTestFixtures.positionTracks(STRATEGY.strides()));

            assertEquals(0, reader.getPendingMessageCount());
            assertEquals(1, reader.readSegment(0).getWindow(0, 0).size());
        }
    }

    @Test
    void reportsItsOwnHeadState() {
        try (ReelLiveReader reader = reader()) {
            registerTypes(reader);
            reader.accept(new ReelMessage.SegmentBegun(0, true));
            reader.accept(LiveTestFixtures.fragment(0, 3,
                LiveTestFixtures.entry(1L, 0L, LiveTestFixtures.statePayload(5))));

            ReelHeadState state = reader.headState();
            assertEquals(2L, state.headFrame());
            assertFalse(state.complete());
            assertEquals(1, state.types().types().size());
            assertEquals(1, state.segments().size());
        }
    }

    private static Segment bootstrapSegment() {
        WindowGroup[][] windows = new WindowGroup[2][];
        windows[0] = new WindowGroup[]{
            new WindowGroup(new WindowEntry[]{new WindowEntry(0, LiveTestFixtures.statePayload(1))}),
            WindowGroup.EMPTY, WindowGroup.EMPTY, WindowGroup.EMPTY};
        windows[1] = new WindowGroup[]{WindowGroup.EMPTY};

        return new Segment(0, 4, new long[]{1L}, new int[]{LiveTestFixtures.PLAYER_TYPE_ID},
            new TrackReference[]{new TrackReference(0, LiveTestFixtures.POSITION_TYPE_ID)}, windows);
    }

    private static void registerTypes(ReelLiveReader reader) {
        reader.accept(new ReelMessage.EntityTypeRegistered("player", LiveTestFixtures.PLAYER_TYPE_ID));
        reader.accept(new ReelMessage.TrackTypeRegistered("position", LiveTestFixtures.POSITION_TYPE_ID, 1,
            TrackKind.DELTA));
    }

}
