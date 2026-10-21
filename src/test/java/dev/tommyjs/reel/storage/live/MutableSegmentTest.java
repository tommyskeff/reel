package dev.tommyjs.reel.storage.live;

import dev.tommyjs.reel.LiveTestFixtures;
import dev.tommyjs.reel.storage.model.Segment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutableSegmentTest {

    private static final long[] STRIDES = {1, 4};

    @Test
    void startsEmptyAndGrowsOnPublish() {
        MutableSegment segment = new MutableSegment(0, STRIDES, true);

        assertEquals(0L, segment.getStartFrame());
        assertEquals(0L, segment.getEndFrame());
        assertEquals(0, segment.getWindowCount(0));
        assertTrue(segment.isAnchor());

        segment.append(LiveTestFixtures.entry(1L, 0L, LiveTestFixtures.deltaPayload(2)));
        segment.publish();

        assertEquals(1L, segment.getEndFrame());
        assertEquals(1, segment.getWindowCount(0));
        assertEquals(1, segment.getWindow(0, 0).size());

        segment.release();
    }

    @Test
    void doesNotExposeAppendsUntilPublished() {
        MutableSegment segment = new MutableSegment(0, STRIDES, false);
        segment.append(LiveTestFixtures.entry(1L, 0L, LiveTestFixtures.deltaPayload(1)));

        assertEquals(0, segment.getWindowCount(0));

        segment.publish();
        assertEquals(1, segment.getWindowCount(0));

        segment.release();
    }

    @Test
    void indexesWindowsRelativeToTheSegmentStart() {
        MutableSegment segment = new MutableSegment(8, STRIDES, false);
        segment.append(LiveTestFixtures.entry(1L, 8L, LiveTestFixtures.deltaPayload(1)));
        segment.append(LiveTestFixtures.entry(1L, 10L, LiveTestFixtures.deltaPayload(2)));
        segment.publish();

        assertEquals(3, segment.getWindowCount(0));
        assertEquals(1, segment.getWindow(0, 0).size());
        assertEquals(0, segment.getWindow(0, 1).size());
        assertEquals(1, segment.getWindow(0, 2).size());

        segment.release();
    }

    @Test
    void indexesWindowsByStrideWhenTheStartIsUnaligned() {
        MutableSegment segment = new MutableSegment(6, STRIDES, false);
        segment.append(new FragmentEntry(1L, 0, 0, 1, 4L, LiveTestFixtures.deltaPayload(1)));
        segment.append(new FragmentEntry(1L, 0, 0, 1, 8L, LiveTestFixtures.deltaPayload(2)));
        segment.publish();

        assertEquals(2, segment.getWindowCount(1));
        assertEquals(1, segment.getWindow(1, 0).size());
        assertEquals(1, segment.getWindow(1, 1).size());

        segment.release();
    }

    @Test
    void sortsEntitiesAndResolvesTrackReferences() {
        MutableSegment segment = new MutableSegment(0, STRIDES, false);
        segment.append(LiveTestFixtures.entry(9L, 0L, LiveTestFixtures.deltaPayload(1)));
        segment.append(LiveTestFixtures.entry(4L, 0L, LiveTestFixtures.deltaPayload(1)));
        segment.publish();

        assertArrayEquals(new long[]{4L, 9L}, segment.getEntityIds());

        int firstRef = segment.getWindow(0, 0).entries()[0].trackRefIndex();
        assertEquals(1, segment.getTrackRefs()[firstRef].entityIndex());

        segment.release();
    }

    @Test
    void accumulatesHigherLayerWindows() {
        MutableSegment segment = new MutableSegment(0, STRIDES, false);
        segment.append(new FragmentEntry(1L, 0, 0, 1, 0L, LiveTestFixtures.deltaPayload(3)));
        segment.publish();

        assertEquals(4L, segment.getEndFrame());
        assertEquals(1, segment.getWindowCount(1));
        assertEquals(4, segment.getWindowCount(0));

        segment.release();
    }

    @Test
    void freezeReturnsAnImmutableSnapshot() {
        MutableSegment segment = new MutableSegment(0, STRIDES, true);
        segment.append(LiveTestFixtures.entry(1L, 0L, LiveTestFixtures.deltaPayload(2)));

        Segment frozen = segment.freeze(4);

        assertEquals(0L, frozen.getStartFrame());
        assertEquals(4L, frozen.getEndFrame());
        assertEquals(4, frozen.getWindowCount(0));
        assertEquals(1, frozen.getWindow(0, 0).size());

        assertThrows(IllegalStateException.class,
            () -> segment.append(LiveTestFixtures.entry(2L, 1L, LiveTestFixtures.deltaPayload(1))));

        frozen.close();
    }

    @Test
    void releaseAfterFreezeLeavesTheSnapshotIntact() {
        MutableSegment segment = new MutableSegment(0, STRIDES, false);
        segment.append(LiveTestFixtures.entry(1L, 0L, LiveTestFixtures.deltaPayload(2)));
        Segment frozen = segment.freeze(1);

        segment.release();

        assertTrue(frozen.getWindow(0, 0).entries()[0].payload().body().refCnt() > 0);
        frozen.close();
    }

    @Test
    void rejectsMisalignedAndEarlyWindows() {
        MutableSegment segment = new MutableSegment(8, STRIDES, false);

        assertThrows(IllegalArgumentException.class,
            () -> segment.append(new FragmentEntry(1L, 0, 0, 1, 9L, LiveTestFixtures.deltaPayload(1))));
        assertThrows(IllegalArgumentException.class,
            () -> segment.append(LiveTestFixtures.entry(1L, 4L, LiveTestFixtures.deltaPayload(1))));
        assertThrows(IllegalArgumentException.class,
            () -> segment.append(new FragmentEntry(1L, 0, 0, 5, 8L, LiveTestFixtures.deltaPayload(1))));
    }

    @Test
    void rejectsConflictingEntityTypes() {
        MutableSegment segment = new MutableSegment(0, STRIDES, false);
        segment.append(new FragmentEntry(1L, 3, 0, 0, 0L, LiveTestFixtures.deltaPayload(1)));

        assertThrows(IllegalArgumentException.class,
            () -> segment.append(new FragmentEntry(1L, 4, 0, 0, 0L, LiveTestFixtures.deltaPayload(1))));

        segment.release();
    }

}
