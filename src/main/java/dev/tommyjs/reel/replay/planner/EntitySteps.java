package dev.tommyjs.reel.replay.planner;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record EntitySteps(int entityTypeId, @NotNull List<Step> steps) {
}
