package dev.tommyjs.reel.storage.live;

import dev.tommyjs.reel.storage.model.*;
import io.netty.util.ReferenceCountUtil;
import it.unimi.dsi.fastutil.longs.Long2IntAVLTreeMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class MutableSegment implements SegmentView {

    private final long startFrame;
    private final long[] layerStrides;
    private final int layerCount;
    private final boolean anchor;

    private final Long2IntAVLTreeMap entityTypes;
    private final List<TrackRefKey> trackRefList;
    private final Map<TrackRefKey, Integer> trackRefIndex;
    private final TreeMap<Integer, List<WindowEntry>>[] layerWindows;

    private long endFrame;
    private boolean closed;
    private boolean frozen;
    private volatile Segment snapshot;

    @SuppressWarnings("unchecked")
    public MutableSegment(long startFrame, long @NotNull [] layerStrides, boolean anchor) {
        this.startFrame = startFrame;
        this.layerStrides = layerStrides.clone();
        this.layerCount = layerStrides.length;
        this.anchor = anchor;
        this.entityTypes = new Long2IntAVLTreeMap();
        this.entityTypes.defaultReturnValue(-1);
        this.trackRefList = new ArrayList<>();
        this.trackRefIndex = new LinkedHashMap<>();
        this.layerWindows = new TreeMap[layerCount];
        for (int i = 0; i < layerCount; i++) {
            this.layerWindows[i] = new TreeMap<>();
        }
        this.endFrame = startFrame;
        this.snapshot = build();
    }

    public boolean isAnchor() {
        return anchor;
    }

    public void append(@NotNull FragmentEntry entry) {
        if (closed) {
            throw new IllegalStateException("segment closed");
        }

        int layer = entry.layer();
        if (layer < 0 || layer >= layerCount) {
            throw new IllegalArgumentException("layer out of range [0, " + layerCount + "): layer=" + layer);
        }

        long stride = layerStrides[layer];
        long windowStartFrame = entry.windowStartFrame();
        if (windowStartFrame % stride != 0) {
            throw new IllegalArgumentException("windowStartFrame must be aligned to layer stride (" + stride
                + "): windowStartFrame=" + windowStartFrame + ", layer=" + layer);
        }
        if (windowStartFrame + stride <= startFrame) {
            throw new IllegalArgumentException("windowStartFrame is before segment start: windowStartFrame="
                + windowStartFrame + ", startFrame=" + startFrame);
        }

        int prev = entityTypes.putIfAbsent(entry.entityId(), entry.entityTypeId());
        if (prev != -1 && prev != entry.entityTypeId()) {
            throw new IllegalArgumentException("entity_type_id mismatch within segment: entity_id=" + entry.entityId()
                + ", existing=" + prev + ", new=" + entry.entityTypeId());
        }

        TrackRefKey key = new TrackRefKey(entry.entityId(), entry.trackTypeId());
        int refIdx = trackRefIndex.computeIfAbsent(key, k -> {
            trackRefList.add(k);
            return trackRefList.size() - 1;
        });

        int localWindowIdx = (int) (windowStartFrame / stride - startFrame / stride);
        layerWindows[layer].computeIfAbsent(localWindowIdx, k -> new ArrayList<>())
            .add(new WindowEntry(refIdx, entry.payload()));

        endFrame = Math.max(endFrame, windowStartFrame + stride);
    }

    public void setEndFrame(long endFrame) {
        if (endFrame > this.endFrame) {
            this.endFrame = endFrame;
        }
    }

    public void publish() {
        snapshot = build();
    }

    public @NotNull Segment freeze(long endFrame) {
        setEndFrame(endFrame);
        publish();
        closed = true;
        frozen = true;
        return snapshot;
    }

    private Segment build() {
        int entityCount = entityTypes.size();
        long[] entityIds = new long[entityCount];
        int[] entityTypeIds = new int[entityCount];
        Long2IntOpenHashMap entityIdx = new Long2IntOpenHashMap(Math.max(1, entityCount * 2));
        entityIdx.defaultReturnValue(-1);

        int i = 0;
        for (Long2IntMap.Entry entry : entityTypes.long2IntEntrySet()) {
            entityIds[i] = entry.getLongKey();
            entityTypeIds[i] = entry.getIntValue();
            entityIdx.put(entry.getLongKey(), i);
            i++;
        }

        TrackReference[] trackRefs = new TrackReference[trackRefList.size()];
        for (int r = 0; r < trackRefs.length; r++) {
            TrackRefKey key = trackRefList.get(r);
            trackRefs[r] = new TrackReference(entityIdx.get(key.entityId()), key.trackTypeId());
        }

        WindowGroup[][] windows = new WindowGroup[layerCount][];
        for (int l = 0; l < layerCount; l++) {
            windows[l] = buildLayer(l);
        }

        return new Segment(startFrame, endFrame, entityIds, entityTypeIds, trackRefs, windows);
    }

    private WindowGroup[] buildLayer(int layer) {
        long stride = layerStrides[layer];
        long firstWindow = startFrame / stride;
        long lastWindow = endFrame <= startFrame ? firstWindow - 1 : (endFrame - 1) / stride;
        int windowCount = (int) Math.max(0, lastWindow - firstWindow + 1);

        WindowGroup[] groups = new WindowGroup[windowCount];
        for (int w = 0; w < windowCount; w++) {
            List<WindowEntry> entries = layerWindows[layer].get(w);
            if (entries == null || entries.isEmpty()) {
                groups[w] = WindowGroup.EMPTY;
            } else {
                groups[w] = new WindowGroup(entries.toArray(new WindowEntry[0]));
            }
        }
        return groups;
    }

    @Override
    public long getStartFrame() {
        return snapshot.getStartFrame();
    }

    @Override
    public long getEndFrame() {
        return snapshot.getEndFrame();
    }

    @Override
    public long @NotNull [] getEntityIds() {
        return snapshot.getEntityIds();
    }

    @Override
    public int @NotNull [] getEntityTypeIds() {
        return snapshot.getEntityTypeIds();
    }

    @Override
    public @NotNull TrackReference @NotNull [] getTrackRefs() {
        return snapshot.getTrackRefs();
    }

    @Override
    public @NotNull WindowGroup getWindow(int layer, int localWindowIndex) {
        return snapshot.getWindow(layer, localWindowIndex);
    }

    @Override
    public int getWindowCount(int layer) {
        return snapshot.getWindowCount(layer);
    }

    @Override
    public int getLayerCount() {
        return snapshot.getLayerCount();
    }

    public void release() {
        if (frozen) {
            return;
        }

        for (TreeMap<Integer, List<WindowEntry>> windows : layerWindows) {
            for (List<WindowEntry> entries : windows.values()) {
                for (WindowEntry entry : entries) {
                    ReferenceCountUtil.safeRelease(entry.payload().body());
                }
            }
            windows.clear();
        }
        closed = true;
    }

    private record TrackRefKey(long entityId, int trackTypeId) {
    }

}
