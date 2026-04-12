package dev.tommyjs.reel.storage;

final class ReelFormat {

    private ReelFormat() {
    }

    public static final short FORMAT_VERSION = 1;

    private static final byte[] HEADER_MAGIC = {0x52, 0x45, 0x45, 0x4C};
    private static final byte[] FOOTER_MAGIC = {0x52, 0x45, 0x45, 0x4C, 0x45, 0x4E, 0x44, 0x00};

    public static final int HEADER_MAGIC_LENGTH = HEADER_MAGIC.length;
    public static final int FOOTER_MAGIC_LENGTH = FOOTER_MAGIC.length;

    public static final int HEADER_SIZE = 8;
    public static final int FOOTER_SIZE = 8 + 8 + 4 + 8 + 8 + 8 + 8;

    public static final int BLOCK_FLUSH_THRESHOLD = 64 * 1024;
    public static final int ZSTD_COMPRESSION_THRESHOLD = 1024;
    public static final int LZ4_TRACK_INDEX_THRESHOLD = 128;

    public static final byte COMPRESSION_NONE = 0;
    public static final byte COMPRESSION_ZSTD = 1;
    public static final byte COMPRESSION_LZ4 = 2;

    private static final int REF_OFFSET_BITS = 20;
    private static final int REF_SIZE_BITS = 20;
    private static final int REF_BLOCK_INDEX_BITS = 64 - REF_OFFSET_BITS - REF_SIZE_BITS;
    private static final long REF_OFFSET_MASK = (1L << REF_OFFSET_BITS) - 1;
    private static final long REF_SIZE_MASK = (1L << REF_SIZE_BITS) - 1;
    private static final int REF_MAX_BLOCK_INDEX = (1 << REF_BLOCK_INDEX_BITS) - 1;
    private static final int REF_MAX_OFFSET = (1 << REF_OFFSET_BITS) - 1;
    private static final int REF_MAX_SIZE = (1 << REF_SIZE_BITS) - 1;

    static byte[] headerMagic() {
        return HEADER_MAGIC.clone();
    }

    static byte[] footerMagic() {
        return FOOTER_MAGIC.clone();
    }

    static long packRefId(int blockIndex, int offsetInBlock, int size) {
        if (blockIndex < 0 || blockIndex > REF_MAX_BLOCK_INDEX) {
            throw new IllegalArgumentException("blockIndex " + blockIndex + " exceeds " + REF_BLOCK_INDEX_BITS + "-bit limit");
        }

        if (offsetInBlock < 0 || offsetInBlock > REF_MAX_OFFSET) {
            throw new IllegalArgumentException("offsetInBlock " + offsetInBlock + " exceeds " + REF_OFFSET_BITS + "-bit limit");
        }

        if (size < 0 || size > REF_MAX_SIZE) {
            throw new IllegalArgumentException("size " + size + " exceeds " + REF_SIZE_BITS + "-bit limit");
        }

        return ((long) blockIndex << (REF_OFFSET_BITS + REF_SIZE_BITS))
                | ((long) offsetInBlock << REF_SIZE_BITS)
                | (long) size;
    }

    static int unpackBlockIndex(long refId) {
        return (int) (refId >>> (REF_OFFSET_BITS + REF_SIZE_BITS));
    }

    static int unpackOffset(long refId) {
        return (int) ((refId >>> REF_SIZE_BITS) & REF_OFFSET_MASK);
    }

    static int unpackSize(long refId) {
        return (int) (refId & REF_SIZE_MASK);
    }

}
