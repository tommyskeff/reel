package dev.tommyjs.reel.storage;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;

public interface ReelCompressor {

    byte LZ4_ID = 0;
    byte ZSTD_ID = 1;

    @NotNull ReelCompressor LZ4 = new Lz4();

    byte id();

    void compress(@NotNull ByteBuf src, @NotNull ByteBuf dst) throws IOException;

    void decompress(@NotNull ByteBuf src, int compressedLength, @NotNull ByteBuf dst) throws IOException;

    static @NotNull ReelCompressor zstd(int level) {
        return new Zstd(level);
    }

    static @NotNull ReelCompressor forId(byte id) {
        return switch (id) {
            case LZ4_ID -> LZ4;
            case ZSTD_ID -> new Zstd(3);
            default -> throw new IllegalArgumentException("unknown compression id: " + id);
        };
    }

    private static void copy(InputStream in, ByteBuf dst) throws IOException {
        byte[] buf = new byte[65536];
        int read;
        while ((read = in.read(buf)) != -1) {
            dst.writeBytes(buf, 0, read);
        }
    }

    final class Lz4 implements ReelCompressor {

        @Override
        public byte id() {
            return LZ4_ID;
        }

        @Override
        public void compress(@NotNull ByteBuf src, @NotNull ByteBuf dst) throws IOException {
            int readable = src.readableBytes();
            try (ByteBufOutputStream out = new ByteBufOutputStream(dst);
                 LZ4FrameOutputStream z = new LZ4FrameOutputStream(out)) {
                if (readable > 0) {
                    src.readBytes(z, readable);
                }
            }
        }

        @Override
        public void decompress(@NotNull ByteBuf src, int compressedLength, @NotNull ByteBuf dst) throws IOException {
            ByteBuf slice = src.slice(src.readerIndex(), compressedLength);
            try (InputStream in = new ByteBufInputStream(slice); LZ4FrameInputStream z = new LZ4FrameInputStream(in)) {
                copy(z, dst);
            }
            src.skipBytes(compressedLength);
        }
    }

    final class Zstd implements ReelCompressor {

        private final int level;

        Zstd(int level) {
            this.level = level;
        }

        public int level() {
            return level;
        }

        @Override
        public byte id() {
            return ZSTD_ID;
        }

        @Override
        public void compress(@NotNull ByteBuf src, @NotNull ByteBuf dst) throws IOException {
            int readable = src.readableBytes();
            try (ByteBufOutputStream out = new ByteBufOutputStream(dst);
                 ZstdOutputStream z = new ZstdOutputStream(out, level)) {
                if (readable > 0) {
                    src.readBytes(z, readable);
                }
            }
        }

        @Override
        public void decompress(@NotNull ByteBuf src, int compressedLength, @NotNull ByteBuf dst) throws IOException {
            ByteBuf slice = src.slice(src.readerIndex(), compressedLength);
            try (InputStream in = new ByteBufInputStream(slice); ZstdInputStream z = new ZstdInputStream(in)) {
                copy(z, dst);
            }
            src.skipBytes(compressedLength);
        }
    }

}
