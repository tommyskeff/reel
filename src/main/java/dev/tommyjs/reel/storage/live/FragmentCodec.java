package dev.tommyjs.reel.storage.live;

import dev.tommyjs.reel.storage.ReelIOUtil;
import dev.tommyjs.reel.storage.codec.WindowPayloadCodec;
import dev.tommyjs.reel.storage.model.RawPayload;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class FragmentCodec {

    private FragmentCodec() {
    }

    public static void encode(@NotNull ByteBuf out, @NotNull ReelFragment fragment) {
        ReelIOUtil.writeVarInt(out, fragment.startFrame());
        ReelIOUtil.writeVarInt(out, fragment.endFrame());

        List<FragmentEntry> entries = fragment.entries();
        ReelIOUtil.writeVarInt(out, entries.size());
        for (FragmentEntry entry : entries) {
            ReelIOUtil.writeVarInt(out, entry.entityId());
            ReelIOUtil.writeVarInt(out, entry.entityTypeId());
            ReelIOUtil.writeVarInt(out, entry.trackTypeId());
            ReelIOUtil.writeVarInt(out, entry.layer());
            ReelIOUtil.writeVarInt(out, entry.windowStartFrame());

            ByteBuf encoded = out.alloc().buffer();
            try {
                writePayload(encoded, entry.payload());
                ReelIOUtil.writeBytes(out, encoded);
            } finally {
                encoded.release();
            }
        }
    }

    public static @NotNull ReelFragment decode(@NotNull ByteBuf in) throws IOException {
        long startFrame = ReelIOUtil.readVarInt(in);
        long endFrame = ReelIOUtil.readVarInt(in);
        if (endFrame < startFrame) {
            throw new IOException("fragment end_frame before start_frame: end=" + endFrame + ", start=" + startFrame);
        }

        int count = ReelIOUtil.readVarIntAsInt(in, "fragment_entry_count");
        List<FragmentEntry> entries = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                long entityId = ReelIOUtil.readVarInt(in);
                int entityTypeId = ReelIOUtil.readVarIntAsInt(in, "fragment_entry[" + i + "].entity_type_id");
                int trackTypeId = ReelIOUtil.readVarIntAsInt(in, "fragment_entry[" + i + "].track_type_id");
                int layer = ReelIOUtil.readVarIntAsInt(in, "fragment_entry[" + i + "].layer");
                long windowStartFrame = ReelIOUtil.readVarInt(in);

                long rawLen = ReelIOUtil.readVarInt(in);
                if (rawLen < 0 || rawLen > Integer.MAX_VALUE) {
                    throw new IOException("fragment entry payload length out of range: " + rawLen);
                }

                ByteBuf slice = in.readRetainedSlice((int) rawLen);
                RawPayload payload;
                try {
                    payload = WindowPayloadCodec.decode(slice);
                } catch (IOException e) {
                    slice.release();
                    throw e;
                }

                entries.add(new FragmentEntry(entityId, entityTypeId, trackTypeId, layer, windowStartFrame, payload));
            }
        } catch (Throwable t) {
            for (FragmentEntry entry : entries) {
                ReferenceCountUtil.safeRelease(entry.payload().body());
            }
            throw t;
        }

        return new ReelFragment(startFrame, endFrame, entries);
    }

    private static void writePayload(ByteBuf out, RawPayload payload) {
        if (payload instanceof RawPayload.Delta d) {
            WindowPayloadCodec.encodeDelta(out, d.body());
        } else if (payload instanceof RawPayload.State s) {
            WindowPayloadCodec.encodeState(out, s.hasPrevious(), s.body());
        } else if (payload instanceof RawPayload.Close c) {
            WindowPayloadCodec.encodeClose(out, c.body());
        } else if (payload instanceof RawPayload.Event e) {
            WindowPayloadCodec.encodeEvent(out, e.body());
        } else {
            throw new IllegalStateException("unhandled RawPayload type: " + payload.getClass());
        }
    }

}
