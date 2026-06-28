package dev.tommyjs.reel.storage.writer;

import dev.tommyjs.reel.storage.ReelCompressor;
import dev.tommyjs.reel.storage.FileByteSink;
import dev.tommyjs.reel.storage.ReelByteSink;
import dev.tommyjs.reel.storage.ReelFormat;
import dev.tommyjs.reel.storage.codec.DirectoryCodec;
import dev.tommyjs.reel.storage.codec.FileLayoutCodec;
import dev.tommyjs.reel.storage.codec.SegmentCodec;
import dev.tommyjs.reel.storage.model.*;
import dev.tommyjs.reel.storage.writer.registry.EntityTypeRegistry;
import dev.tommyjs.reel.storage.writer.registry.TrackTypeRegistry;
import dev.tommyjs.reel.track.TrackKind;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ReelFileWriter implements ReelWriter {

    private final ReelByteSink sink;
    private final ByteBufAllocator allocator;
    private final @NotNull LayerStrategy layerStrategy;
    private final @NotNull ReelCompressor compression;

    private final EntityTypeRegistry entityTypes;
    private final TrackTypeRegistry trackTypes;
    private final List<SegmentEntry> segmentEntries;

    private boolean finished;
    private boolean closed;
    private long segmentDataSectionOffset;
    private long lastSegmentEndFrame;
    private PendingSegment pendingSegment;

    public ReelFileWriter(@NotNull ReelByteSink sink, @NotNull ByteBufAllocator allocator,
                          @NotNull LayerStrategy layerStrategy) {
        this(sink, allocator, layerStrategy, ReelCompressor.LZ4);
    }

    public ReelFileWriter(@NotNull ReelByteSink sink, @NotNull ByteBufAllocator allocator,
                          @NotNull LayerStrategy layerStrategy, @NotNull ReelCompressor compression) {
        this.sink = sink;
        this.allocator = allocator;
        this.layerStrategy = layerStrategy;
        this.compression = compression;
        this.entityTypes = new EntityTypeRegistry();
        this.trackTypes = new TrackTypeRegistry();
        this.segmentEntries = new ArrayList<>();
        this.finished = false;
        this.closed = false;
        this.lastSegmentEndFrame = Long.MIN_VALUE;

        try {
            writeHeader();
        } catch (IOException e) {
            try {
                sink.close();
            } catch (IOException ignored) {
            }
            throw new UncheckedIOException(e);
        }
    }

    public ReelFileWriter(@NotNull File file, @NotNull ByteBufAllocator allocator,
                          @NotNull LayerStrategy layerStrategy) {
        this(openFile(file), allocator, layerStrategy, ReelCompressor.LZ4);
    }

    public ReelFileWriter(@NotNull File file, @NotNull ByteBufAllocator allocator,
                          @NotNull LayerStrategy layerStrategy, @NotNull ReelCompressor compression) {
        this(openFile(file), allocator, layerStrategy, compression);
    }

    private static ReelByteSink openFile(@NotNull File file) {
        try {
            return new FileByteSink(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeHeader() throws IOException {
        writeData(buf -> FileLayoutCodec.encodeHeader(buf, compression.id()), ReelFormat.HEADER_SIZE);
        segmentDataSectionOffset = sink.position();
    }

    @Override
    public void registerEntityType(@NotNull String typeIdentifier, int typeId) {
        checkWritable();
        entityTypes.register(typeIdentifier, typeId);
    }

    @Override
    public void registerTrackType(@NotNull String typeIdentifier, int typeId, int codecVersion,
                                  @NotNull TrackKind kind) {
        checkWritable();
        trackTypes.register(typeIdentifier, typeId, codecVersion, kind);
    }

    @Override
    public void beginSegment(long startFrame, boolean anchor) {
        checkWritable();
        if (pendingSegment != null) {
            throw new IllegalStateException("segment already in progress");
        }

        if (lastSegmentEndFrame != Long.MIN_VALUE && startFrame < lastSegmentEndFrame) {
            throw new IllegalArgumentException(
                "start_frame overlaps previous segment: start_frame=" + startFrame + ", last_end_frame=" +
                    lastSegmentEndFrame);
        }

        long maxStride = layerStrategy.maxStride();
        if (startFrame % maxStride != 0) {
            throw new IllegalArgumentException(
                "start_frame must be aligned to max stride (" + maxStride + "): start_frame=" + startFrame);
        }

        if (lastSegmentEndFrame != Long.MIN_VALUE && lastSegmentEndFrame % maxStride != 0) {
            throw new IllegalStateException(
                "previous segment end_frame must be aligned to max stride (" + maxStride + "): last_end_frame=" +
                    lastSegmentEndFrame);
        }

        pendingSegment = new PendingSegment(startFrame, layerStrategy, anchor);
    }

    @Override
    public void endSegment(long endFrame) {
        checkWritable();
        if (pendingSegment == null) {
            throw new IllegalStateException("no segment in progress");
        }

        if (endFrame < pendingSegment.getStartFrame()) {
            throw new IllegalArgumentException(
                "end_frame before start_frame: end_frame=" + endFrame + ", start_frame=" +
                    pendingSegment.getStartFrame());
        }

        try {
            writeEncodedSegment(endFrame);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            pendingSegment = null;
        }
    }

    private void writeEncodedSegment(long endFrame) throws IOException {
        Segment segment = pendingSegment.build(endFrame);
        ByteBuf compressed = allocator.heapBuffer();
        try {
            SegmentCodec.encodeCompressed(compressed, segment, compression);

            long encodedSize = compressed.readableBytes();
            long byteOffset = sink.position() - segmentDataSectionOffset;
            writeData(compressed);
            segmentEntries.add(
                new SegmentEntry(segment.getStartFrame(), endFrame, byteOffset, encodedSize, pendingSegment.isAnchor()));
            lastSegmentEndFrame = endFrame;
        } finally {
            segment.close();
            ReferenceCountUtil.safeRelease(compressed);
        }
    }

    @Override
    public void writeDelta(long entityId, int entityTypeId, int trackTypeId, int layer, long windowStartFrame,
                           @NotNull ByteBuf deltaBody) {
        try {
            checkInsideSegment();
            validateTypes(entityTypeId, trackTypeId);
            pendingSegment.addEntry(entityId, entityTypeId, trackTypeId, layer, windowStartFrame,
                new RawPayload.Delta(deltaBody), true);
        } catch (Throwable t) {
            ReferenceCountUtil.safeRelease(deltaBody);
            throw t;
        }
    }

    @Override
    public void writeState(long entityId, int entityTypeId, int trackTypeId, int layer, long windowStartFrame,
                           @Nullable ByteBuf previousBody, @NotNull ByteBuf currentBody) {
        ByteBuf combined = null;
        try {
            checkInsideSegment();
            validateTypes(entityTypeId, trackTypeId);
            combined = allocator.buffer();
            if (previousBody != null) {
                combined.writeBytes(previousBody, previousBody.readerIndex(), previousBody.readableBytes());
            }
            combined.writeBytes(currentBody, currentBody.readerIndex(), currentBody.readableBytes());
            pendingSegment.addEntry(entityId, entityTypeId, trackTypeId, layer, windowStartFrame,
                new RawPayload.State(previousBody != null, combined), false);
            combined = null;
        } catch (Throwable t) {
            ReferenceCountUtil.safeRelease(combined);
            throw t;
        } finally {
            ReferenceCountUtil.safeRelease(previousBody);
            ReferenceCountUtil.safeRelease(currentBody);
        }
    }

    @Override
    public void writeClose(long entityId, int entityTypeId, int trackTypeId, int layer, long windowStartFrame,
                           @NotNull ByteBuf previousBody) {
        try {
            checkInsideSegment();
            validateTypes(entityTypeId, trackTypeId);
            pendingSegment.addEntry(entityId, entityTypeId, trackTypeId, layer, windowStartFrame,
                new RawPayload.Close(previousBody), false);
        } catch (Throwable t) {
            ReferenceCountUtil.safeRelease(previousBody);
            throw t;
        }
    }

    @Override
    public void writeEvent(long entityId, int entityTypeId, int trackTypeId, int layer, long windowStartFrame,
                           @NotNull ByteBuf eventBody) {
        try {
            checkInsideSegment();
            validateTypes(entityTypeId, trackTypeId);
            pendingSegment.addEntry(entityId, entityTypeId, trackTypeId, layer, windowStartFrame,
                new RawPayload.Event(eventBody), false);
        } catch (Throwable t) {
            ReferenceCountUtil.safeRelease(eventBody);
            throw t;
        }
    }

    private void validateTypes(int entityTypeId, int trackTypeId) {
        if (entityTypes.get(entityTypeId) == null) {
            throw new IllegalArgumentException("unknown entity_type_id: " + entityTypeId);
        }

        if (trackTypes.get(trackTypeId) == null) {
            throw new IllegalArgumentException("unknown track_type_id: " + trackTypeId);
        }
    }

    @Override
    public void finish(long totalFrames) {
        checkWritable();
        if (pendingSegment != null) {
            throw new IllegalStateException("cannot finish: segment in progress");
        }

        finished = true;
        try {
            long typeDirOffset = sink.position();
            writeData(buf -> DirectoryCodec.encodeEntityTypes(buf, entityTypes.build()));
            long trackDirOffset = sink.position();
            writeData(buf -> DirectoryCodec.encodeTrackTypes(buf, trackTypes.build(layerStrategy)));
            long segmentDirOffset = sink.position();
            writeData(
                buf -> DirectoryCodec.encodeSegments(buf, new SegmentDirectory(List.copyOf(segmentEntries))));
            writeFooter(totalFrames, typeDirOffset, trackDirOffset, segmentDirOffset);
            sink.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (!closed) {
                closed = true;
                try {
                    sink.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void writeFooter(long totalFrames, long typeDirOffset, long trackDirOffset, long segmentDirOffset)
        throws IOException {
        ReelFileLayout layout = new ReelFileLayout(totalFrames, segmentDataSectionOffset, typeDirOffset, trackDirOffset,
            segmentDirOffset);
        writeData(buf -> FileLayoutCodec.encodeFooter(buf, layout), ReelFormat.FOOTER_SIZE);
    }

    private void writeData(ByteBuf buf) throws IOException {
        sink.write(buf);
    }

    private void writeData(Consumer<ByteBuf> writer, int initialCapacity) throws IOException {
        ByteBuf buf = initialCapacity <= 0 ? allocator.heapBuffer() : allocator.heapBuffer(initialCapacity);
        try {
            writer.accept(buf);
            writeData(buf);
        } finally {
            buf.release();
        }
    }

    private void writeData(Consumer<ByteBuf> writer) throws IOException {
        writeData(writer, -1);
    }

    private void checkWritable() {
        if (closed) {
            throw new IllegalStateException("writer closed");
        }

        if (finished) {
            throw new IllegalStateException("writer finished");
        }
    }

    private void checkInsideSegment() {
        checkWritable();
        if (pendingSegment == null) {
            throw new IllegalStateException("no segment in progress");
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }

        closed = true;

        if (pendingSegment != null) {
            pendingSegment.releasePayloads();
            pendingSegment = null;
        }

        sink.close();
    }

    @Override
    public @NotNull LayerStrategy getLayerStrategy() {
        return layerStrategy;
    }

    @Override
    public @NotNull ByteBufAllocator getAllocator() {
        return allocator;
    }

}
