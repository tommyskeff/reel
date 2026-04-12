package dev.tommyjs.reel.codec;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Reversible track codec base class.
 */
public abstract class AbstractReversibleTrackCodec<S, D> implements TrackCodec<S, D> {

    private final LayerStrategy layerStrategy;

    protected AbstractReversibleTrackCodec(@NotNull LayerStrategy layerStrategy) {
        this.layerStrategy = layerStrategy;
    }

    @Override
    public abstract @NotNull D reverse(@NotNull D delta);

    @Override
    public @NotNull LayerStrategy layerStrategy() {
        return layerStrategy;
    }

    @Override
    public final boolean isReversible() {
        return true;
    }

}
