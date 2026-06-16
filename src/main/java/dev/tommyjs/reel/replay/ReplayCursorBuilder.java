package dev.tommyjs.reel.replay;

import dev.tommyjs.reel.storage.reader.ReelReader;
import dev.tommyjs.reel.storage.reader.ReelReaderBuilder;
import dev.tommyjs.reel.track.TrackRegistry;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

public class ReplayCursorBuilder {

    private static final int DEFAULT_CHUNK_CACHE_SIZE = 8;

    private ReelReader reader = null;
    private Consumer<ReelReaderBuilder> readerConfig = null;
    private TrackRegistry trackRegistry = null;
    private int chunkCacheSize = DEFAULT_CHUNK_CACHE_SIZE;
    private long anchorSeekPenalty = -1L;

    ReplayCursorBuilder() {
    }

    public @NotNull ReplayCursorBuilder setTrackRegistry(@NotNull TrackRegistry trackRegistry) {
        this.trackRegistry = trackRegistry;
        return this;
    }

    public @NotNull ReplayCursorBuilder setReader(@NotNull ReelReader reader) {
        this.reader = reader;
        return this;
    }

    public @NotNull ReplayCursorBuilder setReader(@NotNull Consumer<ReelReaderBuilder> config) {
        this.readerConfig = config;
        return this;
    }

    public @NotNull ReplayCursorBuilder setChunkCacheSize(int chunkCacheSize) {
        this.chunkCacheSize = Math.max(0, chunkCacheSize);
        return this;
    }

    public @NotNull ReplayCursorBuilder setAnchorSeekPenalty(long anchorSeekPenalty) {
        this.anchorSeekPenalty = anchorSeekPenalty;
        return this;
    }

    public ReplayCursor build() {
        if (trackRegistry == null) {
            throw new IllegalStateException("Track registry must be provided");
        }

        if (reader != null && readerConfig != null) {
            throw new IllegalStateException("Provide either a reader or a reader config, not both");
        }

        ReelReader reader = this.reader;
        boolean ownsReader = reader == null;
        if (reader == null) {
            if (readerConfig == null) {
                throw new IllegalStateException("Either a reader or a reader config must be provided");
            }
            ReelReaderBuilder readerBuilder = new ReelReaderBuilder();
            readerConfig.accept(readerBuilder);
            try {
                reader = readerBuilder.build();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        return new ReplayCursorImpl(reader, trackRegistry, chunkCacheSize, anchorSeekPenalty, ownsReader);
    }

}
