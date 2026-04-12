package dev.tommyjs.reel;

import dev.tommyjs.reel.codec.*;
import dev.tommyjs.reel.storage.ReelWriter;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;

class ReelRecorderImpl implements ReelRecorder {

    private final ReelWriter writer;
    private final TrackCodecRegistry registry;
    private final Set<String> registeredEntityTypes = new HashSet<>();
    private final Map<Long, EntityHandleImpl> entities = new LinkedHashMap<>();

    private long nextEntityId = 0;
    private long currentFrame = 0;
    private boolean finished = false;

    ReelRecorderImpl(ReelWriter writer, TrackCodecRegistry registry) {
        this.writer = writer;
        this.registry = registry;

        registerTracks(writer, registry);
    }

    @Override
    public <T> @NotNull EntityHandle createEntity(@NotNull EntityTypeHandle<T> type, @NotNull T metadata) {
        ensureNotFinished();
        long entityId = nextEntityId++;
        EntityMetadataCodec<T> codec = resolveEntityCodec(type);
        byte[] encoded = codec.encode(metadata);
        String typeId = type.typeId();
        if (registeredEntityTypes.add(typeId)) {
            writer.registerEntityType(typeId);
        }
        EntityHandleImpl handle = new EntityHandleImpl(entityId, typeId);
        entities.put(entityId, handle);
        writer.writeEntityCreate(entityId, typeId, encoded);
        return handle;
    }

    @Override
    public void spawnEntity(@NotNull EntityHandle handle) {
        ensureNotFinished();
        if (!(handle instanceof EntityHandleImpl impl)) {
            throw new IllegalArgumentException("EntityHandle was not created by this recorder");
        }
        if (impl.spawned) {
            throw new IllegalStateException("Entity " + impl.entityId + " is already spawned");
        }
        impl.trackStates.clear();
        writer.writeEntitySpawn(impl.entityId, currentFrame);
        impl.spawned = true;
    }

    @Override
    public void advanceFrame() {
        ensureNotFinished();
        currentFrame++;
    }

    @Override
    public long currentFrame() {
        return currentFrame;
    }

    @Override
    public void close() throws IOException {
        if (finished) return;
        finished = true;
        for (EntityHandleImpl entity : entities.values()) {
            entity.flushAllTracks();
        }
        writer.finish(currentFrame);
        writer.close();
    }

    private void ensureNotFinished() {
        if (finished) {
            throw new IllegalStateException("Recorder has already been finished");
        }
    }

    private <S, D> TrackCodec<S, D> resolveCodec(TrackHandle<S, D> handle) {
        TrackCodec<S, D> codec = registry.get(handle);
        if (codec == null) {
            throw new IllegalStateException("Track \"" + handle.trackId() + "\" is not registered in the codec registry");
        }
        return codec;
    }

    private <T> EntityMetadataCodec<T> resolveEntityCodec(EntityTypeHandle<T> handle) {
        EntityMetadataCodec<T> codec = registry.getEntityCodec(handle);
        if (codec == null) {
            throw new IllegalStateException("Entity type \"" + handle.typeId() + "\" is not registered in the codec registry");
        }
        return codec;
    }

    private void registerTracks(ReelWriter writer, TrackCodecRegistry registry) {
        for (String trackId : registry.trackIds()) {
            TrackHandle<?, ?> handle = registry.getHandle(trackId);
            if (handle == null) {
                continue;
            }
            writer.registerTrack(trackId, handle.codecVersion());
        }
    }

    private class EntityHandleImpl implements EntityHandle {

        private final long entityId;
        private final String entityType;
        private final Map<String, TrackState<?, ?>> trackStates = new LinkedHashMap<>();
        private boolean spawned = false;

        EntityHandleImpl(long entityId, String entityType) {
            this.entityId = entityId;
            this.entityType = entityType;
        }

        @Override
        public long entityId() {
            return entityId;
        }

        @Override
        public <S, D> void recordDelta(@NotNull TrackHandle<S, D> track, @NotNull D delta) {
            ensureNotFinished();
            ensureSpawned();
            TrackCodec<S, D> codec = resolveCodec(track);
            TrackState<S, D> state = getTrackState(track, codec);
            if (!state.initialized) {
                throw new IllegalStateException(
                        "Track '" + track.trackId() + "' must be initialized with recordSnapshot before recording deltas");
            }

            state.currentState = codec.applyDelta(state.currentState, delta);
            int deltaCost = codec.spanCost(delta);
            LayerStrategy strategy = codec.layerStrategy();

            for (int i = 0; i < state.layers.length; i++) {
                LayerAccumulator<S, D> layer = state.layers[i];
                layer.deltas.add(delta);
                layer.accumulatedCost += deltaCost;

                int threshold = strategy.layers().get(i).costThreshold();
                if (layer.accumulatedCost >= threshold) {
                    flushLayer(track.trackId(), codec, state, layer);
                }
            }

            state.snapshotAccumulatedCost += deltaCost;
            if (strategy.snapshotsEnabled() && state.snapshotAccumulatedCost >= strategy.snapshotCostThreshold()) {
                writeSnapshot(track.trackId(), codec, state);
            }
        }

        @Override
        public <S, D> void recordSnapshot(@NotNull TrackHandle<S, D> track, @NotNull S newState) {
            ensureNotFinished();
            ensureSpawned();
            TrackCodec<S, D> codec = resolveCodec(track);
            TrackState<S, D> state = getTrackState(track, codec);

            state.currentState = newState;
            state.initialized = true;

            for (LayerAccumulator<S, D> layer : state.layers) {
                if (!layer.deltas.isEmpty()) {
                    flushLayer(track.trackId(), codec, state, layer);
                }
                layer.referenceState = codec.cloneState(newState);
                layer.referenceFrame = currentFrame;
            }

            writeSnapshot(track.trackId(), codec, state);
        }

        @Override
        public <S, D> void recordSnapshot(@NotNull TrackHandle<S, D> track) {
            ensureNotFinished();
            ensureSpawned();
            TrackCodec<S, D> codec = resolveCodec(track);
            TrackState<S, D> state = getTrackState(track, codec);
            if (!state.initialized) {
                throw new IllegalStateException(
                        "Track '" + track.trackId() + "' must be initialized with recordSnapshot(track, state) first");
            }

            for (LayerAccumulator<S, D> layer : state.layers) {
                if (!layer.deltas.isEmpty()) {
                    flushLayer(track.trackId(), codec, state, layer);
                }
                layer.referenceState = codec.cloneState(state.currentState);
                layer.referenceFrame = currentFrame;
            }

            writeSnapshot(track.trackId(), codec, state);
        }

        @Override
        public void despawn() {
            ensureNotFinished();
            ensureSpawned();
            flushAllTracks();
            writer.writeEntityDespawn(entityId, currentFrame);
            spawned = false;
        }

        private void ensureSpawned() {
            if (!spawned) {
                throw new IllegalStateException("Entity " + entityId + " is despawned");
            }
        }

        @SuppressWarnings("unchecked")
        private <S, D> TrackState<S, D> getTrackState(TrackHandle<S, D> handle, TrackCodec<S, D> codec) {
            return (TrackState<S, D>) trackStates.computeIfAbsent(handle.trackId(),
                    k -> new TrackState<>(codec));
        }

        private void flushAllTracks() {
            for (var entry : trackStates.entrySet()) {
                flushTrackState(entry.getKey(), entry.getValue());
            }
        }

        private <S, D> void flushTrackState(String trackId, TrackState<S, D> state) {
            if (!state.initialized) return;
            TrackCodec<S, D> codec = state.codec;
            for (LayerAccumulator<S, D> layer : state.layers) {
                if (!layer.deltas.isEmpty()) {
                    flushLayer(trackId, codec, state, layer);
                }
            }
        }

        private <S, D> void flushLayer(String trackId, TrackCodec<S, D> codec,
                                        TrackState<S, D> state, LayerAccumulator<S, D> layer) {
            D condensed;
            if (layer.deltas.size() == 1) {
                condensed = layer.deltas.get(0);
            } else {
                condensed = codec.condense(layer.referenceState, layer.deltas);
            }
            int spanCost = codec.spanCost(condensed);
            byte[] encoded = codec.encodeSpan(condensed);
            writer.writeTrackSpan(entityId, trackId, layer.referenceFrame, currentFrame, spanCost, encoded);

            layer.referenceState = codec.cloneState(state.currentState);
            layer.deltas.clear();
            layer.accumulatedCost = 0;
            layer.referenceFrame = currentFrame;
        }

        private <S, D> void writeSnapshot(String trackId, TrackCodec<S, D> codec, TrackState<S, D> state) {
            int cost = codec.snapshotCost(state.currentState);
            byte[] encoded = codec.encodeSnapshot(state.currentState);
            writer.writeTrackSnapshot(entityId, trackId, currentFrame, cost, encoded);
            state.snapshotAccumulatedCost = 0;
        }

    }

    private static class TrackState<S, D> {

        final TrackCodec<S, D> codec;
        final LayerAccumulator<S, D>[] layers;
        S currentState;
        boolean initialized;
        long snapshotAccumulatedCost;

        @SuppressWarnings("unchecked")
        TrackState(TrackCodec<S, D> codec) {
            this.codec = codec;
            List<LayerDefinition> layerDefinitions = codec.layerStrategy().layers();
            this.layers = new LayerAccumulator[layerDefinitions.size()];
            for (int i = 0; i < layers.length; i++) {
                layers[i] = new LayerAccumulator<>();
            }
        }

    }

    private static class LayerAccumulator<S, D> {

        S referenceState;
        final List<D> deltas = new ArrayList<>();
        long accumulatedCost;
        long referenceFrame;

    }

}
