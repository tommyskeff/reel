package dev.tommyjs.reel.storage.codec;

import dev.tommyjs.reel.storage.model.RawPayload;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public final class WindowPayloadCodec {

    private static final int DELTA = 0;
    private static final int STATE = 1;
    private static final int CLOSE = 2;
    private static final int EVENT = 3;

    private WindowPayloadCodec() {
    }

    public static void encodeDelta(@NotNull ByteBuf out, @NotNull ByteBuf deltaBody) {
        out.writeByte(DELTA);
        out.writeBytes(deltaBody, deltaBody.readerIndex(), deltaBody.readableBytes());
    }

    public static void encodeState(@NotNull ByteBuf out, boolean hasPrevious, @NotNull ByteBuf body) {
        out.writeByte(STATE);
        out.writeByte(hasPrevious ? 1 : 0);
        out.writeBytes(body, body.readerIndex(), body.readableBytes());
    }

    public static void encodeClose(@NotNull ByteBuf out, @NotNull ByteBuf previousBody) {
        out.writeByte(CLOSE);
        out.writeBytes(previousBody, previousBody.readerIndex(), previousBody.readableBytes());
    }

    public static void encodeEvent(@NotNull ByteBuf out, @NotNull ByteBuf eventBody) {
        out.writeByte(EVENT);
        out.writeBytes(eventBody, eventBody.readerIndex(), eventBody.readableBytes());
    }

    public static @NotNull RawPayload decode(@NotNull ByteBuf payload) throws IOException {
        int type = payload.readByte();
        return switch (type) {
            case DELTA -> new RawPayload.Delta(payload);
            case STATE -> new RawPayload.State(payload.readByte() != 0, payload);
            case CLOSE -> new RawPayload.Close(payload);
            case EVENT -> new RawPayload.Event(payload);
            default -> throw new IOException("unknown window entry type: " + type);
        };
    }

}
