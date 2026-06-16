package dev.tommyjs.reel.track.codec;

import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;

public final class StringCodec implements Codec<String> {

    public static final StringCodec INSTANCE = new StringCodec();

    private StringCodec() {
    }

    @Override
    public void encode(@NotNull ByteBuf out, @NotNull String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.writeBytes(bytes);
    }

    @Override
    public @NotNull String decode(@NotNull ByteBuf in) {
        byte[] bytes = new byte[in.readInt()];
        in.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

}
