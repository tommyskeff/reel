package dev.tommyjs.reel.storage.reader;

import dev.tommyjs.reel.storage.model.EntityTypeDirectory;
import dev.tommyjs.reel.storage.model.Segment;
import dev.tommyjs.reel.storage.model.SegmentDirectory;
import dev.tommyjs.reel.storage.model.TrackDirectory;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;

public interface ReelReader extends Closeable {

    long getTotalFrames();

    @NotNull EntityTypeDirectory readTypeDirectory();

    @NotNull TrackDirectory readTrackDirectory();

    @NotNull SegmentDirectory readSegmentDirectory();

    @NotNull Segment readSegment(int segmentIndex);

}
