package dev.tommyjs.reel;

import dev.tommyjs.reel.codec.*;
import dev.tommyjs.reel.storage.ReelFileStorage;
import dev.tommyjs.reel.storage.ReelReader;
import dev.tommyjs.reel.storage.model.*;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReelRecorderTest {

    static final TrackHandle<String, String> POS = TrackHandle.of("position", String.class, String.class);
    static final TrackHandle<String, String> HEALTH = TrackHandle.of("health", String.class, String.class);

    static final EntityTypeHandle<byte[]> PLAYER = EntityTypeHandle.of("player", byte[].class);
    static final EntityTypeHandle<byte[]> MOB = EntityTypeHandle.of("mob", byte[].class);

    @TempDir
    File tempDir;

    private TrackCodecRegistry singleTrackRegistry() {
        return TrackCodecRegistry.builder()
                .register(POS, new StringCodec())
                .registerEntityType(PLAYER, new PassthroughEntityCodec())
                .registerEntityType(MOB, new PassthroughEntityCodec())
                .build();
    }

    private TrackCodecRegistry multiTrackRegistry() {
        return TrackCodecRegistry.builder()
                .register(POS, new StringCodec())
                .register(HEALTH, new StringCodec())
                .registerEntityType(PLAYER, new PassthroughEntityCodec())
                .registerEntityType(MOB, new PassthroughEntityCodec())
                .build();
    }

    private EntityHandle spawn(ReelRecorder recorder, EntityTypeHandle<byte[]> type, byte[] metadata) {
        EntityHandle handle = recorder.createEntity(type, metadata);
        recorder.spawnEntity(handle);
        return handle;
    }

    @Test
    void createEntityAndSpawn() throws Exception {
        File file = new File(tempDir, "spawn.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            spawn(recorder, PLAYER, new byte[]{1, 2, 3});
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityDirectory dir = reader.readEntityDirectory();
            assertEquals(1, dir.getEntities().size());
            assertEquals("player", dir.getEntities().get(0).getEntityType());
            assertArrayEquals(new byte[]{1, 2, 3}, dir.getEntities().get(0).getMetadata());
        }
    }

    @Test
    void entityIdsAutoIncrement() throws Exception {
        File file = new File(tempDir, "autoid.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            EntityHandle e0 = spawn(recorder, PLAYER, new byte[0]);
            EntityHandle e1 = spawn(recorder, PLAYER, new byte[0]);
            EntityHandle e2 = spawn(recorder, MOB, new byte[0]);
            assertEquals(0, e0.entityId());
            assertEquals(1, e1.entityId());
            assertEquals(2, e2.entityId());
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            assertEquals(3, reader.readEntityDirectory().getEntities().size());
        }
    }

    @Test
    void advanceFrameIncrementsCurrentFrame() throws Exception {
        File file = new File(tempDir, "frame.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            assertEquals(0, recorder.currentFrame());
            recorder.advanceFrame();
            assertEquals(1, recorder.currentFrame());
            recorder.advanceFrame();
            recorder.advanceFrame();
            assertEquals(3, recorder.currentFrame());
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            assertEquals(3, reader.getTotalFrames());
        }
    }

    @Test
    void recordSnapshotWritesSnapshot() throws Exception {
        File file = new File(tempDir, "snapshot.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            EntityHandle e = spawn(recorder, PLAYER, new byte[0]);
            e.recordSnapshot(POS, "origin");
            recorder.advanceFrame();
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityTrackIndex idx = reader.readTrackIndex(0);
            assertEquals(1, idx.getTracks().size());
            assertEquals(1, idx.getTracks().get(0).getSnapshots().size());
            assertEquals(0, idx.getTracks().get(0).getSnapshots().get(0).frame());
            assertArrayEquals("origin".getBytes(), reader.readTrackData(idx.getTracks().get(0).getSnapshots().get(0).refId()));
        }
    }

    @Test
    void recordDeltaWithoutSnapshotThrows() throws Exception {
        File file = new File(tempDir, "noinit.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            EntityHandle e = spawn(recorder, PLAYER, new byte[0]);
            assertThrows(IllegalStateException.class, () -> e.recordDelta(POS, "move"));
        }
    }

    @Test
    void recordDeltaWritesBaseLayerSpan() throws Exception {
        File file = new File(tempDir, "delta.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            EntityHandle e = spawn(recorder, PLAYER, new byte[0]);
            e.recordSnapshot(POS, "start");
            recorder.advanceFrame();
            e.recordDelta(POS, "move");
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityTrackIndex idx = reader.readTrackIndex(0);
            var track = idx.getTracks().get(0);
            assertEquals(1, track.getSnapshots().size());
            assertEquals(1, track.getSpans().size());

            TrackSpan span = track.getSpans().get(0);
            assertEquals(0, span.referenceFrame());
            assertEquals(1, span.targetFrame());
            assertArrayEquals("move".getBytes(), reader.readTrackData(span.refId()));
        }
    }

    @Test
    void multipleDeltas() throws Exception {
        File file = new File(tempDir, "multidelta.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            EntityHandle e = spawn(recorder, PLAYER, new byte[0]);
            e.recordSnapshot(POS, "a");
            recorder.advanceFrame();
            e.recordDelta(POS, "b");
            recorder.advanceFrame();
            e.recordDelta(POS, "c");
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            var track = reader.readTrackIndex(0).getTracks().get(0);
            assertEquals(2, track.getSpans().size());
            assertEquals(0, track.getSpans().get(0).referenceFrame());
            assertEquals(1, track.getSpans().get(0).targetFrame());
            assertEquals(1, track.getSpans().get(1).referenceFrame());
            assertEquals(2, track.getSpans().get(1).targetFrame());
        }
    }

    @Test
    void despawnFlushesAndWritesDespawn() throws Exception {
        File file = new File(tempDir, "despawn.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            EntityHandle e = spawn(recorder, PLAYER, new byte[0]);
            e.recordSnapshot(POS, "start");
            recorder.advanceFrame();
            e.recordDelta(POS, "move");
            recorder.advanceFrame();
            e.despawn();
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            var entity = reader.readEntityDirectory().getEntities().get(0);
            assertEquals(1, entity.getSpawns().size());
            assertEquals(0, entity.getSpawns().get(0).getSpawnFrame());
            assertEquals(2, entity.getSpawns().get(0).getDespawnFrame());

            var track = reader.readTrackIndex(0).getTracks().get(0);
            assertEquals(1, track.getSpans().size());
        }
    }

    @Test
    void recordOnDespawnedEntityThrows() throws Exception {
        File file = new File(tempDir, "despawned_record.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            EntityHandle e = spawn(recorder, PLAYER, new byte[0]);
            e.recordSnapshot(POS, "start");
            e.despawn();
            assertThrows(IllegalStateException.class, () -> e.recordDelta(POS, "move"));
            assertThrows(IllegalStateException.class, () -> e.recordSnapshot(POS, "new"));
        }
    }

    @Test
    void multipleTracks() throws Exception {
        File file = new File(tempDir, "multitracks.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), multiTrackRegistry())) {
            EntityHandle e = spawn(recorder, PLAYER, new byte[0]);
            e.recordSnapshot(POS, "origin");
            e.recordSnapshot(HEALTH, "100");
            recorder.advanceFrame();
            e.recordDelta(POS, "move");
            e.recordDelta(HEALTH, "-10");
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            var tracks = reader.readTrackIndex(0).getTracks();
            assertEquals(2, tracks.size());
            for (var track : tracks) {
                assertEquals(1, track.getSnapshots().size());
                assertEquals(1, track.getSpans().size());
            }
        }
    }

    @Test
    void multipleEntities() throws Exception {
        File file = new File(tempDir, "multientities.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            EntityHandle e0 = spawn(recorder, PLAYER, new byte[0]);
            EntityHandle e1 = spawn(recorder, PLAYER, new byte[0]);
            e0.recordSnapshot(POS, "a");
            e1.recordSnapshot(POS, "b");
            recorder.advanceFrame();
            e0.recordDelta(POS, "da");
            e1.recordDelta(POS, "db");
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            assertEquals(2, reader.readEntityDirectory().getEntities().size());
            for (int i = 0; i < 2; i++) {
                var track = reader.readTrackIndex(i).getTracks().get(0);
                assertEquals(1, track.getSnapshots().size());
                assertEquals(1, track.getSpans().size());
            }
        }
    }

    @Test
    void forceSnapshotFlushesLayers() throws Exception {
        File file = new File(tempDir, "forcesnap.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            EntityHandle e = spawn(recorder, PLAYER, new byte[0]);
            e.recordSnapshot(POS, "a");
            recorder.advanceFrame();
            e.recordDelta(POS, "b");
            recorder.advanceFrame();
            e.recordSnapshot(POS);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            var track = reader.readTrackIndex(0).getTracks().get(0);
            assertEquals(2, track.getSnapshots().size());
            assertEquals(1, track.getSpans().size());

            assertEquals(0, track.getSnapshots().get(0).frame());
            assertEquals(2, track.getSnapshots().get(1).frame());
            assertArrayEquals("ab".getBytes(), reader.readTrackData(track.getSnapshots().get(1).refId()));
        }
    }

    @Test
    void noArgRecordSnapshotWithoutInitThrows() throws Exception {
        File file = new File(tempDir, "noinit_snap.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            EntityHandle e = spawn(recorder, PLAYER, new byte[0]);
            assertThrows(IllegalStateException.class, () -> e.recordSnapshot(POS));
        }
    }

    @Test
    void unregisteredTrackThrows() throws Exception {
        File file = new File(tempDir, "unreg.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            EntityHandle e = spawn(recorder, PLAYER, new byte[0]);
            assertThrows(IllegalStateException.class, () -> e.recordSnapshot(HEALTH, "100"));
        }
    }

    @Test
    void closeIsIdempotent() throws Exception {
        File file = new File(tempDir, "doubleclose.reel");
        ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry());
        spawn(recorder, PLAYER, new byte[0]);
        recorder.close();
        assertDoesNotThrow(recorder::close);
    }

    @Test
    void operationsAfterCloseThrow() throws Exception {
        File file = new File(tempDir, "afterclose.reel");
        ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry());
        recorder.close();
        assertThrows(IllegalStateException.class, () -> recorder.createEntity(PLAYER, new byte[0]));
        assertThrows(IllegalStateException.class, recorder::advanceFrame);
    }

    @Test
    void condensationLayerEmitsOnThreshold() throws Exception {
        File file = new File(tempDir, "condense.reel");

        LayerStrategy strategy = LayerStrategy.builder()
                .layer(3)
                .build();
        TrackHandle<String, String> track = TrackHandle.of("t", String.class, String.class);
        TrackCodecRegistry registry = TrackCodecRegistry.builder()
                .register(track, new StringCodec(strategy))
                .registerEntityType(PLAYER, new PassthroughEntityCodec())
                .build();

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), registry)) {
            EntityHandle e = spawn(recorder, PLAYER, new byte[0]);
            e.recordSnapshot(track, "");
            recorder.advanceFrame();
            e.recordDelta(track, "a");
            recorder.advanceFrame();
            e.recordDelta(track, "b");
            recorder.advanceFrame();
            e.recordDelta(track, "c");
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            var t = reader.readTrackIndex(0).getTracks().get(0);
            assertEquals(4, t.getSpans().size());
        }
    }

    @Test
    void condensationProducesCondensedSpan() throws Exception {
        File file = new File(tempDir, "condensed_span.reel");

        LayerStrategy strategy = LayerStrategy.builder()
                .layer(3)
                .build();
        TrackHandle<String, String> track = TrackHandle.of("t", String.class, String.class);
        TrackCodecRegistry registry = TrackCodecRegistry.builder()
                .register(track, new StringCodec(strategy))
                .registerEntityType(PLAYER, new PassthroughEntityCodec())
                .build();

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), registry)) {
            EntityHandle e = spawn(recorder, PLAYER, new byte[0]);
            e.recordSnapshot(track, "");
            recorder.advanceFrame();
            e.recordDelta(track, "x");
            recorder.advanceFrame();
            e.recordDelta(track, "y");
            recorder.advanceFrame();
            e.recordDelta(track, "z");
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            var t = reader.readTrackIndex(0).getTracks().get(0);
            assertTrue(t.getSpans().size() >= 4);

            boolean foundCondensed = false;
            for (var span : t.getSpans()) {
                byte[] data = reader.readTrackData(span.refId());
                if ("xyz".equals(new String(data))) {
                    foundCondensed = true;
                    assertEquals(0, span.referenceFrame());
                    assertEquals(3, span.targetFrame());
                }
            }
            assertTrue(foundCondensed);
        }
    }

    @Test
    void snapshotLayerEmitsOnThreshold() throws Exception {
        File file = new File(tempDir, "autosnap.reel");

        LayerStrategy strategy = LayerStrategy.builder()
                .snapshots(3)
                .build();
        TrackHandle<String, String> track = TrackHandle.of("t", String.class, String.class);
        TrackCodecRegistry registry = TrackCodecRegistry.builder()
                .register(track, new StringCodec(strategy))
                .registerEntityType(PLAYER, new PassthroughEntityCodec())
                .build();

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), registry)) {
            EntityHandle e = spawn(recorder, PLAYER, new byte[0]);
            e.recordSnapshot(track, "");
            recorder.advanceFrame();
            e.recordDelta(track, "a");
            recorder.advanceFrame();
            e.recordDelta(track, "b");
            recorder.advanceFrame();
            e.recordDelta(track, "c");
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            var t = reader.readTrackIndex(0).getTracks().get(0);
            assertEquals(2, t.getSnapshots().size());
            assertEquals(0, t.getSnapshots().get(0).frame());
            assertEquals(3, t.getSnapshots().get(1).frame());
            assertArrayEquals("abc".getBytes(), reader.readTrackData(t.getSnapshots().get(1).refId()));
        }
    }

    @Test
    void spawnFrameMatchesCurrentFrame() throws Exception {
        File file = new File(tempDir, "spawnframe.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            recorder.advanceFrame();
            recorder.advanceFrame();
            spawn(recorder, PLAYER, new byte[0]);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            assertEquals(2, reader.readEntityDirectory().getEntities().get(0).getSpawns().get(0).getSpawnFrame());
        }
    }

    @Test
    void entityTypeRegisteredOnce() throws Exception {
        File file = new File(tempDir, "typereuse.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            spawn(recorder, PLAYER, new byte[0]);
            spawn(recorder, PLAYER, new byte[0]);
            spawn(recorder, MOB, new byte[0]);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            var types = reader.readEntityDirectory().getEntityTypes();
            assertEquals(2, types.size());
            assertTrue(types.contains("player"));
            assertTrue(types.contains("mob"));
        }
    }

    @Test
    void trackDirectoryMatchesRegistry() throws Exception {
        File file = new File(tempDir, "trackdir.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), multiTrackRegistry())) {
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            var tracks = reader.readTrackDirectory().getTracks();
            assertEquals(2, tracks.size());
            assertEquals("position", tracks.get(0).getTrackId());
            assertEquals("health", tracks.get(1).getTrackId());
        }
    }

    @Test
    void finishFlushesRemainingDeltas() throws Exception {
        File file = new File(tempDir, "finishflush.reel");

        LayerStrategy strategy = LayerStrategy.builder()
                .layer(100)
                .build();
        TrackHandle<String, String> track = TrackHandle.of("t", String.class, String.class);
        TrackCodecRegistry registry = TrackCodecRegistry.builder()
                .register(track, new StringCodec(strategy))
                .registerEntityType(PLAYER, new PassthroughEntityCodec())
                .build();

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), registry)) {
            EntityHandle e = spawn(recorder, PLAYER, new byte[0]);
            e.recordSnapshot(track, "");
            recorder.advanceFrame();
            e.recordDelta(track, "a");
            recorder.advanceFrame();
            e.recordDelta(track, "b");
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            var t = reader.readTrackIndex(0).getTracks().get(0);
            assertEquals(3, t.getSpans().size());
        }
    }

    @Test
    void respawnAfterDespawn() throws Exception {
        File file = new File(tempDir, "respawn.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            EntityHandle e = spawn(recorder, PLAYER, new byte[]{1});
            e.recordSnapshot(POS, "a");
            recorder.advanceFrame();
            e.despawn();
            recorder.advanceFrame();
            recorder.spawnEntity(e);
            e.recordSnapshot(POS, "b");
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            var entity = reader.readEntityDirectory().getEntities().get(0);
            assertEquals(2, entity.getSpawns().size());
            assertArrayEquals(new byte[]{1}, entity.getMetadata());
            assertEquals(0, entity.getSpawns().get(0).getSpawnFrame());
            assertEquals(1, entity.getSpawns().get(0).getDespawnFrame());
            assertEquals(2, entity.getSpawns().get(1).getSpawnFrame());
        }
    }

    @Test
    void spawnEntityWhileAliveThrows() throws Exception {
        File file = new File(tempDir, "respawn_alive.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            EntityHandle e = spawn(recorder, PLAYER, new byte[0]);
            assertThrows(IllegalStateException.class, () -> recorder.spawnEntity(e));
        }
    }

    @Test
    void unregisteredEntityTypeThrows() throws Exception {
        File file = new File(tempDir, "unreg_entity.reel");
        EntityTypeHandle<byte[]> UNKNOWN = EntityTypeHandle.of("unknown", byte[].class);

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            assertThrows(IllegalStateException.class, () -> recorder.createEntity(UNKNOWN, new byte[0]));
        }
    }

    @Test
    void createEntityDoesNotSpawn() throws Exception {
        File file = new File(tempDir, "nospawn.reel");

        try (ReelRecorder recorder = ReelRecorder.create(ReelFileStorage.openWriter(file), singleTrackRegistry())) {
            EntityHandle e = recorder.createEntity(PLAYER, new byte[0]);
            assertThrows(IllegalStateException.class, () -> e.recordSnapshot(POS, "a"));
            assertThrows(IllegalStateException.class, () -> e.recordDelta(POS, "b"));
            assertThrows(IllegalStateException.class, e::despawn);
        }
    }

    // --- test fixtures ---

    static class StringCodec extends AbstractTrackCodec<String, String> {

        StringCodec() {
            super(LayerStrategy.builder().build());
        }

        StringCodec(LayerStrategy strategy) {
            super(strategy);
        }

        @Override
        public @NotNull String applyDelta(@NotNull String state, @NotNull String delta) {
            return state + delta;
        }

        @Override
        public @NotNull String cloneState(@NotNull String state) {
            return state;
        }

        @Override
        public @NotNull String condense(@NotNull String referenceState, @NotNull List<String> deltas) {
            return String.join("", deltas);
        }

        @Override
        public int spanCost(@NotNull String delta) {
            return 1;
        }

        @Override
        public int snapshotCost(@NotNull String state) {
            return 1;
        }

        @Override
        public byte @NotNull [] encodeSnapshot(@NotNull String state) {
            return state.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public @NotNull String decodeSnapshot(byte @NotNull [] data) {
            return new String(data, StandardCharsets.UTF_8);
        }

        @Override
        public byte @NotNull [] encodeSpan(@NotNull String delta) {
            return delta.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public @NotNull String decodeSpan(byte @NotNull [] data) {
            return new String(data, StandardCharsets.UTF_8);
        }

    }

    static class PassthroughEntityCodec implements EntityMetadataCodec<byte[]> {

        @Override
        public byte @NotNull [] encode(byte @NotNull [] data) {
            return data.clone();
        }

        @Override
        public byte @NotNull [] decode(byte @NotNull [] data) {
            return data.clone();
        }

    }

}
