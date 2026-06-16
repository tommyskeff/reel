package dev.tommyjs.reel.storage.writer;

import dev.tommyjs.reel.storage.model.EntityTypeDirectory;
import dev.tommyjs.reel.storage.model.Segment;
import dev.tommyjs.reel.storage.model.SegmentDirectory;
import dev.tommyjs.reel.storage.model.SegmentEntry;
import dev.tommyjs.reel.storage.model.TrackDirectory;
import dev.tommyjs.reel.storage.model.TrackReference;
import dev.tommyjs.reel.track.TrackKind;
import dev.tommyjs.reel.storage.model.RawPayload;
import dev.tommyjs.reel.storage.model.WindowEntry;
import dev.tommyjs.reel.storage.model.WindowGroup;
import dev.tommyjs.reel.storage.reader.ReelFileReader;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReelFileWriterTest {

    private static final LayerStrategy STRATEGY = new LayerStrategy(new long[]{1, 4});

    @TempDir
    Path tempDir;

    private final List<ReelFileWriter> writers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (ReelFileWriter writer : writers) {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
        }
    }

    private ReelFileWriter newWriter() {
        return newWriter(file());
    }

    private ReelFileWriter newWriter(File file) {
        ReelFileWriter writer = new ReelFileWriter(file, ByteBufAllocator.DEFAULT, STRATEGY);
        writers.add(writer);
        return writer;
    }

    private File file() {
        return tempDir.resolve("reel.dat").toFile();
    }

    private static ByteBuf payload(int value) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        buf.writeInt(value);
        return buf;
    }

    @Test
    void beginSegmentTwiceThrows() {
        ReelFileWriter writer = newWriter();
        writer.beginSegment(0);
        assertThrows(IllegalStateException.class, () -> writer.beginSegment(4));
    }

    @Test
    void endSegmentWithoutSegmentThrows() {
        ReelFileWriter writer = newWriter();
        assertThrows(IllegalStateException.class, () -> writer.endSegment(4));
    }

    @Test
    void beginSegmentUnalignedThrows() {
        ReelFileWriter writer = newWriter();
        assertThrows(IllegalArgumentException.class, () -> writer.beginSegment(2));
    }

    @Test
    void beginSegmentOverlappingPreviousThrows() {
        ReelFileWriter writer = newWriter();
        writer.beginSegment(0);
        writer.endSegment(4);
        assertThrows(IllegalArgumentException.class, () -> writer.beginSegment(0));
    }

    @Test
    void endSegmentBeforeStartThrows() {
        ReelFileWriter writer = newWriter();
        writer.beginSegment(4);
        assertThrows(IllegalArgumentException.class, () -> writer.endSegment(0));
    }

    @Test
    void finishWithSegmentInProgressThrows() {
        ReelFileWriter writer = newWriter();
        writer.beginSegment(0);
        assertThrows(IllegalStateException.class, () -> writer.finish(4));
    }

    @Test
    void operationsAfterFinishThrow() {
        ReelFileWriter writer = newWriter();
        writer.finish(0);
        assertThrows(IllegalStateException.class, () -> writer.registerEntityType("player", 0));
        assertThrows(IllegalStateException.class, () -> writer.beginSegment(0));
    }

    @Test
    void operationsAfterCloseThrow() throws IOException {
        ReelFileWriter writer = newWriter();
        writer.close();
        assertThrows(IllegalStateException.class, () -> writer.beginSegment(0));
    }

    @Test
    void writeDeltaOutsideSegmentReleasesBuffer() {
        ReelFileWriter writer = newWriter();
        ByteBuf data = payload(1);
        assertThrows(IllegalStateException.class,
            () -> writer.writeDelta(0, 0, 0, 0, 0, data));
        assertEquals(0, data.refCnt());
    }

    @Test
    void writeDeltaUnknownEntityTypeReleasesBuffer() {
        ReelFileWriter writer = newWriter();
        writer.beginSegment(0);
        ByteBuf data = payload(1);
        assertThrows(IllegalArgumentException.class,
            () -> writer.writeDelta(0, 0, 0, 0, 0, data));
        assertEquals(0, data.refCnt());
    }

    @Test
    void writeDeltaUnknownTrackTypeReleasesBuffer() {
        ReelFileWriter writer = newWriter();
        writer.registerEntityType("player", 0);
        writer.beginSegment(0);
        ByteBuf data = payload(1);
        assertThrows(IllegalArgumentException.class,
            () -> writer.writeDelta(0, 0, 0, 0, 0, data));
        assertEquals(0, data.refCnt());
    }

    @Test
    void closeReleasesPendingPayload() throws IOException {
        ReelFileWriter writer = newWriter();
        writer.registerEntityType("player", 0);
        writer.registerTrackType("position", 0, 1, TrackKind.DELTA);
        writer.beginSegment(0);
        ByteBuf data = payload(7);
        writer.writeDelta(0, 0, 0, 0, 0, data);
        writer.close();
        assertEquals(0, data.refCnt());
    }

    @Test
    void happyRoundTrip() throws IOException {
        File file = file();
        ReelFileWriter writer = newWriter(file);
        writer.registerEntityType("player", 0);
        writer.registerTrackType("position", 0, 1, TrackKind.DELTA);
        writer.beginSegment(0);
        writer.writeDelta(42, 0, 0, 0, 0, payload(1337));
        writer.endSegment(4);
        writer.finish(4);

        ReelFileReader reader = new ReelFileReader(file, ByteBufAllocator.DEFAULT);
        try {
            assertEquals(4, reader.getTotalFrames());

            EntityTypeDirectory typeDir = reader.readTypeDirectory();
            assertNotNull(typeDir.get("player"));
            assertEquals(0, typeDir.get("player").id());

            TrackDirectory trackDir = reader.readTrackDirectory();
            assertNotNull(trackDir.get("position"));
            assertEquals(0, trackDir.get("position").id());
            assertEquals(1, trackDir.get("position").codecVersion());

            SegmentDirectory segmentDir = reader.readSegmentDirectory();
            assertEquals(1, segmentDir.segments().size());
            SegmentEntry segmentEntry = segmentDir.segments().get(0);
            assertEquals(0, segmentEntry.startFrame());
            assertEquals(4, segmentEntry.endFrame());

            Segment segment = reader.readSegment(0);
            try {
                WindowGroup group = segment.getWindow(0, 0);
                assertEquals(1, group.size());
                WindowEntry entry = group.entries()[0];

                TrackReference ref = segment.getTrackRefs()[entry.trackRefIndex()];
                assertEquals(0, ref.typeId());
                assertEquals(42, segment.getEntityIds()[ref.entityIndex()]);
                assertEquals(0, segment.getEntityTypeIds()[ref.entityIndex()]);
                assertInstanceOf(RawPayload.Delta.class, entry.payload());
                assertEquals(1337, entry.payload().body().duplicate().readInt());
            } finally {
                segment.close();
            }
        } finally {
            reader.close();
        }
    }

}
