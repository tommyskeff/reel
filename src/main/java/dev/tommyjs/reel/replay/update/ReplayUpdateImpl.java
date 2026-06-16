package dev.tommyjs.reel.replay.update;

import dev.tommyjs.reel.track.TrackHandle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class ReplayUpdateImpl implements ReplayUpdate {

    private final long fromFrame;
    private final long toFrame;
    private final LinkedHashMap<Long, EntityUpdate> entities;
    private final LinkedHashSet<Long> removed;

    public ReplayUpdateImpl(long fromFrame, long toFrame, @NotNull LinkedHashMap<Long, EntityUpdate> entities,
                            @NotNull LinkedHashSet<Long> removed) {
        this.fromFrame = fromFrame;
        this.toFrame = toFrame;
        this.entities = entities;
        this.removed = removed;
    }

    @Override
    public long getFromFrame() {
        return fromFrame;
    }

    @Override
    public long getToFrame() {
        return toFrame;
    }

    @Override
    public @NotNull Collection<EntityUpdate> getEntities() {
        return Collections.unmodifiableCollection(entities.values());
    }

    @Override
    public @Nullable EntityUpdate getEntity(long entityId) {
        return entities.get(entityId);
    }

    @Override
    public @NotNull List<EntityTrackUpdate> getUpdates() {
        List<EntityTrackUpdate> updates = new ArrayList<>();
        for (EntityUpdate entity : entities.values()) {
            for (TrackHandle<?, ?> track : entity.getTracks()) {
                for (TrackUpdate<?, ?> update : entity.getUpdatesFor(track)) {
                    updates.add(new EntityTrackUpdate(entity, track, update));
                }
            }
        }
        return updates;
    }

    @Override
    public @NotNull Set<Long> getRemoved() {
        return Collections.unmodifiableSet(removed);
    }

    @Override
    public boolean isEmpty() {
        return entities.isEmpty() && removed.isEmpty();
    }

    @Override
    public int size() {
        return entities.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReplayUpdateImpl other)) {
            return false;
        }
        return fromFrame == other.fromFrame && toFrame == other.toFrame && entities.equals(other.entities)
            && removed.equals(other.removed);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromFrame, toFrame, entities, removed);
    }

    @Override
    public String toString() {
        return "ReplayUpdate[" + fromFrame + "→" + toFrame + ", " + entities.size() + " entities, "
            + removed.size() + " removed]";
    }
}
