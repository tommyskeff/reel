package dev.tommyjs.reel.storage.codec;

import dev.tommyjs.reel.storage.ReelIOUtil;
import dev.tommyjs.reel.storage.model.*;
import dev.tommyjs.reel.track.TrackKind;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;

public final class DirectoryCodec {

    private DirectoryCodec() {
    }

    public static void encodeEntityTypes(@NotNull ByteBuf out, @NotNull EntityTypeDirectory dir) {
        List<EntityTypeDefinition> types = dir.types();
        ReelIOUtil.writeVarInt(out, types.size());
        for (EntityTypeDefinition def : types) {
            ReelIOUtil.writeVarInt(out, def.id());
            ReelIOUtil.writeString(out, def.identifier());
        }
    }

    public static @NotNull EntityTypeDirectory decodeEntityTypes(@NotNull ByteBuf in) throws IOException {
        int count = ReelIOUtil.readVarIntAsInt(in, "entity_type_count");
        List<EntityTypeDefinition> types = new ArrayList<>(count);
        Map<Integer, EntityTypeDefinition> byId = new HashMap<>(count * 2);
        Map<String, EntityTypeDefinition> byIdentifier = new HashMap<>(count * 2);

        for (int i = 0; i < count; i++) {
            int typeId = ReelIOUtil.readVarIntAsInt(in, "entity_type[" + i + "].type_id");
            String typeIdentifier = ReelIOUtil.readString(in);
            EntityTypeDefinition def = new EntityTypeDefinition(typeId, typeIdentifier);
            types.add(def);
            byId.put(typeId, def);
            byIdentifier.put(typeIdentifier, def);
        }

        return new EntityTypeDirectory(Collections.unmodifiableList(types), byId, byIdentifier);
    }

    public static void encodeTrackTypes(@NotNull ByteBuf out, @NotNull TrackDirectory dir) {
        long[] strides = dir.layerStrides();
        ReelIOUtil.writeVarInt(out, strides.length);
        for (long stride : strides) {
            ReelIOUtil.writeVarInt(out, stride);
        }

        List<TrackDefinition> tracks = dir.trackTypes();
        ReelIOUtil.writeVarInt(out, tracks.size());
        for (TrackDefinition def : tracks) {
            ReelIOUtil.writeVarInt(out, def.id());
            ReelIOUtil.writeString(out, def.identifier());
            ReelIOUtil.writeVarInt(out, def.codecVersion());
            ReelIOUtil.writeVarInt(out, def.kind().ordinal());
        }
    }

    public static @NotNull TrackDirectory decodeTrackTypes(@NotNull ByteBuf in) throws IOException {
        int layerCount = ReelIOUtil.readVarIntAsInt(in, "layer_count");
        long[] strides = new long[layerCount];
        for (int i = 0; i < layerCount; i++) {
            strides[i] = ReelIOUtil.readVarInt(in);
            if (strides[i] <= 0) {
                throw new IOException("layer[" + i + "] stride <= 0: stride=" + strides[i]);
            }
            if (i == 0 && strides[0] != 1) {
                throw new IOException("layer[0] stride must be 1: stride=" + strides[0]);
            }
        }

        int trackTypeCount = ReelIOUtil.readVarIntAsInt(in, "track_type_count");
        List<TrackDefinition> tracks = new ArrayList<>(trackTypeCount);
        Map<Integer, TrackDefinition> byId = new HashMap<>(trackTypeCount * 2);
        Map<String, TrackDefinition> byIdentifier = new HashMap<>(trackTypeCount * 2);

        for (int i = 0; i < trackTypeCount; i++) {
            int typeId = ReelIOUtil.readVarIntAsInt(in, "track_type[" + i + "].type_id");
            String typeIdentifier = ReelIOUtil.readString(in);
            int codecVersion = ReelIOUtil.readVarIntAsInt(in, "track_type[" + i + "].codec_version");
            int kindOrdinal = ReelIOUtil.readVarIntAsInt(in, "track_type[" + i + "].kind");
            TrackKind[] kinds = TrackKind.values();
            if (kindOrdinal < 0 || kindOrdinal >= kinds.length) {
                throw new IOException("track_type[" + i + "] unknown kind ordinal: " + kindOrdinal);
            }
            TrackDefinition def = new TrackDefinition(typeId, typeIdentifier, codecVersion, kinds[kindOrdinal]);
            tracks.add(def);
            byId.put(typeId, def);
            byIdentifier.put(typeIdentifier, def);
        }

        return new TrackDirectory(layerCount, strides, Collections.unmodifiableList(tracks), byId, byIdentifier);
    }

    public static void encodeSegments(@NotNull ByteBuf out, @NotNull SegmentDirectory dir) {
        List<SegmentEntry> segments = dir.segments();
        ReelIOUtil.writeVarInt(out, segments.size());
        for (SegmentEntry seg : segments) {
            ReelIOUtil.writeVarInt(out, seg.startFrame());
            ReelIOUtil.writeVarInt(out, seg.endFrame());
            out.writeLong(seg.byteOffset());
            long byteSize = seg.byteSize();
            if (byteSize > Integer.MAX_VALUE) {
                throw new IllegalStateException("segment byte_size exceeds 2 GiB: byte_size=" + byteSize);
            }
            out.writeInt((int) byteSize);
            out.writeByte(seg.anchor() ? 1 : 0);
        }
    }

    public static @NotNull SegmentDirectory decodeSegments(@NotNull ByteBuf in) throws IOException {
        int count = ReelIOUtil.readVarIntAsInt(in, "segment_count");
        List<SegmentEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long startFrame = ReelIOUtil.readVarInt(in);
            long endFrame = ReelIOUtil.readVarInt(in);
            long byteOffset = in.readLong();
            if (byteOffset < 0) {
                throw new IOException("segment[" + i + "] byte_offset negative: " + byteOffset);
            }
            int byteSize = in.readInt();
            if (byteSize < 0) {
                throw new IOException("segment[" + i + "] byte_size negative: " + byteSize);
            }
            boolean anchor = in.readByte() != 0;
            entries.add(new SegmentEntry(startFrame, endFrame, byteOffset, byteSize, anchor));
        }
        return new SegmentDirectory(Collections.unmodifiableList(entries));
    }

}
