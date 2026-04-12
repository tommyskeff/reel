package dev.tommyjs.reel.storage.model;

import org.jetbrains.annotations.NotNull;

public interface TrackEntry {

    @NotNull String getTrackId();

    int getCodecVersion();

}
