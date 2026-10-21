package dev.tommyjs.reel.storage.live;

import dev.tommyjs.reel.LiveTestFixtures;
import dev.tommyjs.reel.storage.model.SegmentDirectory;
import dev.tommyjs.reel.storage.model.SegmentEntry;
import dev.tommyjs.reel.track.TrackKind;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HeadStateCodecTest {

    private static final long[] STRIDES = {1, 4, 16};

    @Test
    void roundTripsDirectoriesAndHead() throws IOException {
        ReelHeadState original = new ReelHeadState(
            LiveTestFixtures.playerTypes(),
            LiveTestFixtures.positionTracks(STRIDES),
            new SegmentDirectory(List.of(
                new SegmentEntry(0, 16, 0, 0, true),
                new SegmentEntry(16, 32, 0, 0, false))),
            31, false);

        ByteBuf buf = Unpooled.buffer();
        HeadStateCodec.encode(buf, original);
        ReelHeadState decoded = HeadStateCodec.decode(buf);

        assertEquals(1, decoded.types().types().size());
        assertEquals("player", decoded.types().get(LiveTestFixtures.PLAYER_TYPE_ID).identifier());

        assertEquals(1, decoded.tracks().trackTypes().size());
        assertEquals("position", decoded.tracks().get(LiveTestFixtures.POSITION_TYPE_ID).identifier());
        assertEquals(TrackKind.DELTA, decoded.tracks().get(LiveTestFixtures.POSITION_TYPE_ID).kind());
        assertArrayEquals(STRIDES, decoded.tracks().layerStrides());

        assertEquals(2, decoded.segments().size());
        assertTrue(decoded.segments().segments().get(0).anchor());
        assertFalse(decoded.segments().segments().get(1).anchor());
        assertEquals(16L, decoded.segments().segments().get(1).startFrame());

        assertEquals(31L, decoded.headFrame());
        assertFalse(decoded.complete());
    }

    @Test
    void roundTripsACompletedHeadState() throws IOException {
        ReelHeadState original = new ReelHeadState(
            LiveTestFixtures.playerTypes(),
            LiveTestFixtures.positionTracks(STRIDES),
            new SegmentDirectory(List.of()), 99, true);

        ByteBuf buf = Unpooled.buffer();
        HeadStateCodec.encode(buf, original);
        ReelHeadState decoded = HeadStateCodec.decode(buf);

        assertTrue(decoded.complete());
        assertEquals(99L, decoded.headFrame());
        assertEquals(0, decoded.segments().size());
    }

    @Test
    void roundTripsAnEmptyHeadState() throws IOException {
        ReelHeadState original = new ReelHeadState(
            LiveTestFixtures.playerTypes(),
            LiveTestFixtures.positionTracks(STRIDES),
            new SegmentDirectory(List.of()), -1, false);

        ByteBuf buf = Unpooled.buffer();
        HeadStateCodec.encode(buf, original);
        ReelHeadState decoded = HeadStateCodec.decode(buf);

        assertEquals(-1L, decoded.headFrame());
        assertFalse(decoded.complete());
    }

    @Test
    void preservesAnchorFlagsAcrossManySegments() throws IOException {
        List<SegmentEntry> entries = List.of(
            new SegmentEntry(0, 16, 0, 0, true),
            new SegmentEntry(16, 32, 0, 0, false),
            new SegmentEntry(32, 48, 0, 0, true));

        ByteBuf buf = Unpooled.buffer();
        HeadStateCodec.encode(buf, new ReelHeadState(LiveTestFixtures.playerTypes(),
            LiveTestFixtures.positionTracks(STRIDES), new SegmentDirectory(entries), 47, false));
        ReelHeadState decoded = HeadStateCodec.decode(buf);

        assertEquals(32L, decoded.segments().floorAnchorStart(40));
        assertEquals(0L, decoded.segments().floorAnchorStart(20));
    }

}
