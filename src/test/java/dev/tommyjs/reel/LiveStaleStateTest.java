package dev.tommyjs.reel;

import dev.tommyjs.reel.TestFixtures.IntDelta;
import dev.tommyjs.reel.TestFixtures.IntState;
import dev.tommyjs.reel.recorder.EntityRecorder;
import dev.tommyjs.reel.recorder.ReelRecorder;
import dev.tommyjs.reel.replay.ReplayCursor;
import dev.tommyjs.reel.replay.update.EntityUpdate;
import dev.tommyjs.reel.replay.update.TrackUpdate;
import dev.tommyjs.reel.storage.live.LocalLiveReel;
import dev.tommyjs.reel.storage.writer.LayerStrategy;
import dev.tommyjs.reel.track.TrackRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static dev.tommyjs.reel.TestFixtures.PLAYER;
import static dev.tommyjs.reel.TestFixtures.POSITION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LiveStaleStateTest {

    private static final LayerStrategy STRATEGY = new LayerStrategy(new long[]{1, 4, 16});

    @Test
    void resolvesWriteOnceTracksBeforeTheFirstSegmentSeals() throws IOException {
        TrackRegistry registry = TestFixtures.registry();
        Path dir = Files.createTempDirectory("live-stale");
        Map<Long, Integer> latest = new HashMap<>();

        try (LocalLiveReel live = new LocalLiveReel(STRATEGY)) {
            try (ReelRecorder recorder = ReelRecorder.builder()
                     .setTrackRegistry(registry)
                     .setSegmentLength(1200)
                     .setAnchorInterval(14400)
                     .setWriter(w -> w.setFile(dir.resolve("live.reel").toFile())
                         .setLayerStrategy(STRATEGY)
                         .addListener(live.publisher()))
                     .build();
                 ReplayCursor cursor = ReplayCursor.builder()
                     .setTrackRegistry(registry)
                     .setReader(live.reader())
                     .build()) {

                assertThrows(IllegalArgumentException.class, () -> cursor.seek(0));
                cursor.addListener(update -> {
                    for (EntityUpdate entity : update.getEntities()) {
                        for (TrackUpdate<?, ?> step : entity.getUpdatesFor(POSITION)) {
                            if (step instanceof TrackUpdate.Snapshot<?, ?> snapshot) {
                                latest.put(entity.getEntityId(), ((IntState) snapshot.state()).value());
                            } else if (step instanceof TrackUpdate.Delta<?, ?> delta) {
                                latest.merge(entity.getEntityId(), ((IntDelta) delta.delta()).step(), Integer::sum);
                            }
                        }
                    }
                });

                EntityRecorder stationary = recorder.createEntity(PLAYER);
                stationary.recordState(POSITION, new IntState(777));

                EntityRecorder mover = recorder.createEntity(PLAYER);
                mover.recordState(POSITION, new IntState(0));

                for (int i = 0; i < 150; i++) {
                    recorder.advanceFrame();
                    mover.recordDelta(POSITION, new IntDelta(1));
                    live.flush();
                    if (i == 0) {
                        cursor.seek(live.reader().getHeadFrame());
                    }
                }

                assertEquals(0, live.publisher().headState().segments().segments().size(),
                    "no segment should have sealed yet");

                cursor.seek(114);

                assertEquals(777, latest.get(stationary.entityId()),
                    "a track written once at frame 0 must resolve on a live reader");
                assertEquals(114, latest.get(mover.entityId()));
            }
        }
    }

}
