package dev.tommyjs.reel.replay.update;

import dev.tommyjs.reel.track.EntityTypeHandle;
import dev.tommyjs.reel.track.TrackHandle;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public final class EntityUpdateImpl implements EntityUpdate {

    private final long entityId;
    private final EntityTypeHandle entityType;
    private final LinkedHashMap<TrackHandle<?, ?>, List<TrackUpdate<?, ?>>> updatesByTrack;

    public EntityUpdateImpl(long entityId, @NotNull EntityTypeHandle entityType,
                            @NotNull LinkedHashMap<TrackHandle<?, ?>, List<TrackUpdate<?, ?>>> updatesByTrack) {
        this.entityId = entityId;
        this.entityType = entityType;
        this.updatesByTrack = updatesByTrack;
    }

    public void putUpdate(@NotNull TrackHandle<?, ?> track, @NotNull TrackUpdate<?, ?> update) {
        updatesByTrack.computeIfAbsent(track, k -> new ArrayList<>()).add(update);
    }

    @Override
    public long getEntityId() {
        return entityId;
    }

    @Override
    public @NotNull EntityTypeHandle getEntityType() {
        return entityType;
    }

    @Override
    public @NotNull Collection<TrackHandle<?, ?>> getTracks() {
        return Collections.unmodifiableCollection(updatesByTrack.keySet());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public <S, D> @NotNull List<TrackUpdate<S, D>> getUpdatesFor(@NotNull TrackHandle<S, D> track) {
        List<TrackUpdate<?, ?>> raw = updatesByTrack.get(track);
        if (raw == null) return List.of();
        return (List) raw;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EntityUpdateImpl other)) return false;
        return entityId == other.entityId && entityType.equals(other.entityType)
            && updatesByTrack.equals(other.updatesByTrack);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityId, entityType, updatesByTrack);
    }

    @Override
    public String toString() {
        return "EntityUpdate[entity=" + entityId + ", type=" + entityType.identifier()
            + ", tracks=" + updatesByTrack.size() + "]";
    }

}
