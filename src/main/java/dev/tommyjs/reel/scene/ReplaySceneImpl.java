package dev.tommyjs.reel.scene;

import dev.tommyjs.reel.replay.ReplayCursor;
import dev.tommyjs.reel.replay.update.EntityUpdate;
import dev.tommyjs.reel.replay.update.ReplayUpdate;
import dev.tommyjs.reel.track.EntityTypeHandle;
import dev.tommyjs.reel.track.TrackRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ReplaySceneImpl implements ReplayScene, Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReplaySceneImpl.class);

    private final ReplayCursor cursor;
    private final TrackRegistry tracks;
    private final List<SceneHandler> handlers;

    private final SceneResourceManagerImpl resources = new SceneResourceManagerImpl();
    private final Map<Long, EntityTypeHandle> activeEntities = new LinkedHashMap<>();

    private boolean playback;

    public ReplaySceneImpl(@NotNull File file, @NotNull TrackRegistry tracks, @NotNull List<SceneHandler> handlers) {
        this(ReplayCursor.builder().setTrackRegistry(tracks).setReader(r -> r.setFile(file)).build(),
            tracks, handlers);
    }

    ReplaySceneImpl(ReplayCursor cursor, TrackRegistry tracks, List<SceneHandler> handlers) {
        this.cursor = cursor;
        this.tracks = tracks;
        this.handlers = order(handlers);
        this.cursor.addListener(this::onUpdate);
        if (cursor.totalFrames() > 0) {
            seek(0);
        }
    }

    public static @NotNull ReplaySceneBuilder builder() {
        return new ReplaySceneBuilder();
    }

    private static List<SceneHandler> order(List<SceneHandler> input) {
        int n = input.size();
        SceneHandlerGraph graph = new SceneHandlerGraph(input);
        Map<SceneResourceKey<?, ?>, List<Integer>> providers = new IdentityHashMap<>();
        Map<SceneResourceKey<?, ?>, List<Integer>> consumers = new IdentityHashMap<>();

        for (int i = 0; i < n; i++) {
            int index = i;
            input.get(i).load(new SceneHandlerContext() {
                @Override
                public void registerProvider(@NotNull SceneResourceKey<?, ?> key) {
                    for (int consumer : consumers.getOrDefault(key, List.of())) {
                        graph.registerDependency(index, consumer);
                    }
                    providers.computeIfAbsent(key, k -> new ArrayList<>()).add(index);
                }

                @Override
                public void registerConsumer(@NotNull SceneResourceKey<?, ?> key) {
                    for (int provider : providers.getOrDefault(key, List.of())) {
                        graph.registerDependency(provider, index);
                    }
                    consumers.computeIfAbsent(key, k -> new ArrayList<>()).add(index);
                }
            });
        }

        for (SceneResourceKey<?, ?> key : consumers.keySet()) {
            if (!providers.containsKey(key)) {
                throw new IllegalStateException("consumed resource key " + key + " has no provider");
            }
        }

        return graph.order();
    }

    public void seek(long frame) {
        seek(frame, false);
    }

    public void seek(long frame, boolean playback) {
        this.playback = playback;
        try {
            cursor.seek(frame);
        } finally {
            this.playback = false;
        }
    }

    public long getTotalFrames() {
        return cursor.totalFrames();
    }

    public boolean isComplete() {
        return cursor.isComplete();
    }

    private void onUpdate(ReplayUpdate update) {
        SceneTransition transition = new SceneTransition(update.getFromFrame(), update.getToFrame(), playback);
        for (EntityUpdate entity : update.getEntities()) {
            activeEntities.put(entity.getEntityId(), entity.getEntityType());
        }
        for (long id : update.getRemoved()) {
            activeEntities.remove(id);
        }
        for (SceneHandler handler : handlers) {
            try {
                handler.run(update, transition, this);
            } catch (Throwable t) {
                LOGGER.warn("Exception caught whilst dispatching an update to scene handler {}",
                    handler.getClass().getName(), t);
            }
        }
    }

    @Override
    public @NotNull SceneResourceManager getResourceManager() {
        return resources;
    }

    @Override
    public @NotNull TrackRegistry getTrackRegistry() {
        return tracks;
    }

    @Override
    public @Nullable EntityTypeHandle getEntityType(long entityId) {
        return activeEntities.get(entityId);
    }

    @Override
    public @NotNull Set<Long> getActiveEntities() {
        return new LinkedHashSet<>(activeEntities.keySet());
    }

    @Override
    public void close() {
        for (int i = handlers.size() - 1; i >= 0; i--) {
            try {
                handlers.get(i).stop(this);
            } catch (Throwable t) {
                LOGGER.warn("Exception caught whilst stopping scene handler {}",
                    handlers.get(i).getClass().getName(), t);
            }
        }
        resources.clear();
        activeEntities.clear();
        cursor.close();
    }

}
