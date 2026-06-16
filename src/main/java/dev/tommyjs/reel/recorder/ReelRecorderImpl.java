package dev.tommyjs.reel.recorder;

import dev.tommyjs.reel.storage.ReelIOUtil;
import dev.tommyjs.reel.storage.writer.LayerStrategy;
import dev.tommyjs.reel.storage.writer.ReelWriter;
import dev.tommyjs.reel.track.*;
import dev.tommyjs.reel.track.codec.Codec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;

final class ReelRecorderImpl implements ReelRecorder {

    private final ReelWriter writer;
    private final TrackRegistry trackRegistry;
    private final ByteBufAllocator allocator;

    private final LayerStrategy layerStrategy;
    private final long[] strides;
    private final long maxStride;
    private final long segmentLength;
    private final long idleThreshold;
    private final long anchorEverySegments;

    private final Map<String, Integer> entityTypeIds;
    private final Map<String, Integer> trackTypeIds;
    private final Set<EntityRecorderImpl> entities;

    private long nextEntityId;
    private int nextEntityTypeId;
    private int nextTrackTypeId;
    private long currentFrame;
    private long segmentStartFrame;
    private long lastActivityFrame;
    private long segmentsSinceAnchor;
    private boolean segmentOpen;
    private boolean closed;

    ReelRecorderImpl(@NotNull ReelWriter writer, @NotNull TrackRegistry trackRegistry, long segmentLength,
                     long idleThreshold, long anchorInterval) {
        this.writer = writer;
        this.trackRegistry = trackRegistry;
        this.allocator = writer.getAllocator();
        this.layerStrategy = writer.getLayerStrategy();
        this.strides = layerStrategy.strides();
        this.maxStride = layerStrategy.maxStride();
        this.segmentLength = segmentLength;
        this.idleThreshold = idleThreshold;
        this.anchorEverySegments = anchorInterval <= 0
            ? Long.MAX_VALUE
            : Math.max(1L, Math.round((double) anchorInterval / segmentLength));

        this.entityTypeIds = new LinkedHashMap<>();
        this.trackTypeIds = new LinkedHashMap<>();
        this.entities = new LinkedHashSet<>();

        this.nextEntityId = 0;
        this.nextEntityTypeId = 0;
        this.nextTrackTypeId = 0;
        this.currentFrame = 0;
        this.segmentStartFrame = 0;
        this.lastActivityFrame = 0;
        this.segmentsSinceAnchor = 0;
        this.closed = false;

        beginSegment(0);
    }

    @Override
    public @NotNull EntityRecorder createEntity(@NotNull EntityTypeHandle type) {
        ensureOpen();
        int entityTypeId = getEntityTypeId(type);
        long entityId = nextEntityId++;
        EntityRecorderImpl recorder = new EntityRecorderImpl(entityId, entityTypeId);
        entities.add(recorder);
        return recorder;
    }

    @Override
    public void advanceFrame() {
        ensureOpen();
        currentFrame++;

        if (!segmentOpen || currentFrame % maxStride != 0) {
            return;
        }

        long segmentFrames = currentFrame - segmentStartFrame;
        if (segmentFrames >= segmentLength) {
            sealSegment(currentFrame);
            beginSegment(currentFrame);
            return;
        }

        if (idleThreshold > 0 && currentFrame - lastActivityFrame >= idleThreshold) {
            sealSegment(currentFrame);
        }
    }

    private void sealSegment(long endFrame) {
        if (!segmentOpen) {
            return;
        }

        for (EntityRecorderImpl entity : entities) {
            entity.flushAllLayers();
        }

        writer.endSegment(endFrame);
        segmentOpen = false;
    }

    private void beginSegment(long startFrame) {
        boolean anchor;
        if (startFrame == 0) {
            anchor = true;
        } else {
            segmentsSinceAnchor++;
            anchor = segmentsSinceAnchor >= anchorEverySegments;
        }

        writer.beginSegment(startFrame, anchor);
        segmentStartFrame = startFrame;
        segmentOpen = true;

        if (anchor) {
            segmentsSinceAnchor = 0;
            for (EntityRecorderImpl entity : entities) {
                entity.primeAnchor(startFrame);
            }
        }
    }

    @Override
    public long currentFrame() {
        return currentFrame;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }

        closed = true;

        long totalFrames = currentFrame + 1;
        try {
            if (segmentOpen) {
                sealSegment(totalFrames);
            }
            writer.finish(totalFrames);
        } finally {
            writer.close();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Recorder is closed");
        }
    }

    private <S, D> RegisteredTrack<S, D> requireTrack(TrackHandle<S, D> handle) {
        RegisteredTrack<S, D> track = trackRegistry.get(handle);
        if (track == null) {
            throw new IllegalStateException("No codec for track \"" + handle.identifier() + "\"");
        }

        return track;
    }

    private int getTrackTypeId(TrackHandle<?, ?> handle, int codecVersion, TrackKind kind) {
        String id = handle.identifier();
        Integer existing = trackTypeIds.get(id);
        if (existing != null) {
            return existing;
        }

        int typeId = nextTrackTypeId++;
        trackTypeIds.put(id, typeId);
        writer.registerTrackType(id, typeId, codecVersion, kind);
        return typeId;
    }

    private int getEntityTypeId(EntityTypeHandle type) {
        String id = type.identifier();
        Integer existing = entityTypeIds.get(id);
        if (existing != null) {
            return existing;
        }

        int typeId = nextEntityTypeId++;
        entityTypeIds.put(id, typeId);
        writer.registerEntityType(id, typeId);
        return typeId;
    }

    private void ensureSegmentOpen() {
        if (!segmentOpen) {
            long startFrame = (currentFrame / maxStride) * maxStride;
            beginSegment(startFrame);
        }
    }

    private final class EntityRecorderImpl implements EntityRecorder {

        private final long entityId;
        private final int entityTypeId;
        private final Map<TrackHandle<?, ?>, TrackState<?, ?>> tracks;

        private boolean closed;

        private EntityRecorderImpl(long entityId, int entityTypeId) {
            this.entityId = entityId;
            this.entityTypeId = entityTypeId;
            this.tracks = new LinkedHashMap<>();
            this.closed = false;
        }

        @Override
        public long entityId() {
            return entityId;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }

            closed = true;

            if (hasInitializedTrack()) {
                ensureSegmentOpen();
                flushAllLayers();
                emitClose();
            }

            tracks.clear();
            entities.remove(this);
        }

        private boolean hasInitializedTrack() {
            for (TrackState<?, ?> ts : tracks.values()) {
                if (ts.initialized) {
                    return true;
                }
            }

            return false;
        }

        private void emitClose() {
            for (TrackState<?, ?> ts : tracks.values()) {
                if (ts.initialized) {
                    emitTrackClose(ts);
                }
            }
        }

        private <S, D> void emitTrackClose(TrackState<S, D> state) {
            if (state.kind == TrackKind.EVENT) {
                return;
            }
            for (int layer = 0; layer < layerStrategy.depth(); layer++) {
                S base = state.layers[layer].base;
                if (base == null) {
                    continue;
                }

                long window = (currentFrame / strides[layer]) * strides[layer];
                ByteBuf body = encodeStateBody(state.stateCodec, base);
                writer.writeClose(entityId, entityTypeId, state.trackTypeId, layer, window, body);
            }
        }

        private void ensureEntityOpen() {
            if (closed) {
                throw new IllegalStateException("Entity recorder is closed");
            }
        }

        @Override
        public <S, D> void recordDelta(@NotNull TrackHandle<S, D> track, @NotNull D delta) {
            ensureOpen();
            ensureEntityOpen();
            ensureSegmentOpen();

            lastActivityFrame = currentFrame;

            TrackState<S, D> state = getTrack(track);
            if (state.kind == TrackKind.EVENT) {
                throw new IllegalStateException("track \"" + track.identifier() + "\" is an event track; use recordEvent");
            }
            if (!state.initialized) {
                throw new IllegalStateException("Track \"" + track.identifier() + "\" has no initial state for entity");
            }

            for (int layer = 0; layer < layerStrategy.depth(); layer++) {
                syncWindow(state, layer);

                LayerAccumulator<S, D> accumulator = state.layers[layer];
                accumulator.deltas.add(delta);
            }
        }

        @Override
        public <S, D> void recordState(@NotNull TrackHandle<S, D> track, @NotNull S newState) {
            ensureOpen();
            ensureEntityOpen();
            ensureSegmentOpen();

            lastActivityFrame = currentFrame;
            TrackState<S, D> state = getTrack(track);
            if (state.kind == TrackKind.EVENT) {
                throw new IllegalStateException("track \"" + track.identifier() + "\" is an event track; use recordEvent");
            }

            for (int layer = 0; layer < layerStrategy.depth(); layer++) {
                syncWindow(state, layer);

                LayerAccumulator<S, D> accumulator = state.layers[layer];
                accumulator.deltas.clear();
                accumulator.snapshot = layer == 0 ? newState : state.model.cloneState(newState);
            }

            state.initialized = true;
        }

        @Override
        public <E> void recordEvent(@NotNull TrackHandle<Void, E> track, @NotNull E event) {
            ensureOpen();
            ensureEntityOpen();
            ensureSegmentOpen();

            lastActivityFrame = currentFrame;
            TrackState<Void, E> state = getTrack(track);
            if (state.kind != TrackKind.EVENT) {
                throw new IllegalStateException("track \"" + track.identifier() + "\" is not an event track");
            }

            for (int layer = 0; layer < layerStrategy.depth(); layer++) {
                syncWindow(state, layer);
                state.layers[layer].deltas.add(event);
            }
            state.initialized = true;
        }

        private <S, D> void syncWindow(TrackState<S, D> state, int layer) {
            LayerAccumulator<S, D> accumulator = state.layers[layer];
            long window = currentFrame / strides[layer];

            if (accumulator.windowIndex != window) {
                flush(state, layer);
                accumulator.windowIndex = window;
            }
        }

        private void primeAnchor(long startFrame) {
            for (TrackState<?, ?> ts : tracks.values()) {
                primeTrackAnchor(ts, startFrame);
            }
        }

        private <S, D> void primeTrackAnchor(TrackState<S, D> state, long startFrame) {
            if (!state.initialized || state.kind == TrackKind.EVENT) {
                return;
            }

            for (int layer = 0; layer < layerStrategy.depth(); layer++) {
                LayerAccumulator<S, D> accumulator = state.layers[layer];
                if (accumulator.base == null) {
                    continue;
                }

                accumulator.snapshot = state.model.cloneState(accumulator.base);
                accumulator.windowIndex = startFrame / strides[layer];
            }
        }

        private void flushAllLayers() {
            for (TrackState<?, ?> ts : tracks.values()) {
                flushAllTrackLayers(ts);
            }
        }

        private <S, D> void flushAllTrackLayers(TrackState<S, D> state) {
            for (int l = 0; l < layerStrategy.depth(); l++) {
                flush(state, l);
            }
        }

        private <S, D> void flush(TrackState<S, D> state, int layer) {
            LayerAccumulator<S, D> accumulator = state.layers[layer];
            if (accumulator.windowIndex == -1 || (accumulator.snapshot == null && accumulator.deltas.isEmpty())) {
                return;
            }

            TrackModel<S, D> model = state.model;
            Codec<S> stateCodec = state.stateCodec;
            Codec<D> deltaCodec = state.deltaCodec;
            long startFrame = accumulator.windowIndex * strides[layer];

            if (state.kind == TrackKind.EVENT) {
                if (!accumulator.deltas.isEmpty()) {
                    ByteBuf body = encodeDeltaBody(deltaCodec, accumulator.deltas);
                    writer.writeEvent(entityId, entityTypeId, state.trackTypeId, layer, startFrame, body);
                }
                accumulator.snapshot = null;
                accumulator.deltas.clear();
                accumulator.windowIndex = -1;
                return;
            }

            if (accumulator.snapshot == null) {
                S base = Objects.requireNonNull(accumulator.base);
                List<D> deltas = condenseDeltas(base, accumulator.deltas, model);
                if (!deltas.isEmpty()) {
                    S nextBase = base;
                    for (D delta : deltas) {
                        nextBase = model.applyDelta(nextBase, delta);
                    }
                    ByteBuf body = encodeDeltaBody(deltaCodec, deltas);
                    writer.writeDelta(entityId, entityTypeId, state.trackTypeId, layer, startFrame, body);
                    accumulator.base = nextBase;
                }
            } else {
                S end = accumulator.snapshot;
                if (!accumulator.deltas.isEmpty()) {
                    for (D delta : condenseDeltas(end, accumulator.deltas, model)) {
                        end = model.applyDelta(end, delta);
                    }
                }

                S previous = accumulator.base;
                ByteBuf previousBody = null;
                ByteBuf currentBody = null;
                try {
                    if (previous != null) {
                        previousBody = encodeStateBody(stateCodec, previous);
                    }
                    currentBody = encodeStateBody(stateCodec, end);
                } catch (Throwable t) {
                    ReferenceCountUtil.safeRelease(previousBody);
                    ReferenceCountUtil.safeRelease(currentBody);
                    throw t;
                }

                writer.writeState(entityId, entityTypeId, state.trackTypeId, layer, startFrame, previousBody,
                    currentBody);
                accumulator.base = end;
            }

            accumulator.snapshot = null;
            accumulator.deltas.clear();
            accumulator.windowIndex = -1;
        }

        private <D> ByteBuf encodeDeltaBody(Codec<D> deltaCodec, List<D> deltas) {
            ByteBuf body = allocator.buffer();
            try {
                ReelIOUtil.writeVarInt(body, deltas.size());
                for (D delta : deltas) {
                    deltaCodec.encode(body, delta);
                }
                return body;
            } catch (Throwable t) {
                ReferenceCountUtil.safeRelease(body);
                throw t;
            }
        }

        private <S> ByteBuf encodeStateBody(Codec<S> stateCodec, S value) {
            ByteBuf body = allocator.buffer();
            try {
                stateCodec.encode(body, value);
                return body;
            } catch (Throwable t) {
                ReferenceCountUtil.safeRelease(body);
                throw t;
            }
        }

        private <S, D> List<D> condenseDeltas(S state, List<D> deltas, TrackModel<S, D> model) {
            return deltas.size() == 1 ? deltas : model.condense(state, deltas);
        }

        private <S, D> TrackState<S, D> getTrack(TrackHandle<S, D> handle) {
            TrackState<?, ?> existing = tracks.get(handle);
            if (existing != null) {
                //noinspection unchecked
                return (TrackState<S, D>) existing;
            }

            RegisteredTrack<S, D> registered = requireTrack(handle);
            TrackModel<S, D> model = registered.model();
            int typeId = getTrackTypeId(handle, registered.latestVersion(), model.kind());
            TrackState<S, D> state = new TrackState<>(model, registered.latestStateCodec(),
                registered.latestDeltaCodec(), typeId, layerStrategy.depth());
            tracks.put(handle, state);

            return state;
        }

    }

    private static final class LayerAccumulator<S, D> {

        private final List<D> deltas;

        private long windowIndex = -1;
        private @Nullable S base;
        private @Nullable S snapshot;

        private LayerAccumulator() {
            this.deltas = new ArrayList<>();
        }

    }

    private static final class TrackState<S, D> {

        private final TrackModel<S, D> model;
        private final Codec<S> stateCodec;
        private final Codec<D> deltaCodec;
        private final TrackKind kind;
        private final int trackTypeId;
        private final LayerAccumulator<S, D>[] layers;

        private boolean initialized;

        private TrackState(TrackModel<S, D> model, Codec<S> stateCodec, Codec<D> deltaCodec, int trackTypeId,
                           int layers) {
            this.model = model;
            this.stateCodec = stateCodec;
            this.deltaCodec = deltaCodec;
            this.kind = model.kind();
            this.trackTypeId = trackTypeId;

            //noinspection unchecked
            this.layers = (LayerAccumulator<S, D>[]) new LayerAccumulator[layers];
            for (int i = 0; i < layers; i++) {
                this.layers[i] = new LayerAccumulator<>();
            }
        }

    }

}
