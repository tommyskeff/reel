package dev.tommyjs.reel.storage.reader;

import dev.tommyjs.reel.storage.FileByteSource;
import dev.tommyjs.reel.storage.ReelByteSource;
import dev.tommyjs.reel.storage.ReelIOUtil;
import io.netty.buffer.ByteBufAllocator;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

public final class ReelReaderBuilder {

    private ReelByteSource source = null;
    private ByteBufAllocator allocator = ReelIOUtil.DEFAULT_ALLOCATOR;

    public ReelReaderBuilder() {
    }

    public ReelReaderBuilder(@NotNull File file) {
        setFile(file);
    }

    public ReelReaderBuilder(@NotNull Path path) {
        setFile(path);
    }

    public @NotNull ReelReaderBuilder setFile(@NotNull File file) {
        try {
            return setInput(new FileByteSource(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public @NotNull ReelReaderBuilder setFile(@NotNull Path path) {
        return setFile(path.toFile());
    }

    public @NotNull ReelReaderBuilder setInput(@NotNull ReelByteSource source) {
        this.source = source;
        return this;
    }

    public @NotNull ReelReaderBuilder setAllocator(@NotNull ByteBufAllocator allocator) {
        this.allocator = allocator;
        return this;
    }

    public @NotNull ReelReader build() throws IOException {
        if (source == null) {
            throw new IllegalStateException("byte source must be set");
        }

        return new ReelFileReader(source, allocator);
    }

}
