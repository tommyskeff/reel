package dev.tommyjs.reel.example;

import dev.tommyjs.reel.recorder.EntityRecorder;
import dev.tommyjs.reel.recorder.ReelRecorder;
import dev.tommyjs.reel.replay.ReplayCursor;
import dev.tommyjs.reel.replay.update.EntityUpdate;
import dev.tommyjs.reel.replay.update.TrackUpdate;
import dev.tommyjs.reel.storage.live.LocalLiveReel;
import dev.tommyjs.reel.storage.writer.LayerStrategy;
import dev.tommyjs.reel.track.EntityTypeHandle;
import dev.tommyjs.reel.track.TrackHandle;
import dev.tommyjs.reel.track.TrackModel;
import dev.tommyjs.reel.track.TrackRegistry;
import dev.tommyjs.reel.track.codec.Codec;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class LiveExample {

    private static final int FRAMES = 60;
    private static final LayerStrategy STRATEGY = new LayerStrategy(new long[]{1, 4});

    private static final EntityTypeHandle RUNNER = EntityTypeHandle.of("runner");
    private static final TrackHandle<Distance, Step> DISTANCE =
        TrackHandle.of("distance", Distance.class, Step.class);

    private LiveExample() {
    }

    public static void main(String[] args) throws IOException {
        TrackRegistry registry = TrackRegistry.builder()
            .register(DISTANCE, new DistanceModel(), new DistanceCodec(), new StepCodec())
            .build();

        Path file = Files.createTempFile("reel-live", ".reel");

        try (LocalLiveReel live = new LocalLiveReel(STRATEGY)) {
            try (ReelRecorder recorder = ReelRecorder.builder()
                .setWriter(w -> w.setFile(file).setLayerStrategy(STRATEGY).addListener(live.publisher()))
                .setTrackRegistry(registry)
                .setSegmentLength(8)
                .setAnchorInterval(16)
                .setIdleThreshold(0)
                .build();

                 ReplayCursor cursor = ReplayCursor.builder()
                     .setTrackRegistry(registry)
                     .setReader(live.reader())
                     .build()) {

                List<String> observed = new ArrayList<>();
                cursor.addListener(update -> {
                    for (EntityUpdate entity : update.getEntities()) {
                        for (TrackUpdate<Distance, Step> step : entity.getUpdatesFor(DISTANCE)) {
                            observed.add(describe(update.getToFrame(), step));
                        }
                    }
                });

                EntityRecorder runner = recorder.createEntity(RUNNER);
                runner.recordState(DISTANCE, new Distance(0));
                live.flush();

                for (int frame = 1; frame <= FRAMES; frame++) {
                    recorder.advanceFrame();
                    runner.recordDelta(DISTANCE, new Step(frame % 5 == 0 ? 3 : 1));
                    live.flush();

                    cursor.seek(live.reader().getHeadFrame());
                }

                System.out.println("head frame: " + live.reader().getHeadFrame());
                System.out.println("segments:   " + live.reader().readSegmentDirectory().size());
                System.out.println("updates:    " + observed.size());
                System.out.println("complete:   " + live.reader().isComplete());
            }

            System.out.println("after close, total frames: " + live.reader().getTotalFrames());
            System.out.println("archive written to: " + file);
        }
    }

    private static String describe(long frame, TrackUpdate<Distance, Step> step) {
        if (step instanceof TrackUpdate.Snapshot<Distance, Step> snapshot) {
            return frame + ": = " + snapshot.state().value();
        }
        if (step instanceof TrackUpdate.Delta<Distance, Step> delta) {
            return frame + ": + " + delta.delta().amount();
        }
        return frame + ": ?";
    }

    public record Distance(int value) {
    }

    public record Step(int amount) {
    }

    public static final class DistanceModel implements TrackModel<Distance, Step> {

        @Override
        public @NotNull Distance applyDelta(@NotNull Distance state, @NotNull Step delta) {
            return new Distance(state.value() + delta.amount());
        }

        @Override
        public @NotNull Distance cloneState(@NotNull Distance state) {
            return new Distance(state.value());
        }

        @Override
        public @NotNull List<Step> condense(@NotNull Distance ref, @NotNull List<Step> deltas) {
            int sum = 0;
            for (Step delta : deltas) {
                sum += delta.amount();
            }
            return List.of(new Step(sum));
        }

        @Override
        public @NotNull Step reverse(@NotNull Step delta) {
            return new Step(-delta.amount());
        }

    }

    public static final class DistanceCodec implements Codec<Distance> {

        @Override
        public void encode(@NotNull ByteBuf out, @NotNull Distance state) {
            out.writeInt(state.value());
        }

        @Override
        public @NotNull Distance decode(@NotNull ByteBuf in) {
            return new Distance(in.readInt());
        }

    }

    public static final class StepCodec implements Codec<Step> {

        @Override
        public void encode(@NotNull ByteBuf out, @NotNull Step delta) {
            out.writeInt(delta.amount());
        }

        @Override
        public @NotNull Step decode(@NotNull ByteBuf in) {
            return new Step(in.readInt());
        }

    }

}
