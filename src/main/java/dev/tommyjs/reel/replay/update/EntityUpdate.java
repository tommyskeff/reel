package dev.tommyjs.reel.replay.update;

import dev.tommyjs.reel.track.EntityTypeHandle;
import dev.tommyjs.reel.track.TrackHandle;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

public interface EntityUpdate {

    long getEntityId();

    @NotNull EntityTypeHandle getEntityType();

    @NotNull Collection<TrackHandle<?, ?>> getTracks();

    <S, D> @NotNull List<TrackUpdate<S, D>> getUpdatesFor(@NotNull TrackHandle<S, D> track);

}
