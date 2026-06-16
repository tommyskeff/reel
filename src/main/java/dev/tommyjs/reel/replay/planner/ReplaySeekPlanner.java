package dev.tommyjs.reel.replay.planner;

import dev.tommyjs.reel.storage.reader.ReelReader;
import dev.tommyjs.reel.track.TrackRegistry;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;

public interface ReplaySeekPlanner extends Closeable {

    static @NotNull ReplaySeekPlanner create(@NotNull ReelReader reader, @NotNull TrackRegistry registry) {
        return new ReplaySeekPlannerImpl(reader, registry);
    }

    static @NotNull ReplaySeekPlanner create(@NotNull ReelReader reader, @NotNull TrackRegistry registry,
                                             int segmentCacheSize) {
        return new ReplaySeekPlannerImpl(reader, registry, segmentCacheSize);
    }

    static @NotNull ReplaySeekPlanner create(@NotNull ReelReader reader, @NotNull TrackRegistry registry,
                                             int segmentCacheSize, long anchorSeekPenalty) {
        return new ReplaySeekPlannerImpl(reader, registry, segmentCacheSize, anchorSeekPenalty);
    }

    @NotNull SeekResult seek(long fromFrame, long toFrame);

    @Override
    void close();

}
