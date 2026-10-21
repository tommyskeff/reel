package dev.tommyjs.reel.storage.live;

import io.netty.util.ReferenceCountUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record ReelFragment(long startFrame, long endFrame,
                           @NotNull List<FragmentEntry> entries) implements ReelMessage {

    public ReelFragment {
        entries = List.copyOf(entries);
    }

    public void release() {
        for (FragmentEntry entry : entries) {
            ReferenceCountUtil.safeRelease(entry.payload().body());
        }
    }

}
