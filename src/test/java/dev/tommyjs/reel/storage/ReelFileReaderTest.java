package dev.tommyjs.reel.storage;

import dev.tommyjs.reel.storage.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import static org.junit.jupiter.api.Assertions.*;

class ReelFileReaderTest {

    @TempDir
    File tempDir;

    @Test
    void readEmptyFileThrows() {
        assertThrows(IOException.class, () -> {
            File file = new File(tempDir, "empty.reel");
            file.createNewFile();
            ReelFileStorage.openReader(file);
        });
    }

    @Test
    void readFileTooSmallForFooterThrows() throws Exception {
        File file = new File(tempDir, "toosmall.reel");
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.write(new byte[20]);
        }
        assertThrows(IOException.class, () -> ReelFileStorage.openReader(file));
    }

    @Test
    void readerClosesFileOnConstructionFailure() throws Exception {
        File file = new File(tempDir, "badinit.reel");
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.write(ReelFormat.headerMagic());
            raf.writeShort(ReelFormat.FORMAT_VERSION);
            raf.writeShort(0);
            raf.write(new byte[ReelFormat.FOOTER_SIZE]);
        }

        assertThrows(IOException.class, () -> ReelFileStorage.openReader(file));
        assertTrue(file.delete());
    }

    @Test
    void readTrackDataWithInvalidBlockIndexThrows() throws Exception {
        File file = new File(tempDir, "badblock.reel");
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            writer.writeTrackSnapshot(0, "t_1", 0, 1, new byte[]{1});
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            long badRefId = ReelFormat.packRefId(999, 0, 1);
            assertThrows(RuntimeException.class, () -> reader.readTrackData(badRefId));
        }
    }

    @Test
    void readTrackDataWithOffsetBeyondBlockThrows() throws Exception {
        File file = new File(tempDir, "badoffset.reel");
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            writer.writeTrackSnapshot(0, "t_1", 0, 1, new byte[]{1, 2, 3});
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            long badRefId = ReelFormat.packRefId(0, 1000, 100);
            assertThrows(RuntimeException.class, () -> reader.readTrackData(badRefId));
        }
    }

    @Test
    void readTrackDataWithZeroSizeReturnsEmpty() throws Exception {
        File file = new File(tempDir, "zerosize.reel");
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            writer.writeTrackSnapshot(0, "t_1", 0, 1, new byte[0]);
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityTrackIndex idx = reader.readTrackIndex(0);
            long refId = idx.getTracks().get(0).getSnapshots().get(0).refId();
            assertArrayEquals(new byte[0], reader.readTrackData(refId));
        }
    }

    @Test
    void blockCacheReturnsConsistentDataOnRepeatedReads() throws Exception {
        File file = new File(tempDir, "cache_consistent.reel");
        byte[] payload = new byte[1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            writer.writeTrackSnapshot(0, "t_1", 0, 1, payload);
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityTrackIndex idx = reader.readTrackIndex(0);
            long refId = idx.getTracks().get(0).getSnapshots().get(0).refId();
            byte[] read1 = reader.readTrackData(refId);
            byte[] read2 = reader.readTrackData(refId);
            assertArrayEquals(payload, read1);
            assertArrayEquals(payload, read2);
        }
    }

    @Test
    void blockCacheEvictionStillReturnsCorrectData() throws Exception {
        File file = new File(tempDir, "cache_eviction.reel");

        int blockCount = 40;
        byte[][] payloads = new byte[blockCount][];

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);

            for (int i = 0; i < blockCount; i++) {
                payloads[i] = new byte[ReelFormat.BLOCK_FLUSH_THRESHOLD];
                for (int j = 0; j < payloads[i].length; j++) {
                    payloads[i][j] = (byte) (i + j);
                }
                writer.writeTrackSnapshot(0, "t_1", i, 1, payloads[i]);
            }
            writer.finish(blockCount);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityTrackIndex idx = reader.readTrackIndex(0);
            var snapshots = idx.getTracks().get(0).getSnapshots();
            assertEquals(blockCount, snapshots.size());

            // Read all blocks forward (fills cache, evicts early blocks)
            for (int i = 0; i < blockCount; i++) {
                assertArrayEquals(payloads[i], reader.readTrackData(snapshots.get(i).refId()));
            }

            // Re-read earliest blocks (should reload from file after eviction)
            for (int i = 0; i < 5; i++) {
                assertArrayEquals(payloads[i], reader.readTrackData(snapshots.get(i).refId()));
            }
        }
    }

    @Test
    void entityDirectoryListIsUnmodifiable() throws Exception {
        File file = new File(tempDir, "unmod_entity.reel");
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.writeEntityCreate(0, "e_1", new byte[]{1});
            writer.writeEntitySpawn(0, 0);
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            var entities = reader.readEntityDirectory().getEntities();
            assertThrows(UnsupportedOperationException.class, entities::clear);
        }
    }

    @Test
    void trackDirectoryListIsUnmodifiable() throws Exception {
        File file = new File(tempDir, "unmod_track.reel");
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);
            writer.finish(0);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            var tracks = reader.readTrackDirectory().getTracks();
            assertThrows(UnsupportedOperationException.class, tracks::clear);
        }
    }

    @Test
    void trackIndexListsAreUnmodifiable() throws Exception {
        File file = new File(tempDir, "unmod_idx.reel");
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            writer.writeTrackSnapshot(0, "t_1", 0, 1, new byte[]{1});
            writer.writeTrackSpan(0, "t_1", 0, 5, 1, new byte[]{2});
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityTrackIndex idx = reader.readTrackIndex(0);
            assertThrows(UnsupportedOperationException.class, idx.getTracks()::clear);
            assertThrows(UnsupportedOperationException.class, idx.getTracks().get(0).getSnapshots()::clear);
            assertThrows(UnsupportedOperationException.class, idx.getTracks().get(0).getSpans()::clear);
        }
    }

    @Test
    void readAfterCloseThrows() throws Exception {
        File file = new File(tempDir, "closed.reel");
        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.writeEntityCreate(0, "e_1", new byte[]{1});
            writer.writeEntitySpawn(0, 0);
            writer.finish(10);
        }

        ReelReader reader = ReelFileStorage.openReader(file);
        reader.close();
        assertThrows(RuntimeException.class, reader::readEntityDirectory);
    }

    @Test
    void totalFramesLargeValue() throws Exception {
        File file = new File(tempDir, "largeframes.reel");
        long frames = Long.MAX_VALUE;

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.finish(frames);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            assertEquals(frames, reader.getTotalFrames());
        }
    }

    @Test
    void readFromMultipleBlocksForDifferentEntities() throws Exception {
        File file = new File(tempDir, "multiblock_entities.reel");

        byte[] data1 = new byte[40_000];
        byte[] data2 = new byte[40_000];
        for (int i = 0; i < data1.length; i++) data1[i] = (byte) (i & 0xFF);
        for (int i = 0; i < data2.length; i++) data2[i] = (byte) ((i + 50) & 0xFF);

        try (ReelWriter writer = ReelFileStorage.openWriter(file)) {
            writer.registerEntityType("e_1");
            writer.registerEntityType("e_2");
            writer.registerEntityType("e_3");
            writer.registerTrack("t_1", 1);
            writer.writeEntityCreate(0, "e_1", new byte[0]);
            writer.writeEntitySpawn(0, 0);
            writer.writeEntityCreate(1, "e_1", new byte[0]);
            writer.writeEntitySpawn(1, 0);
            writer.writeTrackSnapshot(0, "t_1", 0, 1, data1);
            writer.writeTrackSnapshot(1, "t_1", 0, 1, data2);
            writer.finish(10);
        }

        try (ReelReader reader = ReelFileStorage.openReader(file)) {
            EntityTrackIndex idx0 = reader.readTrackIndex(0);
            EntityTrackIndex idx1 = reader.readTrackIndex(1);

            assertArrayEquals(data1, reader.readTrackData(idx0.getTracks().get(0).getSnapshots().get(0).refId()));
            assertArrayEquals(data2, reader.readTrackData(idx1.getTracks().get(0).getSnapshots().get(0).refId()));
        }
    }

}
