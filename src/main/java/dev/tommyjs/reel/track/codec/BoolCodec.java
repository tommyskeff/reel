package dev.tommyjs.reel.track.codec;

import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

public final class BoolCodec implements Codec<Boolean> {

    public static final BoolCodec INSTANCE = new BoolCodec();

    private BoolCodec() {
    }

    @Override
    public void encode(@NotNull ByteBuf out, @NotNull Boolean value) {
        out.writeBoolean(value);
    }

    @Override
    public @NotNull Boolean decode(@NotNull ByteBuf in) {
        return in.readBoolean();
    }

}
