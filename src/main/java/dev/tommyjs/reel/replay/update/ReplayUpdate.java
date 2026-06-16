package dev.tommyjs.reel.replay.update;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface ReplayUpdate {

    long getFromFrame();

    long getToFrame();

    @NotNull Collection<EntityUpdate> getEntities();

    @Nullable EntityUpdate getEntity(long entityId);

    @NotNull List<EntityTrackUpdate> getUpdates();

    @NotNull Set<Long> getRemoved();

    boolean isEmpty();

    int size();

}
