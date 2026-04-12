package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.codec.EntityMetadataCodec;
import dev.tommyjs.reel.codec.TrackCodec;
import dev.tommyjs.reel.codec.TrackCodecRegistry;
import dev.tommyjs.reel.codec.TrackHandle;
import dev.tommyjs.reel.storage.ReelReader;
import dev.tommyjs.reel.storage.model.*;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;

class ReelSandboxImpl implements ReelSandbox {

    private final ReelReader reader;
    private final TrackCodecRegistry registry;
    private final EntityHandlerRegistry entityHandlers;
    private final ReelSeekPlanner planner;
    private final EntityDirectory entityDirectory;
    private final long totalFrames;

    private final Map<String, HandlerEntry<?, ?>> trackHandlers = new HashMap<>();

    private final Set<Long> aliveEntities = new HashSet<>();
    private final Map<Long, Map<String, Object>> entityStates = new HashMap<>();
    private final Map<Long, Map<String, Long>> entityTrackFrames = new HashMap<>();
    private final Map<Long, Map<String, ReelSeekPlanner.TrackPlan>> trackPlanCache = new HashMap<>();
    private final Map<Long, EntityTrackIndex> trackIndexCache = new HashMap<>();

    private long currentFrame = -1;

    ReelSandboxImpl(ReelReader reader, TrackCodecRegistry registry,
                    EntityHandlerRegistry entityHandlers, ReelSeekPlanner planner) {
        this.reader = reader;
        this.registry = registry;
        this.entityHandlers = entityHandlers;
        this.planner = planner;
        this.entityDirectory = reader.readEntityDirectory();
        this.totalFrames = reader.getTotalFrames();
    }

    @Override
    public <S, D> void registerHandler(@NotNull TrackHandle<S, D> track, @NotNull SnapshotTrackHandler<S, D> handler) {
        TrackCodec<S, D> codec = registry.get(track);
        if (codec == null) {
            throw new IllegalStateException("Track '" + track.trackId() + "' is not in the codec registry");
        }
        trackHandlers.put(track.trackId(), new HandlerEntry<>(handler, codec, false));
    }

    @Override
    public <S, D> void registerDeltaHandler(@NotNull TrackHandle<S, D> track, @NotNull DeltaTrackHandler<S, D> handler) {
        TrackCodec<S, D> codec = registry.get(track);
        if (codec == null) {
            throw new IllegalStateException("Track '" + track.trackId() + "' is not in the codec registry");
        }
        trackHandlers.put(track.trackId(), new HandlerEntry<>(handler, codec, true));
    }

    @Override
    public void seekTo(long frame) {
        Set<Long> newAlive = computeAliveEntities(frame);
        Set<Long> toDespawn = new LinkedHashSet<>(aliveEntities);
        toDespawn.removeAll(newAlive);
        Set<Long> toSpawn = new LinkedHashSet<>(newAlive);
        toSpawn.removeAll(aliveEntities);
        Set<Long> remaining = new LinkedHashSet<>(aliveEntities);
        remaining.retainAll(newAlive);

        // Despawn departing entities
        for (long eid : toDespawn) {
            fireEntityDespawn(eid);
            entityStates.remove(eid);
            entityTrackFrames.remove(eid);
            trackPlanCache.remove(eid);
        }

        // Update tracks on entities that remain alive
        for (long eid : remaining) {
            updateEntityTracks(eid, frame);
        }

        // Spawn arriving entities and initialize their tracks
        for (long eid : toSpawn) {
            fireEntitySpawn(eid, frame);
            updateEntityTracks(eid, frame);
        }

        aliveEntities.clear();
        aliveEntities.addAll(newAlive);
        currentFrame = frame;
    }

    @Override
    public long currentFrame() {
        return currentFrame;
    }

    @Override
    public long totalFrames() {
        return totalFrames;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    // --- entity lifecycle ---

    @SuppressWarnings("unchecked")
    private <T> void fireEntitySpawn(long entityId, long frame) {
        EntityEntry entry = findEntityEntry(entityId);
        if (entry == null) return;

        EntityHandler<T> handler = entityHandlers.get(entry.getEntityType());
        if (handler == null) return;

        EntityMetadataCodec<T> codec = registry.getEntityCodec(entry.getEntityType());
        if (codec == null) return;

        T decoded = codec.decode(entry.getMetadata());
        handler.onSpawn(entityId, decoded);
    }

    @SuppressWarnings("unchecked")
    private <T> void fireEntityDespawn(long entityId) {
        EntityEntry entry = findEntityEntry(entityId);
        if (entry == null) return;

        EntityHandler<T> handler = entityHandlers.get(entry.getEntityType());
        if (handler != null) {
            handler.onDespawn(entityId);
        }
    }

    private Set<Long> computeAliveEntities(long frame) {
        Set<Long> alive = new LinkedHashSet<>();
        for (EntityEntry entity : entityDirectory.getEntities()) {
            if (isAliveAt(entity, frame)) {
                alive.add(entity.getEntityId());
            }
        }
        return alive;
    }

    private boolean isAliveAt(EntityEntry entity, long frame) {
        for (EntitySpawnEntry spawn : entity.getSpawns()) {
            if (spawn.getSpawnFrame() <= frame
                    && (spawn.getDespawnFrame() == -1 || spawn.getDespawnFrame() > frame)) {
                return true;
            }
        }
        return false;
    }

    private EntitySpawnEntry findSpawnEntry(EntityEntry entry, long frame) {
        for (EntitySpawnEntry spawn : entry.getSpawns()) {
            if (spawn.getSpawnFrame() <= frame
                    && (spawn.getDespawnFrame() == -1 || spawn.getDespawnFrame() > frame)) {
                return spawn;
            }
        }
        return null;
    }

    private EntityEntry findEntityEntry(long entityId) {
        for (EntityEntry e : entityDirectory.getEntities()) {
            if (e.getEntityId() == entityId) return e;
        }
        return null;
    }

    // --- track update logic ---

    private void updateEntityTracks(long entityId, long targetFrame) {
        EntityTrackIndex idx = getTrackIndex(entityId);
        EntityEntry entry = findEntityEntry(entityId);
        EntitySpawnEntry spawn = entry != null ? findSpawnEntry(entry, targetFrame) : null;

        for (EntityTrackEntry track : idx.getTracks()) {
            String trackId = track.getTrackId();
            HandlerEntry<?, ?> handlerEntry = trackHandlers.get(trackId);
            if (handlerEntry == null) continue;

            List<TrackSpan> spans = filterSpans(track.getSpans(), spawn);
            List<TrackSnapshot> snapshots = filterSnapshots(track.getSnapshots(), spawn);

            long effectiveFrom = getEffectiveFrame(entityId, trackId);

            ReelSeekPlanner.TrackPlan plan = getTrackPlan(entityId, trackId, spans, snapshots, handlerEntry.codec.isReversible());
            List<SeekStep> steps = plan.seek(effectiveFrom, targetFrame);
            if (steps.isEmpty()) continue;

            if (handlerEntry.isDelta) {
                executeDeltaSteps(entityId, trackId, handlerEntry, steps);
            } else {
                executeSnapshotSteps(entityId, trackId, handlerEntry, steps);
            }
        }
    }

    /**
     * For SnapshotTrackHandler: compute final state internally, deliver single onSnapshot.
     */
    @SuppressWarnings("unchecked")
    private <S, D> void executeSnapshotSteps(long entityId, String trackId,
                                              HandlerEntry<S, D> entry, List<SeekStep> steps) {
        TrackCodec<S, D> codec = entry.codec;
        S state = (S) entityStates.computeIfAbsent(entityId, k -> new HashMap<>()).get(trackId);

        for (SeekStep step : steps) {
            if (step instanceof SeekStep.Snapshot s) {
                byte[] data = reader.readTrackData(s.snapshot().refId());
                state = codec.decodeSnapshot(data);
            } else if (step instanceof SeekStep.Forward f) {
                byte[] data = reader.readTrackData(f.span().refId());
                D delta = codec.decodeSpan(data);
                state = codec.applyDelta(state, delta);
            } else if (step instanceof SeekStep.Backward b) {
                byte[] data = reader.readTrackData(b.span().refId());
                D delta = codec.decodeSpan(data);
                D reversed = codec.reverse(delta);
                state = codec.applyDelta(state, reversed);
            }
        }

        entityStates.computeIfAbsent(entityId, k -> new HashMap<>()).put(trackId, state);
        setEffectiveFrame(entityId, trackId, lastFrameOf(steps));
        ((SnapshotTrackHandler<S, D>) entry.handler).onSnapshot(entityId, state);
    }

    /**
     * For DeltaTrackHandler: forward each step directly to the handler.
     */
    @SuppressWarnings("unchecked")
    private <S, D> void executeDeltaSteps(long entityId, String trackId,
                                           HandlerEntry<S, D> entry, List<SeekStep> steps) {
        TrackCodec<S, D> codec = entry.codec;
        DeltaTrackHandler<S, D> handler = (DeltaTrackHandler<S, D>) entry.handler;

        for (SeekStep step : steps) {
            if (step instanceof SeekStep.Snapshot s) {
                byte[] data = reader.readTrackData(s.snapshot().refId());
                S state = codec.decodeSnapshot(data);
                handler.onSnapshot(entityId, state);
            } else if (step instanceof SeekStep.Forward f) {
                byte[] data = reader.readTrackData(f.span().refId());
                D delta = codec.decodeSpan(data);
                handler.onDelta(entityId, delta);
            } else if (step instanceof SeekStep.Backward b) {
                byte[] data = reader.readTrackData(b.span().refId());
                D delta = codec.decodeSpan(data);
                D reversed = codec.reverse(delta);
                handler.onDelta(entityId, reversed);
            }
        }

        setEffectiveFrame(entityId, trackId, lastFrameOf(steps));
    }

    // --- span/snapshot filtering for spawn cycles ---

    private List<TrackSpan> filterSpans(List<TrackSpan> spans, EntitySpawnEntry spawn) {
        if (spawn == null) return spans;
        long start = spawn.getSpawnFrame();
        long end = spawn.getDespawnFrame();
        List<TrackSpan> filtered = new ArrayList<>();
        for (TrackSpan s : spans) {
            if (s.referenceFrame() >= start && (end == -1 || s.targetFrame() <= end)) {
                filtered.add(s);
            }
        }
        return filtered;
    }

    private List<TrackSnapshot> filterSnapshots(List<TrackSnapshot> snapshots, EntitySpawnEntry spawn) {
        if (spawn == null) return snapshots;
        long start = spawn.getSpawnFrame();
        long end = spawn.getDespawnFrame();
        List<TrackSnapshot> filtered = new ArrayList<>();
        for (TrackSnapshot s : snapshots) {
            if (s.frame() >= start && (end == -1 || s.frame() <= end)) {
                filtered.add(s);
            }
        }
        return filtered;
    }

    // --- track plan caching ---

    private ReelSeekPlanner.TrackPlan getTrackPlan(long entityId, String trackId,
                                                    List<TrackSpan> spans, List<TrackSnapshot> snapshots,
                                                    boolean reversible) {
        return trackPlanCache
                .computeIfAbsent(entityId, k -> new HashMap<>())
                .computeIfAbsent(trackId, k -> planner.prepare(spans, snapshots, reversible));
    }

    // --- frame tracking ---

    private long getEffectiveFrame(long entityId, String trackId) {
        Map<String, Long> frames = entityTrackFrames.get(entityId);
        if (frames == null) return -1;
        return frames.getOrDefault(trackId, -1L);
    }

    private void setEffectiveFrame(long entityId, String trackId, long frame) {
        entityTrackFrames.computeIfAbsent(entityId, k -> new HashMap<>()).put(trackId, frame);
    }

    private long lastFrameOf(List<SeekStep> steps) {
        SeekStep last = steps.get(steps.size() - 1);
        if (last instanceof SeekStep.Snapshot s) {
            return s.snapshot().frame();
        } else if (last instanceof SeekStep.Forward f) {
            return f.span().targetFrame();
        } else if (last instanceof SeekStep.Backward b) {
            return b.span().referenceFrame();
        }
        throw new IllegalStateException("Unknown seek step type");
    }

    private EntityTrackIndex getTrackIndex(long entityId) {
        return trackIndexCache.computeIfAbsent(entityId, reader::readTrackIndex);
    }

    // --- internal types ---

    private record HandlerEntry<S, D>(SnapshotTrackHandler<S, D> handler, TrackCodec<S, D> codec, boolean isDelta) {}

}
