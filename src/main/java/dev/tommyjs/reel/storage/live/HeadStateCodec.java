package dev.tommyjs.reel.storage.live;

import dev.tommyjs.reel.storage.ReelIOUtil;
import dev.tommyjs.reel.storage.codec.DirectoryCodec;
import dev.tommyjs.reel.storage.model.EntityTypeDirectory;
import dev.tommyjs.reel.storage.model.SegmentDirectory;
import dev.tommyjs.reel.storage.model.TrackDirectory;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public final class HeadStateCodec {

    private HeadStateCodec() {
    }

    public static void encode(@NotNull ByteBuf out, @NotNull ReelHeadState state) {
        DirectoryCodec.encodeEntityTypes(out, state.types());
        DirectoryCodec.encodeTrackTypes(out, state.tracks());
        DirectoryCodec.encodeSegments(out, state.segments());
        ReelIOUtil.writeVarInt(out, state.headFrame());
        out.writeByte(state.complete() ? 1 : 0);
    }

    public static @NotNull ReelHeadState decode(@NotNull ByteBuf in) throws IOException {
        EntityTypeDirectory types = DirectoryCodec.decodeEntityTypes(in);
        TrackDirectory tracks = DirectoryCodec.decodeTrackTypes(in);
        SegmentDirectory segments = DirectoryCodec.decodeSegments(in);
        long headFrame = ReelIOUtil.readVarInt(in);
        return new ReelHeadState(types, tracks, segments, headFrame, in.readByte() != 0);
    }

}
