package dev.tommyjs.reel.track.codec;

import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

public interface Codec<T> {

    void encode(@NotNull ByteBuf out, @NotNull T value);

    @NotNull T decode(@NotNull ByteBuf in);

}
