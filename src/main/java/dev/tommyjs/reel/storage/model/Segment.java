package dev.tommyjs.reel.storage.model;

import io.netty.util.ReferenceCountUtil;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;

public final class Segment implements SegmentView, Closeable {

    private final long startFrame;
    private final long endFrame;
    private final long[] entityIds;
    private final int[] entityTypeIds;
    private final TrackReference[] trackRefs;
    private final WindowGroup[][] layerWindows;

    private boolean closed;

    public Segment(long startFrame, long endFrame, long @NotNull [] entityIds, int @NotNull [] entityTypeIds,
                   @NotNull TrackReference @NotNull [] trackRefs,
                   @NotNull WindowGroup @NotNull [] @NotNull [] layerWindows) {
        this.startFrame = startFrame;
        this.endFrame = endFrame;
        this.entityIds = entityIds;
        this.entityTypeIds = entityTypeIds;
        this.trackRefs = trackRefs;
        this.layerWindows = layerWindows;
    }

    @Override
    public long getStartFrame() {
        return startFrame;
    }

    @Override
    public long getEndFrame() {
        return endFrame;
    }

    @Override
    public long @NotNull [] getEntityIds() {
        return entityIds;
    }

    @Override
    public int @NotNull [] getEntityTypeIds() {
        return entityTypeIds;
    }

    @Override
    public @NotNull TrackReference @NotNull [] getTrackRefs() {
        return trackRefs;
    }

    @Override
    public @NotNull WindowGroup getWindow(int layer, int localWindowIndex) {
        WindowGroup[] windows = layerWindows[layer];
        if (localWindowIndex < 0 || localWindowIndex >= windows.length) {
            return WindowGroup.EMPTY;
        }

        return windows[localWindowIndex];
    }

    @Override
    public int getWindowCount(int layer) {
        return layerWindows[layer].length;
    }

    @Override
    public int getLayerCount() {
        return layerWindows.length;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;

        for (WindowGroup[] windows : layerWindows) {
            for (WindowGroup group : windows) {
                for (WindowEntry entry : group.entries()) {
                    ReferenceCountUtil.safeRelease(entry.payload().body());
                }
            }
        }
    }

}
