package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.replay.planner.EntitySteps;
import dev.tommyjs.reel.replay.planner.ReplaySeekPlanner;
import dev.tommyjs.reel.replay.planner.SeekResult;
import dev.tommyjs.reel.replay.planner.Step;
import dev.tommyjs.reel.replay.update.*;
import dev.tommyjs.reel.storage.ReelIOUtil;
import dev.tommyjs.reel.storage.model.EntityTypeDefinition;
import dev.tommyjs.reel.storage.model.EntityTypeDirectory;
import dev.tommyjs.reel.storage.model.RawPayload;
import dev.tommyjs.reel.storage.model.TrackDefinition;
import dev.tommyjs.reel.storage.reader.ReelReader;
import dev.tommyjs.reel.track.EntityTypeHandle;
import dev.tommyjs.reel.track.RegisteredTrack;
import dev.tommyjs.reel.track.TrackKind;
import dev.tommyjs.reel.track.TrackModel;
import dev.tommyjs.reel.track.TrackRegistry;
import dev.tommyjs.reel.track.codec.Codec;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

final class ReplayCursorImpl implements ReplayCursor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayCursorImpl.class);

    private final ReelReader reader;
    private final boolean ownsReader;

    private final TrackRegistry registry;
    private final ReplaySeekPlanner planner;
    private final Long2ObjectOpenHashMap<EntityTypeHandle> entityTypesByTypeId;
    private final CopyOnWriteArrayList<ReplayListener> listeners;
    private final Set<Long> liveEntities;
    private final Set<String> validatedTracks;
    private final Set<Integer> unknownEntityTypes;

    private long currentFrame = Long.MIN_VALUE;
    private long revision;

    ReplayCursorImpl(@NotNull ReelReader reader, @NotNull TrackRegistry registry, int chunkCacheSize,
                     long anchorSeekPenalty, boolean ownsReader) {
        this.reader = reader;
        this.ownsReader = ownsReader;
        this.registry = registry;
        this.planner = ReplaySeekPlanner.create(reader, registry, chunkCacheSize, anchorSeekPenalty);
        this.entityTypesByTypeId = new Long2ObjectOpenHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.liveEntities = new LinkedHashSet<>();
        this.validatedTracks = new LinkedHashSet<>();
        this.unknownEntityTypes = new LinkedHashSet<>();
        this.revision = reader.getRevision();

        readEntityTypes();
        validateTrackKinds();
    }

    private void refreshIfStale() {
        long current = reader.getRevision();
        if (current == revision) {
            return;
        }

        readEntityTypes();
        validateTrackKinds();
        revision = current;
    }

    private void validateTrackKinds() {
        for (TrackDefinition def : reader.readTrackDirectory().trackTypes()) {
            if (validatedTracks.contains(def.identifier())) {
                continue;
            }
            RegisteredTrack<?, ?> track = registry.get(def.identifier());
            if (track != null && track.model().kind() != def.kind()) {
                throw new IllegalStateException("track kind mismatch for \"" + def.identifier() + "\": file=" + def.kind()
                    + ", registry=" + track.model().kind());
            }
            validatedTracks.add(def.identifier());
        }
    }

    private void readEntityTypes() {
        EntityTypeDirectory entityTypeDirectory = reader.readTypeDirectory();
        for (EntityTypeDefinition def : entityTypeDirectory.types()) {
            entityTypesByTypeId.put(def.id(), EntityTypeHandle.of(def.identifier()));
        }
    }

    @Override
    public long currentFrame() {
        return currentFrame == Long.MIN_VALUE ? -1L : currentFrame;
    }

    @Override
    public long totalFrames() {
        return reader.getTotalFrames();
    }

    @Override
    public boolean isComplete() {
        return reader.isComplete();
    }

    @Override
    public void addListener(@NotNull ReplayListener listener) {
        listeners.addIfAbsent(listener);
    }

    @Override
    public void removeListener(@NotNull ReplayListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void seek(long targetFrame) {
        refreshIfStale();

        long total = reader.getTotalFrames();
        if (targetFrame < 0 || targetFrame >= total) {
            throw new IllegalArgumentException(
                "targetFrame out of bounds: " + targetFrame + " (valid range [0, " + (total - 1) + "])");
        }

        if (targetFrame == currentFrame) {
            return;
        }

        SeekResult res = planner.seek(currentFrame, targetFrame);
        try {
            LinkedHashMap<Long, EntityUpdate> entities = new LinkedHashMap<>();
            Set<Long> present = new LinkedHashSet<>();
            Set<Long> closed = new LinkedHashSet<>();

            for (Map.Entry<Long, EntitySteps> e : res.entities().entrySet()) {
                long entityId = e.getKey();
                EntitySteps entitySteps = e.getValue();
                EntityTypeHandle type = entityTypesByTypeId.get(entitySteps.entityTypeId());

                if (type == null) {
                    if (unknownEntityTypes.add(entitySteps.entityTypeId())) {
                        LOGGER.warn("skipping updates for entity_type_id={} (absent from the type directory), "
                            + "entity_id={}", entitySteps.entityTypeId(), entityId);
                    }
                    continue;
                }

                for (Step rs : entitySteps.steps()) {
                    try {
                        List<TrackUpdate<?, ?>> steps = resolveStep(rs.entry().payload(), rs);
                        if (steps == null) {
                            closed.add(entityId);
                            present.remove(entityId);
                        } else if (!steps.isEmpty()) {
                            EntityUpdateImpl update = (EntityUpdateImpl) entities.computeIfAbsent(entityId,
                                id -> new EntityUpdateImpl(id, type, new LinkedHashMap<>()));
                            for (TrackUpdate<?, ?> step : steps) {
                                update.putUpdate(rs.handle(), step);
                            }
                            present.add(entityId);
                            closed.remove(entityId);
                        }
                    } catch (Exception ex) {
                        LOGGER.warn("skipping step for entity_id={} due to decode error: handle={}, reversed={}", entityId,
                            rs.handle(), rs.reversed(), ex);
                    }
                }
            }

            entities.keySet().removeAll(closed);

            Set<Long> removed = new LinkedHashSet<>();
            if (res.complete()) {
                for (long id : liveEntities) {
                    if (!present.contains(id)) {
                        removed.add(id);
                    }
                }
                liveEntities.clear();
                liveEntities.addAll(present);
            } else {
                for (long id : closed) {
                    if (liveEntities.contains(id)) {
                        removed.add(id);
                    }
                }
                liveEntities.removeAll(closed);
                liveEntities.addAll(present);
            }

            if (!entities.isEmpty() || !removed.isEmpty()) {
                long emittedFrom = currentFrame == Long.MIN_VALUE ? -1L : currentFrame;
                dispatch(new ReplayUpdateImpl(emittedFrom, targetFrame, entities, new LinkedHashSet<>(removed)));
            }

            currentFrame = targetFrame;
        } finally {
            for (EntitySteps entitySteps : res.entities().values()) {
                for (Step rs : entitySteps.steps()) {
                    ReferenceCountUtil.safeRelease(rs.entry().payload().body());
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<TrackUpdate<?, ?>> resolveStep(RawPayload payload, Step rs) throws IOException {
        RegisteredTrack track = registry.get(rs.handle());
        if (track == null || !track.hasVersion(rs.codecVersion())) {
            throw new IllegalStateException(
                "no codec found for track handle: " + rs.handle() + " version: " + rs.codecVersion());
        }

        TrackModel model = track.model();
        Codec stateCodec = track.stateCodec(rs.codecVersion());
        Codec deltaCodec = track.deltaCodec(rs.codecVersion());

        boolean reversed = rs.reversed();
        if (payload instanceof RawPayload.Close closePayload) {
            return reversed ? List.of(new TrackUpdate.Snapshot(stateCodec.decode(closePayload.body()))) : null;
        }

        if (payload instanceof RawPayload.Event eventPayload) {
            ByteBuf body = eventPayload.body();
            int count = ReelIOUtil.readVarIntAsInt(body, "event_count");
            List<Object> events = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                events.add(deltaCodec.decode(body));
            }

            List<TrackUpdate<?, ?>> steps = new ArrayList<>(count);
            if (reversed) {
                for (int i = count - 1; i >= 0; i--) {
                    steps.add(new TrackUpdate.Event(events.get(i)));
                }
            } else {
                for (Object event : events) {
                    steps.add(new TrackUpdate.Event(event));
                }
            }
            return steps;
        }

        if (payload instanceof RawPayload.Delta deltaPayload) {
            ByteBuf body = deltaPayload.body();
            int count = ReelIOUtil.readVarIntAsInt(body, "delta_count");
            List<Object> deltas = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                deltas.add(deltaCodec.decode(body));
            }

            List<TrackUpdate<?, ?>> steps = new ArrayList<>(count);
            if (reversed) {
                for (int i = count - 1; i >= 0; i--) {
                    steps.add(new TrackUpdate.Delta(model.reverse(deltas.get(i))));
                }
            } else {
                for (Object delta : deltas) {
                    steps.add(new TrackUpdate.Delta(delta));
                }
            }
            return steps;
        }

        RawPayload.State statePayload = (RawPayload.State) payload;
        Object previous = statePayload.hasPrevious() ? stateCodec.decode(statePayload.body()) : null;
        Object current = stateCodec.decode(statePayload.body());
        if (reversed) {
            return previous == null ? null : List.of(new TrackUpdate.Snapshot(previous));
        }
        return List.of(new TrackUpdate.Snapshot(current));
    }

    private void dispatch(ReplayUpdate update) {
        for (ReplayListener l : listeners) {
            try {
                l.onUpdate(update);
            } catch (Throwable t) {
                LOGGER.warn("Exception caught whilst dispatching listener update", t);
            }
        }
    }

    @Override
    public void close() {
        planner.close();
        listeners.clear();

        if (ownsReader) {
            try {
                reader.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

}
