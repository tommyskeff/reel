package dev.tommyjs.reel.scene;

import dev.tommyjs.reel.replay.update.EntityUpdate;
import dev.tommyjs.reel.replay.update.TrackUpdate;
import dev.tommyjs.reel.track.TrackModel;
import dev.tommyjs.reel.track.TrackHandle;
import dev.tommyjs.reel.track.TrackRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public abstract class AbstractActor implements SceneActor {

    protected ReplayScene scene;
    protected SceneTransition transition;

    private final List<TrackBinding> bindings;
    private final List<Runnable> frameHooks;
    private final List<Runnable> destroyHooks;
    private final Map<TrackHandle<?, ?>, Object> resolvedStates;

    private volatile TrackHandle<?, ?> createTrack;
    private volatile long entityId = -1;
    private volatile boolean configured;
    private volatile boolean created;

    protected AbstractActor() {
        this.bindings = new CopyOnWriteArrayList<>();
        this.frameHooks = new CopyOnWriteArrayList<>();
        this.destroyHooks = new CopyOnWriteArrayList<>();
        this.resolvedStates = new ConcurrentHashMap<>();
    }

    protected abstract void configure();

    @SuppressWarnings("unchecked")
    protected final <S, D> void onCreate(@NotNull TrackHandle<S, D> track, @NotNull Consumer<? super S> fn) {
        if (createTrack != null) {
            throw new IllegalStateException("onCreate already registered for track " + createTrack);
        }
        createTrack = track;
        binding(track).onCreate = (Consumer<Object>) fn;
    }

    @SuppressWarnings("unchecked")
    protected final <S, D> void onState(@NotNull TrackHandle<S, D> track, @NotNull Consumer<? super S> fn) {
        binding(track).onState = (Consumer<Object>) fn;
    }

    @SuppressWarnings("unchecked")
    protected final <S, D> void onState(@NotNull TrackHandle<S, D> track, boolean resolved,
                                        @NotNull Consumer<? super S> fn) {
        if (resolved) {
            binding(track).onStateResolved = (Consumer<Object>) fn;
        } else {
            binding(track).onState = (Consumer<Object>) fn;
        }
    }

    @SuppressWarnings("unchecked")
    protected final <S, D> void onDelta(@NotNull TrackHandle<S, D> track, @NotNull Consumer<? super D> fn) {
        binding(track).onDelta = (Consumer<Object>) fn;
    }

    @SuppressWarnings("unchecked")
    protected final <S, D> void onChange(@NotNull TrackHandle<S, D> track,
                                         @NotNull Consumer<? super ChangeBatch<S, D>> fn) {
        binding(track).onChange = (Consumer<Object>) fn;
    }

    protected final <E> void onEvent(@NotNull TrackHandle<Void, E> track, @NotNull Consumer<? super E> fn) {
        onEvent(track, true, fn);
    }

    @SuppressWarnings("unchecked")
    protected final <E> void onEvent(@NotNull TrackHandle<Void, E> track, boolean skipJumps,
                                     @NotNull Consumer<? super E> fn) {
        TrackBinding binding = binding(track);
        binding.onEvent = (Consumer<Object>) fn;
        binding.eventSkipJumps = skipJumps;
    }

    protected final long getEntityId() {
        return entityId;
    }

    protected final void onFrame(@NotNull Runnable fn) {
        frameHooks.add(fn);
    }

    protected final void onDestroy(@NotNull Runnable fn) {
        destroyHooks.add(fn);
    }

    @Override
    public final void apply(@NotNull EntityUpdate update, @NotNull SceneTransition transition, @NotNull ReplayScene scene) {
        this.scene = scene;
        this.transition = transition;
        this.entityId = update.getEntityId();

        if (!configured) {
            configure();
            if (createTrack == null) {
                throw new IllegalStateException("actor has no onCreate binding");
            }
            configured = true;
        }

        TrackBinding create = binding(createTrack);
        dispatchTrack(create, update);
        for (TrackBinding binding : bindings) {
            if (binding != create) {
                dispatchTrack(binding, update);
            }
        }

        for (Runnable hook : frameHooks) {
            hook.run();
        }
    }

    @Override
    public final void destroy(@NotNull ReplayScene scene) {
        this.scene = scene;
        for (Runnable hook : destroyHooks) {
            hook.run();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dispatchTrack(TrackBinding binding, EntityUpdate update) {
        TrackHandle<?, ?> track = binding.track;
        List<TrackUpdate<Object, Object>> items = (List) update.getUpdatesFor(track);
        if (items.isEmpty()) {
            return;
        }

        if (track.equals(createTrack) && !created) {
            if (!(items.get(0) instanceof TrackUpdate.Snapshot<Object, Object> first)) {
                throw new IllegalStateException("create track " + track + " received a delta before its first snapshot");
            }
            created = true;
            if (binding.onCreate != null) {
                binding.onCreate.accept(first.state());
            }
        }

        int lastSnapshot = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof TrackUpdate.Snapshot) {
                lastSnapshot = i;
            }
        }

        boolean reset = lastSnapshot >= 0;
        Object snapshotState = reset ? ((TrackUpdate.Snapshot<Object, Object>) items.get(lastSnapshot)).state() : null;

        List<Object> deltas = new ArrayList<>();
        for (int i = reset ? lastSnapshot + 1 : 0; i < items.size(); i++) {
            if (items.get(i) instanceof TrackUpdate.Delta<Object, Object> delta) {
                deltas.add(delta.delta());
            }
        }

        Object resolved = null;
        boolean needResolved = binding.onStateResolved != null || binding.onChange != null;
        if (needResolved) {
            TrackModel model = scene.getTrackRegistry().getModel(track);
            if (model == null) {
                throw new IllegalStateException("no model registered for track " + track);
            }
            if (reset) {
                resolved = model.cloneState(snapshotState);
            } else {
                Object previous = resolvedStates.get(track);
                if (previous == null) {
                    throw new IllegalStateException("track " + track + " received a delta with no running state");
                }
                resolved = model.cloneState(previous);
            }
            for (Object delta : deltas) {
                resolved = model.applyDelta(resolved, delta);
            }
            resolvedStates.put(track, resolved);
        }

        if (binding.onState != null && reset) {
            binding.onState.accept(snapshotState);
        }
        if (binding.onStateResolved != null) {
            binding.onStateResolved.accept(resolved);
        }
        if (binding.onDelta != null) {
            for (Object delta : deltas) {
                binding.onDelta.accept(delta);
            }
        }
        if (binding.onEvent != null && shouldFireEvent(binding.eventSkipJumps)) {
            for (TrackUpdate<Object, Object> item : items) {
                if (item instanceof TrackUpdate.Event<Object, Object> event) {
                    binding.onEvent.accept(event.event());
                }
            }
        }
        if (binding.onChange != null) {
            binding.onChange.accept(new ChangeBatch<>(reset, List.copyOf(deltas), resolved));
        }
    }

    private boolean shouldFireEvent(boolean skipJumps) {
        if (transition.isInitial() || !transition.isForward()) {
            return false;
        }
        return !skipJumps || transition.isSingleForwardStep() || transition.playback();
    }

    private TrackBinding binding(TrackHandle<?, ?> track) {
        for (TrackBinding binding : bindings) {
            if (binding.track.equals(track)) {
                return binding;
            }
        }
        TrackBinding binding = new TrackBinding(track);
        bindings.add(binding);
        return binding;
    }

    private static final class TrackBinding {

        private final TrackHandle<?, ?> track;

        private Consumer<Object> onCreate;
        private Consumer<Object> onState;
        private Consumer<Object> onStateResolved;
        private Consumer<Object> onDelta;
        private Consumer<Object> onChange;
        private Consumer<Object> onEvent;
        private boolean eventSkipJumps = true;

        private TrackBinding(TrackHandle<?, ?> track) {
            this.track = track;
        }

    }

}
