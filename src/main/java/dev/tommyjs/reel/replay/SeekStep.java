package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.storage.model.TrackSnapshot;
import dev.tommyjs.reel.storage.model.TrackSpan;

public sealed interface SeekStep {

    record Snapshot(TrackSnapshot snapshot) implements SeekStep {
    }

    record Forward(TrackSpan span) implements SeekStep {
    }

    record Backward(TrackSpan span) implements SeekStep {
    }

}
