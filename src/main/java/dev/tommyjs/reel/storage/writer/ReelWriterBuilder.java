package dev.tommyjs.reel.storage.writer;

import dev.tommyjs.reel.storage.ReelCompressor;
import dev.tommyjs.reel.storage.FileByteSink;
import dev.tommyjs.reel.storage.ReelByteSink;
import dev.tommyjs.reel.storage.ReelIOUtil;
import io.netty.buffer.ByteBufAllocator;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

public final class ReelWriterBuilder {

    private ReelByteSink sink = null;
    private ByteBufAllocator allocator = ReelIOUtil.DEFAULT_ALLOCATOR;
    private LayerStrategy layerStrategy = LayerStrategy.DEFAULT;
    private ReelCompressor compression = ReelCompressor.LZ4;

    public ReelWriterBuilder() {
    }

    public ReelWriterBuilder(@NotNull File file) {
        setFile(file);
    }

    public ReelWriterBuilder(@NotNull Path path) {
        setFile(path);
    }

    public @NotNull ReelWriterBuilder setFile(@NotNull File file) {
        try {
            return setOutput(new FileByteSink(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public @NotNull ReelWriterBuilder setFile(@NotNull Path path) {
        return setFile(path.toFile());
    }

    public @NotNull ReelWriterBuilder setOutput(@NotNull ReelByteSink sink) {
        this.sink = sink;
        return this;
    }

    public @NotNull ReelWriterBuilder setAllocator(@NotNull ByteBufAllocator allocator) {
        this.allocator = allocator;
        return this;
    }

    public @NotNull ReelWriterBuilder setLayerStrategy(@NotNull LayerStrategy layerStrategy) {
        this.layerStrategy = layerStrategy;
        return this;
    }

    public @NotNull ReelWriterBuilder setCompression(@NotNull ReelCompressor compression) {
        this.compression = compression;
        return this;
    }

    public @NotNull ReelWriter build() throws IOException {
        if (sink == null) {
            throw new IllegalStateException("byte sink must be set");
        }

        return new ReelFileWriter(sink, allocator, layerStrategy, compression);
    }

}
