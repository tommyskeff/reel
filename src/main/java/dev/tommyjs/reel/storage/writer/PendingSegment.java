package dev.tommyjs.reel.storage.writer;

import dev.tommyjs.reel.storage.model.*;
import io.netty.util.ReferenceCountUtil;
import it.unimi.dsi.fastutil.longs.Long2IntAVLTreeMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

final class PendingSegment {

    private final long startFrame;
    private final boolean anchor;
    private final long[] layerStrides;
    private final int layerCount;

    private final Long2IntAVLTreeMap entityTypes;
    private final List<TrackRefKey> trackRefList;
    private final Map<TrackRefKey, Integer> trackRefIndex;
    private final @Nullable LongOpenHashSet anchorFirstEntry;

    private final TreeMap<Integer, List<PendingEntry>>[] layerWindows;

    PendingSegment(long startFrame, @NotNull LayerStrategy layerStrategy, boolean anchor) {
        this.startFrame = startFrame;
        this.anchor = anchor;
        this.layerStrides = layerStrategy.strides();
        this.layerCount = layerStrategy.depth();
        this.anchorFirstEntry = anchor ? new LongOpenHashSet() : null;
        this.entityTypes = new Long2IntAVLTreeMap();
        this.entityTypes.defaultReturnValue(-1);
        this.trackRefList = new ArrayList<>();
        this.trackRefIndex = new LinkedHashMap<>();

        //noinspection unchecked
        this.layerWindows = new TreeMap[layerCount];
        for (int i = 0; i < layerCount; i++) {
            this.layerWindows[i] = new TreeMap<>();
        }
    }

    void addEntry(long entityId, int entityTypeId, int trackTypeId, int layer, long windowStartFrame,
                  @NotNull RawPayload payload, boolean requiresPrior) {
        if (layer < 0 || layer >= layerCount) {
            throw new IllegalArgumentException(
                "layer out of range [0, " + layerCount + "): layer=" + layer);
        }
        long stride = layerStrides[layer];
        if (windowStartFrame % stride != 0) {
            throw new IllegalArgumentException(
                "windowStartFrame must be aligned to layer stride (" + stride
                    + "): windowStartFrame=" + windowStartFrame + ", layer=" + layer);
        }
        if (windowStartFrame < startFrame) {
            throw new IllegalArgumentException(
                "windowStartFrame is before segment start: windowStartFrame=" + windowStartFrame
                    + ", startFrame=" + startFrame);
        }

        registerEntity(entityId, entityTypeId);
        int refIdx = registerTrack(entityId, trackTypeId);

        if (anchorFirstEntry != null) {
            long key = (long) refIdx * layerCount + layer;
            if (anchorFirstEntry.add(key) && requiresPrior) {
                throw new IllegalStateException(
                    "anchor segment requires a STATE entry before any DELTA: entity_id=" + entityId
                        + ", track_type_id=" + trackTypeId + ", layer=" + layer);
            }
        }

        int localWindowIdx = (int) ((windowStartFrame - startFrame) / stride);

        layerWindows[layer]
            .computeIfAbsent(localWindowIdx, k -> new ArrayList<>())
            .add(new PendingEntry(refIdx, payload));
    }

    private void registerEntity(long entityId, int entityTypeId) {
        int prev = entityTypes.putIfAbsent(entityId, entityTypeId);
        if (prev != -1 && prev != entityTypeId) {
            throw new IllegalArgumentException(
                "entity_type_id mismatch within segment: entity_id=" + entityId
                    + ", existing=" + prev + ", new=" + entityTypeId);
        }
    }

    private int registerTrack(long entityId, int trackTypeId) {
        TrackRefKey key = new TrackRefKey(entityId, trackTypeId);
        Integer idx = trackRefIndex.get(key);
        if (idx != null) {
            return idx;
        }
        int newIdx = trackRefList.size();
        trackRefList.add(key);
        trackRefIndex.put(key, newIdx);
        return newIdx;
    }

    @NotNull Segment build(long endFrame) {
        int entityCount = entityTypes.size();
        long[] entityIds = new long[entityCount];
        int[] entityTypeIds = new int[entityCount];
        Long2IntOpenHashMap entityIdx = new Long2IntOpenHashMap(entityCount * 2);
        entityIdx.defaultReturnValue(-1);

        int ei = 0;
        for (Long2IntMap.Entry entry : entityTypes.long2IntEntrySet()) {
            entityIds[ei] = entry.getLongKey();
            entityTypeIds[ei] = entry.getIntValue();
            entityIdx.put(entry.getLongKey(), ei);
            ei++;
        }

        int trackRefCount = trackRefList.size();
        TrackReference[] trackRefs = new TrackReference[trackRefCount];
        for (int i = 0; i < trackRefCount; i++) {
            TrackRefKey k = trackRefList.get(i);
            trackRefs[i] = new TrackReference(entityIdx.get(k.entityId()), k.trackTypeId());
        }

        WindowGroup[][] windows = new WindowGroup[layerCount][];
        for (int l = 0; l < layerCount; l++) {
            windows[l] = buildLayer(l, endFrame);
        }

        for (TreeMap<Integer, List<PendingEntry>> layer : layerWindows) {
            layer.clear();
        }

        return new Segment(startFrame, endFrame, entityIds, entityTypeIds, trackRefs, windows);
    }

    private WindowGroup[] buildLayer(int layer, long endFrame) {
        long stride = layerStrides[layer];
        long firstWindow = startFrame / stride;
        long lastWindow = (endFrame - 1) / stride;
        int windowCount = (int) (lastWindow - firstWindow + 1);

        TreeMap<Integer, List<PendingEntry>> windows = layerWindows[layer];
        if (!windows.isEmpty()) {
            int minKey = windows.firstKey();
            int maxKey = windows.lastKey();
            if (minKey < 0 || maxKey >= windowCount) {
                int offendingKey = minKey < 0 ? minKey : maxKey;
                throw new IllegalStateException(
                    "window index out of range for segment [" + startFrame + ", " + endFrame
                        + "), layer=" + layer + ", stride=" + stride
                        + ", windowCount=" + windowCount
                        + ": local window index=" + offendingKey);
            }
        }

        WindowGroup[] groups = new WindowGroup[windowCount];
        for (int w = 0; w < windowCount; w++) {
            List<PendingEntry> entries = windows.get(w);
            if (entries == null || entries.isEmpty()) {
                groups[w] = WindowGroup.EMPTY;
            } else {
                WindowEntry[] arr = new WindowEntry[entries.size()];
                for (int i = 0; i < entries.size(); i++) {
                    PendingEntry e = entries.get(i);
                    arr[i] = new WindowEntry(e.trackRefIndex(), e.payload());
                }
                groups[w] = new WindowGroup(arr);
            }
        }
        return groups;
    }

    void releasePayloads() {
        for (TreeMap<Integer, List<PendingEntry>> windows : layerWindows) {
            for (List<PendingEntry> entries : windows.values()) {
                for (PendingEntry e : entries) {
                    ReferenceCountUtil.safeRelease(e.payload().body());
                }
            }
            windows.clear();
        }
    }

    long getStartFrame() {
        return startFrame;
    }

    boolean isAnchor() {
        return anchor;
    }

    private record TrackRefKey(long entityId, int trackTypeId) {
    }

    private record PendingEntry(int trackRefIndex, @NotNull RawPayload payload) {
    }

}
