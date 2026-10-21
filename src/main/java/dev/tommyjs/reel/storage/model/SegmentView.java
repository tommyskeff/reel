package dev.tommyjs.reel.storage.model;

import org.jetbrains.annotations.NotNull;

public interface SegmentView {

    long getStartFrame();

    long getEndFrame();

    long @NotNull [] getEntityIds();

    int @NotNull [] getEntityTypeIds();

    @NotNull TrackReference @NotNull [] getTrackRefs();

    @NotNull WindowGroup getWindow(int layer, int localWindowIndex);

    int getWindowCount(int layer);

    int getLayerCount();

    default void close() {
    }

}
