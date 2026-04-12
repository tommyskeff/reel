package dev.tommyjs.reel.storage.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface EntityTrackEntry {

    @NotNull String getTrackId();

    @NotNull List<TrackSpan> getSpans();

    @NotNull List<TrackSnapshot> getSnapshots();

}
