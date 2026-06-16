package dev.tommyjs.reel.track.codec;

import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

public final class IntCodec implements Codec<Integer> {

    public static final IntCodec INSTANCE = new IntCodec();

    private IntCodec() {
    }

    @Override
    public void encode(@NotNull ByteBuf out, @NotNull Integer value) {
        out.writeInt(value);
    }

    @Override
    public @NotNull Integer decode(@NotNull ByteBuf in) {
        return in.readInt();
    }

}
