package dev.tommyjs.reel.recorder;

import dev.tommyjs.reel.storage.writer.ReelWriter;
import dev.tommyjs.reel.storage.writer.ReelWriterBuilder;
import dev.tommyjs.reel.track.TrackRegistry;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

public class ReelRecorderBuilder {

    private ReelWriter writer = null;
    private Consumer<ReelWriterBuilder> writerConfig = null;
    private TrackRegistry trackRegistry = null;
    private long segmentLength = 1200;
    private long idleThreshold = 400;
    private long anchorInterval = 14400;

    ReelRecorderBuilder() {
    }

    public @NotNull ReelRecorderBuilder setTrackRegistry(@NotNull TrackRegistry trackRegistry) {
        this.trackRegistry = trackRegistry;
        return this;
    }

    public @NotNull ReelRecorderBuilder setWriter(@NotNull ReelWriter writer) {
        this.writer = writer;
        return this;
    }

    public @NotNull ReelRecorderBuilder setWriter(@NotNull Consumer<ReelWriterBuilder> config) {
        this.writerConfig = config;
        return this;
    }

    public @NotNull ReelRecorderBuilder setSegmentLength(long segmentLength) {
        if (segmentLength < 0) {
            throw new IllegalArgumentException("Segment length must be non-negative");
        }

        this.segmentLength = segmentLength;
        return this;
    }

    public @NotNull ReelRecorderBuilder setSingleSegment() {
        this.segmentLength = Long.MAX_VALUE;
        return this;
    }

    public @NotNull ReelRecorderBuilder setIdleThreshold(long idleThreshold) {
        this.idleThreshold = idleThreshold;
        return this;
    }

    public @NotNull ReelRecorderBuilder setAnchorInterval(long anchorInterval) {
        if (anchorInterval < 0) {
            throw new IllegalArgumentException("Anchor interval must be non-negative");
        }

        this.anchorInterval = anchorInterval;
        return this;
    }

    public @NotNull ReelRecorder build() {
        if (trackRegistry == null) {
            throw new IllegalStateException("Track registry must be provided");
        }

        if (writer != null && writerConfig != null) {
            throw new IllegalStateException("Provide either a writer or a writer config, not both");
        }

        ReelWriter writer = this.writer;
        if (writer == null) {
            if (writerConfig == null) {
                throw new IllegalStateException("Either a writer or a writer config must be provided");
            }

            ReelWriterBuilder writerBuilder = new ReelWriterBuilder();
            writerConfig.accept(writerBuilder);

            try {
                writer = writerBuilder.build();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        return new ReelRecorderImpl(writer, trackRegistry, segmentLength, idleThreshold, anchorInterval);
    }

}
