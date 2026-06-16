package dev.tommyjs.reel.example;

import dev.tommyjs.reel.recorder.EntityRecorder;
import dev.tommyjs.reel.recorder.ReelRecorder;
import dev.tommyjs.reel.replay.update.ReplayUpdate;
import dev.tommyjs.reel.scene.AbstractActor;
import dev.tommyjs.reel.scene.ActorHandler;
import dev.tommyjs.reel.scene.ReplaySceneImpl;
import dev.tommyjs.reel.scene.ReplayScene;
import dev.tommyjs.reel.scene.SceneHandler;
import dev.tommyjs.reel.scene.SceneHandlerContext;
import dev.tommyjs.reel.scene.player.PlaybackState;
import dev.tommyjs.reel.scene.player.ScenePlayer;
import dev.tommyjs.reel.scene.SceneResourceKey;
import dev.tommyjs.reel.scene.SceneTransition;
import dev.tommyjs.reel.track.EntityTypeHandle;
import dev.tommyjs.reel.track.EventTrackModel;
import dev.tommyjs.reel.track.TrackModel;
import dev.tommyjs.reel.track.TrackHandle;
import dev.tommyjs.reel.track.TrackRegistry;
import dev.tommyjs.reel.track.codec.Codec;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public final class SceneExample {

    private static final int WIDTH = 56;
    private static final int HEIGHT = 22;
    private static final int FRAMES = 180;
    private static final int BASE_FPS = 20;
    private static final long FRAME_DELAY_MS = 45;

    private static final EntityTypeHandle DOT = EntityTypeHandle.of("dot");
    private static final TrackHandle<Position, PositionDelta> POSITION =
        TrackHandle.of("position", Position.class, PositionDelta.class);
    private static final TrackHandle<Void, Pulse> PULSE = TrackHandle.of("pulse", Void.class, Pulse.class);
    private static final SceneResourceKey<DotView, DotView> DOTS = SceneResourceKey.of("dots");

    public static void main(String[] args) throws IOException {
        Path file = Files.createTempFile("scene", ".reel");

        TrackRegistry registry = TrackRegistry.builder()
            .register(POSITION, new PositionModel(), new PositionStateCodec(), new PositionDeltaCodec())
            .register(PULSE, new PulseModel(), new PulseCodec())
            .build();

        record(file, registry);
        play(file, registry);

        Files.deleteIfExists(file);
    }

    private static void record(Path file, TrackRegistry registry) throws IOException {
        ReelRecorder recorder = ReelRecorder.builder()
            .setTrackRegistry(registry)
            .setWriter(w -> w.setFile(file))
            .build();

        int count = 5;
        EntityRecorder[] dots = new EntityRecorder[count];
        int[] x = new int[count];
        int[] y = new int[count];
        int[] vx = new int[count];
        int[] vy = new int[count];

        for (int i = 0; i < count; i++) {
            x[i] = 4 + i * 9;
            y[i] = 3 + i * 3;
            vx[i] = (i % 2 == 0) ? 1 : -1;
            vy[i] = (i % 3 == 0) ? 1 : -1;
            dots[i] = recorder.createEntity(DOT);
            dots[i].recordState(POSITION, new Position(x[i], y[i]));
        }

        for (int frame = 0; frame < FRAMES; frame++) {
            for (int i = 0; i < count; i++) {
                boolean bounce = false;

                int nx = x[i] + vx[i];
                if (nx < 1 || nx > WIDTH - 2) {
                    vx[i] = -vx[i];
                    nx = x[i] + vx[i];
                    bounce = true;
                }

                int ny = y[i] + vy[i];
                if (ny < 1 || ny > HEIGHT - 2) {
                    vy[i] = -vy[i];
                    ny = y[i] + vy[i];
                    bounce = true;
                }

                dots[i].recordDelta(POSITION, new PositionDelta(nx - x[i], ny - y[i]));
                x[i] = nx;
                y[i] = ny;

                if (bounce) {
                    dots[i].recordEvent(PULSE, new Pulse(i));
                }
            }
            recorder.advanceFrame();
        }

        recorder.close();
    }

    private static void play(Path file, TrackRegistry registry) {
        FieldRenderer renderer = new FieldRenderer();

        List<SceneHandler> handlers = List.of(
            ActorHandler.create(DOT, DotEntity::new).provides(DOTS),
            renderer
        );

        ReplaySceneImpl scene = ReplayScene.builder()
            .setTrackRegistry(registry)
            .setReader(r -> r.setFile(file))
            .addHandlers(handlers)
            .build();

        long total = scene.getTotalFrames();
        renderer.setTotal(total);

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        ScenePlayer player = ScenePlayer.builder()
            .setScene(scene)
            .setBaseFps(BASE_FPS)
            .schedule(executor)
            .build();

        player.play();
        awaitEnd(player);

        for (long frame = total - 2; frame >= 0; frame--) {
            player.seek(frame);
            sleep(FRAME_DELAY_MS);
        }

        player.setSpeed(2.0);
        player.restart();
        awaitEnd(player);

        player.close();
        executor.shutdownNow();
        scene.close();
        System.out.print("[0m\n");
    }

    private static void awaitEnd(ScenePlayer player) {
        while (player.getState() != PlaybackState.ENDED) {
            sleep(20);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class DotEntity extends AbstractActor {

        private DotView view;

        @Override
        protected void configure() {
            onCreate(POSITION, position -> {
                view = new DotView(position.x(), position.y());
                scene.getResourceManager().publish(DOTS, view, view);
            });
            onState(POSITION, true, position -> {
                view.x = position.x();
                view.y = position.y();
            });
            onEvent(PULSE, pulse -> view.pulse = true);
            onDestroy(() -> scene.getResourceManager().unpublish(DOTS, view));
        }

    }

    private static final class FieldRenderer implements SceneHandler {

        private long total;

        private void setTotal(long total) {
            this.total = total;
        }

        @Override
        public void load(@NotNull SceneHandlerContext ctx) {
            ctx.registerConsumer(DOTS);
        }

        @Override
        public void run(@NotNull ReplayUpdate update, @NotNull SceneTransition transition, @NotNull ReplayScene scene) {
            Collection<DotView> dots = scene.getResourceManager().getResources(DOTS);

            char[][] grid = new char[HEIGHT][WIDTH];
            for (char[] row : grid) {
                java.util.Arrays.fill(row, ' ');
            }

            int pulses = 0;
            for (DotView dot : dots) {
                if (dot.x >= 0 && dot.x < WIDTH && dot.y >= 0 && dot.y < HEIGHT) {
                    grid[dot.y][dot.x] = dot.pulse ? '@' : '*';
                }
                if (dot.pulse) {
                    pulses++;
                }
            }

            StringBuilder out = new StringBuilder();
            out.append("[H[2J");
            out.append(mode(transition)).append("  frame ").append(transition.to() + 1).append('/').append(total);
            out.append("  pulses ").append(pulses).append("[0m\n");
            out.append('+').append("-".repeat(WIDTH)).append("+\n");
            for (char[] row : grid) {
                out.append('|');
                for (char cell : row) {
                    if (cell == '@') {
                        out.append("[91m@[0m");
                    } else if (cell == '*') {
                        out.append("[36m*[0m");
                    } else {
                        out.append(' ');
                    }
                }
                out.append("|\n");
            }
            out.append('+').append("-".repeat(WIDTH)).append("+\n");
            System.out.print(out);
            System.out.flush();

            for (DotView dot : dots) {
                dot.pulse = false;
            }
        }

        @Override
        public void stop(@NotNull ReplayScene scene) {
        }

        private String mode(SceneTransition transition) {
            if (transition.isInitial()) {
                return "[97mINITIAL[0m";
            }
            if (transition.isBackward()) {
                return "[95mSCRUB <[0m";
            }
            if (transition.playback()) {
                return "[92mPLAY  >[0m";
            }
            return "[93mSCRUB >[0m";
        }

    }

    private static final class DotView {

        private int x;
        private int y;
        private boolean pulse;

        private DotView(int x, int y) {
            this.x = x;
            this.y = y;
        }

    }

    public record Position(int x, int y) {
    }

    public record PositionDelta(int dx, int dy) {
    }

    public record Pulse(int kind) {
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

    private static final class PulseModel implements EventTrackModel<Pulse> {

    }

    private static final class PulseCodec implements Codec<Pulse> {

        @Override
        public void encode(@NotNull ByteBuf out, @NotNull Pulse delta) {
            out.writeInt(delta.kind());
        }

        @Override
        public @NotNull Pulse decode(@NotNull ByteBuf in) {
            return new Pulse(in.readInt());
        }

    }

}
