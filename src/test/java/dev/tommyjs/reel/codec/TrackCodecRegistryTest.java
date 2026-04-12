package dev.tommyjs.reel.codec;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrackCodecRegistryTest {

    static final TrackHandle<String, String> POS = TrackHandle.of("pos", String.class, String.class);
    static final TrackHandle<String, String> HEALTH = TrackHandle.of("health", String.class, String.class);
    static final TrackHandle<String, String> ALSO_POS = TrackHandle.of("pos", String.class, String.class);

    static final EntityTypeHandle<byte[]> PLAYER = EntityTypeHandle.of("player", byte[].class);
    static final EntityTypeHandle<byte[]> MOB = EntityTypeHandle.of("mob", byte[].class);
    static final EntityTypeHandle<byte[]> ALSO_PLAYER = EntityTypeHandle.of("player", byte[].class);

    @Test
    void registerAndRetrieveByHandle() {
        StubCodec codec = new StubCodec();
        TrackCodecRegistry registry = TrackCodecRegistry.builder()
                .register(POS, codec)
                .register(HEALTH, new StubCodec())
                .build();

        assertSame(codec, registry.get(POS));
        assertNotNull(registry.get(HEALTH));
        assertEquals(2, registry.size());
    }

    @Test
    void getUnknownTrackReturnsNull() {
        TrackCodecRegistry registry = TrackCodecRegistry.builder()
                .register(POS, new StubCodec())
                .build();

        assertNull(registry.get(HEALTH));
    }

    @Test
    void duplicateTrackIdThrows() {
        TrackCodecRegistry.Builder builder = TrackCodecRegistry.builder()
                .register(POS, new StubCodec());

        assertThrows(IllegalArgumentException.class,
                () -> builder.register(ALSO_POS, new StubCodec()));
    }

    @Test
    void trackIdsReturnsAllRegistered() {
        TrackHandle<String, String> a = TrackHandle.of("a", String.class, String.class);
        TrackHandle<String, String> b = TrackHandle.of("b", String.class, String.class);
        TrackHandle<String, String> c = TrackHandle.of("c", String.class, String.class);

        TrackCodecRegistry registry = TrackCodecRegistry.builder()
                .register(a, new StubCodec())
                .register(b, new StubCodec())
                .register(c, new StubCodec())
                .build();

        assertEquals(3, registry.trackIds().size());
        assertTrue(registry.trackIds().contains("a"));
        assertTrue(registry.trackIds().contains("b"));
        assertTrue(registry.trackIds().contains("c"));
    }

    @Test
    void emptyRegistryHasNoEntries() {
        TrackCodecRegistry registry = TrackCodecRegistry.builder().build();

        assertEquals(0, registry.size());
        assertNull(registry.get(POS));
        assertTrue(registry.trackIds().isEmpty());
        assertTrue(registry.entityTypeIds().isEmpty());
    }

    @Test
    void registryIsImmutable() {
        TrackCodecRegistry registry = TrackCodecRegistry.builder()
                .register(POS, new StubCodec())
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> registry.trackIds().add("hacked"));
    }

    @Test
    void getByStringId() {
        StubCodec codec = new StubCodec();
        TrackCodecRegistry registry = TrackCodecRegistry.builder()
                .register(POS, codec)
                .build();

        TrackCodec<String, String> found = registry.get("pos");
        assertSame(codec, found);
    }

    @Test
    void registerAndRetrieveEntityType() {
        StubEntityCodec codec = new StubEntityCodec();
        TrackCodecRegistry registry = TrackCodecRegistry.builder()
                .registerEntityType(PLAYER, codec)
                .build();

        assertSame(codec, registry.getEntityCodec(PLAYER));
        assertSame(codec, registry.getEntityCodec("player"));
        assertNotNull(registry.getEntityTypeHandle("player"));
        assertEquals(1, registry.entityTypeIds().size());
    }

    @Test
    void getUnknownEntityTypeReturnsNull() {
        TrackCodecRegistry registry = TrackCodecRegistry.builder()
                .registerEntityType(PLAYER, new StubEntityCodec())
                .build();

        assertNull(registry.getEntityCodec(MOB));
        assertNull(registry.getEntityTypeHandle("mob"));
    }

    @Test
    void duplicateEntityTypeThrows() {
        TrackCodecRegistry.Builder builder = TrackCodecRegistry.builder()
                .registerEntityType(PLAYER, new StubEntityCodec());

        assertThrows(IllegalArgumentException.class,
                () -> builder.registerEntityType(ALSO_PLAYER, new StubEntityCodec()));
    }

    @Test
    void mixedTrackAndEntityTypeRegistration() {
        TrackCodecRegistry registry = TrackCodecRegistry.builder()
                .register(POS, new StubCodec())
                .register(HEALTH, new StubCodec())
                .registerEntityType(PLAYER, new StubEntityCodec())
                .registerEntityType(MOB, new StubEntityCodec())
                .build();

        assertEquals(2, registry.size());
        assertEquals(2, registry.entityTypeIds().size());
        assertNotNull(registry.get(POS));
        assertNotNull(registry.getEntityCodec(PLAYER));
    }

    // --- test fixtures ---

    static class StubCodec extends AbstractTrackCodec<String, String> {

        StubCodec() {
            super(LayerStrategy.builder().build());
        }

        @Override
        public @NotNull String applyDelta(@NotNull String state, @NotNull String delta) {
            return state + delta;
        }

        @Override
        public @NotNull String cloneState(@NotNull String state) {
            return state;
        }

        @Override
        public @NotNull String condense(@NotNull String referenceState, @NotNull List<String> deltas) {
            return String.join("", deltas);
        }

        @Override
        public int spanCost(@NotNull String delta) {
            return 1;
        }

        @Override
        public int snapshotCost(@NotNull String state) {
            return 1;
        }

        @Override
        public byte @NotNull [] encodeSnapshot(@NotNull String state) {
            return state.getBytes();
        }

        @Override
        public @NotNull String decodeSnapshot(byte @NotNull [] data) {
            return new String(data);
        }

        @Override
        public byte @NotNull [] encodeSpan(@NotNull String delta) {
            return delta.getBytes();
        }

        @Override
        public @NotNull String decodeSpan(byte @NotNull [] data) {
            return new String(data);
        }

    }

    static class StubEntityCodec implements EntityMetadataCodec<byte[]> {

        @Override
        public byte @NotNull [] encode(byte @NotNull [] data) {
            return data.clone();
        }

        @Override
        public byte @NotNull [] decode(byte @NotNull [] data) {
            return data.clone();
        }

    }

}
