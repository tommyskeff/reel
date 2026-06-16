package dev.tommyjs.reel.storage.model;

public record ReelFileLayout(long totalFrames, long segmentDataOffset, long typeDirOffset, long trackDirOffset,
                             long segmentDirOffset) {
}
