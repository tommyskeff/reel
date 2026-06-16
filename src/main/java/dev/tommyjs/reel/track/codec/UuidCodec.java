package dev.tommyjs.reel.track.codec;

import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class UuidCodec implements Codec<UUID> {

    public static final UuidCodec INSTANCE = new UuidCodec();

    private UuidCodec() {
    }

    @Override
    public void encode(@NotNull ByteBuf out, @NotNull UUID value) {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    @Override
    public @NotNull UUID decode(@NotNull ByteBuf in) {
        return new UUID(in.readLong(), in.readLong());
    }

}
