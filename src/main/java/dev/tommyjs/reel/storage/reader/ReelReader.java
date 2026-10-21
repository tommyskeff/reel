package dev.tommyjs.reel.storage.reader;

import dev.tommyjs.reel.storage.model.EntityTypeDirectory;
import dev.tommyjs.reel.storage.model.SegmentDirectory;
import dev.tommyjs.reel.storage.model.SegmentView;
import dev.tommyjs.reel.storage.model.TrackDirectory;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.util.function.Supplier;

public interface ReelReader extends Closeable {

    long getTotalFrames();

    @NotNull EntityTypeDirectory readTypeDirectory();

    @NotNull TrackDirectory readTrackDirectory();

    @NotNull SegmentDirectory readSegmentDirectory();

    @NotNull SegmentView readSegment(int segmentIndex);

    default long getRevision() {
        return 0L;
    }

    default boolean ownsSegments() {
        return false;
    }

    default boolean isComplete() {
        return true;
    }

    default <T> T withReadLock(@NotNull Supplier<T> action) {
        return action.get();
    }

}
