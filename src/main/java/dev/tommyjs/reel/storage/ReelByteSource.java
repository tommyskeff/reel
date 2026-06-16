package dev.tommyjs.reel.storage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;

public interface ReelByteSource extends Closeable {

    long length();

    @NotNull ByteBuf read(@NotNull ByteBufAllocator allocator, long offset, int length) throws IOException;

}
