package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.TestFixtures;
import dev.tommyjs.reel.replay.planner.ReplaySeekPlanner;
import dev.tommyjs.reel.track.TrackKind;
import dev.tommyjs.reel.track.TrackRegistry;
import dev.tommyjs.reel.replay.planner.EntitySteps;
import dev.tommyjs.reel.replay.planner.SeekResult;
import dev.tommyjs.reel.replay.planner.Step;
import dev.tommyjs.reel.storage.model.Segment;
import dev.tommyjs.reel.storage.model.SegmentDirectory;
import dev.tommyjs.reel.storage.model.SegmentEntry;
import dev.tommyjs.reel.storage.model.TrackDefinition;
import dev.tommyjs.reel.storage.model.TrackDirectory;
import dev.tommyjs.reel.storage.model.TrackReference;
import dev.tommyjs.reel.storage.model.RawPayload;
import dev.tommyjs.reel.storage.model.WindowEntry;
import dev.tommyjs.reel.storage.model.WindowGroup;
import dev.tommyjs.reel.storage.reader.ReelReader;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplaySeekPlannerTest {

    private static final long[] STRIDES = new long[]{1, 4};

    @Mock
    private ReelReader reader;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    private TrackDirectory trackDirectory() {
        TrackDefinition def = new TrackDefinition(0, "position", 1, TrackKind.DELTA);
        return new TrackDirectory(2, STRIDES, List.of(def), Map.of(0, def), Map.of("position", def));
    }

    private SegmentDirectory segmentDirectory(SegmentEntry... entries) {
        return new SegmentDirectory(List.of(entries));
    }

    private WindowGroup group(int... trackRefIndices) {
        WindowEntry[] entries = new WindowEntry[trackRefIndices.length];
        for (int i = 0; i < trackRefIndices.length; i++) {
            entries[i] = new WindowEntry(trackRefIndices[i], new RawPayload.Delta(Unpooled.EMPTY_BUFFER));
        }
        return new WindowGroup(entries);
    }

    private WindowGroup[][] emptyWindows() {
        WindowGroup[][] windows = new WindowGroup[2][];
        windows[0] = new WindowGroup[]{WindowGroup.EMPTY, WindowGroup.EMPTY, WindowGroup.EMPTY, WindowGroup.EMPTY};
        windows[1] = new WindowGroup[]{WindowGroup.EMPTY};
        return windows;
    }

    private WindowGroup[][] emptyWindows8() {
        WindowGroup[][] windows = new WindowGroup[2][];
        windows[0] = new WindowGroup[8];
        windows[1] = new WindowGroup[2];
        for (int i = 0; i < 8; i++) {
            windows[0][i] = WindowGroup.EMPTY;
        }
        windows[1][0] = WindowGroup.EMPTY;
        windows[1][1] = WindowGroup.EMPTY;
        return windows;
    }

    private WindowGroup tagged(int tag) {
        return new WindowGroup(new WindowEntry[]{
            new WindowEntry(0, new RawPayload.Delta(Unpooled.buffer().writeByte(tag)))
        });
    }

    private List<Integer> tagsOf(List<Step> steps) {
        List<Integer> tags = new ArrayList<>();
        for (Step step : steps) {
            ByteBuf body = step.entry().payload().body();
            tags.add((int) body.getByte(body.readerIndex()));
        }
        return tags;
    }

    private ReplaySeekPlanner planner(TrackDirectory dir, SegmentDirectory segDir, TrackRegistry registry) {
        when(reader.readTrackDirectory()).thenReturn(dir);
        when(reader.readSegmentDirectory()).thenReturn(segDir);
        return ReplaySeekPlanner.create(reader, registry, 4);
    }

    private ReplaySeekPlanner planner(TrackDirectory dir, SegmentDirectory segDir, TrackRegistry registry,
                                      long anchorSeekPenalty) {
        when(reader.readTrackDirectory()).thenReturn(dir);
        when(reader.readSegmentDirectory()).thenReturn(segDir);
        return ReplaySeekPlanner.create(reader, registry, 4, anchorSeekPenalty);
    }

    private Segment emptySegment(long start, long end) {
        int l0 = (int) (end - start);
        int l1 = (int) ((end - start + 3) / 4);
        WindowGroup[][] windows = new WindowGroup[2][];
        windows[0] = new WindowGroup[l0];
        windows[1] = new WindowGroup[l1];
        for (int i = 0; i < l0; i++) {
            windows[0][i] = WindowGroup.EMPTY;
        }
        for (int i = 0; i < l1; i++) {
            windows[1][i] = WindowGroup.EMPTY;
        }
        return new Segment(start, end, new long[0], new int[0], new TrackReference[0], windows);
    }

    @Test
    void forwardFullSeekEmitsCoarseLayerWindow() {
        WindowGroup[][] windows = emptyWindows();
        windows[1][0] = group(0);
        Segment seg = new Segment(0, 4, new long[]{100}, new int[]{7},
            new TrackReference[]{new TrackReference(0, 0)}, windows);
        when(reader.readSegment(0)).thenReturn(seg);

        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(0, 4, 0, 0)), TestFixtures.registry());

        Map<Long, EntitySteps> result = planner.seek(Long.MIN_VALUE, 3).entities();

        assertTrue(result.containsKey(100L));
        EntitySteps steps = result.get(100L);
        assertEquals(7, steps.entityTypeId());
        assertEquals(1, steps.steps().size());
        Step step = steps.steps().get(0);
        assertSame(TestFixtures.POSITION, step.handle());
        assertFalse(step.reversed());

        planner.close();
    }

    @Test
    void entityTypeIdIsPropagatedFromSegment() {
        WindowGroup[][] windows = emptyWindows();
        windows[1][0] = group(0);
        Segment seg = new Segment(0, 4, new long[]{100}, new int[]{42},
            new TrackReference[]{new TrackReference(0, 0)}, windows);
        when(reader.readSegment(0)).thenReturn(seg);

        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(0, 4, 0, 0)), TestFixtures.registry());

        Map<Long, EntitySteps> result = planner.seek(Long.MIN_VALUE, 3).entities();

        assertEquals(42, result.get(100L).entityTypeId());

        planner.close();
    }

    @Test
    void backwardSeekEmitsReversedLayerZeroWindows() {
        WindowGroup[][] windows = emptyWindows();
        windows[0][1] = group(0);
        windows[0][2] = group(0);
        windows[0][3] = group(0);
        Segment seg = new Segment(0, 4, new long[]{100}, new int[]{7},
            new TrackReference[]{new TrackReference(0, 0)}, windows);
        when(reader.readSegment(0)).thenReturn(seg);

        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(0, 4, 0, 0)), TestFixtures.registry());

        Map<Long, EntitySteps> result = planner.seek(3, 0).entities();

        List<Step> steps = result.get(100L).steps();
        assertEquals(3, steps.size());
        for (Step step : steps) {
            assertTrue(step.reversed());
        }

        planner.close();
    }

    @Test
    void backwardLongJumpDescendsToLayerZeroInsteadOfCoarseWindowAhead() {
        WindowGroup[][] windows = emptyWindows8();
        windows[0][1] = tagged(1);
        windows[0][2] = tagged(2);
        windows[0][3] = tagged(3);
        windows[0][4] = tagged(4);
        windows[1][1] = tagged(100);
        Segment seg = new Segment(0, 8, new long[]{100}, new int[]{7},
            new TrackReference[]{new TrackReference(0, 0)}, windows);
        when(reader.readSegment(0)).thenReturn(seg);

        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(0, 8, 0, 0)), TestFixtures.registry());

        List<Step> steps = planner.seek(4, 0).entities().get(100L).steps();

        assertEquals(List.of(4, 3, 2, 1), tagsOf(steps));
        for (Step step : steps) {
            assertTrue(step.reversed());
        }

        planner.close();
    }

    @Test
    void backwardSeekUsesCoarseWindowWhenTargetAlignsToBoundary() {
        WindowGroup[][] windows = emptyWindows8();
        windows[0][4] = tagged(4);
        windows[0][5] = tagged(5);
        windows[0][6] = tagged(6);
        windows[0][7] = tagged(7);
        windows[1][1] = tagged(100);
        Segment seg = new Segment(0, 8, new long[]{100}, new int[]{7},
            new TrackReference[]{new TrackReference(0, 0)}, windows);
        when(reader.readSegment(0)).thenReturn(seg);

        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(0, 8, 0, 0)), TestFixtures.registry());

        List<Step> steps = planner.seek(7, 3).entities().get(100L).steps();

        assertEquals(List.of(100), tagsOf(steps));
        assertTrue(steps.get(0).reversed());

        planner.close();
    }

    @Test
    void coldSeekStartsFromNearestAnchorSegment() {
        WindowGroup[][] windows = new WindowGroup[2][];
        windows[0] = new WindowGroup[]{group(0), WindowGroup.EMPTY, WindowGroup.EMPTY, WindowGroup.EMPTY};
        windows[1] = new WindowGroup[]{WindowGroup.EMPTY};
        Segment segB = new Segment(8, 12, new long[]{200}, new int[]{7},
            new TrackReference[]{new TrackReference(0, 0)}, windows);
        when(reader.readSegment(1)).thenReturn(segB);

        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(0, 8, 0, 0, true), new SegmentEntry(8, 12, 0, 0, true)),
            TestFixtures.registry());

        SeekResult result = planner.seek(Long.MIN_VALUE, 10);

        assertTrue(result.complete());
        assertTrue(result.entities().containsKey(200L));
        verify(reader, never()).readSegment(0);

        planner.close();
    }

    @Test
    void forwardJumpReSeedsFromAnchorSkippingCursorSegment() {
        WindowGroup[][] windows = new WindowGroup[2][];
        windows[0] = new WindowGroup[]{group(0), WindowGroup.EMPTY, WindowGroup.EMPTY, WindowGroup.EMPTY};
        windows[1] = new WindowGroup[]{WindowGroup.EMPTY};
        Segment segB = new Segment(8, 12, new long[]{200}, new int[]{7},
            new TrackReference[]{new TrackReference(0, 0)}, windows);
        when(reader.readSegment(1)).thenReturn(segB);

        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(0, 8, 0, 0, true), new SegmentEntry(8, 12, 0, 0, true)),
            TestFixtures.registry());

        SeekResult result = planner.seek(2, 10);

        assertTrue(result.complete());
        assertTrue(result.entities().containsKey(200L));
        verify(reader, never()).readSegment(0);

        planner.close();
    }

    @Test
    void backwardJumpReSeedsForwardFromAnchorWhenCloser() {
        WindowGroup[][] windows = new WindowGroup[2][];
        windows[0] = new WindowGroup[]{group(0), WindowGroup.EMPTY, WindowGroup.EMPTY, WindowGroup.EMPTY};
        windows[1] = new WindowGroup[]{WindowGroup.EMPTY};
        Segment segB = new Segment(8, 16, new long[]{200}, new int[]{7},
            new TrackReference[]{new TrackReference(0, 0)}, windows);
        when(reader.readSegment(1)).thenReturn(segB);

        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(0, 8, 0, 0, true), new SegmentEntry(8, 16, 0, 0, true)),
            TestFixtures.registry());

        List<Step> steps = planner.seek(15, 9).entities().get(200L).steps();

        assertFalse(steps.isEmpty());
        for (Step step : steps) {
            assertFalse(step.reversed());
        }

        planner.close();
    }

    @Test
    void anchorSeekPenaltySuppressesShortForwardReSeed() {
        when(reader.readSegment(0)).thenReturn(emptySegment(0, 8));
        when(reader.readSegment(1)).thenReturn(emptySegment(8, 16));
        SegmentDirectory segDir = segmentDirectory(
            new SegmentEntry(0, 8, 0, 0, true), new SegmentEntry(8, 16, 0, 0, true));

        assertTrue(planner(trackDirectory(), segDir, TestFixtures.registry(), 0).seek(6, 10).complete());
        assertFalse(planner(trackDirectory(), segDir, TestFixtures.registry(), 4).seek(6, 10).complete());
    }

    @Test
    void forwardPreservesEntryOrderWithinWindow() {
        WindowGroup[][] windows = emptyWindows8();
        windows[1][1] = new WindowGroup(new WindowEntry[]{
            new WindowEntry(0, new RawPayload.Delta(Unpooled.buffer().writeByte(1))),
            new WindowEntry(0, new RawPayload.Delta(Unpooled.buffer().writeByte(2)))
        });
        Segment seg = new Segment(0, 8, new long[]{100}, new int[]{7},
            new TrackReference[]{new TrackReference(0, 0)}, windows);
        when(reader.readSegment(0)).thenReturn(seg);

        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(0, 8, 0, 0)), TestFixtures.registry());

        List<Step> steps = planner.seek(Long.MIN_VALUE, 7).entities().get(100L).steps();

        assertEquals(List.of(1, 2), tagsOf(steps));

        planner.close();
    }

    @Test
    void backwardReversesEntryOrderWithinWindow() {
        WindowGroup[][] windows = emptyWindows8();
        windows[1][1] = new WindowGroup(new WindowEntry[]{
            new WindowEntry(0, new RawPayload.Delta(Unpooled.buffer().writeByte(1))),
            new WindowEntry(0, new RawPayload.Delta(Unpooled.buffer().writeByte(2)))
        });
        Segment seg = new Segment(0, 8, new long[]{100}, new int[]{7},
            new TrackReference[]{new TrackReference(0, 0)}, windows);
        when(reader.readSegment(0)).thenReturn(seg);

        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(0, 8, 0, 0)), TestFixtures.registry());

        List<Step> steps = planner.seek(7, 3).entities().get(100L).steps();

        assertEquals(List.of(2, 1), tagsOf(steps));

        planner.close();
    }

    @Test
    void multipleEntitiesInOneWindow() {
        WindowGroup[][] windows = emptyWindows();
        windows[1][0] = group(0, 1);
        Segment seg = new Segment(0, 4, new long[]{100, 200}, new int[]{7, 9},
            new TrackReference[]{new TrackReference(0, 0), new TrackReference(1, 0)}, windows);
        when(reader.readSegment(0)).thenReturn(seg);

        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(0, 4, 0, 0)), TestFixtures.registry());

        Map<Long, EntitySteps> result = planner.seek(Long.MIN_VALUE, 3).entities();

        assertEquals(7, result.get(100L).entityTypeId());
        assertEquals(9, result.get(200L).entityTypeId());

        planner.close();
    }

    @Test
    void floorSegmentBeforeAnySegmentYieldsEmpty() {
        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(8, 12, 0, 0)), TestFixtures.registry());

        Map<Long, EntitySteps> result = planner.seek(Long.MIN_VALUE, 3).entities();

        assertTrue(result.isEmpty());

        planner.close();
    }

    @Test
    void frameAtOrAfterEndFrameIsSkipped() {
        WindowGroup[][] windows = emptyWindows();
        windows[0][3] = group(0);
        Segment seg = new Segment(0, 2, new long[]{100}, new int[]{7},
            new TrackReference[]{new TrackReference(0, 0)}, windows);
        when(reader.readSegment(0)).thenReturn(seg);

        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(0, 2, 0, 0)), TestFixtures.registry());

        Map<Long, EntitySteps> result = planner.seek(2, 3).entities();

        assertTrue(result.isEmpty());

        planner.close();
    }

    @Test
    void localWindowIndexOutOfRangeIsSkipped() {
        WindowGroup[][] windows = new WindowGroup[2][];
        windows[0] = new WindowGroup[]{WindowGroup.EMPTY, WindowGroup.EMPTY};
        windows[1] = new WindowGroup[]{WindowGroup.EMPTY};
        Segment seg = new Segment(0, 4, new long[]{100}, new int[]{7},
            new TrackReference[]{new TrackReference(0, 0)}, windows);
        when(reader.readSegment(0)).thenReturn(seg);

        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(0, 4, 0, 0)), TestFixtures.registry());

        Map<Long, EntitySteps> result = planner.seek(2, 3).entities();

        assertTrue(result.isEmpty());

        planner.close();
    }

    @Test
    void emptyWindowGroupProducesNoSteps() {
        Segment seg = new Segment(0, 4, new long[]{100}, new int[]{7},
            new TrackReference[]{new TrackReference(0, 0)}, emptyWindows());
        when(reader.readSegment(0)).thenReturn(seg);

        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(0, 4, 0, 0)), TestFixtures.registry());

        Map<Long, EntitySteps> result = planner.seek(Long.MIN_VALUE, 3).entities();

        assertTrue(result.isEmpty());

        planner.close();
    }

    @Test
    void unknownTrackTypeIsSkipped() {
        WindowGroup[][] windows = emptyWindows();
        windows[1][0] = group(0);
        Segment seg = new Segment(0, 4, new long[]{100}, new int[]{7},
            new TrackReference[]{new TrackReference(0, 99)}, windows);
        when(reader.readSegment(0)).thenReturn(seg);

        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(0, 4, 0, 0)), TestFixtures.registry());

        Map<Long, EntitySteps> result = planner.seek(Long.MIN_VALUE, 3).entities();

        assertTrue(result.isEmpty());

        planner.close();
    }

    @Test
    void unregisteredHandleIsSkipped() {
        WindowGroup[][] windows = emptyWindows();
        windows[1][0] = group(0);
        Segment seg = new Segment(0, 4, new long[]{100}, new int[]{7},
            new TrackReference[]{new TrackReference(0, 0)}, windows);
        when(reader.readSegment(0)).thenReturn(seg);

        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(0, 4, 0, 0)), TrackRegistry.builder().build());

        Map<Long, EntitySteps> result = planner.seek(Long.MIN_VALUE, 3).entities();

        assertTrue(result.isEmpty());

        planner.close();
    }

    @Test
    void cacheServesSecondSeekWithoutReDecoding() {
        WindowGroup[][] windows = emptyWindows();
        windows[1][0] = group(0);
        Segment seg = new Segment(0, 4, new long[]{100}, new int[]{7},
            new TrackReference[]{new TrackReference(0, 0)}, windows);
        when(reader.readSegment(0)).thenReturn(seg);

        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(0, 4, 0, 0)), TestFixtures.registry());

        planner.seek(Long.MIN_VALUE, 3);
        planner.seek(Long.MIN_VALUE, 3);

        verify(reader, times(1)).readSegment(0);

        planner.close();
    }

    @Test
    void seekPayloadsSurviveSegmentEvictionAndClose() {
        WindowGroup[][] windows0 = emptyWindows();
        windows0[1][0] = tagged(1);
        Segment seg0 = new Segment(0, 4, new long[]{100}, new int[]{7},
            new TrackReference[]{new TrackReference(0, 0)}, windows0);
        WindowGroup[][] windows1 = emptyWindows();
        windows1[1][0] = tagged(2);
        Segment seg1 = new Segment(4, 8, new long[]{100}, new int[]{7},
            new TrackReference[]{new TrackReference(0, 0)}, windows1);
        when(reader.readSegment(0)).thenReturn(seg0);
        when(reader.readSegment(1)).thenReturn(seg1);
        when(reader.readTrackDirectory()).thenReturn(trackDirectory());
        when(reader.readSegmentDirectory()).thenReturn(
            segmentDirectory(new SegmentEntry(0, 4, 0, 0), new SegmentEntry(4, 8, 0, 0)));

        ReplaySeekPlanner planner = ReplaySeekPlanner.create(reader, TestFixtures.registry(), 1);

        List<Step> steps = planner.seek(Long.MIN_VALUE, 7).entities().get(100L).steps();
        planner.close();

        assertEquals(List.of(1, 2), tagsOf(steps));

        for (Step step : steps) {
            step.entry().payload().body().release();
        }
    }

    @Test
    void closeClosesCachedSegments() {
        WindowGroup[][] windows = emptyWindows();
        windows[1][0] = group(0);
        Segment spySegment = spy(new Segment(0, 4, new long[]{100}, new int[]{7},
            new TrackReference[]{new TrackReference(0, 0)}, windows));
        when(reader.readSegment(anyInt())).thenReturn(spySegment);

        ReplaySeekPlanner planner = planner(trackDirectory(),
            segmentDirectory(new SegmentEntry(0, 4, 0, 0)), TestFixtures.registry());

        planner.seek(Long.MIN_VALUE, 3);
        planner.close();

        verify(spySegment).close();
    }

}
