package dev.tommyjs.reel.replay.planner;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.tommyjs.reel.storage.model.*;
import dev.tommyjs.reel.storage.reader.ReelReader;
import dev.tommyjs.reel.track.TrackHandle;
import dev.tommyjs.reel.track.TrackRegistry;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ReplaySeekPlannerImpl implements ReplaySeekPlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReplaySeekPlannerImpl.class);

    private static final int DEFAULT_SEGMENT_CACHE_SIZE = 4;

    private final ReelReader reader;
    private final TrackRegistry registry;
    private final Cache<Integer, SegmentView> segmentCache;
    private final boolean readerOwnsSegments;
    private final long anchorPenalty;
    private final Set<Integer> unknownTrackTypes = new HashSet<>();
    private final Set<String> unregisteredTracks = new HashSet<>();

    private TrackDirectory trackDirectory;
    private SegmentDirectory segmentDirectory;
    private long revision;

    ReplaySeekPlannerImpl(@NotNull ReelReader reader, @NotNull TrackRegistry registry) {
        this(reader, registry, DEFAULT_SEGMENT_CACHE_SIZE, -1L);
    }

    ReplaySeekPlannerImpl(@NotNull ReelReader reader, @NotNull TrackRegistry registry, int segmentCacheSize) {
        this(reader, registry, segmentCacheSize, -1L);
    }

    ReplaySeekPlannerImpl(@NotNull ReelReader reader, @NotNull TrackRegistry registry, int segmentCacheSize,
                          long anchorSeekPenalty) {
        this.reader = reader;
        this.registry = registry;
        this.readerOwnsSegments = reader.ownsSegments();
        this.segmentCache = Caffeine.newBuilder().maximumSize(segmentCacheSize).<Integer, SegmentView>evictionListener(
            (idx, segment, cause) -> {
                if (segment != null) {
                    segment.close();
                }
            }).build();
        this.trackDirectory = reader.readTrackDirectory();
        this.segmentDirectory = reader.readSegmentDirectory();
        this.revision = reader.getRevision();

        long[] strides = trackDirectory.layerStrides();
        this.anchorPenalty = anchorSeekPenalty < 0 ? strides[strides.length - 1] : anchorSeekPenalty;
    }

    @Override
    public @NotNull SeekResult seek(long fromFrame, long toFrame) {
        return reader.withReadLock(() -> plan(fromFrame, toFrame));
    }

    private SeekResult plan(long fromFrame, long toFrame) {
        refreshIfStale();

        long[] strides = trackDirectory.layerStrides();
        boolean known = fromFrame != Long.MIN_VALUE;
        long anchor = segmentDirectory.floorAnchorStart(toFrame);

        List<WindowStep> steps;
        boolean complete;
        if (!known) {
            steps = walkForward((anchor >= 0 ? anchor : 0L) - 1, toFrame, strides);
            complete = true;
        } else if (toFrame >= fromFrame) {
            complete = anchor >= 0 && (anchor - fromFrame) > anchorPenalty;
            steps = complete
                ? walkForward(anchor - 1, toFrame, strides)
                : walkForward(fromFrame, toFrame, strides);
        } else {
            complete = anchor >= 0 && (fromFrame - toFrame) > (toFrame - anchor) + anchorPenalty;
            steps = complete
                ? walkForward(anchor - 1, toFrame, strides)
                : walkBackward(fromFrame, toFrame, strides);
        }

        Map<Long, EntitySteps> entities = new LinkedHashMap<>();
        for (WindowStep step : steps) {
            visitWindow(step.layer(), step.globalWindowIndex(), step.reversed(), strides, entities);
        }

        return new SeekResult(entities, complete);
    }

    private void refreshIfStale() {
        long current = reader.getRevision();
        if (current == revision) {
            return;
        }

        revision = current;
        trackDirectory = reader.readTrackDirectory();
        segmentDirectory = reader.readSegmentDirectory();
        invalidateOpenSegments();
    }

    private void invalidateOpenSegments() {
        int lastIndex = segmentDirectory.size() - 1;
        if (lastIndex < 0) {
            return;
        }

        for (int index : List.copyOf(segmentCache.asMap().keySet())) {
            if (index >= lastIndex) {
                SegmentView stale = segmentCache.asMap().remove(index);
                if (stale != null) {
                    stale.close();
                }
            }
        }
    }

    private static List<WindowStep> walkForward(long fromFrame, long toFrame, long[] strides) {
        List<WindowStep> steps = new ArrayList<>();
        long cursor = fromFrame + 1;
        while (cursor <= toFrame) {
            int layer = pickLayer(strides, cursor, toFrame - cursor + 1);
            long stride = strides[layer];
            steps.add(new WindowStep(layer, cursor / stride, false));
            cursor += stride;
        }
        return steps;
    }

    private static List<WindowStep> walkBackward(long fromFrame, long toFrame, long[] strides) {
        List<WindowStep> steps = new ArrayList<>();
        long cursor = fromFrame;
        while (cursor > toFrame) {
            int layer = pickLayer(strides, cursor + 1, cursor - toFrame);
            long stride = strides[layer];
            steps.add(new WindowStep(layer, cursor / stride, true));
            cursor -= stride;
        }
        return steps;
    }

    private static int pickLayer(long[] strides, long cursor, long remaining) {
        for (int l = strides.length - 1; l >= 0; l--) {
            long stride = strides[l];
            if (stride <= remaining && cursor % stride == 0) {
                return l;
            }
        }
        return 0;
    }

    private void visitWindow(int layer, long globalWindowIndex, boolean reversed, long[] strides,
                             Map<Long, EntitySteps> result) {
        long stride = strides[layer];
        long frameInWindow = globalWindowIndex * stride;
        int segIdx = segmentDirectory.floorSegment(frameInWindow);
        if (segIdx < 0) {
            return;
        }

        SegmentEntry entry = segmentDirectory.segments().get(segIdx);
        if (frameInWindow >= entry.endFrame()) {
            return;
        }

        SegmentView seg = readerOwnsSegments
            ? reader.readSegment(segIdx)
            : segmentCache.get(segIdx, reader::readSegment);

        int localWindowIdx = (int) (globalWindowIndex - entry.startFrame() / stride);
        if (localWindowIdx < 0 || localWindowIdx >= seg.getWindowCount(layer)) {
            return;
        }

        WindowGroup group = seg.getWindow(layer, localWindowIdx);
        if (group.size() == 0) {
            return;
        }

        long[] entityIds = seg.getEntityIds();
        int[] entityTypeIds = seg.getEntityTypeIds();
        TrackReference[] trackRefs = seg.getTrackRefs();

        WindowEntry[] groupEntries = group.entries();
        for (int i = 0; i < groupEntries.length; i++) {
            WindowEntry winEntry = reversed ? groupEntries[groupEntries.length - 1 - i] : groupEntries[i];
            int refIdx = winEntry.trackRefIndex();
            if (refIdx >= trackRefs.length) {
                continue;
            }
            TrackReference ref = trackRefs[refIdx];
            if (ref.entityIndex() >= entityIds.length) {
                continue;
            }

            long entityId = entityIds[ref.entityIndex()];
            int entityTypeId = entityTypeIds[ref.entityIndex()];
            TrackDefinition def = trackDirectory.get(ref.typeId());
            if (def == null) {
                if (unknownTrackTypes.add(ref.typeId())) {
                    LOGGER.warn("skipping payloads for track_type_id={} (absent from the track directory), "
                        + "entity_id={}", ref.typeId(), entityId);
                }
                continue;
            }

            TrackHandle<?, ?> handle = registry.getHandle(def.identifier());
            if (handle == null) {
                if (unregisteredTracks.add(def.identifier())) {
                    LOGGER.warn("skipping payloads for track \"{}\" (absent from the track registry)",
                        def.identifier());
                }
                continue;
            }

            WindowEntry retained = new WindowEntry(refIdx, winEntry.payload().retainedDuplicate());
            result.computeIfAbsent(entityId, k -> new EntitySteps(entityTypeId, new ArrayList<>()))
                .steps().add(new Step(handle, def.codecVersion(), def.kind(), retained, reversed));
        }
    }

    @Override
    public void close() {
        segmentCache.asMap().values().forEach(SegmentView::close);
        segmentCache.invalidateAll();
    }

    private record WindowStep(int layer, long globalWindowIndex, boolean reversed) {
    }

}
