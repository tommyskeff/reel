package dev.tommyjs.reel.scene;

import dev.tommyjs.reel.track.EntityTypeHandle;
import dev.tommyjs.reel.track.TrackRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public interface ReplayScene {

    static @NotNull ReplaySceneBuilder builder() {
        return new ReplaySceneBuilder();
    }

    @NotNull SceneResourceManager getResourceManager();

    @NotNull TrackRegistry getTrackRegistry();

    @Nullable EntityTypeHandle getEntityType(long entityId);

    @NotNull Set<Long> getActiveEntities();

}
