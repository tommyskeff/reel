package dev.tommyjs.reel;

import dev.tommyjs.reel.storage.ReelIOUtil;
import dev.tommyjs.reel.storage.live.FragmentEntry;
import dev.tommyjs.reel.storage.live.ReelFragment;
import dev.tommyjs.reel.storage.model.EntityTypeDefinition;
import dev.tommyjs.reel.storage.model.EntityTypeDirectory;
import dev.tommyjs.reel.storage.model.RawPayload;
import dev.tommyjs.reel.storage.model.TrackDefinition;
import dev.tommyjs.reel.storage.model.TrackDirectory;
import dev.tommyjs.reel.track.TrackKind;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LiveTestFixtures {

    public static final int POSITION_TYPE_ID = 0;
    public static final int PLAYER_TYPE_ID = 0;

    private LiveTestFixtures() {
    }

    public static @NotNull RawPayload.Delta deltaPayload(int... steps) {
        ByteBuf body = Unpooled.buffer();
        ReelIOUtil.writeVarInt(body, steps.length);
        for (int step : steps) {
            TestFixtures.intDeltaCodec().encode(body, new TestFixtures.IntDelta(step));
        }
        return new RawPayload.Delta(body);
    }

    public static @NotNull RawPayload.State statePayload(int value) {
        ByteBuf body = Unpooled.buffer();
        TestFixtures.intStateCodec().encode(body, new TestFixtures.IntState(value));
        return new RawPayload.State(false, body);
    }

    public static @NotNull RawPayload.Event eventPayload(int code) {
        ByteBuf body = Unpooled.buffer();
        ReelIOUtil.writeVarInt(body, 1);
        TestFixtures.signalCodec().encode(body, new TestFixtures.Signal(code));
        return new RawPayload.Event(body);
    }

    public static @NotNull RawPayload.Close closePayload(int value) {
        ByteBuf body = Unpooled.buffer();
        TestFixtures.intStateCodec().encode(body, new TestFixtures.IntState(value));
        return new RawPayload.Close(body);
    }

    public static @NotNull FragmentEntry entry(long entityId, long windowStartFrame, @NotNull RawPayload payload) {
        return new FragmentEntry(entityId, PLAYER_TYPE_ID, POSITION_TYPE_ID, 0, windowStartFrame, payload);
    }

    public static @NotNull ReelFragment fragment(long startFrame, long endFrame, @NotNull FragmentEntry... entries) {
        return new ReelFragment(startFrame, endFrame, List.of(entries));
    }

    public static @NotNull EntityTypeDirectory playerTypes() {
        EntityTypeDefinition def = new EntityTypeDefinition(PLAYER_TYPE_ID, "player");
        Map<Integer, EntityTypeDefinition> byId = new HashMap<>();
        byId.put(def.id(), def);
        Map<String, EntityTypeDefinition> byIdentifier = new HashMap<>();
        byIdentifier.put(def.identifier(), def);
        return new EntityTypeDirectory(List.of(def), byId, byIdentifier);
    }

    public static @NotNull TrackDirectory positionTracks(long @NotNull [] strides) {
        TrackDefinition def = new TrackDefinition(POSITION_TYPE_ID, "position", 1, TrackKind.DELTA);
        Map<Integer, TrackDefinition> byId = new HashMap<>();
        byId.put(def.id(), def);
        Map<String, TrackDefinition> byIdentifier = new HashMap<>();
        byIdentifier.put(def.identifier(), def);
        return new TrackDirectory(strides.length, strides, List.of(def), byId, byIdentifier);
    }

}
