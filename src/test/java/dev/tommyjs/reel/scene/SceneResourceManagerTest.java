package dev.tommyjs.reel.scene;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneResourceManagerTest {

    private static final SceneResourceKey<String, Integer> KEY = SceneResourceKey.of("k");
    private static final SceneResourceKey<String, Integer> OTHER = SceneResourceKey.of("other");

    @Test
    void publishThenFindReturnsValue() {
        SceneResourceManagerImpl manager = new SceneResourceManagerImpl();
        manager.publish(KEY, "a", 1);
        assertEquals(1, manager.find(KEY, "a"));
    }

    @Test
    void findReturnsNullForMissing() {
        SceneResourceManagerImpl manager = new SceneResourceManagerImpl();
        assertNull(manager.find(KEY, "a"));
        manager.publish(KEY, "a", 1);
        assertNull(manager.find(KEY, "b"));
        assertNull(manager.find(OTHER, "a"));
    }

    @Test
    void requireThrowsForMissing() {
        SceneResourceManagerImpl manager = new SceneResourceManagerImpl();
        assertThrows(IllegalStateException.class, () -> manager.require(KEY, "a"));
        manager.publish(KEY, "a", 5);
        assertEquals(5, manager.require(KEY, "a"));
    }

    @Test
    void unpublishRemovesValue() {
        SceneResourceManagerImpl manager = new SceneResourceManagerImpl();
        manager.publish(KEY, "a", 1);
        manager.unpublish(KEY, "a");
        assertNull(manager.find(KEY, "a"));
        manager.unpublish(KEY, "missing");
        manager.unpublish(OTHER, "a");
    }

    @Test
    void getResourcesReturnsAllValuesInInsertionOrder() {
        SceneResourceManagerImpl manager = new SceneResourceManagerImpl();
        assertTrue(manager.getResources(KEY).isEmpty());
        manager.publish(KEY, "a", 1);
        manager.publish(KEY, "b", 2);
        assertEquals(List.of(1, 2), List.copyOf(manager.getResources(KEY)));
    }

    @Test
    void clearRemovesEverything() {
        SceneResourceManagerImpl manager = new SceneResourceManagerImpl();
        manager.publish(KEY, "a", 1);
        manager.clear();
        assertNull(manager.find(KEY, "a"));
        assertTrue(manager.getResources(KEY).isEmpty());
    }

}
