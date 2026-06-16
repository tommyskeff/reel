package dev.tommyjs.reel.track;

import dev.tommyjs.reel.track.codec.Codec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

public final class RegisteredTrack<S, D> {

    private final TrackHandle<S, D> handle;
    private final NavigableMap<Integer, Codecs<S, D>> codecs;

    private TrackModel<S, D> model;

    RegisteredTrack(@NotNull TrackHandle<S, D> handle) {
        this.handle = Objects.requireNonNull(handle);
        this.codecs = new TreeMap<>();
    }

    void register(int version, @NotNull TrackModel<S, D> model, @NotNull Codec<S> stateCodec,
                  @NotNull Codec<D> deltaCodec) {
        if (codecs.containsKey(version)) {
            throw new IllegalStateException(
                "Codec version " + version + " is already registered for track \"" + handle.identifier() + "\"");
        }

        Objects.requireNonNull(model);
        if (this.model != null && this.model.kind() != model.kind()) {
            throw new IllegalStateException(
                "Conflicting track kind for \"" + handle.identifier() + "\": " + this.model.kind() + " vs "
                    + model.kind());
        }

        this.model = model;
        codecs.put(version, new Codecs<>(Objects.requireNonNull(stateCodec), Objects.requireNonNull(deltaCodec)));
    }

    public @NotNull TrackHandle<S, D> handle() {
        return handle;
    }

    public @NotNull TrackModel<S, D> model() {
        return model;
    }

    public int latestVersion() {
        return codecs.lastKey();
    }

    public @NotNull Codec<S> latestStateCodec() {
        return codecs.lastEntry().getValue().state();
    }

    public @NotNull Codec<D> latestDeltaCodec() {
        return codecs.lastEntry().getValue().delta();
    }

    public @Nullable Codec<S> stateCodec(int version) {
        Codecs<S, D> entry = codecs.get(version);
        return entry == null ? null : entry.state();
    }

    public @Nullable Codec<D> deltaCodec(int version) {
        Codecs<S, D> entry = codecs.get(version);
        return entry == null ? null : entry.delta();
    }

    public boolean hasVersion(int version) {
        return codecs.containsKey(version);
    }

    private record Codecs<S, D>(@NotNull Codec<S> state, @NotNull Codec<D> delta) {
    }

}
