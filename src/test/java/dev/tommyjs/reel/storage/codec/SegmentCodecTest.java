package dev.tommyjs.reel.storage.codec;

import dev.tommyjs.reel.storage.model.RawPayload;
import dev.tommyjs.reel.storage.model.Segment;
import dev.tommyjs.reel.storage.model.TrackReference;
import dev.tommyjs.reel.storage.model.WindowEntry;
import dev.tommyjs.reel.storage.model.WindowGroup;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class SegmentCodecTest {

    private static WindowEntry deltaEntry(int trackRefIndex, byte... body) {
        return new WindowEntry(trackRefIndex, new RawPayload.Delta(Unpooled.wrappedBuffer(body)));
    }

    private static WindowEntry stateEntry(int trackRefIndex, boolean hasPrevious, byte... body) {
        return new WindowEntry(trackRefIndex, new RawPayload.State(hasPrevious, Unpooled.wrappedBuffer(body)));
    }

    private static WindowEntry closeEntry(int trackRefIndex, byte... body) {
        return new WindowEntry(trackRefIndex, new RawPayload.Close(Unpooled.wrappedBuffer(body)));
    }

    private static WindowEntry eventEntry(int trackRefIndex, byte... body) {
        return new WindowEntry(trackRefIndex, new RawPayload.Event(Unpooled.wrappedBuffer(body)));
    }

    private static Segment sampleSegment() {
        WindowGroup[][] layers = {
            {new WindowGroup(new WindowEntry[]{deltaEntry(0, (byte) 1, (byte) 2), stateEntry(1, true, (byte) 4, (byte) 5),
                eventEntry(0, (byte) 2, (byte) 6, (byte) 7)}),
                WindowGroup.EMPTY},
            {new WindowGroup(new WindowEntry[]{closeEntry(0, (byte) 9)})}
        };
        return new Segment(10, 20, new long[]{100, 105, 200}, new int[]{0, 1, 0},
            new TrackReference[]{new TrackReference(0, 7), new TrackReference(2, 3)}, layers);
    }

    private static void assertPayloadsEqual(RawPayload expected, RawPayload actual) {
        assertEquals(expected.getClass(), actual.getClass());
        if (expected instanceof RawPayload.State es && actual instanceof RawPayload.State as) {
            assertEquals(es.hasPrevious(), as.hasPrevious());
        }
        ByteBuf eb = expected.body().duplicate();
        ByteBuf ab = actual.body().duplicate();
        assertEquals(eb.readableBytes(), ab.readableBytes());
        while (eb.isReadable()) {
            assertEquals(eb.readByte(), ab.readByte());
        }
    }

    private static void assertSegmentsEqual(Segment expected, Segment actual) {
        assertEquals(expected.getStartFrame(), actual.getStartFrame());
        assertEquals(expected.getEndFrame(), actual.getEndFrame());
        assertArrayEquals(expected.getEntityIds(), actual.getEntityIds());
        assertArrayEquals(expected.getEntityTypeIds(), actual.getEntityTypeIds());
        assertArrayEquals(expected.getTrackRefs(), actual.getTrackRefs());
        assertEquals(expected.getLayerCount(), actual.getLayerCount());

        for (int l = 0; l < expected.getLayerCount(); l++) {
            assertEquals(expected.getWindowCount(l), actual.getWindowCount(l));
            for (int w = 0; w < expected.getWindowCount(l); w++) {
                WindowEntry[] exp = expected.getWindow(l, w).entries();
                WindowEntry[] act = actual.getWindow(l, w).entries();
                assertEquals(exp.length, act.length);
                for (int i = 0; i < exp.length; i++) {
                    assertEquals(exp[i].trackRefIndex(), act[i].trackRefIndex());
                    assertPayloadsEqual(exp[i].payload(), act[i].payload());
                }
            }
        }
    }

    @Test
    void roundTrip() throws IOException {
        Segment original = sampleSegment();
        ByteBuf buf = Unpooled.buffer();
        try {
            SegmentCodec.encode(buf, original);
            try (Segment decoded = SegmentCodec.decode(buf, original.getLayerCount())) {
                assertSegmentsEqual(original, decoded);
                assertEquals(0, buf.readableBytes());
            }
        } finally {
            buf.release();
            original.close();
        }
    }

    @Test
    void roundTripCompressed() throws IOException {
        Segment original = sampleSegment();
        ByteBuf buf = Unpooled.buffer();
        try {
            SegmentCodec.encodeCompressed(buf, original);
            int compressedLength = buf.readableBytes();
            try (Segment decoded = SegmentCodec.decodeCompressed(buf, compressedLength, original.getLayerCount())) {
                assertSegmentsEqual(original, decoded);
            }
        } finally {
            buf.release();
            original.close();
        }
    }

    @Test
    void roundTripEmpty() throws IOException {
        Segment original = new Segment(0, 0, new long[0], new int[0], new TrackReference[0],
            new WindowGroup[][]{{WindowGroup.EMPTY}});
        ByteBuf buf = Unpooled.buffer();
        try {
            SegmentCodec.encode(buf, original);
            try (Segment decoded = SegmentCodec.decode(buf, 1)) {
                assertSegmentsEqual(original, decoded);
                assertEquals(0, buf.readableBytes());
            }
        } finally {
            buf.release();
            original.close();
        }
    }

    @Test
    void decodeRejectsEndBeforeStart() {
        Segment original = new Segment(50, 10, new long[0], new int[0], new TrackReference[0], new WindowGroup[][]{{}});
        ByteBuf buf = Unpooled.buffer();
        try {
            SegmentCodec.encode(buf, original);
            assertThrows(IOException.class, () -> SegmentCodec.decode(buf, 1));
        } finally {
            buf.release();
            original.close();
        }
    }

}
