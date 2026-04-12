package dev.tommyjs.reel.storage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ReelIOUtilTest {

    @Test
    void varIntRoundTrip() {
        long[] values = {0, 1, 127, 128, 255, 256, 16383, 16384, 65535, Integer.MAX_VALUE, Long.MAX_VALUE};
        ByteBuf buf = Unpooled.buffer();
        try {
            for (long v : values) {
                buf.clear();
                ReelIOUtil.writeVarInt(buf, v);
                assertEquals(v, ReelIOUtil.readVarInt(buf));
            }
        } finally {
            buf.release();
        }
    }

    @Test
    void varIntHandlesMaxUnsigned() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelIOUtil.writeVarInt(buf, -1L);
            assertEquals(10, buf.readableBytes());
            assertEquals(-1L, ReelIOUtil.readVarInt(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void varIntSingleByte() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelIOUtil.writeVarInt(buf, 0);
            assertEquals(1, buf.readableBytes());
            buf.clear();

            ReelIOUtil.writeVarInt(buf, 127);
            assertEquals(1, buf.readableBytes());
            buf.clear();

            ReelIOUtil.writeVarInt(buf, 128);
            assertEquals(2, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void varIntEncodingSize() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelIOUtil.writeVarInt(buf, 0x7F);
            assertEquals(1, buf.readableBytes());
            buf.clear();

            ReelIOUtil.writeVarInt(buf, 0x80);
            assertEquals(2, buf.readableBytes());
            buf.clear();

            ReelIOUtil.writeVarInt(buf, 0x3FFF);
            assertEquals(2, buf.readableBytes());
            buf.clear();

            ReelIOUtil.writeVarInt(buf, 0x4000);
            assertEquals(3, buf.readableBytes());
            buf.clear();

            ReelIOUtil.writeVarInt(buf, Long.MAX_VALUE);
            assertEquals(9, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void varIntMultipleValuesSequential() {
        ByteBuf buf = Unpooled.buffer();
        try {
            long[] values = {0, 300, 100000, 42, Long.MAX_VALUE, 1};
            for (long v : values) {
                ReelIOUtil.writeVarInt(buf, v);
            }
            for (long v : values) {
                assertEquals(v, ReelIOUtil.readVarInt(buf));
            }
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void varIntBoundaryValues() {
        ByteBuf buf = Unpooled.buffer();
        try {
            long[] boundaries = {
                    (1L << 7) - 1,
                    (1L << 7),
                    (1L << 14) - 1,
                    (1L << 14),
                    (1L << 21) - 1,
                    (1L << 21),
                    (1L << 28) - 1,
                    (1L << 28),
                    (1L << 35) - 1,
                    (1L << 35),
                    (1L << 42) - 1,
                    (1L << 42),
                    (1L << 49) - 1,
                    (1L << 49),
                    (1L << 56) - 1,
                    (1L << 56),
                    (1L << 63) - 1,
            };
            for (long v : boundaries) {
                buf.clear();
                ReelIOUtil.writeVarInt(buf, v);
                assertEquals(v, ReelIOUtil.readVarInt(buf));
            }
        } finally {
            buf.release();
        }
    }

    @Test
    void varIntRejectsMalformedInput() {
        ByteBuf buf = Unpooled.buffer();
        try {
            for (int i = 0; i < 11; i++) {
                buf.writeByte(0x80);
            }
            assertThrows(IllegalStateException.class, () -> ReelIOUtil.readVarInt(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void varIntReadFromEmptyBufferThrows() {
        ByteBuf buf = Unpooled.buffer(0);
        try {
            assertThrows(IndexOutOfBoundsException.class, () -> ReelIOUtil.readVarInt(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void varIntPowersOfTwo() {
        ByteBuf buf = Unpooled.buffer();
        try {
            for (int shift = 0; shift < 63; shift++) {
                long value = 1L << shift;
                buf.clear();
                ReelIOUtil.writeVarInt(buf, value);
                assertEquals(value, ReelIOUtil.readVarInt(buf));
            }
        } finally {
            buf.release();
        }
    }

    @Test
    void varIntPowersOfTwoMinusOne() {
        ByteBuf buf = Unpooled.buffer();
        try {
            for (int shift = 1; shift < 63; shift++) {
                long value = (1L << shift) - 1;
                buf.clear();
                ReelIOUtil.writeVarInt(buf, value);
                assertEquals(value, ReelIOUtil.readVarInt(buf));
            }
        } finally {
            buf.release();
        }
    }

    @Test
    void varIntRandomValues() {
        Random rng = new Random(12345);
        ByteBuf buf = Unpooled.buffer();
        try {
            for (int i = 0; i < 1000; i++) {
                long value = rng.nextLong() & Long.MAX_VALUE;
                buf.clear();
                ReelIOUtil.writeVarInt(buf, value);
                assertEquals(value, ReelIOUtil.readVarInt(buf));
            }
        } finally {
            buf.release();
        }
    }

    @Test
    void varIntTruncatedInputThrows() {
        ByteBuf buf = Unpooled.buffer();
        try {
            buf.writeByte(0x80);
            assertThrows(IndexOutOfBoundsException.class, () -> ReelIOUtil.readVarInt(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void svarIntRoundTrip() {
        long[] values = {0, 1, -1, 127, -127, 128, -128, 16384, -16384, Long.MAX_VALUE, Long.MIN_VALUE};
        ByteBuf buf = Unpooled.buffer();
        try {
            for (long v : values) {
                buf.clear();
                ReelIOUtil.writeSVarInt(buf, v);
                assertEquals(v, ReelIOUtil.readSVarInt(buf));
            }
        } finally {
            buf.release();
        }
    }

    @Test
    void svarIntZigZagEncoding() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelIOUtil.writeSVarInt(buf, 0);
            assertEquals(1, buf.readableBytes());
            buf.clear();

            ReelIOUtil.writeSVarInt(buf, -1);
            assertEquals(1, buf.readableBytes());
            buf.clear();

            ReelIOUtil.writeSVarInt(buf, 1);
            assertEquals(1, buf.readableBytes());
            buf.clear();

            ReelIOUtil.writeSVarInt(buf, -64);
            assertEquals(1, buf.readableBytes());
            buf.clear();

            ReelIOUtil.writeSVarInt(buf, 64);
            assertEquals(2, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void svarIntMultipleValuesSequential() {
        ByteBuf buf = Unpooled.buffer();
        try {
            long[] values = {0, -1, 300, -300, Long.MIN_VALUE, Long.MAX_VALUE};
            for (long v : values) {
                ReelIOUtil.writeSVarInt(buf, v);
            }
            for (long v : values) {
                assertEquals(v, ReelIOUtil.readSVarInt(buf));
            }
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void svarIntBoundaryValues() {
        ByteBuf buf = Unpooled.buffer();
        try {
            long[] boundaries = {
                    -64, 63,
                    -65, 64,
                    -8192, 8191,
                    -8193, 8192,
            };
            for (long v : boundaries) {
                buf.clear();
                ReelIOUtil.writeSVarInt(buf, v);
                assertEquals(v, ReelIOUtil.readSVarInt(buf));
            }
        } finally {
            buf.release();
        }
    }

    @Test
    void svarIntMinMaxValues() {
        ByteBuf buf = Unpooled.buffer();
        try {
            buf.clear();
            ReelIOUtil.writeSVarInt(buf, Long.MIN_VALUE);
            assertEquals(Long.MIN_VALUE, ReelIOUtil.readSVarInt(buf));

            buf.clear();
            ReelIOUtil.writeSVarInt(buf, Long.MAX_VALUE);
            assertEquals(Long.MAX_VALUE, ReelIOUtil.readSVarInt(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void svarIntRandomValues() {
        Random rng = new Random(67890);
        ByteBuf buf = Unpooled.buffer();
        try {
            for (int i = 0; i < 1000; i++) {
                long value = rng.nextLong();
                buf.clear();
                ReelIOUtil.writeSVarInt(buf, value);
                assertEquals(value, ReelIOUtil.readSVarInt(buf));
            }
        } finally {
            buf.release();
        }
    }

    @Test
    void svarIntSmallNegativeValues() {
        ByteBuf buf = Unpooled.buffer();
        try {
            for (long v = -100; v < 0; v++) {
                buf.clear();
                ReelIOUtil.writeSVarInt(buf, v);
                assertEquals(v, ReelIOUtil.readSVarInt(buf));
            }
        } finally {
            buf.release();
        }
    }

    @Test
    void svarIntSmallPositiveValuesCompact() {
        ByteBuf buf = Unpooled.buffer();
        try {
            for (long v = -64; v <= 63; v++) {
                buf.clear();
                ReelIOUtil.writeSVarInt(buf, v);
                assertEquals(1, buf.readableBytes(), "value " + v + " should encode in 1 byte");
                assertEquals(v, ReelIOUtil.readSVarInt(buf));
            }
        } finally {
            buf.release();
        }
    }

    @Test
    void interleavedVarIntAndSVarInt() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelIOUtil.writeVarInt(buf, 42);
            ReelIOUtil.writeSVarInt(buf, -100);
            ReelIOUtil.writeVarInt(buf, 99999);
            ReelIOUtil.writeSVarInt(buf, Long.MIN_VALUE);
            ReelIOUtil.writeVarInt(buf, 0);

            assertEquals(42, ReelIOUtil.readVarInt(buf));
            assertEquals(-100, ReelIOUtil.readSVarInt(buf));
            assertEquals(99999, ReelIOUtil.readVarInt(buf));
            assertEquals(Long.MIN_VALUE, ReelIOUtil.readSVarInt(buf));
            assertEquals(0, ReelIOUtil.readVarInt(buf));
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void lz4RoundTrip() throws IOException {
        byte[] original = "hello world hello world hello world".getBytes();
        byte[] compressed = ReelIOUtil.compressLz4(original);
        byte[] decompressed = ReelIOUtil.decompressLz4(compressed, original.length);
        assertArrayEquals(original, decompressed);
    }

    @Test
    void lz4EmptyData() throws IOException {
        byte[] original = new byte[0];
        byte[] compressed = ReelIOUtil.compressLz4(original);
        byte[] decompressed = ReelIOUtil.decompressLz4(compressed, 0);
        assertArrayEquals(original, decompressed);
    }

    @Test
    void lz4LargeData() throws IOException {
        byte[] original = new byte[256 * 1024];
        new Random(42).nextBytes(original);
        byte[] compressed = ReelIOUtil.compressLz4(original);
        byte[] decompressed = ReelIOUtil.decompressLz4(compressed, original.length);
        assertArrayEquals(original, decompressed);
    }

    @Test
    void lz4WrongSizeThrows() {
        byte[] original = "test data".getBytes();
        assertThrows(IOException.class, () -> {
            byte[] compressed = ReelIOUtil.compressLz4(original);
            ReelIOUtil.decompressLz4(compressed, original.length + 100);
        });
    }

    @Test
    void lz4CompressibleDataActuallyCompresses() throws IOException {
        byte[] original = new byte[10_000];
        byte[] compressed = ReelIOUtil.compressLz4(original);
        assertTrue(compressed.length < original.length,
                "LZ4 compressed size (" + compressed.length + ") should be smaller than original (" + original.length + ")");
        assertArrayEquals(original, ReelIOUtil.decompressLz4(compressed, original.length));
    }

    @Test
    void lz4SingleByteData() throws IOException {
        byte[] original = {42};
        byte[] compressed = ReelIOUtil.compressLz4(original);
        byte[] decompressed = ReelIOUtil.decompressLz4(compressed, 1);
        assertArrayEquals(original, decompressed);
    }

    @Test
    void lz4CorruptedDataThrows() {
        byte[] garbage = {0x01, 0x02, 0x03, 0x04, 0x05};
        assertThrows(IOException.class, () -> ReelIOUtil.decompressLz4(garbage, 100));
    }

    @Test
    void lz4RepeatableCompression() throws IOException {
        byte[] data = "deterministic input data for testing".getBytes();
        byte[] compressed1 = ReelIOUtil.compressLz4(data);
        byte[] compressed2 = ReelIOUtil.compressLz4(data);
        assertArrayEquals(compressed1, compressed2);
    }

    @Test
    void lz4HighlyCompressibleData() throws IOException {
        byte[] data = new byte[100_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 4);
        byte[] compressed = ReelIOUtil.compressLz4(data);
        assertTrue(compressed.length < data.length / 10,
                "Highly repetitive data should compress to <10% of original");
        assertArrayEquals(data, ReelIOUtil.decompressLz4(compressed, data.length));
    }

    @Test
    void lz4IncompressibleData() throws IOException {
        byte[] data = new byte[10_000];
        new Random(111).nextBytes(data);
        byte[] compressed = ReelIOUtil.compressLz4(data);
        assertArrayEquals(data, ReelIOUtil.decompressLz4(compressed, data.length));
    }

    @Test
    void zstdRoundTrip() {
        byte[] original = "zstd compression test data repeated repeated repeated".getBytes();
        byte[] compressed = ReelIOUtil.compressZstd(original);
        byte[] decompressed = ReelIOUtil.decompressZstd(compressed, original.length);
        assertArrayEquals(original, decompressed);
    }

    @Test
    void zstdLargeData() {
        byte[] original = new byte[128 * 1024];
        new Random(99).nextBytes(original);
        byte[] compressed = ReelIOUtil.compressZstd(original);
        byte[] decompressed = ReelIOUtil.decompressZstd(compressed, original.length);
        assertArrayEquals(original, decompressed);
    }

    @Test
    void zstdCompressibleDataActuallyCompresses() {
        byte[] original = new byte[10_000];
        byte[] compressed = ReelIOUtil.compressZstd(original);
        assertTrue(compressed.length < original.length,
                "ZSTD compressed size (" + compressed.length + ") should be smaller than original (" + original.length + ")");
        assertArrayEquals(original, ReelIOUtil.decompressZstd(compressed, original.length));
    }

    @Test
    void zstdSingleByteData() {
        byte[] original = {7};
        byte[] compressed = ReelIOUtil.compressZstd(original);
        byte[] decompressed = ReelIOUtil.decompressZstd(compressed, 1);
        assertArrayEquals(original, decompressed);
    }

    @Test
    void zstdCorruptedDataThrows() {
        byte[] garbage = {0x01, 0x02, 0x03, 0x04, 0x05};
        assertThrows(RuntimeException.class, () -> ReelIOUtil.decompressZstd(garbage, 100));
    }

    @Test
    void zstdRepeatableCompression() {
        byte[] data = "deterministic zstd input data".getBytes();
        byte[] compressed1 = ReelIOUtil.compressZstd(data);
        byte[] compressed2 = ReelIOUtil.compressZstd(data);
        assertArrayEquals(compressed1, compressed2);
    }

    @Test
    void zstdEmptyData() {
        byte[] data = new byte[0];
        byte[] compressed = ReelIOUtil.compressZstd(data);
        byte[] decompressed = ReelIOUtil.decompressZstd(compressed, 0);
        assertArrayEquals(data, decompressed);
    }

    @Test
    void zstdHighlyCompressibleData() {
        byte[] data = new byte[100_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 3);
        byte[] compressed = ReelIOUtil.compressZstd(data);
        assertTrue(compressed.length < data.length / 10,
                "Highly repetitive data should compress to <10% of original");
        assertArrayEquals(data, ReelIOUtil.decompressZstd(compressed, data.length));
    }

    @Test
    void zstdBetterThanLz4ForTypicalData() throws IOException {
        byte[] data = new byte[50_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 7);
        byte[] lz4 = ReelIOUtil.compressLz4(data);
        byte[] zstd = ReelIOUtil.compressZstd(data);
        assertTrue(zstd.length <= lz4.length,
                "ZSTD (" + zstd.length + ") should be at most equal to LZ4 (" + lz4.length + ") in size");
    }

}
