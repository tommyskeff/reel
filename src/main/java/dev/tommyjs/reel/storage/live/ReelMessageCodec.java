package dev.tommyjs.reel.storage.live;

import dev.tommyjs.reel.storage.ReelIOUtil;
import dev.tommyjs.reel.track.TrackKind;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public final class ReelMessageCodec {

    private static final int ENTITY_TYPE = 0;
    private static final int TRACK_TYPE = 1;
    private static final int SEGMENT_BEGUN = 2;
    private static final int FRAGMENT = 3;
    private static final int SEGMENT_SEALED = 4;
    private static final int COMPLETE = 5;

    private ReelMessageCodec() {
    }

    public static void encode(@NotNull ByteBuf out, @NotNull ReelMessage message) {
        if (message instanceof ReelMessage.EntityTypeRegistered m) {
            out.writeByte(ENTITY_TYPE);
            ReelIOUtil.writeVarInt(out, m.typeId());
            ReelIOUtil.writeString(out, m.identifier());
        } else if (message instanceof ReelMessage.TrackTypeRegistered m) {
            out.writeByte(TRACK_TYPE);
            ReelIOUtil.writeVarInt(out, m.typeId());
            ReelIOUtil.writeString(out, m.identifier());
            ReelIOUtil.writeVarInt(out, m.codecVersion());
            ReelIOUtil.writeVarInt(out, m.kind().ordinal());
        } else if (message instanceof ReelMessage.SegmentBegun m) {
            out.writeByte(SEGMENT_BEGUN);
            ReelIOUtil.writeVarInt(out, m.startFrame());
            out.writeByte(m.anchor() ? 1 : 0);
        } else if (message instanceof ReelFragment m) {
            out.writeByte(FRAGMENT);
            FragmentCodec.encode(out, m);
        } else if (message instanceof ReelMessage.SegmentSealed m) {
            out.writeByte(SEGMENT_SEALED);
            ReelIOUtil.writeVarInt(out, m.startFrame());
            ReelIOUtil.writeVarInt(out, m.endFrame());
            out.writeByte(m.anchor() ? 1 : 0);
        } else if (message instanceof ReelMessage.Complete m) {
            out.writeByte(COMPLETE);
            ReelIOUtil.writeVarInt(out, m.totalFrames());
        } else {
            throw new IllegalStateException("unhandled ReelMessage type: " + message.getClass());
        }
    }

    public static @NotNull ReelMessage decode(@NotNull ByteBuf in) throws IOException {
        int kind = in.readByte();
        return switch (kind) {
            case ENTITY_TYPE -> {
                int typeId = ReelIOUtil.readVarIntAsInt(in, "entity_type_id");
                yield new ReelMessage.EntityTypeRegistered(ReelIOUtil.readString(in), typeId);
            }
            case TRACK_TYPE -> {
                int typeId = ReelIOUtil.readVarIntAsInt(in, "track_type_id");
                String identifier = ReelIOUtil.readString(in);
                int codecVersion = ReelIOUtil.readVarIntAsInt(in, "codec_version");
                yield new ReelMessage.TrackTypeRegistered(identifier, typeId, codecVersion, readKind(in));
            }
            case SEGMENT_BEGUN -> new ReelMessage.SegmentBegun(ReelIOUtil.readVarInt(in), in.readByte() != 0);
            case FRAGMENT -> FragmentCodec.decode(in);
            case SEGMENT_SEALED -> {
                long startFrame = ReelIOUtil.readVarInt(in);
                long endFrame = ReelIOUtil.readVarInt(in);
                yield new ReelMessage.SegmentSealed(startFrame, endFrame, in.readByte() != 0);
            }
            case COMPLETE -> new ReelMessage.Complete(ReelIOUtil.readVarInt(in));
            default -> throw new IOException("unknown reel message kind: " + kind);
        };
    }

    private static TrackKind readKind(ByteBuf in) throws IOException {
        int ordinal = ReelIOUtil.readVarIntAsInt(in, "track_kind");
        TrackKind[] kinds = TrackKind.values();
        if (ordinal < 0 || ordinal >= kinds.length) {
            throw new IOException("unknown track kind ordinal: " + ordinal);
        }
        return kinds[ordinal];
    }

}
