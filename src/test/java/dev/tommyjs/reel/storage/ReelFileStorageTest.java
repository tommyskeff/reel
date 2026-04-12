package dev.tommyjs.reel.storage;

import dev.tommyjs.reel.storage.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ReelFileStorageTest {

    @TempDir
    File tempDir;

    @Test
    void roundTripBasic() throws Exception {
        File file = new File(tempDir, "test.reel");

        byte[] spawnData0 = "player-uuid-123".getBytes(StandardCharsets.UTF_8);
        byte[] spawnData1 = "item-uuid-456".getBytes(StandardCharsets.UTF_8);
        byte[] spanData = {1, 2, 3, 4, 5};
        byte[] snapshotData = {10, 20, 30};

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);
            writer.registerTrack("t_2", 3);

            writer.writeEntityCreate(0, "e_10", spawnData0);
            writer.writeEntitySpawn(0, 0);
            writer.writeEntityCreate(1, "e_20", spawnData1);
            writer.writeEntitySpawn(1, 5);

            writer.writeTrackSpan(0, "t_1", 0, 10,  1,spanData);
            writer.writeTrackSnapshot(0, "t_1", 5,  1,snapshotData);
            writer.writeTrackSpan(1, "t_2", 5, 15,  1,new byte[]{99});

            writer.writeEntityDespawn(0, 100);

            writer.finish(200);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityDirectory entityDir = reader.readEntityDirectory();
            List<? extends EntityEntry> entities = entityDir.getEntities();
            assertEquals(2, entities.size());

            EntityEntry e0 = entities.get(0);
            assertEquals(0, e0.getEntityId());
            assertEquals("e_10", e0.getEntityType());
            assertEquals(1, e0.getSpawns().size());
            assertArrayEquals(spawnData0, e0.getMetadata());
            assertEquals(0, e0.getSpawns().get(0).getSpawnFrame());
            assertEquals(100, e0.getSpawns().get(0).getDespawnFrame());

            EntityEntry e1 = entities.get(1);
            assertEquals(1, e1.getEntityId());
            assertEquals("e_20", e1.getEntityType());
            assertEquals(1, e1.getSpawns().size());
            assertArrayEquals(spawnData1, e1.getMetadata());
            assertEquals(5, e1.getSpawns().get(0).getSpawnFrame());
            assertEquals(-1, e1.getSpawns().get(0).getDespawnFrame());

            TrackDirectory trackDir = reader.readTrackDirectory();
            List<? extends TrackEntry> tracks = trackDir.getTracks();
            assertEquals(2, tracks.size());
            assertEquals("t_1", tracks.get(0).getTrackId());
            assertEquals(1, tracks.get(0).getCodecVersion());
            assertEquals("t_2", tracks.get(1).getTrackId());
            assertEquals(3, tracks.get(1).getCodecVersion());

            EntityTrackIndex idx0 = reader.readTrackIndex(0);
            assertEquals(1, idx0.getTracks().size());
            EntityTrackEntry track0 = idx0.getTracks().get(0);
            assertEquals("t_1", track0.getTrackId());
            assertEquals(1, track0.getSpans().size());
            assertEquals(1, track0.getSnapshots().size());

            TrackSpan span = track0.getSpans().get(0);
            assertEquals(0, span.referenceFrame());
            assertEquals(10, span.targetFrame());

            TrackSnapshot snapshot = track0.getSnapshots().get(0);
            assertEquals(5, snapshot.frame());

            assertArrayEquals(spanData, reader.readTrackData(span.refId()));
            assertArrayEquals(snapshotData, reader.readTrackData(snapshot.refId()));

            EntityTrackIndex idx1 = reader.readTrackIndex(1);
            assertEquals(1, idx1.getTracks().size());
            EntityTrackEntry track1 = idx1.getTracks().get(0);
            assertEquals("t_2", track1.getTrackId());
            assertEquals(1, track1.getSpans().size());
            assertArrayEquals(new byte[]{99}, reader.readTrackData(track1.getSpans().get(0).refId()));
        }
    }

    @Test
    void multipleSpawnCycles() throws Exception {
        File file = new File(tempDir, "respawn.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.writeEntityCreate(0, "e_1", new byte[]{1});
            writer.writeEntitySpawn(0, 0);
            writer.writeEntityDespawn(0, 50);
            writer.writeEntitySpawn(0, 100);
            writer.finish(200);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityDirectory dir = reader.readEntityDirectory();
            EntityEntry e = dir.getEntities().get(0);
            assertEquals(2, e.getSpawns().size());

            assertEquals(0, e.getSpawns().get(0).getSpawnFrame());
            assertEquals(50, e.getSpawns().get(0).getDespawnFrame());

            assertEquals(100, e.getSpawns().get(1).getSpawnFrame());
            assertEquals(-1, e.getSpawns().get(1).getDespawnFrame());
            assertArrayEquals(new byte[]{1}, e.getMetadata());
        }
    }

    @Test
    void entityWithNoTracks() throws Exception {
        File file = new File(tempDir, "notracks.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.writeEntityCreate(0, "e_1", new byte[]{42});
            writer.writeEntitySpawn(0, 0);
            writer.finish(50);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityDirectory dir = reader.readEntityDirectory();
            assertEquals(1, dir.getEntities().size());

            EntityTrackIndex idx = reader.readTrackIndex(0);
            assertEquals(0, idx.getTracks().size());
        }
    }

    @Test
    void emptyRecording() throws Exception {
        File file = new File(tempDir, "empty.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.finish(0);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            assertEquals(0, reader.readEntityDirectory().getEntities().size());
            assertEquals(0, reader.readTrackDirectory().getTracks().size());
        }
    }

    @Test
    void entityWithEmptyMetadata() throws Exception {
        File file = new File(tempDir, "emptyspawn.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.writeEntityCreate(0, "e_5", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityEntry e = reader.readEntityDirectory().getEntities().get(0);
            assertArrayEquals(new byte[0], e.getMetadata());
        }
    }

    @Test
    void nonConsecutiveEntityIds() throws Exception {
        File file = new File(tempDir, "nonconsec.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.writeEntityCreate(10, "e_1", new byte[]{1});
            writer.writeEntitySpawn(10, 0);
            writer.writeEntityCreate(50, "e_2", new byte[]{2});
            writer.writeEntitySpawn(50, 5);
            writer.writeEntityCreate(100, "e_3", new byte[]{3});
            writer.writeEntitySpawn(100, 10);
            writer.finish(20);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            List<? extends EntityEntry> entities = reader.readEntityDirectory().getEntities();
            assertEquals(3, entities.size());
            assertEquals(10, entities.get(0).getEntityId());
            assertEquals(50, entities.get(1).getEntityId());
            assertEquals(100, entities.get(2).getEntityId());

            EntityTrackIndex idx = reader.readTrackIndex(50);
            assertEquals(0, idx.getTracks().size());
        }
    }

    @Test
    void multipleSpawnCyclesWithTracks() throws Exception {
        File file = new File(tempDir, "respawntracks.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);

            writer.writeEntityCreate(0, "e_1", new byte[]{1});
            writer.writeEntitySpawn(0, 0);
            writer.writeTrackSnapshot(0, "t_1", 5,  1,new byte[]{10});
            writer.writeEntityDespawn(0, 50);

            writer.writeEntitySpawn(0, 100);
            writer.writeTrackSnapshot(0, "t_1", 110,  1,new byte[]{20});
            writer.finish(200);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityEntry e = reader.readEntityDirectory().getEntities().get(0);
            assertEquals(2, e.getSpawns().size());

            EntityTrackIndex idx = reader.readTrackIndex(0);
            List<TrackSnapshot> snaps = idx.getTracks().get(0).getSnapshots();
            assertEquals(2, snaps.size());
            assertEquals(5, snaps.get(0).frame());
            assertEquals(110, snaps.get(1).frame());
            assertArrayEquals(new byte[]{10}, reader.readTrackData(snaps.get(0).refId()));
            assertArrayEquals(new byte[]{20}, reader.readTrackData(snaps.get(1).refId()));
        }
    }

    @Test
    void trackDataAfterDespawnThrows() throws Exception {
        File file = new File(tempDir, "postdespawn.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            writer.writeEntityDespawn(0, 10);
            assertThrows(IllegalStateException.class,
                    () -> writer.writeTrackSnapshot(0, "t_1", 15,  1,new byte[]{42}));
        }
    }

    @Test
    void largeDataForcesMultipleBlocks() throws Exception {
        File file = new File(tempDir, "large.reel");

        byte[] bigData = new byte[40_000];
        for (int i = 0; i < bigData.length; i++) bigData[i] = (byte) (i & 0xFF);

        byte[] bigData2 = new byte[40_000];
        for (int i = 0; i < bigData2.length; i++) bigData2[i] = (byte) ((i + 1) & 0xFF);

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);

            writer.writeTrackSpan(0, "t_1", 0, 10,  1,bigData);
            writer.writeTrackSpan(0, "t_1", 10, 20,  1,bigData2);

            writer.finish(100);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityTrackIndex idx = reader.readTrackIndex(0);
            List<TrackSpan> spans = idx.getTracks().get(0).getSpans();
            assertEquals(2, spans.size());

            assertArrayEquals(bigData, reader.readTrackData(spans.get(0).refId()));
            assertArrayEquals(bigData2, reader.readTrackData(spans.get(1).refId()));
        }
    }

    @Test
    void manySmallWrites() throws Exception {
        File file = new File(tempDir, "manysmall.reel");

        int count = 500;
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);

            for (int i = 0; i < count; i++) {
                writer.writeTrackSnapshot(0, "t_1", i,  1,new byte[]{(byte) i, (byte) (i >> 8)});
            }
            writer.finish(count);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityTrackIndex idx = reader.readTrackIndex(0);
            List<TrackSnapshot> snapshots = idx.getTracks().get(0).getSnapshots();
            assertEquals(count, snapshots.size());

            for (int i = 0; i < count; i++) {
                assertEquals(i, snapshots.get(i).frame());
                byte[] data = reader.readTrackData(snapshots.get(i).refId());
                assertEquals((byte) i, data[0]);
                assertEquals((byte) (i >> 8), data[1]);
            }
        }
    }

    @Test
    void exactBlockThreshold() throws Exception {
        File file = new File(tempDir, "exactblock.reel");

        byte[] data = new byte[ReelFormat.BLOCK_FLUSH_THRESHOLD];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            writer.writeTrackSnapshot(0, "t_1", 0,  1,data);
            writer.writeTrackSnapshot(0, "t_1", 1,  1,new byte[]{42});
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityTrackIndex idx = reader.readTrackIndex(0);
            List<TrackSnapshot> snapshots = idx.getTracks().get(0).getSnapshots();
            assertEquals(2, snapshots.size());
            assertArrayEquals(data, reader.readTrackData(snapshots.get(0).refId()));
            assertArrayEquals(new byte[]{42}, reader.readTrackData(snapshots.get(1).refId()));
        }
    }

    @Test
    void multipleTracksOnSameEntity() throws Exception {
        File file = new File(tempDir, "multitracks.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);
            writer.registerTrack("t_2", 1);
            writer.registerTrack("t_3", 2);

            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);

            writer.writeTrackSpan(0, "t_1", 0, 5,  1,new byte[]{10});
            writer.writeTrackSpan(0, "t_2", 0, 5,  1,new byte[]{20});
            writer.writeTrackSnapshot(0, "t_3", 3,  1,new byte[]{30});

            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityTrackIndex idx = reader.readTrackIndex(0);
            assertEquals(3, idx.getTracks().size());

            assertEquals("t_1", idx.getTracks().get(0).getTrackId());
            assertArrayEquals(new byte[]{10}, reader.readTrackData(idx.getTracks().get(0).getSpans().get(0).refId()));

            assertEquals("t_2", idx.getTracks().get(1).getTrackId());
            assertArrayEquals(new byte[]{20}, reader.readTrackData(idx.getTracks().get(1).getSpans().get(0).refId()));

            assertEquals("t_3", idx.getTracks().get(2).getTrackId());
            assertArrayEquals(new byte[]{30}, reader.readTrackData(idx.getTracks().get(2).getSnapshots().get(0).refId()));
        }
    }

    @Test
    void multipleEntitiesWithMultipleTracks() throws Exception {
        File file = new File(tempDir, "manyentitytracks.reel");

        int entityCount = 50;
        int trackCount = 5;

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            for (int t = 0; t < trackCount; t++) {
                writer.registerTrack("t_" + t, 1);
            }
            for (int i = 0; i < 3; i++) {
                writer.registerEntityType("e_" + i);
            }

            for (int e = 0; e < entityCount; e++) {
                writer.writeEntityCreate(e, "e_" + (e % 3), new byte[]{(byte) e});
                writer.writeEntitySpawn(e, 0);
                for (int t = 0; t < trackCount; t++) {
                    writer.writeTrackSnapshot(e, "t_" + t, e * 10 + t,  1,new byte[]{(byte) e, (byte) t});
                }
            }
            writer.finish(1000);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            assertEquals(entityCount, reader.readEntityDirectory().getEntities().size());
            assertEquals(trackCount, reader.readTrackDirectory().getTracks().size());

            for (int e = 0; e < entityCount; e++) {
                EntityTrackIndex idx = reader.readTrackIndex(e);
                assertEquals(trackCount, idx.getTracks().size());

                for (int t = 0; t < trackCount; t++) {
                    EntityTrackEntry track = idx.getTracks().get(t);
                    assertEquals(1, track.getSnapshots().size());
                    assertEquals(e * 10 + t, track.getSnapshots().get(0).frame());
                    byte[] data = reader.readTrackData(track.getSnapshots().get(0).refId());
                    assertArrayEquals(new byte[]{(byte) e, (byte) t}, data);
                }
            }
        }
    }

    @Test
    void trackDirectoryPreservesOrder() throws Exception {
        File file = new File(tempDir, "trackorder.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_5", 10);
            writer.registerTrack("t_3", 20);
            writer.registerTrack("t_8", 30);
            writer.finish(0);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            List<? extends TrackEntry> tracks = reader.readTrackDirectory().getTracks();
            assertEquals(3, tracks.size());
            assertEquals("t_5", tracks.get(0).getTrackId());
            assertEquals(10, tracks.get(0).getCodecVersion());
            assertEquals("t_3", tracks.get(1).getTrackId());
            assertEquals(20, tracks.get(1).getCodecVersion());
            assertEquals("t_8", tracks.get(2).getTrackId());
            assertEquals(30, tracks.get(2).getCodecVersion());
        }
    }

    @Test
    void duplicateTrackRegistrationKeepsFirst() throws Exception {
        File file = new File(tempDir, "duptrack.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 10);
            writer.registerTrack("t_1", 99); // should be ignored
            writer.finish(0);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            List<? extends TrackEntry> tracks = reader.readTrackDirectory().getTracks();
            assertEquals(1, tracks.size());
            assertEquals(10, tracks.get(0).getCodecVersion());
        }
    }

    @Test
    void trackDirectoryCachedAcrossCalls() throws Exception {
        File file = new File(tempDir, "trackdircache.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);
            writer.registerTrack("t_2", 2);
            writer.finish(0);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            TrackDirectory dir1 = reader.readTrackDirectory();
            TrackDirectory dir2 = reader.readTrackDirectory();
            assertEquals(dir1.getTracks().size(), dir2.getTracks().size());
            assertSame(dir1.getTracks(), dir2.getTracks());
        }
    }

    @Test
    void totalFramesRoundTrip() throws Exception {
        File file = new File(tempDir, "frames.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.finish(12345);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            assertEquals(12345, reader.getTotalFrames());
        }
    }

    @Test
    void totalFramesZero() throws Exception {
        File file = new File(tempDir, "zeroframes.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.finish(0);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            assertEquals(0, reader.getTotalFrames());
        }
    }

    @Test
    void totalFramesLargeValue() throws Exception {
        File file = new File(tempDir, "largeframes.reel");
        long frames = Long.MAX_VALUE;

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.finish(frames);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            assertEquals(frames, reader.getTotalFrames());
        }
    }

    @Test
    void despawnWithoutSpawnThrows() {
        assertThrows(IllegalStateException.class, () -> {
            File file = new File(tempDir, "bad.reel");
            try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
                writer.writeEntityDespawn(0, 10);
            }
        });
    }

    @Test
    void doubleDespawnThrows() {
        assertThrows(IllegalStateException.class, () -> {
            File file = new File(tempDir, "bad2.reel");
            try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
                writer.writeEntityCreate(0, "e_1", new byte[0]);
                writer.writeEntitySpawn(0, 0);
                writer.writeEntityDespawn(0, 10);
                writer.writeEntityDespawn(0, 20);
            }
        });
    }

    @Test
    void respawnWithoutDespawnThrows() {
        assertThrows(IllegalStateException.class, () -> {
            File file = new File(tempDir, "bad3.reel");
            try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
                writer.writeEntityCreate(0, "e_1", new byte[0]);
                writer.writeEntitySpawn(0, 0);
                writer.writeEntitySpawn(0, 10);
            }
        });
    }

    @Test
    void entityTypeMismatchThrows() {
        assertThrows(IllegalStateException.class, () -> {
            File file = new File(tempDir, "bad4.reel");
            try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntityCreate(0, "e_2", new byte[0]); // same entity ID, already created
            }
        });
    }

    @Test
    void writeAfterFinishThrows() throws Exception {
        File file = new File(tempDir, "afterfinish.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.finish(0);
            assertThrows(IllegalStateException.class, () -> writer.writeEntityCreate(0, "e_1", new byte[0]));
            assertThrows(IllegalStateException.class, () -> writer.writeEntitySpawn(0, 0));
            assertThrows(IllegalStateException.class, () -> writer.registerTrack("t_1", 1));
            assertThrows(IllegalStateException.class, () -> writer.writeTrackSpan(0, "t_1", 0, 1,  1,new byte[0]));
            assertThrows(IllegalStateException.class, () -> writer.writeTrackSnapshot(0, "t_1", 0,  1,new byte[0]));
            assertThrows(IllegalStateException.class, () -> writer.writeEntityDespawn(0, 10));
        }
    }

    @Test
    void doubleFinishIsIdempotent() throws Exception {
        File file = new File(tempDir, "doublefinish.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.writeEntityCreate(0, "e_1", new byte[]{1});
            writer.writeEntitySpawn(0, 0);
            writer.finish(10);
            writer.finish(10); // should not throw
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            assertEquals(1, reader.readEntityDirectory().getEntities().size());
        }
    }

    @Test
    void trackSpanForUnspawnedEntityThrows() throws Exception {
        File file = new File(tempDir, "unspawned_span.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);
            assertThrows(IllegalStateException.class,
                    () -> writer.writeTrackSpan(0, "t_1", 0, 10,  1,new byte[]{1}));
        }
    }

    @Test
    void trackSnapshotForUnspawnedEntityThrows() throws Exception {
        File file = new File(tempDir, "unspawned_snap.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);
            assertThrows(IllegalStateException.class,
                    () -> writer.writeTrackSnapshot(0, "t_1", 0,  1,new byte[]{1}));
        }
    }

    @Test
    void trackSpanForUnregisteredTrackThrows() throws Exception {
        File file = new File(tempDir, "unreg_track_span.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            assertThrows(IllegalStateException.class,
                    () -> writer.writeTrackSpan(0, "t_99", 0, 10,  1,new byte[]{1}));
        }
    }

    @Test
    void trackSnapshotForUnregisteredTrackThrows() throws Exception {
        File file = new File(tempDir, "unreg_track_snap.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            assertThrows(IllegalStateException.class,
                    () -> writer.writeTrackSnapshot(0, "t_99", 0,  1,new byte[]{1}));
        }
    }

    @Test
    void closeWithoutFinishDoesNotThrow() throws Exception {
        File file = new File(tempDir, "nofinish.reel");
        ReelWriter writer = ReelFileStorage.openWriter(file);
        writer.registerEntityType("e_1");
        writer.writeEntityCreate(0, "e_1", new byte[]{1});
        writer.writeEntitySpawn(0, 0);
        assertDoesNotThrow(writer::close);
    }

    @Test
    void closeIsIdempotent() throws Exception {
        File file = new File(tempDir, "doubleclose.reel");
        ReelWriter writer = ReelFileStorage.openWriter(file);
        writer.finish(0);
        writer.close();
        assertDoesNotThrow(writer::close);
    }

    @Test
    void readInvalidEntityIdThrows() throws Exception {
        File file = new File(tempDir, "invalident.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            assertThrows(IllegalArgumentException.class, () -> reader.readTrackIndex(999));
        }
    }

    @Test
    void readCorruptedHeaderThrows() throws Exception {
        File file = new File(tempDir, "corrupt.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.finish(0);
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(0);
            raf.write(new byte[]{0, 0, 0, 0});
        }

        assertThrows(IOException.class, () -> {
            ReelReader reader = ReelFileStorage.openReader(file);
            reader.close();
        });
    }

    @Test
    void readTruncatedFileThrows() throws Exception {
        File file = new File(tempDir, "truncated.reel");

        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.write(new byte[10]);
        }

        assertThrows(IOException.class, () -> {
            ReelReader reader = ReelFileStorage.openReader(file);
            reader.close();
        });
    }

    @Test
    void readCorruptedFooterThrows() throws Exception {
        File file = new File(tempDir, "badfooter.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.finish(0);
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(raf.length() - 8);
            raf.write(new byte[]{0, 0, 0, 0, 0, 0, 0, 0});
        }

        assertThrows(IOException.class, () -> {
            ReelReader reader = ReelFileStorage.openReader(file);
            reader.close();
        });
    }

    @Test
    void readerCloseIsIdempotent() throws Exception {
        File file = new File(tempDir, "readerclose.reel");
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.finish(0);
        }

        ReelReader reader = ReelFileStorage.openReader(file);
        reader.close();
        assertDoesNotThrow(reader::close);
    }

    @Test
    void metadataIsDefensivelyCopiedOnRead() throws Exception {
        File file = new File(tempDir, "defensive.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.writeEntityCreate(0, "e_1", new byte[]{1, 2, 3});
            writer.writeEntitySpawn(0, 0);
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityEntry e = reader.readEntityDirectory().getEntities().get(0);
            byte[] data1 = e.getMetadata();
            byte[] data2 = e.getMetadata();
            assertArrayEquals(data1, data2);
            data1[0] = 99;
            assertNotEquals(data1[0], e.getMetadata()[0]);
        }
    }

    @Test
    void metadataIsDefensivelyCopiedOnWrite() throws Exception {
        File file = new File(tempDir, "defensive_write.reel");

        byte[] data = {1, 2, 3};
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.writeEntityCreate(0, "e_1", data);

            data[0] = 99; // Mutate after write — should not affect recorded data
            writer.writeEntitySpawn(0, 0);
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityEntry e = reader.readEntityDirectory().getEntities().get(0);
            assertEquals(1, e.getMetadata()[0]);
        }
    }

    @Test
    void entityDirectoryIsCachedAcrossCalls() throws Exception {
        File file = new File(tempDir, "cached.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.writeEntityCreate(0, "e_1", new byte[]{1});
            writer.writeEntitySpawn(0, 0);
            writer.writeEntityCreate(1, "e_2", new byte[]{2});
            writer.writeEntitySpawn(1, 5);
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityDirectory dir1 = reader.readEntityDirectory();
            EntityDirectory dir2 = reader.readEntityDirectory();
            assertEquals(dir1.getEntities().size(), dir2.getEntities().size());
        }
    }

    @Test
    void pathOverloadWorks() throws Exception {
        Path path = tempDir.toPath().resolve("pathtest.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(path)) {
            writer.registerEntityType("e_1");
            writer.writeEntityCreate(0, "e_1", new byte[]{7});
            writer.writeEntitySpawn(0, 0);
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(path)) {
            assertEquals(1, reader.readEntityDirectory().getEntities().size());
        }
    }

    @Test
    void largeEntityDirectoryUsesZstdCompression() throws Exception {
        File file = new File(tempDir, "zstd.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            for (int i = 0; i < 5; i++) {
                writer.registerEntityType("e_" + i);
            }
            for (int i = 0; i < 100; i++) {
                byte[] data = new byte[20];
                for (int j = 0; j < data.length; j++) data[j] = (byte) (i + j);
                writer.writeEntityCreate(i, "e_" + (i % 5), data);
                writer.writeEntitySpawn(i, 0);
            }
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            List<? extends EntityEntry> entities = reader.readEntityDirectory().getEntities();
            assertEquals(100, entities.size());
            for (int i = 0; i < 100; i++) {
                assertEquals(i, entities.get(i).getEntityId());
                assertEquals("e_" + (i % 5), entities.get(i).getEntityType());
            }
        }
    }

    @Test
    void largeTrackIndexTriggersLz4Compression() throws Exception {
        File file = new File(tempDir, "lz4idx.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);

            // Write enough track data to exceed LZ4_TRACK_INDEX_THRESHOLD
            for (int i = 0; i < 50; i++) {
                writer.writeTrackSnapshot(0, "t_1", i,  1,new byte[]{(byte) i});
            }
            writer.finish(100);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityTrackIndex idx = reader.readTrackIndex(0);
            List<TrackSnapshot> snaps = idx.getTracks().get(0).getSnapshots();
            assertEquals(50, snaps.size());
            for (int i = 0; i < 50; i++) {
                assertEquals(i, snaps.get(i).frame());
                assertArrayEquals(new byte[]{(byte) i}, reader.readTrackData(snaps.get(i).refId()));
            }
        }
    }


    @Test
    void concurrentReaderAccess() throws Exception {
        File file = new File(tempDir, "concurrent_read.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);
            for (int i = 0; i < 10; i++) {
                writer.writeEntityCreate(i, "e_1", new byte[]{(byte) i});
                writer.writeEntitySpawn(i, i);
                writer.writeTrackSnapshot(i, "t_1", i,  1,new byte[]{(byte) (i * 2)});
            }
            writer.finish(100);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            int threadCount = 8;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicReference<Throwable> error = new AtomicReference<>();

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < 10; i++) {
                            EntityDirectory dir = reader.readEntityDirectory();
                            assertEquals(10, dir.getEntities().size());

                            EntityTrackIndex idx = reader.readTrackIndex(i);
                            assertEquals(1, idx.getTracks().size());

                            byte[] data = reader.readTrackData(idx.getTracks().get(0).getSnapshots().get(0).refId());
                            assertEquals((byte) (i * 2), data[0]);
                        }
                    } catch (Throwable e) {
                        error.compareAndSet(null, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();
            assertNull(error.get(), () -> "Thread failed: " + error.get());
        }
    }

    @Test
    void concurrentReaderHeavyLoad() throws Exception {
        File file = new File(tempDir, "concurrent_read_heavy.reel");

        int entityCount = 50;
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);
            writer.registerTrack("t_2", 1);
            for (int i = 0; i < entityCount; i++) {
                writer.writeEntityCreate(i, "e_1", new byte[]{(byte) i});
                writer.writeEntitySpawn(i, i);
                writer.writeTrackSnapshot(i, "t_1", i,  1,new byte[]{(byte) i, 1});
                writer.writeTrackSpan(i, "t_2", i, i + 5,  1,new byte[]{(byte) i, 2});
            }
            writer.finish(500);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            int threadCount = 16;
            int iterations = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicReference<Throwable> error = new AtomicReference<>();

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        for (int iter = 0; iter < iterations; iter++) {
                            EntityDirectory dir = reader.readEntityDirectory();
                            assertEquals(entityCount, dir.getEntities().size());

                            assertEquals(2, reader.readTrackDirectory().getTracks().size());

                            for (int i = 0; i < entityCount; i++) {
                                EntityTrackIndex idx = reader.readTrackIndex(i);
                                assertEquals(2, idx.getTracks().size());

                                byte[] snapData = reader.readTrackData(
                                        idx.getTracks().get(0).getSnapshots().get(0).refId());
                                assertEquals((byte) i, snapData[0]);
                            }
                        }
                    } catch (Throwable e) {
                        error.compareAndSet(null, e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startGate.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();
            assertNull(error.get(), () -> "Thread failed: " + error.get());
        }
    }

    @Test
    void concurrentWriterAccess() throws Exception {
        File file = new File(tempDir, "concurrent_write.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);

            for (int i = 0; i < 20; i++) {
                writer.writeEntityCreate(i, "e_1", new byte[]{(byte) i});
                writer.writeEntitySpawn(i, 0);
            }

            int threadCount = 4;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicReference<Throwable> error = new AtomicReference<>();

            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < 5; i++) {
                            int entityId = threadId * 5 + i;
                            writer.writeTrackSnapshot(entityId, "t_1", i,  1,new byte[]{(byte) entityId, (byte) i});
                        }
                    } catch (Throwable e) {
                        error.compareAndSet(null, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();
            assertNull(error.get(), () -> "Thread failed: " + error.get());

            writer.finish(100);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            assertEquals(20, reader.readEntityDirectory().getEntities().size());
            for (int i = 0; i < 20; i++) {
                EntityTrackIndex idx = reader.readTrackIndex(i);
                assertEquals(1, idx.getTracks().size());
                List<TrackSnapshot> snapshots = idx.getTracks().get(0).getSnapshots();
                assertEquals(1, snapshots.size());
            }
        }
    }

    @Test
    void concurrentWriterHeavyLoad() throws Exception {
        File file = new File(tempDir, "concurrent_write_heavy.reel");

        int entitiesPerThread = 10;
        int snapshotsPerEntity = 20;
        int threadCount = 8;

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);
            writer.registerTrack("t_2", 1);

            int totalEntities = entitiesPerThread * threadCount;
            for (int i = 0; i < totalEntities; i++) {
                writer.writeEntityCreate(i, "e_1", new byte[]{(byte) i});
                writer.writeEntitySpawn(i, 0);
            }

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicReference<Throwable> error = new AtomicReference<>();

            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        for (int e = 0; e < entitiesPerThread; e++) {
                            int entityId = threadId * entitiesPerThread + e;
                            for (int s = 0; s < snapshotsPerEntity; s++) {
                                writer.writeTrackSnapshot(entityId, "t_1", s,
                                         1,new byte[]{(byte) entityId, (byte) s});
                                writer.writeTrackSpan(entityId, "t_2", s, s + 1,
                                         1,new byte[]{(byte) entityId, (byte) s, 2});
                            }
                        }
                    } catch (Throwable e) {
                        error.compareAndSet(null, e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startGate.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();
            assertNull(error.get(), () -> "Thread failed: " + error.get());

            writer.finish(1000);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            int totalEntities = entitiesPerThread * threadCount;
            assertEquals(totalEntities, reader.readEntityDirectory().getEntities().size());

            for (int i = 0; i < totalEntities; i++) {
                EntityTrackIndex idx = reader.readTrackIndex(i);
                assertEquals(2, idx.getTracks().size());

                List<TrackSnapshot> snaps = idx.getTracks().get(0).getSnapshots();
                assertEquals(snapshotsPerEntity, snaps.size());

                List<TrackSpan> spans = idx.getTracks().get(1).getSpans();
                assertEquals(snapshotsPerEntity, spans.size());

                for (int s = 0; s < snapshotsPerEntity; s++) {
                    byte[] snapData = reader.readTrackData(snaps.get(s).refId());
                    assertEquals((byte) i, snapData[0]);
                    assertEquals((byte) s, snapData[1]);
                }
            }
        }
    }

    @Test
    void concurrentEntitySpawnsFromMultipleThreads() throws Exception {
        File file = new File(tempDir, "concurrent_spawn.reel");

        int entitiesPerThread = 25;
        int threadCount = 4;

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicReference<Throwable> error = new AtomicReference<>();

            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        for (int i = 0; i < entitiesPerThread; i++) {
                            int entityId = threadId * entitiesPerThread + i;
                            writer.writeEntityCreate(entityId, "e_1", new byte[]{(byte) entityId});
                            writer.writeEntitySpawn(entityId, entityId);
                        }
                    } catch (Throwable e) {
                        error.compareAndSet(null, e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startGate.countDown();
            doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();
            assertNull(error.get(), () -> "Thread failed: " + error.get());

            writer.finish(1000);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            int totalEntities = entitiesPerThread * threadCount;
            assertEquals(totalEntities, reader.readEntityDirectory().getEntities().size());
        }
    }

    @Test
    void concurrentRegisterTrackFromMultipleThreads() throws Exception {
        File file = new File(tempDir, "concurrent_register.reel");

        int tracksPerThread = 10;
        int threadCount = 4;

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicReference<Throwable> error = new AtomicReference<>();

            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        for (int i = 0; i < tracksPerThread; i++) {
                            int trackId = threadId * tracksPerThread + i;
                            writer.registerTrack("t_" + trackId, trackId * 10);
                        }
                    } catch (Throwable e) {
                        error.compareAndSet(null, e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startGate.countDown();
            doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();
            assertNull(error.get(), () -> "Thread failed: " + error.get());

            writer.finish(0);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            int totalTracks = tracksPerThread * threadCount;
            assertEquals(totalTracks, reader.readTrackDirectory().getTracks().size());
        }
    }

    @Test
    void concurrentMixedOperations() throws Exception {
        File file = new File(tempDir, "concurrent_mixed.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);

            int entityCount = 40;
            for (int i = 0; i < entityCount; i++) {
                writer.writeEntityCreate(i, "e_1", new byte[]{(byte) i});
                writer.writeEntitySpawn(i, 0);
            }

            int threadCount = 8;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicReference<Throwable> error = new AtomicReference<>();

            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        int entitiesPerThread = entityCount / threadCount;
                        int startEntity = threadId * entitiesPerThread;
                        for (int e = 0; e < entitiesPerThread; e++) {
                            int entityId = startEntity + e;
                            if (threadId % 2 == 0) {
                                writer.writeTrackSnapshot(entityId, "t_1", threadId,
                                         1,new byte[]{(byte) entityId, (byte) threadId});
                            } else {
                                writer.writeTrackSpan(entityId, "t_1", 0, threadId,
                                         1,new byte[]{(byte) entityId, (byte) threadId});
                            }
                        }
                    } catch (Throwable e) {
                        error.compareAndSet(null, e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startGate.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();
            assertNull(error.get(), () -> "Thread failed: " + error.get());

            writer.finish(100);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            assertEquals(40, reader.readEntityDirectory().getEntities().size());
            for (int i = 0; i < 40; i++) {
                EntityTrackIndex idx = reader.readTrackIndex(i);
                assertEquals(1, idx.getTracks().size());
                EntityTrackEntry track = idx.getTracks().get(0);
                int totalOps = track.getSpans().size() + track.getSnapshots().size();
                assertEquals(1, totalOps);
            }
        }
    }

    @Test
    void spansAndSnapshotsMixedOnSameTrack() throws Exception {
        File file = new File(tempDir, "mixed.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);

            writer.writeTrackSnapshot(0, "t_1", 0,  1,new byte[]{1});
            writer.writeTrackSpan(0, "t_1", 0, 5,  1,new byte[]{2});
            writer.writeTrackSnapshot(0, "t_1", 5,  1,new byte[]{3});
            writer.writeTrackSpan(0, "t_1", 5, 10,  1,new byte[]{4});
            writer.writeTrackSnapshot(0, "t_1", 10,  1,new byte[]{5});

            writer.finish(15);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityTrackIndex idx = reader.readTrackIndex(0);
            EntityTrackEntry track = idx.getTracks().get(0);

            assertEquals(3, track.getSnapshots().size());
            assertEquals(2, track.getSpans().size());

            assertArrayEquals(new byte[]{1}, reader.readTrackData(track.getSnapshots().get(0).refId()));
            assertArrayEquals(new byte[]{2}, reader.readTrackData(track.getSpans().get(0).refId()));
            assertArrayEquals(new byte[]{3}, reader.readTrackData(track.getSnapshots().get(1).refId()));
            assertArrayEquals(new byte[]{4}, reader.readTrackData(track.getSpans().get(1).refId()));
            assertArrayEquals(new byte[]{5}, reader.readTrackData(track.getSnapshots().get(2).refId()));
        }
    }

    @Test
    void emptyTrackDataBytes() throws Exception {
        File file = new File(tempDir, "emptytrack.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            writer.writeTrackSnapshot(0, "t_1", 0,  1,new byte[0]);
            writer.writeTrackSpan(0, "t_1", 0, 5,  1,new byte[0]);
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityTrackIndex idx = reader.readTrackIndex(0);
            EntityTrackEntry track = idx.getTracks().get(0);
            assertArrayEquals(new byte[0], reader.readTrackData(track.getSnapshots().get(0).refId()));
            assertArrayEquals(new byte[0], reader.readTrackData(track.getSpans().get(0).refId()));
        }
    }

    @Test
    void entityDirectoryPreservesInsertionOrder() throws Exception {
        File file = new File(tempDir, "entityorder.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.writeEntityCreate(50, "e_1", new byte[0]);
            writer.writeEntitySpawn(50, 0);
            writer.writeEntityCreate(10, "e_2", new byte[0]);
            writer.writeEntitySpawn(10, 0);
            writer.writeEntityCreate(99, "e_3", new byte[0]);
            writer.writeEntitySpawn(99, 0);
            writer.writeEntityCreate(1, "e_4", new byte[0]);
            writer.writeEntitySpawn(1, 0);
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            List<? extends EntityEntry> entities = reader.readEntityDirectory().getEntities();
            assertEquals(50, entities.get(0).getEntityId());
            assertEquals(10, entities.get(1).getEntityId());
            assertEquals(99, entities.get(2).getEntityId());
            assertEquals(1, entities.get(3).getEntityId());
        }
    }

    @Test
    void costRoundTrip() throws Exception {
        File file = new File(tempDir, "costroundtrip.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);

            writer.writeTrackSnapshot(0, "t_1", 0, 500, new byte[]{1});
            writer.writeTrackSpan(0, "t_1", 0, 10, 3, new byte[]{2});
            writer.writeTrackSnapshot(0, "t_1", 10, 0, new byte[]{3});
            writer.writeTrackSpan(0, "t_1", 10, 20, 127, new byte[]{4});

            writer.finish(20);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityTrackIndex idx = reader.readTrackIndex(0);
            EntityTrackEntry track = idx.getTracks().get(0);

            assertEquals(2, track.getSnapshots().size());
            assertEquals(500, track.getSnapshots().get(0).cost());
            assertEquals(0, track.getSnapshots().get(1).cost());

            assertEquals(2, track.getSpans().size());
            assertEquals(3, track.getSpans().get(0).cost());
            assertEquals(127, track.getSpans().get(1).cost());
        }
    }

    @Test
    void costLargeValueRoundTrip() throws Exception {
        File file = new File(tempDir, "costlarge.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            writer.writeTrackSnapshot(0, "t_1", 0, Integer.MAX_VALUE, new byte[]{1});
            writer.writeTrackSpan(0, "t_1", 0, 10, 100_000, new byte[]{2});
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityTrackIndex idx = reader.readTrackIndex(0);
            EntityTrackEntry track = idx.getTracks().get(0);
            assertEquals(Integer.MAX_VALUE, track.getSnapshots().get(0).cost());
            assertEquals(100_000, track.getSpans().get(0).cost());
        }
    }

    @Test
    void repeatedReadsReturnConsistentData() throws Exception {
        File file = new File(tempDir, "consistent.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerEntityType("e_4");
            writer.registerEntityType("e_5");
            writer.registerEntityType("e_10");
            writer.registerEntityType("e_20");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[]{1, 2, 3});
            writer.writeEntitySpawn(0, 0);
            writer.writeTrackSnapshot(0, "t_1", 5,  1,new byte[]{10, 20});
            writer.finish(100);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            for (int i = 0; i < 10; i++) {
                assertEquals(100, reader.getTotalFrames());
                assertEquals(1, reader.readEntityDirectory().getEntities().size());
                assertEquals(1, reader.readTrackDirectory().getTracks().size());

                EntityTrackIndex idx = reader.readTrackIndex(0);
                byte[] data = reader.readTrackData(idx.getTracks().get(0).getSnapshots().get(0).refId());
                assertArrayEquals(new byte[]{10, 20}, data);
            }
        }
    }

}
