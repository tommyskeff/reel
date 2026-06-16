package dev.tommyjs.reel.storage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

public final class FileByteSource implements ReelByteSource {

    private final RandomAccessFile file;
    private final FileChannel channel;
    private final long fileLength;

    public FileByteSource(@NotNull File file) throws IOException {
        this.file = new RandomAccessFile(file, "r");
        this.channel = this.file.getChannel();
        this.fileLength = this.file.length();
    }

    public FileByteSource(@NotNull Path path) throws IOException {
        this(path.toFile());
    }

    @Override
    public @NotNull ByteBuf read(@NotNull ByteBufAllocator allocator, long offset, int length) throws IOException {
        if (length == 0) {
            return allocator.heapBuffer(0);
        }

        if (length < 0) {
            throw new IOException("negative read length: length=" + length + ", offset=" + offset);
        }

        if (offset < 0 || offset + length > fileLength) {
            throw new IOException(
                "read range out of bounds: offset=" + offset + ", length=" + length + ", file_length=" + fileLength);
        }

        ByteBuf buf = allocator.heapBuffer(length, length);
        try {
            int read = buf.writeBytes(channel, offset, length);
            if (read < length) {
                throw new IOException("unexpected EOF: offset=" + (offset + read));
            }

            return buf;
        } catch (IOException | RuntimeException e) {
            buf.release();
            throw e;
        }
    }

    @Override
    public long length() {
        return fileLength;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

}
