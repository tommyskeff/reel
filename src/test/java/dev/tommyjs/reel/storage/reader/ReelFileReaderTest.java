package dev.tommyjs.reel.storage.reader;

import dev.tommyjs.reel.storage.writer.LayerStrategy;
import dev.tommyjs.reel.storage.ReelFormat;
import dev.tommyjs.reel.storage.model.EntityTypeDirectory;
import dev.tommyjs.reel.storage.model.Segment;
import dev.tommyjs.reel.storage.model.SegmentDirectory;
import dev.tommyjs.reel.storage.model.SegmentEntry;
import dev.tommyjs.reel.storage.model.SegmentView;
import dev.tommyjs.reel.track.TrackKind;
import dev.tommyjs.reel.storage.model.TrackDirectory;
import dev.tommyjs.reel.storage.model.TrackReference;
import dev.tommyjs.reel.storage.model.RawPayload;
import dev.tommyjs.reel.storage.model.WindowEntry;
import dev.tommyjs.reel.storage.model.WindowGroup;
import dev.tommyjs.reel.storage.writer.ReelFileWriter;
import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReelFileReaderTest {

    private static final LayerStrategy STRATEGY = new LayerStrategy(new long[]{1, 4});
    private static final ByteBufAllocator ALLOC = ByteBufAllocator.DEFAULT;

    @Test
    void roundTripExposesDirectoriesAndSegmentContents(@TempDir Path dir) throws IOException {
        File file = writeFixture(dir);
        ReelFileReader reader = new ReelFileReader(file, ALLOC);

        try {
            assertEquals(4L, reader.getTotalFrames());

            EntityTypeDirectory types = reader.readTypeDirectory();
            assertEquals(2, types.types().size());
            assertEquals(0, types.get("player").id());
            assertEquals("player", types.get(0).identifier());
            assertEquals(1, types.get("npc").id());
            assertEquals("npc", types.get(1).identifier());

            TrackDirectory tracks = reader.readTrackDirectory();
            assertArrayEquals(new long[]{1, 4}, tracks.layerStrides());
            assertEquals(2, tracks.layerCount());
            assertEquals(0, tracks.get("position").id());
            assertEquals(1, tracks.get("position").codecVersion());

            SegmentDirectory segments = reader.readSegmentDirectory();
            assertEquals(1, segments.size());
            SegmentEntry entry = segments.segments().get(0);
            assertEquals(0L, entry.startFrame());
            assertEquals(4L, entry.endFrame());

            SegmentView segment = reader.readSegment(0);
            try {
                assertEquals(0L, segment.getStartFrame());
                assertEquals(4L, segment.getEndFrame());
                assertArrayEquals(new long[]{7L}, segment.getEntityIds());
                assertArrayEquals(new int[]{0}, segment.getEntityTypeIds());

                assertEquals(123, readPayload(segment, 0, 0));
                assertEquals(456, readPayload(segment, 1, 0));
            } finally {
                segment.close();
            }
        } finally {
            reader.close();
        }
    }

    @Test
    void directoriesAreLazilyCached(@TempDir Path dir) throws IOException {
        File file = writeFixture(dir);
        ReelFileReader reader = new ReelFileReader(file, ALLOC);

        try {
            assertSame(reader.readTypeDirectory(), reader.readTypeDirectory());
            assertSame(reader.readTrackDirectory(), reader.readTrackDirectory());
            assertSame(reader.readSegmentDirectory(), reader.readSegmentDirectory());
        } finally {
            reader.close();
        }
    }

    @Test
    void closeIsIdempotent(@TempDir Path dir) throws IOException {
        File file = writeFixture(dir);
        ReelFileReader reader = new ReelFileReader(file, ALLOC);

        reader.close();
        reader.close();
    }

    @Test
    void constructorRejectsFileSmallerThanMinimum(@TempDir Path dir) throws IOException {
        File file = dir.resolve("tiny.reel").toFile();
        Files.write(file.toPath(), new byte[]{1, 2, 3, 4});

        assertThrows(UncheckedIOException.class, () -> new ReelFileReader(file, ALLOC));
    }

    @Test
    void constructorRejectsFileWithBadMagic(@TempDir Path dir) throws IOException {
        File file = dir.resolve("garbage.reel").toFile();
        Files.write(file.toPath(), new byte[ReelFormat.HEADER_SIZE + ReelFormat.FOOTER_SIZE]);

        assertThrows(UncheckedIOException.class, () -> new ReelFileReader(file, ALLOC));
    }

    @Test
    void readSegmentRejectsOutOfRangeIndex(@TempDir Path dir) throws IOException {
        File file = writeFixture(dir);
        ReelFileReader reader = new ReelFileReader(file, ALLOC);

        try {
            assertThrows(IndexOutOfBoundsException.class, () -> reader.readSegment(1));
        } finally {
            reader.close();
        }
    }

    private static int readPayload(SegmentView segment, int layer, int localWindowIndex) {
        WindowGroup group = segment.getWindow(layer, localWindowIndex);
        WindowEntry[] entries = group.entries();
        assertEquals(1, entries.length);
        WindowEntry windowEntry = entries[0];
        TrackReference ref = segment.getTrackRefs()[windowEntry.trackRefIndex()];
        assertEquals(0, ref.typeId());
        assertInstanceOf(RawPayload.Delta.class, windowEntry.payload());
        return windowEntry.payload().body().duplicate().readInt();
    }

    private static File writeFixture(Path dir) {
        File file = dir.resolve("fixture.reel").toFile();
        ReelFileWriter writer = new ReelFileWriter(file, ALLOC, STRATEGY);

        writer.registerEntityType("player", 0);
        writer.registerEntityType("npc", 1);
        writer.registerTrackType("position", 0, 1, TrackKind.DELTA);

        writer.beginSegment(0L);
        writer.writeDelta(7L, 0, 0, 0, 0L, ALLOC.buffer().writeInt(123));
        writer.writeDelta(7L, 0, 0, 1, 0L, ALLOC.buffer().writeInt(456));
        writer.endSegment(4L);

        writer.finish(4L);
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertNotNull(file);
        return file;
    }

}
