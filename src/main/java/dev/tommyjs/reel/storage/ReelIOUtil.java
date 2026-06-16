package dev.tommyjs.reel.storage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ReelIOUtil {

    public static final ByteBufAllocator DEFAULT_ALLOCATOR = new PooledByteBufAllocator(false);

    private static final int MAX_VARINT_BYTES = 10;

    private ReelIOUtil() {
    }

    public static void compressLz4(@NotNull ByteBuf src, @NotNull ByteBuf dst) throws IOException {
        int readable = src.readableBytes();
        try (ByteBufOutputStream stream = new ByteBufOutputStream(dst);
             LZ4FrameOutputStream lzStream = new LZ4FrameOutputStream(stream)) {
            if (readable > 0) {
                src.readBytes(lzStream, readable);
            }
        }
    }

    public static void decompressLz4(@NotNull ByteBuf src, int compressedLength, @NotNull ByteBuf dst,
                                     int uncompressedLength) throws IOException {
        ByteBuf slice = src.slice(src.readerIndex(), compressedLength);
        dst.ensureWritable(uncompressedLength);
        try (ByteBufInputStream stream = new ByteBufInputStream(slice);
             LZ4FrameInputStream lzStream = new LZ4FrameInputStream(stream)) {
            int remaining = uncompressedLength;
            while (remaining > 0) {
                int read = dst.writeBytes(lzStream, remaining);
                if (read == -1) {
                    break;
                }
                remaining -= read;
            }

            if (remaining != 0) {
                throw new IOException(
                    "lz4 decompression size mismatch: actual=" + (uncompressedLength - remaining) + ", expected=" +
                        uncompressedLength);
            }

            if (lzStream.read() != -1) {
                throw new IOException("lz4 decompression exceeded expected size: expected=" + uncompressedLength);
            }
        }

        src.skipBytes(compressedLength);
    }

    public static void writeVarInt(@NotNull ByteBuf buf, long value) {
        while ((value & ~0x7FL) != 0) {
            buf.writeByte((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }

        buf.writeByte((int) (value & 0x7F));
    }

    public static long readVarInt(@NotNull ByteBuf buf) throws IOException {
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

        throw new IOException("varint too long: max_bytes=" + MAX_VARINT_BYTES);
    }

    public static int readVarIntAsInt(@NotNull ByteBuf buf, @NotNull String context) throws IOException {
        long v = readVarInt(buf);
        if (v < 0 || v > Integer.MAX_VALUE) {
            throw new IOException(context + " out of range: value=" + v);
        }
        return (int) v;
    }

    public static void writeString(@NotNull ByteBuf buf, String value) {
        int byteLength = ByteBufUtil.utf8Bytes(value);
        writeVarInt(buf, byteLength);
        ByteBufUtil.writeUtf8(buf, value);
    }

    public static String readString(@NotNull ByteBuf buf) throws IOException {
        long rawLen = readVarInt(buf);
        if (rawLen < 0 || rawLen > Integer.MAX_VALUE) {
            throw new IOException("string length out of range: value=" + rawLen);
        }
        int len = (int) rawLen;
        String result = buf.toString(buf.readerIndex(), len, StandardCharsets.UTF_8);
        buf.skipBytes(len);
        return result;
    }

    public static void writeBytes(@NotNull ByteBuf buf, byte[] data) {
        writeVarInt(buf, data.length);
        buf.writeBytes(data);
    }

    public static void writeBytes(@NotNull ByteBuf buf, @NotNull ByteBuf data) {
        int length = data.readableBytes();
        writeVarInt(buf, length);
        buf.writeBytes(data, data.readerIndex(), length);
    }

    public static byte[] readBytes(@NotNull ByteBuf buf) throws IOException {
        long rawLen = readVarInt(buf);
        if (rawLen < 0 || rawLen > Integer.MAX_VALUE) {
            throw new IOException("bytes length out of range: value=" + rawLen);
        }
        int len = (int) rawLen;
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return bytes;
    }

}
