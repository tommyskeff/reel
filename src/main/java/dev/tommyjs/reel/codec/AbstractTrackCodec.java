package dev.tommyjs.reel.codec;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Non-reversible track codec base class.
 */
public abstract class AbstractTrackCodec<S, D> implements TrackCodec<S, D> {

    private final LayerStrategy layerStrategy;

    protected AbstractTrackCodec(@NotNull LayerStrategy layerStrategy) {
        this.layerStrategy = layerStrategy;
    }

    @Override
    public @NotNull LayerStrategy layerStrategy() {
        return layerStrategy;
    }

    @Override
    public final boolean isReversible() {
        return false;
    }

    @Override
    public final @NotNull D reverse(@NotNull D delta) {
        throw new UnsupportedOperationException("Codec does not support reverse()");
    }

}
