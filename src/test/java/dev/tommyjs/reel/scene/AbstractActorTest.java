package dev.tommyjs.reel.scene;

import dev.tommyjs.reel.TestFixtures;
import dev.tommyjs.reel.TestFixtures.IntDelta;
import dev.tommyjs.reel.TestFixtures.IntState;
import dev.tommyjs.reel.TestFixtures.Signal;
import dev.tommyjs.reel.replay.update.EntityUpdate;
import dev.tommyjs.reel.replay.update.EntityUpdateImpl;
import dev.tommyjs.reel.replay.update.TrackUpdate;
import dev.tommyjs.reel.track.TrackHandle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static dev.tommyjs.reel.TestFixtures.PLAYER;
import static dev.tommyjs.reel.TestFixtures.POSITION;
import static dev.tommyjs.reel.TestFixtures.SIGNAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AbstractActorTest {

    private final ReplayScene scene = mock(ReplayScene.class);

    @Test
    void firesEventsOnForwardSingleStep() {
        TestEntity entity = entity(true);
        entity.apply(eventUpdate(7), new SceneTransition(0, 1, false), scene);
        assertTrue(entity.created);
        assertEquals(List.of(7), entity.events);
    }

    @Test
    void firesEventsDuringPlaybackJump() {
        TestEntity entity = entity(true);
        entity.apply(eventUpdate(7), new SceneTransition(0, 5, true), scene);
        assertEquals(List.of(7), entity.events);
    }

    @Test
    void doesNotFireEventsOnInitialOrBackward() {
        TestEntity initial = entity(true);
        initial.apply(eventUpdate(7), new SceneTransition(-1, 0, false), scene);
        assertEquals(List.of(), initial.events);

        TestEntity backward = entity(true);
        backward.apply(eventUpdate(7), new SceneTransition(2, 1, false), scene);
        assertEquals(List.of(), backward.events);
    }

    @Test
    void skipsJumpsUnlessOptedOut() {
        TestEntity skipping = entity(true);
        skipping.apply(eventUpdate(7), new SceneTransition(0, 5, false), scene);
        assertEquals(List.of(), skipping.events);

        TestEntity replaying = entity(false);
        replaying.apply(eventUpdate(7), new SceneTransition(0, 5, false), scene);
        assertEquals(List.of(7), replaying.events);
    }

    @Test
    void deliversEveryEventInTheBatch() {
        TestEntity entity = entity(true);
        EntityUpdateImpl update = new EntityUpdateImpl(1L, PLAYER, new LinkedHashMap<>());
        update.putUpdate(POSITION, new TrackUpdate.Snapshot<>(new IntState(0)));
        update.putUpdate(SIGNAL, new TrackUpdate.Event<>(new Signal(7)));
        update.putUpdate(SIGNAL, new TrackUpdate.Event<>(new Signal(8)));

        entity.apply(update, new SceneTransition(0, 1, false), scene);
        assertEquals(List.of(7, 8), entity.events);
    }

    @Test
    void knowsItsEntityIdWhenCreated() {
        TestEntity entity = entity(true);
        EntityUpdateImpl update = new EntityUpdateImpl(42L, PLAYER, new LinkedHashMap<>());
        update.putUpdate(POSITION, new TrackUpdate.Snapshot<>(new IntState(0)));

        entity.apply(update, new SceneTransition(-1, 0, false), scene);
        assertEquals(42L, entity.createdAs);
    }

    private static EntityUpdate eventUpdate(int code) {
        EntityUpdateImpl update = new EntityUpdateImpl(1L, PLAYER, new LinkedHashMap<>());
        update.putUpdate(POSITION, new TrackUpdate.Snapshot<>(new IntState(0)));
        update.putUpdate(SIGNAL, new TrackUpdate.Event<>(new Signal(code)));
        return update;
    }

    private static TestEntity entity(boolean skipJumps) {
        return new TestEntity(skipJumps);
    }

    private static final class TestEntity extends AbstractActor {

        private final boolean skipJumps;
        private final List<Integer> events = new ArrayList<>();
        private boolean created;
        private long createdAs = -1;

        private TestEntity(boolean skipJumps) {
            this.skipJumps = skipJumps;
        }

        @Override
        protected void configure() {
            TrackHandle<IntState, IntDelta> position = POSITION;
            TrackHandle<Void, Signal> signal = SIGNAL;
            onCreate(position, p -> {
                created = true;
                createdAs = getEntityId();
            });
            onEvent(signal, skipJumps, s -> events.add(s.code()));
        }

    }

}
