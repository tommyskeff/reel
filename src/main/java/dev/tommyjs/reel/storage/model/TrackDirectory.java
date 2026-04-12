package dev.tommyjs.reel.storage.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface TrackDirectory {

    @NotNull List<TrackEntry> getTracks();

}
