package dev.tommyjs.reel;

import dev.tommyjs.reel.replay.update.EntityUpdate;
import dev.tommyjs.reel.replay.update.ReplayUpdate;
import dev.tommyjs.reel.replay.update.TrackUpdate;
import dev.tommyjs.reel.track.*;
import dev.tommyjs.reel.track.codec.BoolCodec;
import dev.tommyjs.reel.track.codec.Codec;
import dev.tommyjs.reel.track.codec.StringCodec;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TestFixtures {

    public static final TrackHandle<IntState, IntDelta> POSITION = TrackHandle.of("position", IntState.class, IntDelta.class);
    public static final TrackHandle<ChatState, ChatDelta> CHAT = TrackHandle.of("chat", ChatState.class, ChatDelta.class);
    public static final TrackHandle<Void, Signal> SIGNAL = TrackHandle.of("signal", Void.class, Signal.class);

    public static final EntityTypeHandle PLAYER = EntityTypeHandle.of("player");
    public static final EntityTypeHandle NPC = EntityTypeHandle.of("npc");

    private TestFixtures() {
    }

    public static @NotNull IntModel intModel() {
        return new IntModel();
    }

    public static @NotNull Codec<IntState> intStateCodec() {
        return new IntStateCodec();
    }

    public static @NotNull Codec<IntDelta> intDeltaCodec() {
        return new IntDeltaCodec();
    }

    public static @NotNull ChatModel chatModel() {
        return new ChatModel();
    }

    public static @NotNull Codec<ChatState> chatStateCodec() {
        return new ChatStateCodec();
    }

    public static @NotNull Codec<ChatDelta> chatDeltaCodec() {
        return new ChatDeltaCodec();
    }

    public static @NotNull SignalModel signalModel() {
        return new SignalModel();
    }

    public static @NotNull Codec<Signal> signalCodec() {
        return new SignalCodec();
    }

    public static void applyIntModel(@NotNull Map<Long, IntState> model, @NotNull IntModel trackModel,
                                     @NotNull ReplayUpdate update) {
        for (EntityUpdate entity : update.getEntities()) {
            IntState state = model.getOrDefault(entity.getEntityId(), new IntState(0));
            for (TrackUpdate<IntState, IntDelta> step : entity.getUpdatesFor(POSITION)) {
                if (step instanceof TrackUpdate.Snapshot<IntState, IntDelta> snapshot) {
                    state = snapshot.state();
                } else if (step instanceof TrackUpdate.Delta<IntState, IntDelta> delta) {
                    state = trackModel.applyDelta(state, delta.delta());
                }
            }
            model.put(entity.getEntityId(), state);
        }
        for (Long removed : update.getRemoved()) {
            model.remove(removed);
        }
    }

    public static @NotNull TrackRegistry registry() {
        return TrackRegistry.builder().register(POSITION, intModel(), intStateCodec(), intDeltaCodec()).build();
    }

    public static @NotNull TrackRegistry eventRegistry() {
        return TrackRegistry.builder()
            .register(POSITION, intModel(), intStateCodec(), intDeltaCodec())
            .register(SIGNAL, signalModel(), signalCodec())
            .build();
    }

    public static @NotNull TrackRegistry chatRegistry() {
        return TrackRegistry.builder()
            .register(POSITION, intModel(), intStateCodec(), intDeltaCodec())
            .register(CHAT, 2, chatModel(), chatStateCodec(), chatDeltaCodec())
            .build();
    }

    public record IntState(int value) {
    }

    public record IntDelta(int step) {
    }

    public static class IntModel implements TrackModel<IntState, IntDelta> {

        IntModel() {
        }

        @Override
        public @NotNull IntState applyDelta(@NotNull IntState state, @NotNull IntDelta delta) {
            return new IntState(state.value() + delta.step());
        }

        @Override
        public @NotNull IntState cloneState(@NotNull IntState state) {
            return new IntState(state.value());
        }

        @Override
        public @NotNull List<IntDelta> condense(@NotNull IntState ref, @NotNull List<IntDelta> deltas) {
            int sum = 0;
            for (IntDelta d : deltas) {
                sum += d.step();
            }
            return List.of(new IntDelta(sum));
        }

        @Override
        public @NotNull IntDelta reverse(@NotNull IntDelta delta) {
            return new IntDelta(-delta.step());
        }

    }

    public static final class IntStateCodec implements Codec<IntState> {

        @Override
        public void encode(@NotNull ByteBuf out, @NotNull IntState state) {
            out.writeInt(state.value());
        }

        @Override
        public @NotNull IntState decode(@NotNull ByteBuf in) {
            return new IntState(in.readInt());
        }

    }

    public static final class IntDeltaCodec implements Codec<IntDelta> {

        @Override
        public void encode(@NotNull ByteBuf out, @NotNull IntDelta delta) {
            out.writeInt(delta.step());
        }

        @Override
        public @NotNull IntDelta decode(@NotNull ByteBuf in) {
            return new IntDelta(in.readInt());
        }

    }

    public record Signal(int code) {
    }

    public static class SignalModel implements EventTrackModel<Signal> {

        SignalModel() {
        }

    }

    public static final class SignalCodec implements Codec<Signal> {

        @Override
        public void encode(@NotNull ByteBuf out, @NotNull Signal value) {
            out.writeInt(value.code());
        }

        @Override
        public @NotNull Signal decode(@NotNull ByteBuf in) {
            return new Signal(in.readInt());
        }

    }

    public record ChatState(@NotNull List<String> messages) {
    }

    public record ChatDelta(@NotNull String message, boolean removal) {

        public ChatDelta(@NotNull String message) {
            this(message, false);
        }
    }

    public static class ChatModel implements TrackModel<ChatState, ChatDelta> {

        ChatModel() {
        }

        @Override
        public @NotNull ChatState applyDelta(@NotNull ChatState state, @NotNull ChatDelta delta) {
            List<String> messages = new ArrayList<>(state.messages());
            if (delta.removal()) {
                messages.remove(messages.size() - 1);
            } else {
                messages.add(delta.message());
            }
            return new ChatState(messages);
        }

        @Override
        public @NotNull ChatState cloneState(@NotNull ChatState state) {
            return new ChatState(new ArrayList<>(state.messages()));
        }

        @Override
        public @NotNull List<ChatDelta> condense(@NotNull ChatState ref, @NotNull List<ChatDelta> deltas) {
            return List.copyOf(deltas);
        }

        @Override
        public @NotNull ChatDelta reverse(@NotNull ChatDelta delta) {
            return new ChatDelta(delta.message(), !delta.removal());
        }

    }

    public static final class ChatStateCodec implements Codec<ChatState> {

        @Override
        public void encode(@NotNull ByteBuf out, @NotNull ChatState state) {
            out.writeInt(state.messages().size());
            for (String message : state.messages()) {
                StringCodec.INSTANCE.encode(out, message);
            }
        }

        @Override
        public @NotNull ChatState decode(@NotNull ByteBuf in) {
            int count = in.readInt();
            List<String> messages = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                messages.add(StringCodec.INSTANCE.decode(in));
            }
            return new ChatState(messages);
        }

    }

    public static final class ChatDeltaCodec implements Codec<ChatDelta> {

        @Override
        public void encode(@NotNull ByteBuf out, @NotNull ChatDelta delta) {
            BoolCodec.INSTANCE.encode(out, delta.removal());
            StringCodec.INSTANCE.encode(out, delta.message());
        }

        @Override
        public @NotNull ChatDelta decode(@NotNull ByteBuf in) {
            boolean removal = BoolCodec.INSTANCE.decode(in);
            return new ChatDelta(StringCodec.INSTANCE.decode(in), removal);
        }

    }

}
