package dev.tommyjs.reel.codec;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines the multi-resolution recording strategy for a track codec.
 *
 * <p>The strategy specifies a list of layers (ordered by ascending cost threshold)
 * and an optional snapshot layer. The first layer (index 0) is always the base layer
 * with a cost threshold of 0 — it emits a raw span on every change. Higher layers
 * accumulate deltas and emit condensed spans when their cost threshold is exceeded.</p>
 *
 * <p>When snapshots are enabled, a full state snapshot is emitted whenever the
 * accumulated cost since the last snapshot exceeds {@code snapshotCostThreshold}.</p>
 *
 * @param layers                 ordered list of layer definitions; index 0 must have costThreshold 0
 * @param snapshotCostThreshold  cost threshold for snapshot emission, or -1 if snapshots are disabled
 */
public record LayerStrategy(@NotNull List<LayerDefinition> layers, int snapshotCostThreshold) {

    public LayerStrategy {
        if (layers.isEmpty()) {
            throw new IllegalArgumentException("At least one layer is required");
        }

        if (layers.get(0).costThreshold() != 0) {
            throw new IllegalArgumentException("Base layer must have costThreshold 0");
        }

        for (int i = 1; i < layers.size(); i++) {
            if (layers.get(i).costThreshold() <= layers.get(i - 1).costThreshold()) {
                throw new IllegalArgumentException("Layer cost thresholds must be strictly ascending; layer " + i
                    + " (" + layers.get(i).costThreshold() + ") is not greater than layer "
                    + (i - 1) + " (" + layers.get(i - 1).costThreshold() + ")");
            }
        }

        if (snapshotCostThreshold < -1) {
            throw new IllegalArgumentException("snapshotCostThreshold must be >= -1, got " + snapshotCostThreshold);
        }

        layers = List.copyOf(layers);
    }

    public boolean snapshotsEnabled() {
        return snapshotCostThreshold >= 0;
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final List<LayerDefinition> layers;
        private int snapshotCostThreshold;

        private Builder() {
            this.layers = new ArrayList<>();
            this.snapshotCostThreshold = -1;
            layers.add(new LayerDefinition(0));
        }

        public @NotNull Builder layer(int costThreshold) {
            layers.add(new LayerDefinition(costThreshold));
            return this;
        }

        public @NotNull Builder snapshots(int costThreshold) {
            this.snapshotCostThreshold = costThreshold;
            return this;
        }

        public @NotNull LayerStrategy build() {
            return new LayerStrategy(layers, snapshotCostThreshold);
        }

    }

}
