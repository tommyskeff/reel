package dev.tommyjs.reel.recorder;

import dev.tommyjs.reel.TestFixtures;
import dev.tommyjs.reel.TestFixtures.IntDelta;
import dev.tommyjs.reel.TestFixtures.IntModel;
import dev.tommyjs.reel.TestFixtures.IntState;
import dev.tommyjs.reel.storage.writer.LayerStrategy;
import dev.tommyjs.reel.track.TrackHandle;
import dev.tommyjs.reel.track.TrackKind;
import dev.tommyjs.reel.track.TrackModel;
import dev.tommyjs.reel.track.TrackRegistry;
import dev.tommyjs.reel.track.codec.Codec;
import dev.tommyjs.reel.track.codec.VoidCodec;
import dev.tommyjs.reel.storage.ReelIOUtil;
import dev.tommyjs.reel.storage.writer.ReelWriter;
import org.jetbrains.annotations.NotNull;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static dev.tommyjs.reel.TestFixtures.PLAYER;
import static dev.tommyjs.reel.TestFixtures.POSITION;
import static dev.tommyjs.reel.TestFixtures.SIGNAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReelRecorderImplTest {

    private static final LayerStrategy STRATEGY = new LayerStrategy(new long[]{1, 4});

    private final Codec<IntState> stateCodec = TestFixtures.intStateCodec();
    private final Codec<IntDelta> deltaCodec = TestFixtures.intDeltaCodec();

    @Mock
    private ReelWriter writer;

    private AutoCloseable mocks;
    private List<Entry> written;
    private List<CloseEntry> closes;
    private List<EventEntry> events;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        written = new ArrayList<>();
        closes = new ArrayList<>();
        events = new ArrayList<>();
        when(writer.getLayerStrategy()).thenReturn(STRATEGY);
        when(writer.getAllocator()).thenReturn(ByteBufAllocator.DEFAULT);
        doAnswer(this::captureDelta).when(writer)
            .writeDelta(anyLong(), anyInt(), anyInt(), anyInt(), anyLong(), any());
        doAnswer(this::captureState).when(writer)
            .writeState(anyLong(), anyInt(), anyInt(), anyInt(), anyLong(), any(), any());
        doAnswer(this::captureClose).when(writer)
            .writeClose(anyLong(), anyInt(), anyInt(), anyInt(), anyLong(), any());
        doAnswer(this::captureEvent).when(writer)
            .writeEvent(anyLong(), anyInt(), anyInt(), anyInt(), anyLong(), any());
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    private Void captureDelta(InvocationOnMock invocation) throws IOException {
        long entityId = invocation.getArgument(0);
        int entityTypeId = invocation.getArgument(1);
        int trackTypeId = invocation.getArgument(2);
        int layer = invocation.getArgument(3);
        long startFrame = invocation.getArgument(4);
        ByteBuf body = invocation.getArgument(5);

        assertTrue(body.refCnt() > 0, "writer received a released buffer");
        try {
            int count = ReelIOUtil.readVarIntAsInt(body, "delta_count");
            int value = 0;
            for (int i = 0; i < count; i++) {
                value += deltaCodec.decode(body).step();
            }
            written.add(new Entry(entityId, entityTypeId, trackTypeId, layer, startFrame, value, false));
        } finally {
            body.release();
        }
        return null;
    }

    private Void captureState(InvocationOnMock invocation) {
        long entityId = invocation.getArgument(0);
        int entityTypeId = invocation.getArgument(1);
        int trackTypeId = invocation.getArgument(2);
        int layer = invocation.getArgument(3);
        long startFrame = invocation.getArgument(4);
        ByteBuf previous = invocation.getArgument(5);
        ByteBuf current = invocation.getArgument(6);

        assertTrue(current.refCnt() > 0, "writer received a released buffer");
        try {
            boolean hasPrevious = previous != null;
            int value = stateCodec.decode(current).value();
            written.add(new Entry(entityId, entityTypeId, trackTypeId, layer, startFrame, value, hasPrevious));
        } finally {
            if (previous != null) {
                previous.release();
            }
            current.release();
        }
        return null;
    }

    private Void captureClose(InvocationOnMock invocation) {
        long entityId = invocation.getArgument(0);
        int entityTypeId = invocation.getArgument(1);
        int trackTypeId = invocation.getArgument(2);
        int layer = invocation.getArgument(3);
        long startFrame = invocation.getArgument(4);
        ByteBuf previous = invocation.getArgument(5);

        assertTrue(previous.refCnt() > 0, "writer received a released buffer");
        try {
            int value = stateCodec.decode(previous).value();
            closes.add(new CloseEntry(entityId, entityTypeId, trackTypeId, layer, startFrame, value));
        } finally {
            previous.release();
        }
        return null;
    }

    private Void captureEvent(InvocationOnMock invocation) throws IOException {
        long entityId = invocation.getArgument(0);
        int entityTypeId = invocation.getArgument(1);
        int trackTypeId = invocation.getArgument(2);
        int layer = invocation.getArgument(3);
        long startFrame = invocation.getArgument(4);
        ByteBuf body = invocation.getArgument(5);

        assertTrue(body.refCnt() > 0, "writer received a released buffer");
        try {
            int count = ReelIOUtil.readVarIntAsInt(body, "event_count");
            List<Integer> codes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                codes.add(body.readInt());
            }
            events.add(new EventEntry(entityId, entityTypeId, trackTypeId, layer, startFrame, codes));
        } finally {
            body.release();
        }
        return null;
    }

    private ReelRecorder newRecorder() {
        return newRecorder(TestFixtures.registry());
    }

    private ReelRecorder newRecorder(TrackRegistry registry) {
        return ReelRecorder.builder()
            .setWriter(writer)
            .setTrackRegistry(registry)
            .setSingleSegment()
            .setIdleThreshold(0)
            .build();
    }

    @Test
    void opensInitialSegmentOnConstruction() {
        newRecorder();
        verify(writer).getLayerStrategy();
        verify(writer).beginSegment(0L, true);
    }

    @Test
    void createEntityRegistersTypeAndAssignsSequentialIds() {
        ReelRecorder recorder = newRecorder();

        EntityRecorder first = recorder.createEntity(PLAYER);
        EntityRecorder second = recorder.createEntity(PLAYER);

        assertEquals(0L, first.entityId());
        assertEquals(1L, second.entityId());
        verify(writer).registerEntityType("player", 0);
    }

    @Test
    void recordDeltaWithoutInitialStateThrows() {
        ReelRecorder recorder = newRecorder();
        EntityRecorder entity = recorder.createEntity(PLAYER);

        assertThrows(IllegalStateException.class, () -> entity.recordDelta(POSITION, new IntDelta(1)));
        verify(writer, never()).writeDelta(anyLong(), anyInt(), anyInt(), anyInt(), anyLong(), any());
        verify(writer, never()).writeState(anyLong(), anyInt(), anyInt(), anyInt(), anyLong(), any(), any());
    }

    @Test
    void operationsAfterCloseThrow() throws IOException {
        ReelRecorder recorder = newRecorder();
        recorder.close();
        assertThrows(IllegalStateException.class, () -> recorder.createEntity(PLAYER));
    }

    @Test
    void flushesLayeredWindowsOnRolloverAndClose() throws IOException {
        ReelRecorder recorder = newRecorder();
        EntityRecorder entity = recorder.createEntity(PLAYER);

        entity.recordState(POSITION, new IntState(10));
        recorder.advanceFrame();
        entity.recordDelta(POSITION, new IntDelta(5));
        recorder.advanceFrame();
        entity.recordDelta(POSITION, new IntDelta(7));
        recorder.advanceFrame();
        entity.recordDelta(POSITION, new IntDelta(3));
        recorder.advanceFrame();
        recorder.close();

        List<Entry> expected = List.of(
            stateEntry(0, 0, 10, false),
            deltaEntry(0, 1, 5),
            deltaEntry(0, 2, 7),
            deltaEntry(0, 3, 3),
            stateEntry(1, 0, 25, false)
        );
        assertEquals(expected, written);

        verify(writer).registerTrackType("position", 0, 1, TrackKind.DELTA);
        verify(writer).endSegment(5L);
        verify(writer).finish(5L);
        verify(writer).close();
    }

    @Test
    void multipleDeltasInSameFrameAreCondensed() throws IOException {
        ReelRecorder recorder = newRecorder();
        EntityRecorder entity = recorder.createEntity(PLAYER);

        entity.recordState(POSITION, new IntState(0));
        recorder.advanceFrame();
        entity.recordDelta(POSITION, new IntDelta(5));
        entity.recordDelta(POSITION, new IntDelta(7));
        recorder.close();

        assertTrue(written.contains(deltaEntry(0, 1, 12)), () -> "got " + written);
        assertTrue(written.contains(stateEntry(1, 0, 12, false)), () -> "got " + written);
    }

    @Test
    void emptyWindowsProduceNoEntries() throws IOException {
        ReelRecorder recorder = newRecorder();
        recorder.createEntity(PLAYER);

        recorder.advanceFrame();
        recorder.advanceFrame();
        recorder.close();

        assertTrue(written.isEmpty(), () -> "got " + written);
        verify(writer, never()).registerTrackType(any(), anyInt(), anyInt(), any());
    }

    @Test
    void condenseIsSkippedForSingleDeltaWindows() throws IOException {
        IntModel spyModel = spy(TestFixtures.intModel());
        TrackRegistry registry = TrackRegistry.builder()
            .register(POSITION, spyModel, TestFixtures.intStateCodec(), TestFixtures.intDeltaCodec()).build();

        ReelRecorder recorder = newRecorder(registry);
        EntityRecorder entity = recorder.createEntity(PLAYER);
        entity.recordState(POSITION, new IntState(0));
        recorder.advanceFrame();
        entity.recordDelta(POSITION, new IntDelta(9));
        recorder.advanceFrame();
        entity.recordDelta(POSITION, new IntDelta(4));
        recorder.close();

        assertTrue(written.contains(deltaEntry(0, 1, 9)), () -> "got " + written);
        assertTrue(written.contains(deltaEntry(0, 2, 4)), () -> "got " + written);
        assertTrue(written.contains(stateEntry(1, 0, 13, false)), () -> "got " + written);

        verify(spyModel, times(1)).condense(any(), any());
    }

    @Test
    void totalFramesIsCursorPositionPlusOneAtConstruction() throws IOException {
        ReelRecorder recorder = newRecorder();

        assertEquals(0L, recorder.currentFrame());
        recorder.close();

        verify(writer).endSegment(1L);
        verify(writer).finish(1L);
    }

    @Test
    void totalFramesCountsTrailingAdvances() throws IOException {
        ReelRecorder recorder = newRecorder();
        recorder.advanceFrame();
        recorder.advanceFrame();
        recorder.advanceFrame();
        recorder.advanceFrame();

        assertEquals(4L, recorder.currentFrame());
        recorder.close();

        verify(writer).endSegment(5L);
        verify(writer).finish(5L);
    }

    @Test
    void closeEmitsCloseEntriesForAllLayers() throws IOException {
        ReelRecorder recorder = newRecorder();
        EntityRecorder entity = recorder.createEntity(PLAYER);

        entity.recordState(POSITION, new IntState(5));
        recorder.advanceFrame();
        entity.recordDelta(POSITION, new IntDelta(2));
        recorder.advanceFrame();
        entity.close();
        recorder.close();

        assertTrue(closes.contains(new CloseEntry(0L, 0, 0, 0, 2L, 7)), () -> "got " + closes);
        assertTrue(closes.contains(new CloseEntry(0L, 0, 0, 1, 0L, 7)), () -> "got " + closes);
    }

    @Test
    void closeWithoutInitializedTrackEmitsNothing() throws IOException {
        ReelRecorder recorder = newRecorder();
        EntityRecorder entity = recorder.createEntity(PLAYER);

        entity.close();
        recorder.close();

        assertTrue(closes.isEmpty(), () -> "got " + closes);
    }

    @Test
    void recordEventWritesToAllLayersAndPreservesAllEvents() throws IOException {
        ReelRecorder recorder = newRecorder(TestFixtures.eventRegistry());
        EntityRecorder entity = recorder.createEntity(PLAYER);

        recorder.advanceFrame();
        entity.recordEvent(SIGNAL, new TestFixtures.Signal(7));
        entity.recordEvent(SIGNAL, new TestFixtures.Signal(8));
        recorder.advanceFrame();
        entity.recordEvent(SIGNAL, new TestFixtures.Signal(9));
        recorder.close();

        assertTrue(events.contains(new EventEntry(0L, 0, 0, 0, 1L, List.of(7, 8))), () -> "got " + events);
        assertTrue(events.contains(new EventEntry(0L, 0, 0, 0, 2L, List.of(9))), () -> "got " + events);
        assertTrue(events.contains(new EventEntry(0L, 0, 0, 1, 0L, List.of(7, 8, 9))), () -> "got " + events);
        assertEquals(3, events.size());
        assertTrue(written.isEmpty(), () -> "got " + written);
        verify(writer).registerTrackType("signal", 0, 1, TrackKind.EVENT);
    }

    @Test
    void eventTracksEmitNoCloseEntries() throws IOException {
        ReelRecorder recorder = newRecorder(TestFixtures.eventRegistry());
        EntityRecorder entity = recorder.createEntity(PLAYER);

        recorder.advanceFrame();
        entity.recordEvent(SIGNAL, new TestFixtures.Signal(1));
        recorder.advanceFrame();
        entity.close();
        recorder.close();

        assertTrue(closes.isEmpty(), () -> "got " + closes);
    }

    @Test
    void recordStateOnEventTrackThrows() {
        ReelRecorder recorder = newRecorder(TestFixtures.eventRegistry());
        EntityRecorder entity = recorder.createEntity(PLAYER);

        assertThrows(IllegalStateException.class, () -> entity.recordState(SIGNAL, null));
    }

    @Test
    void recordEventOnNonEventTrackThrows() {
        TrackHandle<Void, IntDelta> handle = TrackHandle.of("plain", Void.class, IntDelta.class);
        TrackRegistry registry = TrackRegistry.builder()
            .register(handle, plainVoidModel(), VoidCodec.INSTANCE, plainDeltaCodec()).build();

        ReelRecorder recorder = newRecorder(registry);
        EntityRecorder entity = recorder.createEntity(PLAYER);

        assertThrows(IllegalStateException.class, () -> entity.recordEvent(handle, new IntDelta(1)));
    }

    private static TrackModel<Void, IntDelta> plainVoidModel() {
        return new TrackModel<>() {
            @Override
            public @NotNull Void applyDelta(@NotNull Void state, @NotNull IntDelta delta) {
                return null;
            }

            @Override
            public @NotNull Void cloneState(@NotNull Void state) {
                return null;
            }

            @Override
            public @NotNull List<IntDelta> condense(@NotNull Void ref, @NotNull List<IntDelta> deltas) {
                return deltas;
            }

            @Override
            public @NotNull IntDelta reverse(@NotNull IntDelta delta) {
                return delta;
            }
        };
    }

    private static Codec<IntDelta> plainDeltaCodec() {
        return new Codec<>() {
            @Override
            public void encode(@NotNull ByteBuf out, @NotNull IntDelta delta) {
                out.writeInt(delta.step());
            }

            @Override
            public @NotNull IntDelta decode(@NotNull ByteBuf in) {
                return new IntDelta(in.readInt());
            }
        };
    }

    private Entry stateEntry(int layer, long startFrame, int value, boolean hasPrevious) {
        return new Entry(0L, 0, 0, layer, startFrame, value, hasPrevious);
    }

    private Entry deltaEntry(int layer, long startFrame, int value) {
        return new Entry(0L, 0, 0, layer, startFrame, value, false);
    }

    private record Entry(long entityId, int entityTypeId, int trackTypeId, int layer,
                         long startFrame, int value, boolean hasPrevious) {
    }

    private record CloseEntry(long entityId, int entityTypeId, int trackTypeId, int layer, long startFrame, int value) {
    }

    private record EventEntry(long entityId, int entityTypeId, int trackTypeId, int layer, long startFrame,
                              List<Integer> codes) {
    }

}
