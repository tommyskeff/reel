package dev.tommyjs.reel.replay.update;

import org.jetbrains.annotations.NotNull;

public sealed interface TrackUpdate<S, D> {

    record Snapshot<S, D>(@NotNull S state) implements TrackUpdate<S, D> {
    }

    record Delta<S, D>(@NotNull D delta) implements TrackUpdate<S, D> {
    }

    record Event<S, D>(@NotNull D event) implements TrackUpdate<S, D> {
    }

}
