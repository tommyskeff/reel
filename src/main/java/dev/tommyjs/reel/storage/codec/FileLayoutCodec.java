package dev.tommyjs.reel.storage.codec;

import dev.tommyjs.reel.storage.ReelFormat;
import dev.tommyjs.reel.storage.model.ReelFileLayout;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Arrays;

public final class FileLayoutCodec {

    private static final byte[] RESERVED_BYTES = new byte[32];

    private FileLayoutCodec() {
    }

    public static void encodeHeader(@NotNull ByteBuf out) {
        encodeHeader(out, (byte) 0);
    }

    public static void encodeHeader(@NotNull ByteBuf out, byte compressionId) {
        out.writeBytes(ReelFormat.headerMagic());
        out.writeShort(ReelFormat.FORMAT_VERSION);
        out.writeByte(compressionId);
        out.writeBytes(RESERVED_BYTES, 0, RESERVED_BYTES.length - 1);
    }

    public static void decodeHeader(@NotNull ByteBuf in) throws IOException {
        decodeHeaderCompression(in);
    }

    public static byte decodeHeaderCompression(@NotNull ByteBuf in) throws IOException {
        byte[] magic = new byte[ReelFormat.HEADER_MAGIC_LENGTH];
        in.readBytes(magic);
        if (!Arrays.equals(magic, ReelFormat.headerMagic())) {
            throw new IOException("header magic mismatch");
        }

        int version = in.readUnsignedShort();
        if (version != ReelFormat.FORMAT_VERSION) {
            throw new IOException(
                "unsupported format_version: got=" + version + ", expected=" + ReelFormat.FORMAT_VERSION);
        }

        byte compressionId = in.readByte();
        in.readBytes(new byte[RESERVED_BYTES.length - 1]);
        return compressionId;
    }

    public static void encodeFooter(@NotNull ByteBuf out, @NotNull ReelFileLayout layout) {
        out.writeLong(layout.totalFrames());
        out.writeLong(layout.segmentDataOffset());
        out.writeLong(layout.typeDirOffset());
        out.writeLong(layout.trackDirOffset());
        out.writeLong(layout.segmentDirOffset());
        out.writeBytes(RESERVED_BYTES);
        out.writeBytes(ReelFormat.footerMagic());
    }

    public static @NotNull ReelFileLayout decodeFooter(@NotNull ByteBuf in) throws IOException {
        long totalFrames = readOffset(in, "total_frames");
        long segmentDataOffset = readOffset(in, "segment_data_offset");
        long typeDirOffset = readOffset(in, "entity_type_dir_offset");
        long trackDirOffset = readOffset(in, "track_dir_offset");
        long segmentDirOffset = readOffset(in, "segment_dir_offset");

        in.readBytes(new byte[RESERVED_BYTES.length]);

        byte[] magic = new byte[ReelFormat.FOOTER_MAGIC_LENGTH];
        in.readBytes(magic);
        if (!Arrays.equals(magic, ReelFormat.footerMagic())) {
            throw new IOException("footer magic mismatch");
        }

        return new ReelFileLayout(totalFrames, segmentDataOffset, typeDirOffset, trackDirOffset, segmentDirOffset);
    }

    private static long readOffset(ByteBuf in, String name) throws IOException {
        long value = in.readLong();
        if (value < 0) {
            throw new IOException(name + " negative: value=" + value);
        }

        return value;
    }

}
