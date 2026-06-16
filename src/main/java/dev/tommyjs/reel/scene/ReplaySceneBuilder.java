package dev.tommyjs.reel.scene;

import dev.tommyjs.reel.replay.ReplayCursor;
import dev.tommyjs.reel.replay.ReplayCursorBuilder;
import dev.tommyjs.reel.storage.reader.ReelReaderBuilder;
import dev.tommyjs.reel.track.TrackRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ReplaySceneBuilder {

    private TrackRegistry trackRegistry;
    private Consumer<ReelReaderBuilder> readerConfig;
    private Consumer<ReplayCursorBuilder> cursorConfig;
    private ReplayCursor cursor;
    private final List<SceneHandler> handlers = new ArrayList<>();

    ReplaySceneBuilder() {
    }

    public @NotNull ReplaySceneBuilder setTrackRegistry(@NotNull TrackRegistry trackRegistry) {
        this.trackRegistry = trackRegistry;
        return this;
    }

    public @NotNull ReplaySceneBuilder setReader(@NotNull Consumer<ReelReaderBuilder> readerConfig) {
        this.readerConfig = readerConfig;
        return this;
    }

    public @NotNull ReplaySceneBuilder setCursor(@NotNull Consumer<ReplayCursorBuilder> cursorConfig) {
        this.cursorConfig = cursorConfig;
        return this;
    }

    public @NotNull ReplaySceneBuilder setCursor(@NotNull ReplayCursor cursor) {
        this.cursor = cursor;
        return this;
    }

    public @NotNull ReplaySceneBuilder addHandler(@NotNull SceneHandler handler) {
        handlers.add(handler);
        return this;
    }

    public @NotNull ReplaySceneBuilder addHandlers(@NotNull List<SceneHandler> handlers) {
        this.handlers.addAll(handlers);
        return this;
    }

    public @NotNull ReplaySceneImpl build() {
        if (trackRegistry == null) {
            throw new IllegalStateException("track registry must be provided");
        }

        ReplayCursor cursor = this.cursor;
        if (cursor == null) {
            if (readerConfig == null && cursorConfig == null) {
                throw new IllegalStateException("a reader or a cursor must be provided");
            }
            ReplayCursorBuilder cursorBuilder = ReplayCursor.builder().setTrackRegistry(trackRegistry);
            if (cursorConfig != null) {
                cursorConfig.accept(cursorBuilder);
            }
            if (readerConfig != null) {
                cursorBuilder.setReader(readerConfig);
            }
            cursor = cursorBuilder.build();
        }

        return new ReplaySceneImpl(cursor, trackRegistry, handlers);
    }

}
