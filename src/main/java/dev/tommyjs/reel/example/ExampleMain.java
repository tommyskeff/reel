package dev.tommyjs.reel.example;

import dev.tommyjs.reel.recorder.EntityRecorder;
import dev.tommyjs.reel.recorder.ReelRecorder;
import dev.tommyjs.reel.replay.ReplayCursor;
import dev.tommyjs.reel.replay.update.TrackUpdate;
import dev.tommyjs.reel.storage.writer.LayerStrategy;
import dev.tommyjs.reel.track.EntityTypeHandle;
import dev.tommyjs.reel.track.TrackModel;
import dev.tommyjs.reel.track.TrackHandle;
import dev.tommyjs.reel.track.TrackRegistry;
import dev.tommyjs.reel.track.codec.Codec;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ExampleMain {

    private static final LayerStrategy LAYER_STRATEGY = new LayerStrategy(new long[]{1, 4});
    private static final long SEAL_TARGET_FRAMES = 8;
    private static final long IDLE_GAP_THRESHOLD = 0;

    private static final TrackHandle<Position, PositionDelta> POSITION =
        TrackHandle.of("position", Position.class, PositionDelta.class);

    private static final EntityTypeHandle PLAYER = EntityTypeHandle.of("player");
    private static final EntityTypeHandle NPC = EntityTypeHandle.of("npc");

    public static void main(String[] args) throws IOException {
        Path file = Files.createTempFile("example", ".reel");

        TrackRegistry registry = TrackRegistry.builder()
            .register(POSITION, new PositionModel(), new PositionStateCodec(), new PositionDeltaCodec())
            .build();

        record(file, registry);
        replay(file, registry);

        Files.deleteIfExists(file);
    }

    private static void record(Path file, TrackRegistry registry) throws IOException {
        System.out.println("=== recording to " + file + " ===");

        ReelRecorder recorder = ReelRecorder.builder()
            .setTrackRegistry(registry)
            .setWriter(w -> w.setFile(file).setLayerStrategy(LAYER_STRATEGY))
            .setSegmentLength(SEAL_TARGET_FRAMES)
            .setIdleThreshold(IDLE_GAP_THRESHOLD)
            .build();

        EntityRecorder player = recorder.createEntity(PLAYER);
        EntityRecorder npc = recorder.createEntity(NPC);

        player.recordState(POSITION, new Position(0, 0));
        npc.recordState(POSITION, new Position(100, 50));

        for (int frame = 0; frame < 30; frame++) {
            player.recordDelta(POSITION, new PositionDelta(1, 0));
            if (frame % 2 == 0) {
                npc.recordDelta(POSITION, new PositionDelta(0, -1));
            }
            recorder.advanceFrame();
        }

        System.out.println("recorded " + recorder.currentFrame() + " frames across 2 entities");

        recorder.close();

        System.out.println("file size: " + Files.size(file) + " bytes");
    }

    private static void replay(Path file, TrackRegistry registry) throws IOException {
        System.out.println();
        System.out.println("=== replaying " + file + " ===");

        try (ReplayCursor cursor = ReplayCursor.fromFile(file, registry)) {

            System.out.println("total frames: " + cursor.totalFrames());

            cursor.addListener(update -> {
                System.out.println("  update " + update.getFromFrame() + " -> " + update.getToFrame());
                for (var entity : update.getEntities()) {
                    List<TrackUpdate<Position, PositionDelta>> steps = entity.getUpdatesFor(POSITION);
                    for (TrackUpdate<Position, PositionDelta> step : steps) {
                        if (step instanceof TrackUpdate.Snapshot<Position, PositionDelta> s) {
                            System.out.println("    entity " + entity.getEntityId() + " snapshot " + s.state());
                        } else if (step instanceof TrackUpdate.Delta<Position, PositionDelta> d) {
                            System.out.println("    entity " + entity.getEntityId() + " delta    " + d.delta());
                        }
                    }
                }
            });

            // Random-access to the middle, then step to the end, then jump back —
            // exercises forward, cross-chunk forward, and backward random-access paths.
            cursor.seek(15);
            cursor.seek(29);
            cursor.seek(3);
        }
    }

    public record Position(int x, int y) {
    }

    public record PositionDelta(int dx, int dy) {
    }

    private static final class PositionModel implements TrackModel<Position, PositionDelta> {

        @Override
        public @NotNull Position applyDelta(@NotNull Position state, @NotNull PositionDelta delta) {
            return new Position(state.x() + delta.dx(), state.y() + delta.dy());
        }

        @Override
        public @NotNull Position cloneState(@NotNull Position state) {
            return state;
        }

        @Override
        public @NotNull List<PositionDelta> condense(@NotNull Position ref, @NotNull List<PositionDelta> deltas) {
            int dx = 0, dy = 0;
            for (PositionDelta d : deltas) {
                dx += d.dx();
                dy += d.dy();
            }
            return List.of(new PositionDelta(dx, dy));
        }

        @Override
        public @NotNull PositionDelta reverse(@NotNull PositionDelta delta) {
            return new PositionDelta(-delta.dx(), -delta.dy());
        }
    }

    private static final class PositionStateCodec implements Codec<Position> {

        @Override
        public void encode(@NotNull ByteBuf out, @NotNull Position state) {
            out.writeInt(state.x()).writeInt(state.y());
        }

        @Override
        public @NotNull Position decode(@NotNull ByteBuf in) {
            return new Position(in.readInt(), in.readInt());
        }
    }

    private static final class PositionDeltaCodec implements Codec<PositionDelta> {

        @Override
        public void encode(@NotNull ByteBuf out, @NotNull PositionDelta delta) {
            out.writeInt(delta.dx()).writeInt(delta.dy());
        }

        @Override
        public @NotNull PositionDelta decode(@NotNull ByteBuf in) {
            return new PositionDelta(in.readInt(), in.readInt());
        }
    }
}
