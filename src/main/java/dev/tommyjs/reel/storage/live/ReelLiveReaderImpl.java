package dev.tommyjs.reel.storage.live;

import dev.tommyjs.reel.storage.ReelCompressor;
import dev.tommyjs.reel.storage.codec.SegmentCodec;
import dev.tommyjs.reel.storage.model.*;
import dev.tommyjs.reel.storage.writer.LayerStrategy;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

final class ReelLiveReaderImpl implements ReelLiveReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReelLiveReaderImpl.class);

    private final long[] strides;
    private final int layerCount;
    private final long maxStride;
    private final ReelCompressor compression;
    private final int maxPendingMessages;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock readLock = lock.readLock();
    private final Lock writeLock = lock.writeLock();
    private final List<EntityTypeDefinition> entityDefs = new ArrayList<>();
    private final List<TrackDefinition> trackDefs = new ArrayList<>();
    private final List<SegmentEntry> sealedEntries = new ArrayList<>();
    private final Map<Integer, SegmentView> segmentData = new ConcurrentHashMap<>();
    private final Deque<ReelMessage> pending = new ArrayDeque<>();
    private final AtomicLong revision = new AtomicLong();

    private volatile EntityTypeDirectory typeDirectory = EntityTypeDirectory.of(List.of());
    private volatile TrackDirectory trackDirectory;
    private volatile SegmentDirectory segmentDirectory = new SegmentDirectory(List.of());
    private volatile long headFrame = -1;
    private volatile boolean complete;
    private volatile long totalFrames;
    private volatile long retainedFrames = Long.MAX_VALUE;

    private volatile MutableSegment tail;
    private volatile int sealedCount;
    private int evictedBefore;
    private boolean closed;

    ReelLiveReaderImpl(@NotNull LayerStrategy layerStrategy, @NotNull ReelCompressor compression,
                       int maxPendingMessages, long retainedFrames) {
        this.strides = layerStrategy.strides();
        this.layerCount = strides.length;
        this.maxStride = layerStrategy.maxStride();
        this.compression = compression;
        this.maxPendingMessages = maxPendingMessages;
        this.retainedFrames = retainedFrames;
        this.trackDirectory = TrackDirectory.of(layerCount, strides, List.of());
    }

    @Override
    public long getTotalFrames() {
        return complete ? totalFrames : headFrame + 1;
    }

    @Override
    public @NotNull EntityTypeDirectory readTypeDirectory() {
        return typeDirectory;
    }

    @Override
    public @NotNull TrackDirectory readTrackDirectory() {
        return trackDirectory;
    }

    @Override
    public @NotNull SegmentDirectory readSegmentDirectory() {
        return segmentDirectory;
    }

    @Override
    public @NotNull SegmentView readSegment(int segmentIndex) {
        MutableSegment current = tail;
        if (current != null && segmentIndex == sealedCount) {
            return current;
        }

        SegmentView stored = segmentData.get(segmentIndex);
        if (stored != null) {
            return stored;
        }

        List<SegmentEntry> entries = segmentDirectory.segments();
        if (segmentIndex < 0 || segmentIndex >= entries.size()) {
            return emptySegment(0, 0);
        }

        SegmentEntry entry = entries.get(segmentIndex);
        return emptySegment(entry.startFrame(), entry.endFrame());
    }

    @Override
    public long getRevision() {
        return revision.get();
    }

    @Override
    public boolean ownsSegments() {
        return true;
    }

    @Override
    public boolean isComplete() {
        return complete;
    }

    @Override
    public <T> T withReadLock(@NotNull Supplier<T> action) {
        readLock.lock();
        try {
            return action.get();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public long getHeadFrame() {
        return headFrame;
    }

    @Override
    public void setRetainedFrames(long frames) {
        writeLock.lock();
        try {
            this.retainedFrames = frames < 0 ? Long.MAX_VALUE : frames;
            applyRetention();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public int getPendingMessageCount() {
        writeLock.lock();
        try {
            return pending.size();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public @NotNull ReelHeadState headState() {
        return new ReelHeadState(typeDirectory, trackDirectory, segmentDirectory, headFrame, complete);
    }

    @Override
    public void acceptDirectories(@NotNull EntityTypeDirectory types, @NotNull TrackDirectory tracks) {
        writeLock.lock();
        try {
            for (EntityTypeDefinition def : types.types()) {
                registerEntityType(def.identifier(), def.id());
            }
            for (TrackDefinition def : tracks.trackTypes()) {
                registerTrackType(def.identifier(), def.id(), def.codecVersion(), def.kind());
            }
            rebuildDirectories();
            drainPending();
            bump();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void accept(@NotNull ReelMessage message) {
        writeLock.lock();
        try {
            if (closed) {
                releaseIfFragment(message);
                return;
            }

            if (message instanceof ReelMessage.EntityTypeRegistered m) {
                registerEntityType(m.identifier(), m.typeId());
                rebuildDirectories();
                drainPending();
                bump();
                return;
            }

            if (message instanceof ReelMessage.TrackTypeRegistered m) {
                registerTrackType(m.identifier(), m.typeId(), m.codecVersion(), m.kind());
                rebuildDirectories();
                drainPending();
                bump();
                return;
            }

            if (!pending.isEmpty() || blockedOn(message)) {
                enqueue(message);
                return;
            }

            apply(message);
            bump();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void acceptSegment(long startFrame, long endFrame, boolean anchor, @NotNull ByteBuf compressed)
        throws IOException {
        Segment decoded = SegmentCodec.decodeCompressed(compressed, compressed.readableBytes(), layerCount,
            compression);

        writeLock.lock();
        try {
            if (closed) {
                decoded.close();
                return;
            }
            if (!sealedEntries.isEmpty() && startFrame < sealedEntries.get(sealedEntries.size() - 1).endFrame()) {
                decoded.close();
                throw new IOException("segment out of order: start_frame=" + startFrame + ", last_end_frame="
                    + sealedEntries.get(sealedEntries.size() - 1).endFrame());
            }

            discardTail();
            int index = sealedEntries.size();
            sealedEntries.add(new SegmentEntry(startFrame, endFrame, 0L, 0L, anchor));
            sealedCount = sealedEntries.size();
            segmentData.put(index, decoded);
            advanceHead(endFrame - 1);
            rebuildSegmentDirectory();
            applyRetention();
            bump();
        } finally {
            writeLock.unlock();
        }
    }

    private void apply(ReelMessage message) {
        if (message instanceof ReelFragment fragment) {
            applyFragment(fragment);
        } else if (message instanceof ReelMessage.SegmentBegun m) {
            beginSegment(m.startFrame(), m.anchor());
        } else if (message instanceof ReelMessage.SegmentSealed m) {
            sealSegment(m.startFrame(), m.endFrame(), m.anchor());
        } else if (message instanceof ReelMessage.HeadAdvanced m) {
            advanceHead(m.headFrame());
        } else if (message instanceof ReelMessage.Complete m) {
            totalFrames = m.totalFrames();
            advanceHead(m.totalFrames() - 1);
            complete = true;
            rebuildSegmentDirectory();
        }
    }

    private void applyFragment(ReelFragment fragment) {
        MutableSegment current = tail;
        if (current == null) {
            current = new MutableSegment(alignedStart(fragment.startFrame()), strides, sealedEntries.isEmpty());
            tail = current;
        }

        for (FragmentEntry entry : fragment.entries()) {
            current.append(entry);
        }

        current.setEndFrame(fragment.endFrame());
        current.publish();
        advanceHead(reachedFrame(fragment));
        rebuildSegmentDirectory();
        applyRetention();
    }

    private void beginSegment(long startFrame, boolean anchor) {
        if (tail != null) {
            if (tail.getStartFrame() == startFrame) {
                return;
            }
            sealSegment(tail.getStartFrame(), startFrame, tail.isAnchor());
        }
        tail = new MutableSegment(startFrame, strides, anchor);
        rebuildSegmentDirectory();
    }

    private void sealSegment(long startFrame, long endFrame, boolean anchor) {
        MutableSegment current = tail;
        if (current == null) {
            current = new MutableSegment(startFrame, strides, anchor);
        }

        Segment frozen = current.freeze(endFrame);
        int index = sealedEntries.size();
        sealedEntries.add(new SegmentEntry(current.getStartFrame(), endFrame, 0L, 0L, current.isAnchor()));
        sealedCount = sealedEntries.size();
        segmentData.put(index, frozen);
        tail = null;

        advanceHead(endFrame - 1);
        rebuildSegmentDirectory();
        applyRetention();
    }

    private long reachedFrame(ReelFragment fragment) {
        long reached = -1;
        for (FragmentEntry entry : fragment.entries()) {
            if (entry.layer() == 0) {
                reached = Math.max(reached, entry.windowStartFrame() + strides[0] - 1);
            }
        }
        return reached;
    }

    private long alignedStart(long frame) {
        if (!sealedEntries.isEmpty()) {
            return sealedEntries.get(sealedEntries.size() - 1).endFrame();
        }
        return Math.floorDiv(frame, maxStride) * maxStride;
    }

    private void enqueue(ReelMessage message) {
        pending.addLast(message);
        if (pending.size() <= maxPendingMessages) {
            return;
        }

        Iterator<ReelMessage> iterator = pending.iterator();
        while (iterator.hasNext()) {
            if (iterator.next() instanceof ReelFragment dropped) {
                iterator.remove();
                LOGGER.warn("dropping a buffered reel fragment: the pending queue exceeded {} entries",
                    maxPendingMessages);
                dropped.release();
                return;
            }
        }
    }

    private void drainPending() {
        while (!pending.isEmpty()) {
            ReelMessage next = pending.peekFirst();
            if (blockedOn(next)) {
                return;
            }
            pending.pollFirst();
            apply(next);
        }
    }

    private boolean blockedOn(ReelMessage message) {
        if (!(message instanceof ReelFragment fragment)) {
            return false;
        }

        EntityTypeDirectory types = typeDirectory;
        TrackDirectory tracks = trackDirectory;
        for (FragmentEntry entry : fragment.entries()) {
            if (tracks.get(entry.trackTypeId()) == null || types.get(entry.entityTypeId()) == null) {
                return true;
            }
        }
        return false;
    }

    private void registerEntityType(String identifier, int typeId) {
        if (typeDirectory.get(typeId) != null) {
            return;
        }
        entityDefs.add(new EntityTypeDefinition(typeId, identifier));
    }

    private void registerTrackType(String identifier, int typeId, int codecVersion,
                                   dev.tommyjs.reel.track.TrackKind kind) {
        if (trackDirectory.get(typeId) != null) {
            return;
        }
        trackDefs.add(new TrackDefinition(typeId, identifier, codecVersion, kind));
    }

    private void rebuildDirectories() {
        typeDirectory = EntityTypeDirectory.of(entityDefs);
        trackDirectory = TrackDirectory.of(layerCount, strides, trackDefs);
    }

    private void rebuildSegmentDirectory() {
        MutableSegment current = tail;
        if (current == null) {
            segmentDirectory = new SegmentDirectory(List.copyOf(sealedEntries));
            return;
        }

        List<SegmentEntry> entries = new ArrayList<>(sealedEntries.size() + 1);
        entries.addAll(sealedEntries);
        entries.add(new SegmentEntry(current.getStartFrame(), current.getEndFrame(), 0L, 0L, current.isAnchor()));
        segmentDirectory = new SegmentDirectory(List.copyOf(entries));
    }

    private void applyRetention() {
        long retained = retainedFrames;
        if (retained == Long.MAX_VALUE) {
            return;
        }

        long threshold = headFrame - retained;
        long cutoff = Math.min(threshold, segmentDirectory.floorAnchorStart(threshold + 1));
        while (evictedBefore < sealedEntries.size() && sealedEntries.get(evictedBefore).endFrame() <= cutoff) {
            SegmentView evicted = segmentData.remove(evictedBefore);
            if (evicted != null) {
                evicted.close();
            }
            evictedBefore++;
        }
    }

    private void advanceHead(long frame) {
        if (frame > headFrame) {
            headFrame = frame;
        }
    }

    private void discardTail() {
        MutableSegment current = tail;
        if (current != null) {
            current.release();
            tail = null;
        }
    }

    private void bump() {
        revision.incrementAndGet();
    }

    private static void releaseIfFragment(ReelMessage message) {
        if (message instanceof ReelFragment fragment) {
            fragment.release();
        }
    }

    private Segment emptySegment(long startFrame, long endFrame) {
        WindowGroup[][] windows = new WindowGroup[layerCount][];
        for (int l = 0; l < layerCount; l++) {
            windows[l] = new WindowGroup[0];
        }
        return new Segment(startFrame, endFrame, new long[0], new int[0], new TrackReference[0], windows);
    }

    @Override
    public void close() {
        writeLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;

            while (!pending.isEmpty()) {
                releaseIfFragment(pending.pollFirst());
            }
            discardTail();
            for (SegmentView view : segmentData.values()) {
                view.close();
            }
            segmentData.clear();
        } finally {
            writeLock.unlock();
        }
    }

}
