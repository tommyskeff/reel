package dev.tommyjs.reel.storage.live;

import dev.tommyjs.reel.LiveTestFixtures;
import dev.tommyjs.reel.track.TrackKind;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ReelMessageCodecTest {

    @Test
    void roundTripsEntityTypeRegistration() throws IOException {
        ReelMessage decoded = roundTrip(new ReelMessage.EntityTypeRegistered("player", 4));
        ReelMessage.EntityTypeRegistered message = assertInstanceOf(ReelMessage.EntityTypeRegistered.class, decoded);
        assertEquals("player", message.identifier());
        assertEquals(4, message.typeId());
    }

    @Test
    void roundTripsTrackTypeRegistration() throws IOException {
        ReelMessage decoded = roundTrip(new ReelMessage.TrackTypeRegistered("position", 7, 2, TrackKind.EVENT));
        ReelMessage.TrackTypeRegistered message = assertInstanceOf(ReelMessage.TrackTypeRegistered.class, decoded);
        assertEquals("position", message.identifier());
        assertEquals(7, message.typeId());
        assertEquals(2, message.codecVersion());
        assertEquals(TrackKind.EVENT, message.kind());
    }

    @Test
    void roundTripsSegmentBegun() throws IOException {
        ReelMessage decoded = roundTrip(new ReelMessage.SegmentBegun(256, true));
        ReelMessage.SegmentBegun message = assertInstanceOf(ReelMessage.SegmentBegun.class, decoded);
        assertEquals(256L, message.startFrame());
        assertTrue(message.anchor());
    }

    @Test
    void roundTripsSegmentSealed() throws IOException {
        ReelMessage decoded = roundTrip(new ReelMessage.SegmentSealed(256, 512, false));
        ReelMessage.SegmentSealed message = assertInstanceOf(ReelMessage.SegmentSealed.class, decoded);
        assertEquals(256L, message.startFrame());
        assertEquals(512L, message.endFrame());
        assertEquals(false, message.anchor());
    }

    @Test
    void roundTripsComplete() throws IOException {
        ReelMessage decoded = roundTrip(new ReelMessage.Complete(1234));
        assertEquals(1234L, assertInstanceOf(ReelMessage.Complete.class, decoded).totalFrames());
    }

    @Test
    void roundTripsFragment() throws IOException {
        ReelFragment original = LiveTestFixtures.fragment(0, 4,
            LiveTestFixtures.entry(1L, 0L, LiveTestFixtures.deltaPayload(5)));

        ReelMessage decoded = roundTrip(original);
        ReelFragment fragment = assertInstanceOf(ReelFragment.class, decoded);
        assertEquals(0L, fragment.startFrame());
        assertEquals(4L, fragment.endFrame());
        assertEquals(1, fragment.entries().size());

        fragment.release();
        original.release();
    }

    @Test
    void rejectsAnUnknownMessageKind() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(99);
        assertThrows(IOException.class, () -> ReelMessageCodec.decode(buf));
    }

    private static ReelMessage roundTrip(ReelMessage message) throws IOException {
        ByteBuf buf = Unpooled.buffer();
        ReelMessageCodec.encode(buf, message);
        return ReelMessageCodec.decode(buf);
    }

}
