package dev.tommyjs.reel.storage.model;

public record SegmentEntry(long startFrame, long endFrame, long byteOffset, long byteSize, boolean anchor) {

    public SegmentEntry(long startFrame, long endFrame, long byteOffset, long byteSize) {
        this(startFrame, endFrame, byteOffset, byteSize, false);
    }

}
