package dev.tommyjs.reel.scene;

import dev.tommyjs.reel.replay.update.EntityUpdate;
import dev.tommyjs.reel.replay.update.ReplayUpdate;
import dev.tommyjs.reel.track.EntityTypeHandle;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Supplier;

public final class ActorHandler implements SceneHandler {

    private final EntityTypeHandle type;
    private final Supplier<SceneActor> supplier;
    private final List<SceneResourceKey<?, ?>> provides = new ArrayList<>();
    private final List<SceneResourceKey<?, ?>> consumes = new ArrayList<>();
    private final Map<Long, SceneActor> actors = new LinkedHashMap<>();

    private ActorHandler(EntityTypeHandle type, Supplier<SceneActor> supplier) {
        this.type = type;
        this.supplier = supplier;
    }

    public static @NotNull ActorHandler create(@NotNull EntityTypeHandle type, @NotNull Supplier<SceneActor> supplier) {
        return new ActorHandler(type, supplier);
    }

    public @NotNull ActorHandler provides(@NotNull SceneResourceKey<?, ?>... keys) {
        Collections.addAll(provides, keys);
        return this;
    }

    public @NotNull ActorHandler consumes(@NotNull SceneResourceKey<?, ?>... keys) {
        Collections.addAll(consumes, keys);
        return this;
    }

    @Override
    public void load(@NotNull SceneHandlerContext ctx) {
        for (SceneResourceKey<?, ?> key : provides) {
            ctx.registerProvider(key);
        }
        for (SceneResourceKey<?, ?> key : consumes) {
            ctx.registerConsumer(key);
        }
    }

    @Override
    public void run(@NotNull ReplayUpdate update, @NotNull SceneTransition transition, @NotNull ReplayScene scene) {
        for (EntityUpdate entity : update.getEntities()) {
            if (!entity.getEntityType().equals(type)) {
                continue;
            }
            actors.computeIfAbsent(entity.getEntityId(), id -> supplier.get())
                .apply(entity, transition, scene);
        }
        for (long id : update.getRemoved()) {
            SceneActor actor = actors.remove(id);
            if (actor != null) {
                actor.destroy(scene);
            }
        }
    }

    @Override
    public void stop(@NotNull ReplayScene scene) {
        for (SceneActor actor : actors.values()) {
            actor.destroy(scene);
        }
        actors.clear();
    }

}
