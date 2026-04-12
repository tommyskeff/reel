package dev.tommyjs.reel.storage.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface EntityTrackIndex {

    @NotNull List<EntityTrackEntry> getTracks();

}
