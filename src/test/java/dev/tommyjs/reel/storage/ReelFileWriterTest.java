package dev.tommyjs.reel.storage;

import dev.tommyjs.reel.storage.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.RandomAccessFile;

import static org.junit.jupiter.api.Assertions.*;

class ReelFileWriterTest {

    @TempDir
    File tempDir;

    @Test
    void writeTrackSpanOnDespawnedEntityThrows() throws Exception {
        File file = new File(tempDir, "despawned_span.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            writer.writeEntityDespawn(0, 10);
            assertThrows(IllegalStateException.class,
                    () -> writer.writeTrackSpan(0, "t_1", 10, 20, 1, new byte[]{1}));
        }
    }

    @Test
    void writeTrackAfterRespawnSucceeds() throws Exception {
        File file = new File(tempDir, "respawn_track.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            writer.writeEntityDespawn(0, 10);
            writer.writeEntitySpawn(0, 20);
            assertDoesNotThrow(() -> writer.writeTrackSnapshot(0, "t_1", 25, 1, new byte[]{1}));
            writer.finish(30);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityTrackIndex idx = reader.readTrackIndex(0);
            assertEquals(1, idx.getTracks().get(0).getSnapshots().size());
        }
    }

    @Test
    void openWriterTruncatesExistingFile() throws Exception {
        File file = new File(tempDir, "truncate.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.writeEntityCreate(0, "e_1", new byte[]{1, 2, 3, 4, 5});
            writer.writeEntitySpawn(0, 0);
            writer.writeEntityCreate(1, "e_2", new byte[]{6, 7, 8, 9, 10});
            writer.writeEntitySpawn(1, 0);
            writer.finish(100);
        }

        long firstSize = file.length();

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.finish(0);
        }

        assertTrue(file.length() < firstSize);

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            assertEquals(0, reader.readEntityDirectory().getEntities().size());
            assertEquals(0, reader.getTotalFrames());
        }
    }

    @Test
    void largeEntityIdValues() throws Exception {
        File file = new File(tempDir, "largeids.reel");

        long largeId = Long.MAX_VALUE / 2;
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(largeId, "e_1", new byte[]{42});
            writer.writeEntitySpawn(largeId, 0);
            writer.writeTrackSnapshot(largeId, "t_1", 5, 1, new byte[]{99});
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            var entities = reader.readEntityDirectory().getEntities();
            assertEquals(1, entities.size());
            assertEquals(largeId, entities.get(0).getEntityId());

            EntityTrackIndex idx = reader.readTrackIndex(largeId);
            assertArrayEquals(new byte[]{99}, reader.readTrackData(idx.getTracks().get(0).getSnapshots().get(0).refId()));
        }
    }

    @Test
    void threeSpawnCyclesWithTracksEach() throws Exception {
        File file = new File(tempDir, "threecycles.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);

            writer.writeEntityCreate(0, "e_1", new byte[]{42});
            for (int cycle = 0; cycle < 3; cycle++) {
                long startFrame = cycle * 100;
                writer.writeEntitySpawn(0, startFrame);
                writer.writeTrackSnapshot(0, "t_1", startFrame + 10, 1, new byte[]{(byte) (cycle * 10)});
                writer.writeTrackSpan(0, "t_1", startFrame, startFrame + 50, 1, new byte[]{(byte) (cycle * 20)});
                writer.writeEntityDespawn(0, startFrame + 99);
            }
            writer.finish(300);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            var entity = reader.readEntityDirectory().getEntities().get(0);
            assertEquals(3, entity.getSpawns().size());

            EntityTrackIndex idx = reader.readTrackIndex(0);
            var track = idx.getTracks().get(0);
            assertEquals(3, track.getSnapshots().size());
            assertEquals(3, track.getSpans().size());

            for (int cycle = 0; cycle < 3; cycle++) {
                assertArrayEquals(new byte[]{(byte) (cycle * 10)},
                        reader.readTrackData(track.getSnapshots().get(cycle).refId()));
                assertArrayEquals(new byte[]{(byte) (cycle * 20)},
                        reader.readTrackData(track.getSpans().get(cycle).refId()));
            }
        }
    }

    @Test
    void headerContainsMagicAndVersion() throws Exception {
        File file = new File(tempDir, "header.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.finish(0);
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] magic = new byte[4];
            raf.readFully(magic);
            assertArrayEquals(ReelFormat.headerMagic(), magic);
            assertEquals(ReelFormat.FORMAT_VERSION, raf.readShort());
            assertEquals(0, raf.readShort());
        }
    }

    @Test
    void footerContainsCorrectTotalFrames() throws Exception {
        File file = new File(tempDir, "footer.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.writeEntityCreate(0, "e_1", new byte[]{1});
            writer.writeEntitySpawn(0, 0);
            writer.finish(42);
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(raf.length() - ReelFormat.FOOTER_SIZE);
            assertEquals(42, raf.readLong());

            long opDataOffset = raf.readLong();
            assertEquals(ReelFormat.HEADER_SIZE, opDataOffset);

            raf.skipBytes(4 + 8 + 8 + 8);
            byte[] footerMagic = new byte[ReelFormat.FOOTER_MAGIC_LENGTH];
            raf.readFully(footerMagic);
            assertArrayEquals(ReelFormat.footerMagic(), footerMagic);
        }
    }

    @Test
    void manyEntitiesNoTracks() throws Exception {
        File file = new File(tempDir, "manyentities.reel");
        int count = 200;

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            for (int i = 0; i < 10; i++) {
                writer.registerEntityType("e_" + i);
            }
            for (int i = 0; i < count; i++) {
                writer.writeEntityCreate(i, "e_" + (i % 10), new byte[]{(byte) i});
                writer.writeEntitySpawn(i, i);
            }
            writer.finish(count);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            var entities = reader.readEntityDirectory().getEntities();
            assertEquals(count, entities.size());
            for (int i = 0; i < count; i++) {
                assertEquals(i, entities.get(i).getEntityId());
                assertEquals("e_" + (i % 10), entities.get(i).getEntityType());
                assertEquals(i, entities.get(i).getSpawns().get(0).getSpawnFrame());
                assertArrayEquals(new byte[]{(byte) i}, entities.get(i).getMetadata());
            }
        }
    }

    @Test
    void finishWithNoOperationBlocks() throws Exception {
        File file = new File(tempDir, "noblocks.reel");

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            writer.finish(5);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            assertEquals(5, reader.getTotalFrames());
            assertEquals(1, reader.readEntityDirectory().getEntities().size());
            assertEquals(0, reader.readTrackIndex(0).getTracks().size());
        }
    }

    @Test
    void negativeSpawnFrameThrows() throws Exception {
        File file = new File(tempDir, "neg_spawn.reel");
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            assertThrows(IllegalArgumentException.class,
                    () -> writer.writeEntitySpawn(0, -1));
        }
    }

    @Test
    void negativeDespawnFrameThrows() throws Exception {
        File file = new File(tempDir, "neg_despawn.reel");
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            assertThrows(IllegalArgumentException.class,
                    () -> writer.writeEntityDespawn(0, -5));
        }
    }

    @Test
    void negativeSnapshotFrameThrows() throws Exception {
        File file = new File(tempDir, "neg_snap.reel");
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            assertThrows(IllegalArgumentException.class,
                    () -> writer.writeTrackSnapshot(0, "t_1", -1, 1, new byte[]{1}));
        }
    }

    @Test
    void negativeSpanReferenceFrameThrows() throws Exception {
        File file = new File(tempDir, "neg_span_ref.reel");
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            assertThrows(IllegalArgumentException.class,
                    () -> writer.writeTrackSpan(0, "t_1", -1, 10, 1, new byte[]{1}));
        }
    }

    @Test
    void negativeSpanTargetFrameThrows() throws Exception {
        File file = new File(tempDir, "neg_span_target.reel");
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            assertThrows(IllegalArgumentException.class,
                    () -> writer.writeTrackSpan(0, "t_1", 0, -1, 1, new byte[]{1}));
        }
    }

    @Test
    void negativeFinishTotalFramesThrows() throws Exception {
        File file = new File(tempDir, "neg_finish.reel");
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            assertThrows(IllegalArgumentException.class,
                    () -> writer.finish(-1));
        }
    }

    @Test
    void negativeSpanCostThrows() throws Exception {
        File file = new File(tempDir, "neg_span_cost.reel");
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            assertThrows(IllegalArgumentException.class,
                    () -> writer.writeTrackSpan(0, "t_1", 0, 10, -1, new byte[]{1}));
        }
    }

    @Test
    void negativeSnapshotCostThrows() throws Exception {
        File file = new File(tempDir, "neg_snap_cost.reel");
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            assertThrows(IllegalArgumentException.class,
                    () -> writer.writeTrackSnapshot(0, "t_1", 0, -1, new byte[]{1}));
        }
    }

    @Test
    void zeroCostIsValid() throws Exception {
        File file = new File(tempDir, "zero_cost.reel");
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            assertDoesNotThrow(() -> writer.writeTrackSpan(0, "t_1", 0, 10, 0, new byte[]{1}));
            assertDoesNotThrow(() -> writer.writeTrackSnapshot(0, "t_1", 0, 0, new byte[]{2}));
            writer.finish(10);
        }
    }

    @Test
    void zeroFrameValuesAreValid() throws Exception {
        File file = new File(tempDir, "zero_frames.reel");
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            writer.writeTrackSnapshot(0, "t_1", 0, 1, new byte[]{1});
            writer.writeTrackSpan(0, "t_1", 0, 0, 1, new byte[]{2});
            writer.writeEntityDespawn(0, 0);
            writer.finish(0);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            assertEquals(0, reader.getTotalFrames());
        }
    }
}
