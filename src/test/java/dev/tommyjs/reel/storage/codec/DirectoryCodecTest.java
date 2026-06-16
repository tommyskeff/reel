package dev.tommyjs.reel.storage.codec;

import dev.tommyjs.reel.storage.ReelIOUtil;
import dev.tommyjs.reel.storage.model.*;
import dev.tommyjs.reel.track.TrackKind;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DirectoryCodecTest {

    @Test
    void entityTypesRoundTrip() throws IOException {
        EntityTypeDirectory dir = new EntityTypeDirectory(
            List.of(new EntityTypeDefinition(0, "player"), new EntityTypeDefinition(5, "npc")), Map.of(), Map.of());
        ByteBuf buf = Unpooled.buffer();
        try {
            DirectoryCodec.encodeEntityTypes(buf, dir);
            EntityTypeDirectory decoded = DirectoryCodec.decodeEntityTypes(buf);
            assertEquals(dir.types(), decoded.types());
            assertEquals("npc", decoded.get(5).identifier());
            assertEquals(0, decoded.get("player").id());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void entityTypesBytes() {
        EntityTypeDirectory dir = new EntityTypeDirectory(List.of(new EntityTypeDefinition(1, "ab")), Map.of(),
            Map.of());
        ByteBuf buf = Unpooled.buffer();
        try {
            DirectoryCodec.encodeEntityTypes(buf, dir);
            assertArrayEquals(new byte[]{1, 1, 2, 'a', 'b'}, ByteBufUtil.getBytes(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void trackTypesRoundTrip() throws IOException {
        TrackDirectory dir = new TrackDirectory(2, new long[]{1, 4},
            List.of(new TrackDefinition(0, "position", 3, TrackKind.DELTA),
                new TrackDefinition(1, "health", 1, TrackKind.SNAPSHOT)), Map.of(), Map.of());
        ByteBuf buf = Unpooled.buffer();
        try {
            DirectoryCodec.encodeTrackTypes(buf, dir);
            TrackDirectory decoded = DirectoryCodec.decodeTrackTypes(buf);
            assertEquals(2, decoded.layerCount());
            assertArrayEquals(new long[]{1, 4}, decoded.layerStrides());
            assertEquals(dir.trackTypes(), decoded.trackTypes());
            assertEquals(3, decoded.get("position").codecVersion());
            assertEquals(TrackKind.DELTA, decoded.get("position").kind());
            assertEquals(TrackKind.SNAPSHOT, decoded.get("health").kind());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void decodeRejectsUnknownTrackKind() {
        ByteBuf buf = Unpooled.buffer();
        ReelIOUtil.writeVarInt(buf, 1);
        ReelIOUtil.writeVarInt(buf, 1);
        ReelIOUtil.writeVarInt(buf, 1);
        ReelIOUtil.writeVarInt(buf, 0);
        ReelIOUtil.writeString(buf, "position");
        ReelIOUtil.writeVarInt(buf, 1);
        ReelIOUtil.writeVarInt(buf, 99);
        try {
            assertThrows(IOException.class, () -> DirectoryCodec.decodeTrackTypes(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void trackTypesRejectsNonUnitFirstStride() {
        TrackDirectory dir = new TrackDirectory(1, new long[]{2}, List.of(), Map.of(), Map.of());
        ByteBuf buf = Unpooled.buffer();
        try {
            DirectoryCodec.encodeTrackTypes(buf, dir);
            assertThrows(IOException.class, () -> DirectoryCodec.decodeTrackTypes(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void segmentsRoundTrip() throws IOException {
        SegmentDirectory dir = new SegmentDirectory(
            List.of(new SegmentEntry(0, 99, 38, 512, true), new SegmentEntry(100, 199, 550, 480, false)));
        ByteBuf buf = Unpooled.buffer();
        try {
            DirectoryCodec.encodeSegments(buf, dir);
            SegmentDirectory decoded = DirectoryCodec.decodeSegments(buf);
            assertEquals(dir.segments(), decoded.segments());
            assertTrue(decoded.segments().get(0).anchor());
            assertFalse(decoded.segments().get(1).anchor());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void segmentsRejectsOversizedByteSize() {
        SegmentDirectory dir = new SegmentDirectory(List.of(new SegmentEntry(0, 1, 0, (long) Integer.MAX_VALUE + 1)));
        ByteBuf buf = Unpooled.buffer();
        try {
            assertThrows(IllegalStateException.class, () -> DirectoryCodec.encodeSegments(buf, dir));
        } finally {
            buf.release();
        }
    }

}
