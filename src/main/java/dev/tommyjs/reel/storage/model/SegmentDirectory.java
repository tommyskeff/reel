package dev.tommyjs.reel.storage.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record SegmentDirectory(@NotNull List<SegmentEntry> segments) {

    public int size() {
        return segments.size();
    }

    public int floorSegment(long frame) {
        int lo = 0, hi = segments.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (segments.get(mid).startFrame() <= frame) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }

        return lo - 1;
    }

    public long floorAnchorStart(long frame) {
        for (int i = floorSegment(frame); i >= 0; i--) {
            SegmentEntry seg = segments.get(i);
            if (seg.anchor()) {
                return seg.startFrame();
            }
        }

        return -1L;
    }

}
