package dev.tommyjs.reel.storage.writer;

import dev.tommyjs.reel.storage.model.RawPayload;
import dev.tommyjs.reel.track.TrackKind;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public final class ListeningReelWriter implements ReelWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ListeningReelWriter.class);

    private final ReelWriter delegate;
    private final List<ReelWriterListener> listeners;

    private long segmentStartFrame;
    private boolean segmentAnchor;

    public ListeningReelWriter(@NotNull ReelWriter delegate, @NotNull List<ReelWriterListener> listeners) {
        this.delegate = delegate;
        this.listeners = List.copyOf(listeners);
    }

    @Override
    public @NotNull LayerStrategy getLayerStrategy() {
        return delegate.getLayerStrategy();
    }

    @Override
    public @NotNull ByteBufAllocator getAllocator() {
        return delegate.getAllocator();
    }

    @Override
    public void registerEntityType(@NotNull String typeIdentifier, int typeId) {
        delegate.registerEntityType(typeIdentifier, typeId);
        dispatch(l -> l.onEntityType(typeIdentifier, typeId));
    }

    @Override
    public void registerTrackType(@NotNull String typeIdentifier, int typeId, int codecVersion,
                                  @NotNull TrackKind kind) {
        delegate.registerTrackType(typeIdentifier, typeId, codecVersion, kind);
        dispatch(l -> l.onTrackType(typeIdentifier, typeId, codecVersion, kind));
    }

    @Override
    public void beginSegment(long startFrame, boolean anchor) {
        delegate.beginSegment(startFrame, anchor);
        segmentStartFrame = startFrame;
        segmentAnchor = anchor;
        dispatch(l -> l.onSegmentBegin(startFrame, anchor));
    }

    @Override
    public void endSegment(long endFrame) {
        long startFrame = segmentStartFrame;
        boolean anchor = segmentAnchor;
        delegate.endSegment(endFrame);
        dispatch(l -> l.onSegmentEnd(startFrame, endFrame, anchor));
    }

    @Override
    public void advanceFrame(long frame) {
        delegate.advanceFrame(frame);
        dispatch(l -> l.onFrameAdvanced(frame));
    }

    @Override
    public void writeDelta(long entityId, int entityTypeId, int trackTypeId, int layer, long windowStartFrame,
                           @NotNull ByteBuf deltaBody) {
        emit(entityId, entityTypeId, trackTypeId, layer, windowStartFrame, new RawPayload.Delta(deltaBody.duplicate()));
        delegate.writeDelta(entityId, entityTypeId, trackTypeId, layer, windowStartFrame, deltaBody);
    }

    @Override
    public void writeState(long entityId, int entityTypeId, int trackTypeId, int layer, long windowStartFrame,
                           @Nullable ByteBuf previousBody, @NotNull ByteBuf currentBody) {
        ByteBuf combined = null;
        if (!listeners.isEmpty()) {
            combined = delegate.getAllocator().heapBuffer();
            if (previousBody != null) {
                combined.writeBytes(previousBody, previousBody.readerIndex(), previousBody.readableBytes());
            }
            combined.writeBytes(currentBody, currentBody.readerIndex(), currentBody.readableBytes());
        }

        try {
            if (combined != null) {
                emit(entityId, entityTypeId, trackTypeId, layer, windowStartFrame,
                    new RawPayload.State(previousBody != null, combined));
            }
        } finally {
            ReferenceCountUtil.safeRelease(combined);
        }

        delegate.writeState(entityId, entityTypeId, trackTypeId, layer, windowStartFrame, previousBody, currentBody);
    }

    @Override
    public void writeClose(long entityId, int entityTypeId, int trackTypeId, int layer, long windowStartFrame,
                           @NotNull ByteBuf previousBody) {
        emit(entityId, entityTypeId, trackTypeId, layer, windowStartFrame,
            new RawPayload.Close(previousBody.duplicate()));
        delegate.writeClose(entityId, entityTypeId, trackTypeId, layer, windowStartFrame, previousBody);
    }

    @Override
    public void writeEvent(long entityId, int entityTypeId, int trackTypeId, int layer, long windowStartFrame,
                           @NotNull ByteBuf eventBody) {
        emit(entityId, entityTypeId, trackTypeId, layer, windowStartFrame, new RawPayload.Event(eventBody.duplicate()));
        delegate.writeEvent(entityId, entityTypeId, trackTypeId, layer, windowStartFrame, eventBody);
    }

    @Override
    public void finish(long totalFrames) {
        delegate.finish(totalFrames);
        dispatch(l -> l.onFinish(totalFrames));
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    private void emit(long entityId, int entityTypeId, int trackTypeId, int layer, long windowStartFrame,
                      RawPayload payload) {
        dispatch(l -> l.onPayload(entityId, entityTypeId, trackTypeId, layer, windowStartFrame, payload));
    }

    private void dispatch(Notification notification) {
        for (ReelWriterListener listener : listeners) {
            try {
                notification.accept(listener);
            } catch (Throwable t) {
                LOGGER.warn("Exception caught whilst dispatching to a reel writer listener", t);
            }
        }
    }

    @FunctionalInterface
    private interface Notification {

        void accept(ReelWriterListener listener);

    }

}
