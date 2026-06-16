package dev.tommyjs.reel.storage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ReelIOUtilTest {

    private static void roundTripVarInt(long value) throws IOException {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelIOUtil.writeVarInt(buf, value);
            assertEquals(value, ReelIOUtil.readVarInt(buf));
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void varIntRoundTripZero() throws IOException {
        roundTripVarInt(0);
    }

    @Test
    void varIntRoundTripOne() throws IOException {
        roundTripVarInt(1);
    }

    @Test
    void varIntRoundTrip127() throws IOException {
        roundTripVarInt(127);
    }

    @Test
    void varIntRoundTrip128() throws IOException {
        roundTripVarInt(128);
    }

    @Test
    void varIntRoundTrip16383() throws IOException {
        roundTripVarInt(16383);
    }

    @Test
    void varIntRoundTrip16384() throws IOException {
        roundTripVarInt(16384);
    }

    @Test
    void varIntRoundTrip2097151() throws IOException {
        roundTripVarInt(2097151);
    }

    @Test
    void varIntRoundTripIntegerMax() throws IOException {
        roundTripVarInt(Integer.MAX_VALUE);
    }

    @Test
    void varIntRoundTripLongMax() throws IOException {
        roundTripVarInt(Long.MAX_VALUE);
    }

    @Test
    void varIntRoundTripLargePowerOfTwo() throws IOException {
        roundTripVarInt(1L << 56);
    }

    @Test
    void varIntEncodingLengthZero() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelIOUtil.writeVarInt(buf, 0);
            assertEquals(1, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void varIntEncodingLength127() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelIOUtil.writeVarInt(buf, 127);
            assertEquals(1, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void varIntEncodingLength128() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelIOUtil.writeVarInt(buf, 128);
            assertEquals(2, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void varIntEncodingLength16384() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelIOUtil.writeVarInt(buf, 16384);
            assertEquals(3, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void stringRoundTripEmpty() throws IOException {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelIOUtil.writeString(buf, "");
            assertEquals("", ReelIOUtil.readString(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void stringRoundTripAscii() throws IOException {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelIOUtil.writeString(buf, "hello");
            assertEquals("hello", ReelIOUtil.readString(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void stringRoundTripUtf8Multibyte() throws IOException {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelIOUtil.writeString(buf, "héllo");
            assertEquals("héllo", ReelIOUtil.readString(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void stringRoundTripEmoji() throws IOException {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelIOUtil.writeString(buf, "🎬reel");
            assertEquals("🎬reel", ReelIOUtil.readString(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void bytesRoundTripEmpty() throws IOException {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelIOUtil.writeBytes(buf, new byte[0]);
            assertArrayEquals(new byte[0], ReelIOUtil.readBytes(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void bytesRoundTripSmall() throws IOException {
        ByteBuf buf = Unpooled.buffer();
        try {
            byte[] data = {1, 2, 3, 4, 5};
            ReelIOUtil.writeBytes(buf, data);
            assertArrayEquals(data, ReelIOUtil.readBytes(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void bytesRoundTripLarge() throws IOException {
        ByteBuf buf = Unpooled.buffer();
        try {
            byte[] data = new byte[1000];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i * 31 + 7);
            }
            ReelIOUtil.writeBytes(buf, data);
            assertArrayEquals(data, ReelIOUtil.readBytes(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void lz4ByteBufRoundTripEmpty() throws IOException {
        ByteBuf src = Unpooled.buffer();
        ByteBuf compressed = Unpooled.buffer();
        ByteBuf decompressed = Unpooled.buffer();
        try {
            ReelIOUtil.compressLz4(src, compressed);
            int compressedLen = compressed.readableBytes();
            ReelIOUtil.decompressLz4(compressed, compressedLen, decompressed, 0);
            assertEquals(0, decompressed.readableBytes());
            assertEquals(0, compressed.readableBytes());
        } finally {
            src.release();
            compressed.release();
            decompressed.release();
        }
    }

    @Test
    void lz4ByteBufRoundTripLarge() throws IOException {
        byte[] data = new byte[64 * 1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ((i * 17 + 5) & 0xFF);
        }
        ByteBuf src = Unpooled.buffer(data.length);
        ByteBuf compressed = Unpooled.buffer();
        ByteBuf decompressed = Unpooled.buffer(data.length);
        try {
            src.writeBytes(data);
            ReelIOUtil.compressLz4(src, compressed);
            assertEquals(0, src.readableBytes(), "compress should consume src");
            int compressedLen = compressed.readableBytes();
            ReelIOUtil.decompressLz4(compressed, compressedLen, decompressed, data.length);
            assertEquals(data.length, decompressed.readableBytes());
            byte[] out = new byte[data.length];
            decompressed.readBytes(out);
            assertArrayEquals(data, out);
        } finally {
            src.release();
            compressed.release();
            decompressed.release();
        }
    }

    @Test
    void lz4ByteBufDecompressWrongSizeThrows() throws IOException {
        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};
        ByteBuf src = Unpooled.buffer();
        ByteBuf compressed = Unpooled.buffer();
        ByteBuf decompressed = Unpooled.buffer();
        try {
            src.writeBytes(data);
            ReelIOUtil.compressLz4(src, compressed);
            int compressedLen = compressed.readableBytes();
            // Claim too few uncompressed bytes — stream yields more than expected.
            assertThrows(IOException.class,
                () -> ReelIOUtil.decompressLz4(compressed, compressedLen, decompressed, data.length - 1));
        } finally {
            src.release();
            compressed.release();
            decompressed.release();
        }
    }

    @Test
    void varIntReadThrowsOnMalformed() {
        ByteBuf buf = Unpooled.buffer();
        try {
            for (int i = 0; i < 11; i++) {
                buf.writeByte(0xFF);
            }
            assertThrows(IOException.class, () -> ReelIOUtil.readVarInt(buf));
        } finally {
            buf.release();
        }
    }

}
