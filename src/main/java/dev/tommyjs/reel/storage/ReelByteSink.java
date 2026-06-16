package dev.tommyjs.reel.storage;

import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;

public interface ReelByteSink extends Closeable {

    long position();

    void write(@NotNull ByteBuf buf) throws IOException;

    void flush() throws IOException;

}
