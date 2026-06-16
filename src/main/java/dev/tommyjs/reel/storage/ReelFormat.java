package dev.tommyjs.reel.storage;

public final class ReelFormat {

    private static final byte[] HEADER_MAGIC = {0x52, 0x45, 0x45, 0x4C};
    private static final byte[] FOOTER_MAGIC = {0x52, 0x45, 0x45, 0x4C, 0x45, 0x4E, 0x44, 0x00};

    public static final short FORMAT_VERSION = 1;

    public static final int HEADER_SIZE = 38;
    public static final int FOOTER_SIZE = 80;

    public static final int HEADER_MAGIC_LENGTH = HEADER_MAGIC.length;
    public static final int FOOTER_MAGIC_LENGTH = FOOTER_MAGIC.length;

    private ReelFormat() {
    }

    public static byte[] headerMagic() {
        return HEADER_MAGIC.clone();
    }

    public static byte[] footerMagic() {
        return FOOTER_MAGIC.clone();
    }

}
