package dev.tommyjs.reel.replay.update;

import dev.tommyjs.reel.track.TrackHandle;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class EntityTrackUpdate {

    private final EntityUpdate entity;
    private final TrackHandle<?, ?> track;
    private final TrackUpdate<?, ?> update;

    public EntityTrackUpdate(@NotNull EntityUpdate entity, @NotNull TrackHandle<?, ?> track,
                             @NotNull TrackUpdate<?, ?> update) {
        this.entity = entity;
        this.track = track;
        this.update = update;
    }

    public @NotNull EntityUpdate getEntity() {
        return entity;
    }

    public @NotNull TrackHandle<?, ?> getTrack() {
        return track;
    }

    public @NotNull TrackUpdate<?, ?> getUpdate() {
        return update;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EntityTrackUpdate other)) return false;
        return entity.equals(other.entity) && track.equals(other.track) && update.equals(other.update);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entity, track, update);
    }

    @Override
    public String toString() {
        return "EntityTrackUpdate[entity=" + entity.getEntityId() + ", track=" + track + ", update=" + update + "]";
    }

}
