package dev.tommyjs.reel.track.codec;

import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

public final class VoidCodec implements Codec<Void> {

    public static final VoidCodec INSTANCE = new VoidCodec();

    private VoidCodec() {
    }

    @Override
    public void encode(@NotNull ByteBuf out, @NotNull Void value) {
        throw new UnsupportedOperationException("void codec cannot encode");
    }

    @Override
    public @NotNull Void decode(@NotNull ByteBuf in) {
        throw new UnsupportedOperationException("void codec cannot decode");
    }

}
