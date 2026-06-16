package dev.tommyjs.reel.scene;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record ChangeBatch<S, D>(boolean reset, @NotNull List<D> deltas, S state) {

    public boolean isEmpty() {
        return !reset && deltas.isEmpty();
    }

}
