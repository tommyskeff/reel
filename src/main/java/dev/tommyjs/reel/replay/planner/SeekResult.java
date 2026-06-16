package dev.tommyjs.reel.replay.planner;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

public record SeekResult(@NotNull Map<Long, EntitySteps> entities, boolean complete) {
}
