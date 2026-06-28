package dev.tommyjs.reel.storage.codec;

import dev.tommyjs.reel.storage.ReelCompressor;
import dev.tommyjs.reel.storage.ReelIOUtil;
import dev.tommyjs.reel.storage.model.*;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public final class SegmentCodec {

    private SegmentCodec() {
    }

    public static void encodeCompressed(@NotNull ByteBuf out, @NotNull Segment seg) throws IOException {
        encodeCompressed(out, seg, ReelCompressor.LZ4);
    }

    public static void encodeCompressed(@NotNull ByteBuf out, @NotNull Segment seg, @NotNull ReelCompressor compression)
        throws IOException {
        ByteBuf body = out.alloc().heapBuffer();
        try {
            encode(body, seg);
            compression.compress(body, out);
        } finally {
            body.release();
        }
    }

    public static @NotNull Segment decodeCompressed(@NotNull ByteBuf compressed, int compressedLength, int layerCount)
        throws IOException {
        return decodeCompressed(compressed, compressedLength, layerCount, ReelCompressor.LZ4);
    }

    public static @NotNull Segment decodeCompressed(@NotNull ByteBuf compressed, int compressedLength, int layerCount,
                                                    @NotNull ReelCompressor compression) throws IOException {
        ByteBuf decompressed = compressed.alloc().heapBuffer();
        try {
            compression.decompress(compressed, compressedLength, decompressed);
            return decode(decompressed, layerCount);
        } finally {
            ReferenceCountUtil.safeRelease(decompressed);
        }
    }

    public static void encode(@NotNull ByteBuf out, @NotNull Segment seg) {
        ReelIOUtil.writeVarInt(out, seg.getStartFrame());
        ReelIOUtil.writeVarInt(out, seg.getEndFrame());

        long[] entityIds = seg.getEntityIds();
        int[] entityTypeIds = seg.getEntityTypeIds();
        int entityCount = entityIds.length;
        ReelIOUtil.writeVarInt(out, entityCount);
        long prev = 0;
        for (int i = 0; i < entityCount; i++) {
            ReelIOUtil.writeVarInt(out, i == 0 ? entityIds[i] : entityIds[i] - prev);
            ReelIOUtil.writeVarInt(out, entityTypeIds[i]);
            prev = entityIds[i];
        }

        TrackReference[] trackRefs = seg.getTrackRefs();
        ReelIOUtil.writeVarInt(out, trackRefs.length);
        for (TrackReference ref : trackRefs) {
            ReelIOUtil.writeVarInt(out, ref.entityIndex());
            ReelIOUtil.writeVarInt(out, ref.typeId());
        }

        int layerCount = seg.getLayerCount();
        int offsetsIndex = out.writerIndex();
        for (int l = 0; l < layerCount; l++) {
            out.writeInt(0);
        }

        int[] layerOffsets = new int[layerCount];
        for (int l = 0; l < layerCount; l++) {
            layerOffsets[l] = out.writerIndex() - offsetsIndex - (layerCount * 4);
            writeLayerRegion(out, seg, l);
        }

        int savedWriter = out.writerIndex();
        out.writerIndex(offsetsIndex);
        for (int l = 0; l < layerCount; l++) {
            out.writeInt(layerOffsets[l]);
        }
        out.writerIndex(savedWriter);
    }

    private static void writeLayerRegion(@NotNull ByteBuf out, @NotNull Segment seg, int layer) {
        int windowCount = seg.getWindowCount(layer);
        ReelIOUtil.writeVarInt(out, windowCount);

        int[] offsets = new int[windowCount + 1];
        ByteBuf windowData = out.alloc().heapBuffer();
        try {
            for (int w = 0; w < windowCount; w++) {
                offsets[w] = windowData.writerIndex();
                WindowEntry[] entries = seg.getWindow(layer, w).entries();
                if (entries.length == 0) {
                    ReelIOUtil.writeVarInt(windowData, 0);
                } else {
                    ReelIOUtil.writeVarInt(windowData, entries.length);
                    for (WindowEntry e : entries) {
                        ReelIOUtil.writeVarInt(windowData, e.trackRefIndex());
                        ByteBuf encoded = windowData.alloc().buffer();
                        try {
                            RawPayload rp = e.payload();
                            if (rp instanceof RawPayload.Delta d) {
                                WindowPayloadCodec.encodeDelta(encoded, d.body());
                            } else if (rp instanceof RawPayload.State s) {
                                WindowPayloadCodec.encodeState(encoded, s.hasPrevious(), s.body());
                            } else if (rp instanceof RawPayload.Close c) {
                                WindowPayloadCodec.encodeClose(encoded, c.body());
                            } else if (rp instanceof RawPayload.Event ev) {
                                WindowPayloadCodec.encodeEvent(encoded, ev.body());
                            }
                            ReelIOUtil.writeBytes(windowData, encoded);
                        } finally {
                            encoded.release();
                        }
                    }
                }
            }
            offsets[windowCount] = windowData.writerIndex();

            for (int w = 0; w <= windowCount; w++) {
                out.writeInt(offsets[w]);
            }
            out.writeBytes(windowData, windowData.readerIndex(), windowData.readableBytes());
        } finally {
            ReferenceCountUtil.safeRelease(windowData);
        }
    }

    public static @NotNull Segment decode(@NotNull ByteBuf in, int layerCount) throws IOException {
        long startFrame = ReelIOUtil.readVarInt(in);
        long endFrame = ReelIOUtil.readVarInt(in);
        if (endFrame < startFrame) {
            throw new IOException("segment end_frame before start_frame: end=" + endFrame + ", start=" + startFrame);
        }

        int entityCount = ReelIOUtil.readVarIntAsInt(in, "entity_count");
        long[] entityIds = new long[entityCount];
        int[] entityTypeIds = new int[entityCount];
        long prev = 0;
        for (int i = 0; i < entityCount; i++) {
            long delta = ReelIOUtil.readVarInt(in);
            entityIds[i] = i == 0 ? delta : prev + delta;
            prev = entityIds[i];
            entityTypeIds[i] = ReelIOUtil.readVarIntAsInt(in, "entity_type_id[" + i + "]");
        }

        int trackRefCount = ReelIOUtil.readVarIntAsInt(in, "track_ref_count");
        TrackReference[] trackRefs = new TrackReference[trackRefCount];
        for (int i = 0; i < trackRefCount; i++) {
            int entityIndex = ReelIOUtil.readVarIntAsInt(in, "track_ref[" + i + "].entity_index");
            int typeId = ReelIOUtil.readVarIntAsInt(in, "track_ref[" + i + "].type_id");
            trackRefs[i] = new TrackReference(entityIndex, typeId);
        }

        int[] layerDataOffsets = new int[layerCount];
        for (int l = 0; l < layerCount; l++) {
            layerDataOffsets[l] = in.readInt();
        }

        WindowGroup[][] layerWindows = new WindowGroup[layerCount][];
        int baseOffset = in.readerIndex();
        for (int l = 0; l < layerCount; l++) {
            in.readerIndex(baseOffset + layerDataOffsets[l]);
            layerWindows[l] = parseLayerRegion(in);
        }

        return new Segment(startFrame, endFrame, entityIds, entityTypeIds, trackRefs, layerWindows);
    }

    private static WindowGroup[] parseLayerRegion(@NotNull ByteBuf in) throws IOException {
        int windowCount = ReelIOUtil.readVarIntAsInt(in, "window_count");
        int[] offsets = new int[windowCount + 1];
        for (int w = 0; w <= windowCount; w++) {
            offsets[w] = in.readInt();
        }

        int dataStart = in.readerIndex();
        WindowGroup[] groups = new WindowGroup[windowCount];
        for (int w = 0; w < windowCount; w++) {
            in.readerIndex(dataStart + offsets[w]);
            groups[w] = parseWindowGroup(in);
        }
        in.readerIndex(dataStart + offsets[windowCount]);
        return groups;
    }

    private static WindowGroup parseWindowGroup(@NotNull ByteBuf in) throws IOException {
        int entryCount = ReelIOUtil.readVarIntAsInt(in, "entry_count");
        if (entryCount == 0) {
            return WindowGroup.EMPTY;
        }
        WindowEntry[] entries = new WindowEntry[entryCount];
        for (int i = 0; i < entryCount; i++) {
            int trackRefIndex = ReelIOUtil.readVarIntAsInt(in, "track_ref_index");
            long rawLen = ReelIOUtil.readVarInt(in);
            if (rawLen < 0 || rawLen > Integer.MAX_VALUE) {
                throw new IOException("window entry payload length out of range: " + rawLen);
            }
            ByteBuf slice = in.readRetainedSlice((int) rawLen);
            RawPayload payload;
            try {
                payload = WindowPayloadCodec.decode(slice);
            } catch (IOException e) {
                slice.release();
                throw e;
            }
            entries[i] = new WindowEntry(trackRefIndex, payload);
        }
        return new WindowGroup(entries);
    }

}
