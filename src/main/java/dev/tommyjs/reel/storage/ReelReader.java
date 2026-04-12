package dev.tommyjs.reel.storage;

import dev.tommyjs.reel.storage.model.*;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;

public interface ReelReader extends Closeable {

    long getTotalFrames();

    @NotNull EntityDirectory readEntityDirectory();

    @NotNull TrackDirectory readTrackDirectory();

    @NotNull EntityTrackIndex readTrackIndex(long entityId);

    byte @NotNull [] readTrackData(long refId);

}
