package dev.tommyjs.reel.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReelFormatTest {

    @Test
    void packUnpackRoundTrip() {
        int blockIndex = 5;
        int offset = 1000;
        int size = 2048;
        long refId = ReelFormat.packRefId(blockIndex, offset, size);
        assertEquals(blockIndex, ReelFormat.unpackBlockIndex(refId));
        assertEquals(offset, ReelFormat.unpackOffset(refId));
        assertEquals(size, ReelFormat.unpackSize(refId));
    }

    @Test
    void packUnpackZeroValues() {
        long refId = ReelFormat.packRefId(0, 0, 0);
        assertEquals(0, ReelFormat.unpackBlockIndex(refId));
        assertEquals(0, ReelFormat.unpackOffset(refId));
        assertEquals(0, ReelFormat.unpackSize(refId));
    }

    @Test
    void packUnpackMaxOffsetAndSize() {
        int maxVal = (1 << 20) - 1;
        long refId = ReelFormat.packRefId(0, maxVal, maxVal);
        assertEquals(0, ReelFormat.unpackBlockIndex(refId));
        assertEquals(maxVal, ReelFormat.unpackOffset(refId));
        assertEquals(maxVal, ReelFormat.unpackSize(refId));
    }

    @Test
    void packUnpackLargeBlockIndex() {
        int blockIndex = 1_000_000;
        long refId = ReelFormat.packRefId(blockIndex, 100, 200);
        assertEquals(blockIndex, ReelFormat.unpackBlockIndex(refId));
        assertEquals(100, ReelFormat.unpackOffset(refId));
        assertEquals(200, ReelFormat.unpackSize(refId));
    }

    @Test
    void packRejectsOffsetOverflow() {
        int tooLarge = (1 << 20);
        assertThrows(IllegalArgumentException.class, () -> ReelFormat.packRefId(0, tooLarge, 0));
    }

    @Test
    void packRejectsSizeOverflow() {
        int tooLarge = (1 << 20);
        assertThrows(IllegalArgumentException.class, () -> ReelFormat.packRefId(0, 0, tooLarge));
    }

    @Test
    void packRejectsNegativeOffset() {
        assertThrows(IllegalArgumentException.class, () -> ReelFormat.packRefId(0, -1, 0));
    }

    @Test
    void packRejectsNegativeSize() {
        assertThrows(IllegalArgumentException.class, () -> ReelFormat.packRefId(0, 0, -1));
    }

    @Test
    void packRejectsNegativeBlockIndex() {
        assertThrows(IllegalArgumentException.class, () -> ReelFormat.packRefId(-1, 0, 0));
    }

    @Test
    void packRejectsBlockIndexOverflow() {
        int maxBlockIndex = (1 << 24) - 1;
        assertDoesNotThrow(() -> ReelFormat.packRefId(maxBlockIndex, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ReelFormat.packRefId(maxBlockIndex + 1, 0, 0));
    }

    @Test
    void compressionConstantsAreDistinct() {
        assertNotEquals(ReelFormat.COMPRESSION_NONE, ReelFormat.COMPRESSION_ZSTD);
        assertNotEquals(ReelFormat.COMPRESSION_NONE, ReelFormat.COMPRESSION_LZ4);
        assertNotEquals(ReelFormat.COMPRESSION_ZSTD, ReelFormat.COMPRESSION_LZ4);
    }

    @Test
    void footerSizeMatchesLayout() {
        assertEquals(52, ReelFormat.FOOTER_SIZE);
        assertEquals(8, ReelFormat.FOOTER_MAGIC_LENGTH);
    }

    @Test
    void headerSizeMatchesLayout() {
        assertEquals(8, ReelFormat.HEADER_SIZE);
        assertEquals(4, ReelFormat.HEADER_MAGIC_LENGTH);
    }

    @Test
    void headerMagicReturnsCopy() {
        byte[] a = ReelFormat.headerMagic();
        byte[] b = ReelFormat.headerMagic();
        assertArrayEquals(a, b);
        a[0] = 0;
        assertNotEquals(a[0], ReelFormat.headerMagic()[0]);
    }

    @Test
    void footerMagicReturnsCopy() {
        byte[] a = ReelFormat.footerMagic();
        byte[] b = ReelFormat.footerMagic();
        assertArrayEquals(a, b);
        a[0] = 0;
        assertNotEquals(a[0], ReelFormat.footerMagic()[0]);
    }

    @Test
    void packUnpackManyValues() {
        int[] blockIndices = {0, 1, 100, 10_000, Integer.MAX_VALUE >> 8};
        int[] offsets = {0, 1, 512, (1 << 20) - 1};
        int[] sizes = {0, 1, 1024, (1 << 20) - 1};

        for (int bi : blockIndices) {
            for (int off : offsets) {
                for (int sz : sizes) {
                    long refId = ReelFormat.packRefId(bi, off, sz);
                    assertEquals(bi, ReelFormat.unpackBlockIndex(refId));
                    assertEquals(off, ReelFormat.unpackOffset(refId));
                    assertEquals(sz, ReelFormat.unpackSize(refId));
                }
            }
        }
    }

}
