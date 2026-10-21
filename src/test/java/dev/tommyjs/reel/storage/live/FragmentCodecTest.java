package dev.tommyjs.reel.storage.live;

import dev.tommyjs.reel.LiveTestFixtures;
import dev.tommyjs.reel.TestFixtures;
import dev.tommyjs.reel.storage.ReelIOUtil;
import dev.tommyjs.reel.storage.model.RawPayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FragmentCodecTest {

    @Test
    void roundTripsFrameRangeAndCoordinates() throws IOException {
        ReelFragment original = new ReelFragment(4, 8, List.of(
            new FragmentEntry(7L, 3, 5, 1, 4L, LiveTestFixtures.deltaPayload(2)),
            new FragmentEntry(9L, 3, 5, 0, 6L, LiveTestFixtures.deltaPayload(-1))));

        ByteBuf buf = Unpooled.buffer();
        FragmentCodec.encode(buf, original);
        ReelFragment decoded = FragmentCodec.decode(buf);

        assertEquals(4L, decoded.startFrame());
        assertEquals(8L, decoded.endFrame());
        assertEquals(2, decoded.entries().size());

        FragmentEntry first = decoded.entries().get(0);
        assertEquals(7L, first.entityId());
        assertEquals(3, first.entityTypeId());
        assertEquals(5, first.trackTypeId());
        assertEquals(1, first.layer());
        assertEquals(4L, first.windowStartFrame());

        FragmentEntry second = decoded.entries().get(1);
        assertEquals(9L, second.entityId());
        assertEquals(0, second.layer());
        assertEquals(6L, second.windowStartFrame());

        decoded.release();
        original.release();
    }

    @Test
    void roundTripsEveryPayloadKind() throws IOException {
        ReelFragment original = new ReelFragment(0, 1, List.of(
            LiveTestFixtures.entry(1L, 0L, LiveTestFixtures.deltaPayload(3)),
            LiveTestFixtures.entry(2L, 0L, LiveTestFixtures.statePayload(11)),
            LiveTestFixtures.entry(3L, 0L, LiveTestFixtures.eventPayload(42)),
            LiveTestFixtures.entry(4L, 0L, LiveTestFixtures.closePayload(7))));

        ByteBuf buf = Unpooled.buffer();
        FragmentCodec.encode(buf, original);
        ReelFragment decoded = FragmentCodec.decode(buf);

        assertInstanceOf(RawPayload.Delta.class, decoded.entries().get(0).payload());
        assertInstanceOf(RawPayload.State.class, decoded.entries().get(1).payload());
        assertInstanceOf(RawPayload.Event.class, decoded.entries().get(2).payload());
        assertInstanceOf(RawPayload.Close.class, decoded.entries().get(3).payload());

        decoded.release();
        original.release();
    }

    @Test
    void preservesPayloadBytes() throws IOException {
        ReelFragment original = new ReelFragment(0, 1,
            List.of(LiveTestFixtures.entry(1L, 0L, LiveTestFixtures.deltaPayload(9, -4))));

        ByteBuf buf = Unpooled.buffer();
        FragmentCodec.encode(buf, original);
        ReelFragment decoded = FragmentCodec.decode(buf);

        ByteBuf body = decoded.entries().get(0).payload().body();
        assertEquals(2, ReelIOUtil.readVarIntAsInt(body, "delta_count"));
        assertEquals(new TestFixtures.IntDelta(9), TestFixtures.intDeltaCodec().decode(body));
        assertEquals(new TestFixtures.IntDelta(-4), TestFixtures.intDeltaCodec().decode(body));

        decoded.release();
        original.release();
    }

    @Test
    void roundTripsAnEmptyFragment() throws IOException {
        ByteBuf buf = Unpooled.buffer();
        FragmentCodec.encode(buf, new ReelFragment(12, 16, List.of()));
        ReelFragment decoded = FragmentCodec.decode(buf);

        assertEquals(12L, decoded.startFrame());
        assertEquals(16L, decoded.endFrame());
        assertTrue(decoded.entries().isEmpty());
    }

    @Test
    void rejectsAnInvertedFrameRange() {
        ByteBuf buf = Unpooled.buffer();
        ReelIOUtil.writeVarInt(buf, 9);
        ReelIOUtil.writeVarInt(buf, 4);
        ReelIOUtil.writeVarInt(buf, 0);

        assertThrows(IOException.class, () -> FragmentCodec.decode(buf));
    }

}
