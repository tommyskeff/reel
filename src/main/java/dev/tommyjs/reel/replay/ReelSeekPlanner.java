package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.storage.model.TrackSnapshot;
import dev.tommyjs.reel.storage.model.TrackSpan;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface ReelSeekPlanner {

    @NotNull TrackPlan prepare(@NotNull List<TrackSpan> spans,
                               @NotNull List<TrackSnapshot> snapshots,
                               boolean reversible);

    interface TrackPlan {

        @NotNull List<SeekStep> seek(long reference, long target);

    }

}
