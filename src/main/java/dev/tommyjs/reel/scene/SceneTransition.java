package dev.tommyjs.reel.scene;

public record SceneTransition(long from, long to, boolean playback) {

    public boolean isInitial() {
        return from < 0;
    }

    public boolean isForward() {
        return to > from;
    }

    public boolean isBackward() {
        return to < from;
    }

    public long distance() {
        return Math.abs(to - from);
    }

    public boolean isSingleStep() {
        return from >= 0 && distance() == 1;
    }

    public boolean isSingleForwardStep() {
        return from >= 0 && to == from + 1;
    }

}
