package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.TestFixtures;
import dev.tommyjs.reel.TestFixtures.IntDelta;
import dev.tommyjs.reel.TestFixtures.IntState;
import dev.tommyjs.reel.replay.update.EntityUpdate;
import dev.tommyjs.reel.replay.update.ReplayUpdate;
import dev.tommyjs.reel.replay.update.TrackUpdate;
import dev.tommyjs.reel.track.TrackKind;
import dev.tommyjs.reel.track.TrackRegistry;
import dev.tommyjs.reel.storage.ReelIOUtil;
import dev.tommyjs.reel.storage.model.EntityTypeDefinition;
import dev.tommyjs.reel.storage.model.EntityTypeDirectory;
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
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.tommyjs.reel.TestFixtures.PLAYER;
import static dev.tommyjs.reel.TestFixtures.POSITION;
import static dev.tommyjs.reel.TestFixtures.SIGNAL;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplayCursorImplTest {

    private static final long TOTAL_FRAMES = 4L;

    private final TrackRegistry registry = TestFixtures.registry();

    @Mock
    private ReelReader reader;

    @Mock
    private ReplayListener listener;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(reader.getTotalFrames()).thenReturn(TOTAL_FRAMES);
        when(reader.readTypeDirectory()).thenReturn(typeDirectory(0, "player"));
        when(reader.readTrackDirectory()).thenReturn(trackDirectory());
        when(reader.readSegmentDirectory()).thenReturn(segmentDirectory());
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    void currentFrameIsMinusOneBeforeSeekAndTotalFramesDelegates() {
        when(reader.readSegment(0)).thenReturn(segment(100L, 0, snapshot(0, 10)));
        ReplayCursorImpl cursor = newCursor(false);

        assertEquals(-1L, cursor.currentFrame());
        assertEquals(TOTAL_FRAMES, cursor.totalFrames());
    }

    @Test
    void seekOutOfBoundsThrows() {
        when(reader.readSegment(0)).thenReturn(segment(100L, 0, snapshot(0, 10)));
        ReplayCursorImpl cursor = newCursor(false);

        assertThrows(IllegalArgumentException.class, () -> cursor.seek(-1L));
        assertThrows(IllegalArgumentException.class, () -> cursor.seek(TOTAL_FRAMES));
    }

    @Test
    void firstSeekDispatchesSingleUpdate() {
        when(reader.readSegment(0)).thenReturn(segment(100L, 0, snapshot(0, 10)));
        ReplayCursorImpl cursor = newCursor(false);
        cursor.addListener(listener);

        cursor.seek(0L);

        ArgumentCaptor<ReplayUpdate> captor = ArgumentCaptor.forClass(ReplayUpdate.class);
        verify(listener).onUpdate(captor.capture());

        ReplayUpdate update = captor.getValue();
        assertEquals(-1L, update.getFromFrame());
        assertEquals(0L, update.getToFrame());

        EntityUpdate entity = update.getEntity(100L);
        assertNotNull(entity);
        assertEquals(PLAYER, entity.getEntityType());

        List<TrackUpdate<IntState, IntDelta>> steps = entity.getUpdatesFor(POSITION);
        assertEquals(1, steps.size());
        TrackUpdate.Snapshot<IntState, IntDelta> snap = assertInstanceOf(TrackUpdate.Snapshot.class, steps.get(0));
        assertEquals(10, snap.state().value());

        assertEquals(0L, cursor.currentFrame());
    }

    @Test
    void sameTargetSeekIsNoOp() {
        when(reader.readSegment(0)).thenReturn(segment(100L, 0, snapshot(0, 10)));
        ReplayCursorImpl cursor = newCursor(false);
        cursor.addListener(listener);

        cursor.seek(0L);
        cursor.seek(0L);

        verify(listener, times(1)).onUpdate(ArgumentCaptor.forClass(ReplayUpdate.class).capture());
    }

    @Test
    void unknownEntityTypeSkipsEntityAndDispatchesNothing() {
        when(reader.readSegment(0)).thenReturn(segment(100L, 99, snapshot(0, 10)));
        ReplayCursorImpl cursor = newCursor(false);
        cursor.addListener(listener);

        cursor.seek(0L);

        verify(listener, never()).onUpdate(ArgumentCaptor.forClass(ReplayUpdate.class).capture());
        assertEquals(0L, cursor.currentFrame());
    }

    @Test
    void decodeErrorIsSkippedNotThrown() {
        WindowEntry good = delta(0, 5);
        WindowEntry bad = truncatedDelta(0);
        when(reader.readSegment(0)).thenReturn(segment(100L, 0, good, bad));
        ReplayCursorImpl cursor = newCursor(false);
        cursor.addListener(listener);

        cursor.seek(0L);

        ArgumentCaptor<ReplayUpdate> captor = ArgumentCaptor.forClass(ReplayUpdate.class);
        verify(listener).onUpdate(captor.capture());

        EntityUpdate entity = captor.getValue().getEntity(100L);
        assertNotNull(entity);
        List<TrackUpdate<IntState, IntDelta>> steps = entity.getUpdatesFor(POSITION);
        assertEquals(1, steps.size());
        assertInstanceOf(TrackUpdate.Delta.class, steps.get(0));
    }

    @Test
    void addListenerIsIdempotentAndRemoveStopsDispatch() {
        WindowGroup[] layer0 = {
            new WindowGroup(new WindowEntry[]{snapshot(0, 10)}),
            new WindowGroup(new WindowEntry[]{snapshot(0, 20)}),
            WindowGroup.EMPTY,
            WindowGroup.EMPTY
        };
        when(reader.readSegment(0)).thenReturn(segmentWithLayer0(100L, 0, layer0));
        ReplayCursorImpl cursor = newCursor(false);

        cursor.addListener(listener);
        cursor.addListener(listener);
        cursor.seek(0L);
        verify(listener, times(1)).onUpdate(ArgumentCaptor.forClass(ReplayUpdate.class).capture());

        cursor.removeListener(listener);
        cursor.seek(1L);
        verify(listener, times(1)).onUpdate(ArgumentCaptor.forClass(ReplayUpdate.class).capture());
    }

    @Test
    void forwardCloseRemovesEntity() {
        WindowGroup[] layer0 = {
            new WindowGroup(new WindowEntry[]{snapshot(0, 10)}),
            new WindowGroup(new WindowEntry[]{closeEntry(0, 10)}),
            WindowGroup.EMPTY,
            WindowGroup.EMPTY
        };
        when(reader.readSegment(0)).thenReturn(segmentWithLayer0(100L, 0, layer0));
        ReplayCursorImpl cursor = newCursor(false);
        cursor.addListener(listener);

        cursor.seek(0L);
        cursor.seek(1L);

        ArgumentCaptor<ReplayUpdate> captor = ArgumentCaptor.forClass(ReplayUpdate.class);
        verify(listener, times(2)).onUpdate(captor.capture());

        ReplayUpdate last = captor.getAllValues().get(1);
        assertTrue(last.getRemoved().contains(100L));
        assertNull(last.getEntity(100L));
    }

    @Test
    void backwardCloseRestoresEntityWithPreCloseState() {
        WindowGroup[] layer0 = {
            new WindowGroup(new WindowEntry[]{snapshot(0, 5)}),
            new WindowGroup(new WindowEntry[]{closeEntry(0, 5)}),
            WindowGroup.EMPTY,
            WindowGroup.EMPTY
        };
        when(reader.readSegment(0)).thenReturn(segmentWithLayer0(100L, 0, layer0));
        ReplayCursorImpl cursor = newCursor(false);
        cursor.addListener(listener);

        cursor.seek(1L);
        cursor.seek(0L);

        ArgumentCaptor<ReplayUpdate> captor = ArgumentCaptor.forClass(ReplayUpdate.class);
        verify(listener).onUpdate(captor.capture());

        ReplayUpdate last = captor.getValue();
        EntityUpdate entity = last.getEntity(100L);
        assertNotNull(entity);
        List<TrackUpdate<IntState, IntDelta>> steps = entity.getUpdatesFor(POSITION);
        TrackUpdate.Snapshot<IntState, IntDelta> snap = assertInstanceOf(TrackUpdate.Snapshot.class, steps.get(0));
        assertEquals(5, snap.state().value());
    }

    @Test
    void closeClosesReaderWhenOwned() throws Exception {
        when(reader.readSegment(0)).thenReturn(segment(100L, 0, snapshot(0, 10)));
        ReplayCursorImpl cursor = newCursor(true);

        cursor.close();

        verify(reader).close();
    }

    @Test
    void closeLeavesReaderOpenWhenNotOwned() throws Exception {
        when(reader.readSegment(0)).thenReturn(segment(100L, 0, snapshot(0, 10)));
        ReplayCursorImpl cursor = newCursor(false);

        cursor.close();

        verify(reader, never()).close();
    }

    @Test
    void eventForwardSeekEmitsEventsInOrder() {
        when(reader.readTrackDirectory()).thenReturn(signalDirectory());
        when(reader.readSegment(0)).thenReturn(segment(100L, 0, eventEntry(0, 7, 8)));
        ReplayCursorImpl cursor = newEventCursor();
        cursor.addListener(listener);

        cursor.seek(0L);

        ArgumentCaptor<ReplayUpdate> captor = ArgumentCaptor.forClass(ReplayUpdate.class);
        verify(listener).onUpdate(captor.capture());
        assertEquals(List.of(7, 8), signals(captor.getValue()));
    }

    @Test
    void eventReverseSeekEmitsEventsInReverseOrder() {
        WindowGroup[] layer0 = {
            WindowGroup.EMPTY,
            new WindowGroup(new WindowEntry[]{eventEntry(0, 7, 8)}),
            WindowGroup.EMPTY,
            WindowGroup.EMPTY
        };
        when(reader.readTrackDirectory()).thenReturn(signalDirectory());
        when(reader.readSegment(0)).thenReturn(segmentWithLayer0(100L, 0, layer0));
        ReplayCursorImpl cursor = newEventCursor();
        cursor.addListener(listener);

        cursor.seek(1L);
        cursor.seek(0L);

        ArgumentCaptor<ReplayUpdate> captor = ArgumentCaptor.forClass(ReplayUpdate.class);
        verify(listener, times(2)).onUpdate(captor.capture());
        assertEquals(List.of(7, 8), signals(captor.getAllValues().get(0)));
        assertEquals(List.of(8, 7), signals(captor.getAllValues().get(1)));
    }

    @Test
    void trackKindMismatchThrowsOnOpen() {
        when(reader.readTrackDirectory()).thenReturn(directory(new TrackDefinition(0, "position", 1, TrackKind.EVENT)));
        assertThrows(IllegalStateException.class, () -> newCursor(false));
    }

    @Test
    void unknownTrackInRegistryDoesNotFailValidation() {
        when(reader.readTrackDirectory()).thenReturn(directory(new TrackDefinition(0, "ghost", 1, TrackKind.EVENT)));
        assertDoesNotThrow(() -> newCursor(false));
    }

    private List<Integer> signals(ReplayUpdate update) {
        EntityUpdate entity = update.getEntity(100L);
        assertNotNull(entity);
        return entity.getUpdatesFor(SIGNAL).stream()
            .map(step -> assertInstanceOf(TrackUpdate.Event.class, step).event())
            .map(signal -> ((TestFixtures.Signal) signal).code())
            .toList();
    }

    private ReplayCursorImpl newCursor(boolean ownsReader) {
        return new ReplayCursorImpl(reader, registry, 4, -1L, ownsReader);
    }

    private ReplayCursorImpl newEventCursor() {
        return new ReplayCursorImpl(reader, TestFixtures.eventRegistry(), 4, -1L, false);
    }

    private static EntityTypeDirectory typeDirectory(int id, String identifier) {
        EntityTypeDefinition def = new EntityTypeDefinition(id, identifier);
        return new EntityTypeDirectory(List.of(def), Map.of(id, def), Map.of(identifier, def));
    }

    private static TrackDirectory trackDirectory() {
        TrackDefinition def = new TrackDefinition(0, "position", 1, TrackKind.DELTA);
        return new TrackDirectory(2, new long[]{1, 4}, List.of(def), Map.of(0, def), Map.of("position", def));
    }

    private static TrackDirectory signalDirectory() {
        return directory(new TrackDefinition(0, "signal", 1, TrackKind.EVENT));
    }

    private static TrackDirectory directory(TrackDefinition... defs) {
        Map<Integer, TrackDefinition> byId = new HashMap<>();
        Map<String, TrackDefinition> byName = new HashMap<>();
        for (TrackDefinition def : defs) {
            byId.put(def.id(), def);
            byName.put(def.identifier(), def);
        }
        return new TrackDirectory(2, new long[]{1, 4}, List.of(defs), byId, byName);
    }

    private static SegmentDirectory segmentDirectory() {
        return new SegmentDirectory(List.of(new SegmentEntry(0L, 4L, 0L, 0L)));
    }

    private static Segment segment(long entityId, int entityTypeId, WindowEntry... entries) {
        long[] entityIds = {entityId};
        int[] entityTypeIds = {entityTypeId};
        TrackReference[] trackRefs = {new TrackReference(0, 0)};

        WindowGroup[] layer0 = {new WindowGroup(entries), WindowGroup.EMPTY, WindowGroup.EMPTY, WindowGroup.EMPTY};
        WindowGroup[] layer1 = {WindowGroup.EMPTY};
        WindowGroup[][] layerWindows = {layer0, layer1};

        return new Segment(0L, 4L, entityIds, entityTypeIds, trackRefs, layerWindows);
    }

    private static Segment segmentWithLayer0(long entityId, int entityTypeId, WindowGroup[] layer0) {
        long[] entityIds = {entityId};
        int[] entityTypeIds = {entityTypeId};
        TrackReference[] trackRefs = {new TrackReference(0, 0)};
        WindowGroup[] layer1 = {WindowGroup.EMPTY};
        WindowGroup[][] layerWindows = {layer0, layer1};

        return new Segment(0L, 4L, entityIds, entityTypeIds, trackRefs, layerWindows);
    }

    private static WindowEntry snapshot(int trackRefIndex, int current) {
        return new WindowEntry(trackRefIndex,
            new RawPayload.State(false, Unpooled.buffer().writeInt(current)));
    }

    private static WindowEntry delta(int trackRefIndex, int value) {
        var body = Unpooled.buffer();
        ReelIOUtil.writeVarInt(body, 1);
        body.writeInt(value);
        return new WindowEntry(trackRefIndex, new RawPayload.Delta(body));
    }

    private static WindowEntry closeEntry(int trackRefIndex, int previous) {
        return new WindowEntry(trackRefIndex,
            new RawPayload.Close(Unpooled.buffer().writeInt(previous)));
    }

    private static WindowEntry eventEntry(int trackRefIndex, int... codes) {
        var body = Unpooled.buffer();
        ReelIOUtil.writeVarInt(body, codes.length);
        for (int code : codes) {
            body.writeInt(code);
        }
        return new WindowEntry(trackRefIndex, new RawPayload.Event(body));
    }

    private static WindowEntry truncatedDelta(int trackRefIndex) {
        var body = Unpooled.buffer();
        ReelIOUtil.writeVarInt(body, 1);
        body.writeByte(0); // too short: readInt() needs 4 bytes
        return new WindowEntry(trackRefIndex, new RawPayload.Delta(body));
    }

}
