package dev.tommyjs.reel.storage.live;

import dev.tommyjs.reel.storage.model.*;
import dev.tommyjs.reel.storage.writer.LayerStrategy;
import dev.tommyjs.reel.storage.writer.ReelWriterListener;
import dev.tommyjs.reel.track.TrackKind;
import io.netty.util.ReferenceCountUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Consumer;

public final class ReelLivePublisher implements ReelWriterListener {

    private final Consumer<ReelMessage> sink;
    private final long[] strides;
    private final int layerCount;

    private final List<FragmentEntry> buffered = new ArrayList<>();
    private final List<EntityTypeDefinition> entityDefs = new ArrayList<>();
    private final List<TrackDefinition> trackDefs = new ArrayList<>();
    private final List<SegmentEntry> segmentEntries = new ArrayList<>();

    private long fragmentStart = Long.MAX_VALUE;
    private long fragmentEnd = Long.MIN_VALUE;
    private long headFrame = -1;
    private boolean complete;
    private long totalFrames;

    public ReelLivePublisher(@NotNull LayerStrategy layerStrategy, @NotNull Consumer<ReelMessage> sink) {
        this.sink = sink;
        this.strides = layerStrategy.strides();
        this.layerCount = strides.length;
    }

    @Override
    public void onEntityType(@NotNull String typeIdentifier, int typeId) {
        entityDefs.add(new EntityTypeDefinition(typeId, typeIdentifier));
        sink.accept(new ReelMessage.EntityTypeRegistered(typeIdentifier, typeId));
    }

    @Override
    public void onTrackType(@NotNull String typeIdentifier, int typeId, int codecVersion, @NotNull TrackKind kind) {
        trackDefs.add(new TrackDefinition(typeId, typeIdentifier, codecVersion, kind));
        sink.accept(new ReelMessage.TrackTypeRegistered(typeIdentifier, typeId, codecVersion, kind));
    }

    @Override
    public void onSegmentBegin(long startFrame, boolean anchor) {
        sink.accept(new ReelMessage.SegmentBegun(startFrame, anchor));
    }

    @Override
    public void onPayload(long entityId, int entityTypeId, int trackTypeId, int layer, long windowStartFrame,
                          @NotNull RawPayload payload) {
        if (layer < 0 || layer >= layerCount) {
            return;
        }

        buffered.add(new FragmentEntry(entityId, entityTypeId, trackTypeId, layer, windowStartFrame,
            payload.retainedDuplicate()));

        fragmentStart = Math.min(fragmentStart, windowStartFrame);
        fragmentEnd = Math.max(fragmentEnd, windowStartFrame + strides[layer]);
    }

    @Override
    public void onSegmentEnd(long startFrame, long endFrame, boolean anchor) {
        flush();
        segmentEntries.add(new SegmentEntry(startFrame, endFrame, 0L, 0L, anchor));
        headFrame = Math.max(headFrame, endFrame - 1);
        sink.accept(new ReelMessage.SegmentSealed(startFrame, endFrame, anchor));
    }

    @Override
    public void onFinish(long totalFrames) {
        flush();
        this.complete = true;
        this.totalFrames = totalFrames;
        this.headFrame = Math.max(headFrame, totalFrames - 1);
        sink.accept(new ReelMessage.Complete(totalFrames));
    }

    public void flush() {
        if (buffered.isEmpty()) {
            return;
        }

        ReelFragment fragment = new ReelFragment(fragmentStart, fragmentEnd, buffered);
        buffered.clear();
        fragmentStart = Long.MAX_VALUE;
        fragmentEnd = Long.MIN_VALUE;
        headFrame = Math.max(headFrame, fragment.endFrame() - 1);

        try {
            sink.accept(fragment);
        } finally {
            fragment.release();
        }
    }

    public @NotNull ReelHeadState headState() {
        return new ReelHeadState(EntityTypeDirectory.of(entityDefs), TrackDirectory.of(layerCount, strides, trackDefs),
            new SegmentDirectory(List.copyOf(segmentEntries)), headFrame, complete);
    }

    public void release() {
        for (FragmentEntry entry : buffered) {
            ReferenceCountUtil.safeRelease(entry.payload().body());
        }
        buffered.clear();
    }

}
