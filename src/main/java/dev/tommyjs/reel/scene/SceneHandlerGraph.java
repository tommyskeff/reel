package dev.tommyjs.reel.scene;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SceneHandlerGraph {

    private final List<SceneHandler> handlers;
    private final List<Set<Integer>> adjacency;
    private final int[] indegree;

    public SceneHandlerGraph(@NotNull List<SceneHandler> handlers) {
        int n = handlers.size();
        this.handlers = new ArrayList<>(handlers);
        this.adjacency = new ArrayList<>(n);
        this.indegree = new int[n];
        for (int i = 0; i < n; i++) {
            adjacency.add(new LinkedHashSet<>());
        }
    }

    public void registerDependency(int from, int to) {
        if (from == to) {
            throw new IllegalStateException("handler at index " + from + " depends on itself");
        }
        if (adjacency.get(from).add(to)) {
            indegree[to]++;
        }
    }

    public @NotNull List<SceneHandler> order() {
        int n = handlers.size();
        int[] remaining = indegree.clone();
        List<SceneHandler> ordered = new ArrayList<>(n);
        boolean[] done = new boolean[n];
        for (int placed = 0; placed < n; placed++) {
            int pick = -1;
            for (int i = 0; i < n; i++) {
                if (!done[i] && remaining[i] == 0) {
                    pick = i;
                    break;
                }
            }
            if (pick == -1) {
                throw new IllegalStateException("dependency cycle among scene handlers");
            }
            done[pick] = true;
            ordered.add(handlers.get(pick));
            for (int next : adjacency.get(pick)) {
                remaining[next]--;
            }
        }
        return ordered;
    }

}
