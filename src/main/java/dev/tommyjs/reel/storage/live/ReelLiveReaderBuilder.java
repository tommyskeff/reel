package dev.tommyjs.reel.storage.live;

import dev.tommyjs.reel.storage.ReelCompressor;
import dev.tommyjs.reel.storage.writer.LayerStrategy;
import org.jetbrains.annotations.NotNull;

public final class ReelLiveReaderBuilder {

    private static final int DEFAULT_MAX_PENDING_MESSAGES = 1024;

    private LayerStrategy layerStrategy;
    private ReelCompressor compression = ReelCompressor.LZ4;
    private int maxPendingMessages = DEFAULT_MAX_PENDING_MESSAGES;
    private long retainedFrames = Long.MAX_VALUE;

    ReelLiveReaderBuilder() {
    }

    public @NotNull ReelLiveReaderBuilder setLayerStrategy(@NotNull LayerStrategy layerStrategy) {
        this.layerStrategy = layerStrategy;
        return this;
    }

    public @NotNull ReelLiveReaderBuilder setCompression(@NotNull ReelCompressor compression) {
        this.compression = compression;
        return this;
    }

    public @NotNull ReelLiveReaderBuilder setMaxPendingMessages(int maxPendingMessages) {
        if (maxPendingMessages <= 0) {
            throw new IllegalArgumentException("maxPendingMessages must be positive");
        }
        this.maxPendingMessages = maxPendingMessages;
        return this;
    }

    public @NotNull ReelLiveReaderBuilder setRetainedFrames(long retainedFrames) {
        this.retainedFrames = retainedFrames < 0 ? Long.MAX_VALUE : retainedFrames;
        return this;
    }

    public @NotNull ReelLiveReader build() {
        if (layerStrategy == null) {
            throw new IllegalStateException("layer strategy must be provided");
        }
        return new ReelLiveReaderImpl(layerStrategy, compression, maxPendingMessages, retainedFrames);
    }

}
