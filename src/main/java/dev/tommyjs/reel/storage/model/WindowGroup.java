package dev.tommyjs.reel.storage.model;

import org.jetbrains.annotations.NotNull;

public record WindowGroup(@NotNull WindowEntry @NotNull [] entries) {

    public static final WindowGroup EMPTY = new WindowGroup(new WindowEntry[0]);

    public int size() {
        return entries.length;
    }

}
