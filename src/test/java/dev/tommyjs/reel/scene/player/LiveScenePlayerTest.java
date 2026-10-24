package dev.tommyjs.reel.scene.player;

import dev.tommyjs.reel.TestFixtures;
import dev.tommyjs.reel.TestFixtures.IntDelta;
import dev.tommyjs.reel.TestFixtures.IntState;
import dev.tommyjs.reel.recorder.EntityRecorder;
import dev.tommyjs.reel.recorder.ReelRecorder;
import dev.tommyjs.reel.replay.ReplayCursor;
import dev.tommyjs.reel.scene.ReplaySceneImpl;
import dev.tommyjs.reel.storage.live.LocalLiveReel;
import dev.tommyjs.reel.storage.writer.LayerStrategy;
import dev.tommyjs.reel.track.TrackRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import static dev.tommyjs.reel.TestFixtures.PLAYER;
import static dev.tommyjs.reel.TestFixtures.POSITION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveScenePlayerTest {

    private static final LayerStrategy STRATEGY = new LayerStrategy(new long[]{1, 4});
    private static final int BASE_FPS = 20;

    @TempDir
    Path tmp;

    private TrackRegistry registry;
    private LocalLiveReel live;
    private ReelRecorder recorder;
    private EntityRecorder player;

    @BeforeEach
    void setUp() {
        registry = TestFixtures.registry();
        live = new LocalLiveReel(STRATEGY);
        recorder = ReelRecorder.builder()
            .setWriter(w -> w.setFile(tmp.resolve("player.reel").toFile())
                .setLayerStrategy(STRATEGY)
                .addListener(live.publisher()))
            .setTrackRegistry(registry)
            .setSegmentLength(8)
            .setIdleThreshold(0)
            .build();

        player = recorder.createEntity(PLAYER);
        player.recordState(POSITION, new IntState(0));
        live.flush();
    }

    @AfterEach
    void tearDown() throws IOException {
        recorder.close();
        live.close();
    }

    private void advance(int frames) {
        for (int i = 0; i < frames; i++) {
            recorder.advanceFrame();
            player.recordDelta(POSITION, new IntDelta(1));
            live.flush();
        }
    }

    private LiveScenePlayer livePlayer(Duration delay) {
        return ScenePlayer.builder()
            .setScene(scene())
            .setBaseFps(BASE_FPS)
            .setLiveDelay(delay)
            .buildLive();
    }

    private ReplaySceneImpl scene() {
        ReplayCursor cursor = ReplayCursor.builder().setTrackRegistry(registry).setReader(live.reader()).build();
        return ReplaySceneImpl.builder().setTrackRegistry(registry).setCursor(cursor).build();
    }

    @Test
    void waitsForTheFirstPublishedFrame() {
        LiveScenePlayer player = livePlayer(Duration.ZERO);
        player.play();
        player.tick(Duration.ofSeconds(1));

        assertEquals(0L, live.reader().getTotalFrames());
        assertEquals(-1L, player.getCurrentFrame());

        advance(10);
        player.tick(Duration.ofSeconds(1));

        assertEquals(player.getLiveTargetFrame(), player.getCurrentFrame());
    }

    @Test
    void targetsTheHeadMinusTheDelay() {
        advance(60);
        LiveScenePlayer player = livePlayer(Duration.ofSeconds(1));

        long total = live.reader().getTotalFrames();
        assertEquals(Math.max(0, total - 1 - BASE_FPS), player.getLiveTargetFrame());
        assertEquals(Duration.ofSeconds(1), player.getLiveDelay());
    }

    @Test
    void tracksTheHeadWithZeroDelay() {
        advance(40);
        LiveScenePlayer player = livePlayer(Duration.ZERO);

        assertEquals(live.reader().getTotalFrames() - 1, player.getLiveTargetFrame());
    }

    @Test
    void neverTicksPastTheDelayedTarget() {
        advance(60);
        LiveScenePlayer player = livePlayer(Duration.ofSeconds(1));
        player.play();

        player.tick(Duration.ofSeconds(10));

        assertEquals(player.getLiveTargetFrame(), player.getCurrentFrame());
        assertEquals(PlaybackState.PLAYING, player.getState());
    }

    @Test
    void clampsSeeksToTheDelayedTarget() {
        advance(60);
        LiveScenePlayer player = livePlayer(Duration.ofSeconds(1));

        player.seek(live.reader().getTotalFrames() - 1);

        assertEquals(player.getLiveTargetFrame(), player.getCurrentFrame());
    }

    @Test
    void clampsSeekToProgressToTheDelayedTarget() {
        advance(60);
        LiveScenePlayer player = livePlayer(Duration.ofSeconds(1));

        player.seekToProgress(1.0);

        assertEquals(player.getLiveTargetFrame(), player.getCurrentFrame());
    }

    @Test
    void advancesAsTheHeadGrows() {
        advance(40);
        LiveScenePlayer player = livePlayer(Duration.ZERO);
        player.play();
        player.tick(Duration.ofSeconds(5));

        long before = player.getCurrentFrame();
        assertEquals(player.getLiveTargetFrame(), before);

        advance(20);
        player.tick(Duration.ofSeconds(5));

        assertTrue(player.getCurrentFrame() > before);
        assertEquals(player.getLiveTargetFrame(), player.getCurrentFrame());
    }

    @Test
    void staysPlayingAtTheHeadWhileTheReelIsIncomplete() {
        advance(40);
        LiveScenePlayer player = livePlayer(Duration.ZERO);
        player.play();

        for (int i = 0; i < 5; i++) {
            player.tick(Duration.ofSeconds(1));
        }

        assertFalse(live.reader().isComplete());
        assertEquals(PlaybackState.PLAYING, player.getState());
    }

    @Test
    void endsOnceTheReelIsCompleteAndFullyPlayed() throws IOException {
        advance(40);
        LiveScenePlayer player = livePlayer(Duration.ZERO);
        player.play();

        recorder.close();
        player.tick(Duration.ofSeconds(10));

        assertTrue(live.reader().isComplete());
        assertEquals(PlaybackState.ENDED, player.getState());
    }

    @Test
    void playsOutTheDelayedTailOnceTheReelIsComplete() throws IOException {
        advance(60);
        LiveScenePlayer player = livePlayer(Duration.ofSeconds(1));
        player.play();
        player.tick(Duration.ofSeconds(10));
        assertEquals(PlaybackState.PLAYING, player.getState());

        recorder.close();
        player.tick(Duration.ofSeconds(10));

        assertEquals(live.reader().getTotalFrames() - 1, player.getCurrentFrame());
        assertEquals(PlaybackState.ENDED, player.getState());
    }

    @Test
    void keepsTheDelayWhilePlayingOutTheTail() throws IOException {
        advance(100);
        LiveScenePlayer player = livePlayer(Duration.ofSeconds(2));
        player.play();
        player.tick(Duration.ofSeconds(10));

        recorder.close();
        long last = live.reader().getTotalFrames() - 1;
        player.tick(Duration.ofSeconds(1));

        assertTrue(player.getCurrentFrame() < last - BASE_FPS / 2);
        assertEquals(PlaybackState.PLAYING, player.getState());

        player.tick(Duration.ofSeconds(2));

        assertEquals(last, player.getCurrentFrame());
        assertEquals(PlaybackState.ENDED, player.getState());
    }

    @Test
    void rejectsControlsThatHaveNoLiveMeaning() {
        advance(40);
        LiveScenePlayer player = livePlayer(Duration.ZERO);

        assertThrows(IllegalStateException.class, player::stop);
        assertThrows(IllegalStateException.class, player::restart);
        assertThrows(IllegalStateException.class, () -> player.setSpeed(-1.0));
        assertThrows(IllegalStateException.class, () -> player.setLooping(true));
    }

    @Test
    void rejectsNegativeDelays() {
        advance(20);
        LiveScenePlayer player = livePlayer(Duration.ZERO);

        assertThrows(IllegalArgumentException.class, () -> player.setLiveDelay(Duration.ofSeconds(-1)));
    }

    @Test
    void reportsAnUndefinedProgressWhileFollowingLive() {
        advance(40);
        LiveScenePlayer player = livePlayer(Duration.ofSeconds(1));

        assertTrue(Double.isNaN(player.getProgress()));
    }

    @Test
    void allowsTheDelayToChangeAtRuntime() {
        advance(60);
        LiveScenePlayer player = livePlayer(Duration.ofSeconds(2));
        long narrow = player.getLiveTargetFrame();

        player.setLiveDelay(Duration.ZERO);

        assertTrue(player.getLiveTargetFrame() > narrow);
    }

    @Test
    void aFixedPlayerKeepsItsExistingBehaviour() {
        advance(40);
        ScenePlayer player = ScenePlayer.builder().setScene(scene()).setBaseFps(BASE_FPS).build();

        assertFalse(player instanceof LiveScenePlayer);
        player.setLooping(true);
        assertTrue(player.isLooping());
        player.setSpeed(-1.0);
        assertEquals(-1.0, player.getSpeed());
    }

    @Test
    void rejectsIncompatibleSettingsAtBuildTime() {
        advance(20);

        assertThrows(IllegalStateException.class, () -> ScenePlayer.builder()
            .setScene(scene()).setBaseFps(BASE_FPS).setLooping(true).buildLive());
        assertThrows(IllegalStateException.class, () -> ScenePlayer.builder()
            .setScene(scene()).setBaseFps(BASE_FPS).setSpeed(-1.0).buildLive());
        assertThrows(IllegalStateException.class, () -> ScenePlayer.builder()
            .setScene(scene()).setBaseFps(BASE_FPS).setLiveDelay(Duration.ofSeconds(-1)).buildLive());
    }

}
