package dev.tommyjs.reel.storage;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;

import java.io.*;

final class ReelIOUtil {

    private static final int MAX_VARINT_BYTES = 10;

    private ReelIOUtil() {
    }

    static byte[] compressLz4(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream out = new LZ4FrameOutputStream(baos)) {
            out.write(data);
        }

        return baos.toByteArray();
    }

    static byte[] decompressLz4(byte[] compressed, int uncompressedSize) throws IOException {
        try (LZ4FrameInputStream in = new LZ4FrameInputStream(new ByteArrayInputStream(compressed))) {
            byte[] result = new byte[uncompressedSize];
            int offset = 0;

            while (offset < uncompressedSize) {
                int read = in.read(result, offset, uncompressedSize - offset);
                if (read == -1) break;
                offset += read;
            }

            if (offset != uncompressedSize) {
                throw new IOException("LZ4 decompression yielded " + offset + " bytes, expected " + uncompressedSize);
            }

            return result;
        }
    }

    static byte[] compressZstd(byte[] data) {
        return Zstd.compress(data);
    }

    static byte[] decompressZstd(byte[] compressed, int uncompressedSize) {
        return Zstd.decompress(compressed, uncompressedSize);
    }

    static void writeVarInt(ByteBuf buf, long value) {
        while ((value & ~0x7FL) != 0) {
            buf.writeByte((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }

        buf.writeByte((int) (value & 0x7F));
    }

    static long readVarInt(ByteBuf buf) {
        long result = 0;
        int shift = 0;
        for (int i = 0; i < MAX_VARINT_BYTES; i++) {
            byte b = buf.readByte();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }

        throw new IllegalStateException("varint exceeds " + MAX_VARINT_BYTES + " bytes");
    }

    static void writeSVarInt(ByteBuf buf, long value) {
        writeVarInt(buf, (value << 1) ^ (value >> 63));
    }

    static long readSVarInt(ByteBuf buf) {
        long raw = readVarInt(buf);
        return (raw >>> 1) ^ -(raw & 1);
    }

}
