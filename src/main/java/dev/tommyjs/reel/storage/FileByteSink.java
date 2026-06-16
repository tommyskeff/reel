package dev.tommyjs.reel.storage;

import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

public final class FileByteSink implements ReelByteSink {

    private final RandomAccessFile file;
    private final FileChannel channel;

    public FileByteSink(@NotNull File file) throws IOException {
        this.file = new RandomAccessFile(file, "rw");
        try {
            this.file.setLength(0);
        } catch (IOException e) {
            try {
                this.file.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
        this.channel = this.file.getChannel();
    }

    public FileByteSink(@NotNull Path path) throws IOException {
        this(path.toFile());
    }

    @Override
    public long position() {
        try {
            return channel.position();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    public void write(@NotNull ByteBuf buf) throws IOException {
        int length = buf.readableBytes();
        if (length == 0) {
            return;
        }

        ByteBuffer[] bufs = buf.nioBuffers();
        long written = 0;
        while (written < (long) length) {
            long size = channel.write(bufs);
            if (size <= 0) {
                throw new IOException("failed to write to file");
            }
            written += size;
        }

        buf.readerIndex(buf.writerIndex());
    }

    @Override
    public void flush() throws IOException {
        channel.force(true);
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

}
