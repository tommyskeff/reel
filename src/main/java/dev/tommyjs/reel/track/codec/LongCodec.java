package dev.tommyjs.reel.track.codec;

import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

public final class LongCodec implements Codec<Long> {

    public static final LongCodec INSTANCE = new LongCodec();

    private LongCodec() {
    }

    @Override
    public void encode(@NotNull ByteBuf out, @NotNull Long value) {
        out.writeLong(value);
    }

    @Override
    public @NotNull Long decode(@NotNull ByteBuf in) {
        return in.readLong();
    }

}
