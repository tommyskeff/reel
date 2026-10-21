package dev.tommyjs.reel.storage.reader;

import dev.tommyjs.reel.storage.ReelCompressor;
import dev.tommyjs.reel.storage.FileByteSource;
import dev.tommyjs.reel.storage.ReelByteSource;
import dev.tommyjs.reel.storage.ReelFormat;
import dev.tommyjs.reel.storage.codec.DirectoryCodec;
import dev.tommyjs.reel.storage.codec.FileLayoutCodec;
import dev.tommyjs.reel.storage.codec.SegmentCodec;
import dev.tommyjs.reel.storage.model.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

public final class ReelFileReader implements ReelReader {

    private final ReelByteSource source;
    private final ReelFileLayout layout;
    private final ByteBufAllocator allocator;
    private ReelCompressor compression = ReelCompressor.LZ4;

    private EntityTypeDirectory typeDirectory;
    private TrackDirectory trackDirectory;
    private SegmentDirectory segmentDirectory;

    private boolean closed;

    public ReelFileReader(@NotNull ReelByteSource source, @NotNull ByteBufAllocator allocator) {
        this.allocator = allocator;
        this.source = source;
        try {
            this.layout = readLayout();
        } catch (IOException e) {
            try {
                source.close();
            } catch (IOException ignored) {
            }
            throw new UncheckedIOException(e);
        }
    }

    public ReelFileReader(@NotNull File file, @NotNull ByteBufAllocator allocator) {
        this(openFile(file), allocator);
    }

    private static ReelByteSource openFile(@NotNull File file) {
        try {
            return new FileByteSource(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public long getTotalFrames() {
        return layout.totalFrames();
    }

    @Override
    public @NotNull EntityTypeDirectory readTypeDirectory() {
        if (typeDirectory == null) {
            typeDirectory = decodeDirectory(layout.typeDirOffset(), layout.trackDirOffset(),
                DirectoryCodec::decodeEntityTypes);
        }
        return typeDirectory;
    }

    @Override
    public @NotNull TrackDirectory readTrackDirectory() {
        if (trackDirectory == null) {
            trackDirectory = decodeDirectory(layout.trackDirOffset(), layout.segmentDirOffset(),
                DirectoryCodec::decodeTrackTypes);
        }
        return trackDirectory;
    }

    @Override
    public @NotNull SegmentDirectory readSegmentDirectory() {
        if (segmentDirectory == null) {
            long end = source.length() - ReelFormat.FOOTER_SIZE;
            segmentDirectory = decodeDirectory(layout.segmentDirOffset(), end, DirectoryCodec::decodeSegments);
        }
        return segmentDirectory;
    }

    @Override
    public @NotNull SegmentView readSegment(int segmentIndex) {
        try {
            TrackDirectory td = readTrackDirectory();
            SegmentEntry entry = readSegmentDirectory().segments().get(segmentIndex);

            long absoluteOffset = layout.segmentDataOffset() + entry.byteOffset();
            long byteSize = entry.byteSize();
            if (byteSize > Integer.MAX_VALUE) {
                throw new IOException("segment[" + segmentIndex + "] byte_size exceeds 2 GiB: " + byteSize);
            }

            ByteBuf compressed = source.read(allocator, absoluteOffset, (int) byteSize);
            try {
                return SegmentCodec.decodeCompressed(compressed, (int) byteSize, td.layerCount(), compression);
            } finally {
                ReferenceCountUtil.safeRelease(compressed);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }

        closed = true;
        source.close();
    }

    private ReelFileLayout readLayout() throws IOException {
        long fileLength = source.length();
        if (fileLength < ReelFormat.HEADER_SIZE + ReelFormat.FOOTER_SIZE) {
            throw new IOException(
                "file too small: length=" + fileLength + ", min=" + (ReelFormat.HEADER_SIZE + ReelFormat.FOOTER_SIZE));
        }

        ByteBuf header = source.read(allocator, 0, ReelFormat.HEADER_SIZE);
        try {
            this.compression = ReelCompressor.forId(FileLayoutCodec.decodeHeaderCompression(header));
        } finally {
            header.release();
        }

        long footerPos = fileLength - ReelFormat.FOOTER_SIZE;
        ByteBuf footer = source.read(allocator, footerPos, ReelFormat.FOOTER_SIZE);
        ReelFileLayout layout;
        try {
            layout = FileLayoutCodec.decodeFooter(footer);
        } finally {
            footer.release();
        }

        validateLayout(layout, footerPos);
        return layout;
    }

    private static void validateLayout(ReelFileLayout layout, long footerPos) throws IOException {
        if (layout.segmentDataOffset() < ReelFormat.HEADER_SIZE) {
            throw new IOException(
                "segment_data_offset before header end: segment_data_offset=" + layout.segmentDataOffset() +
                    ", header_size=" + ReelFormat.HEADER_SIZE);
        }
        if (layout.typeDirOffset() < layout.segmentDataOffset() || layout.trackDirOffset() < layout.typeDirOffset() ||
            layout.segmentDirOffset() < layout.trackDirOffset()) {
            throw new IOException("directory offsets out of order: entity_type_dir_offset=" + layout.typeDirOffset() +
                ", track_dir_offset=" + layout.trackDirOffset() + ", segment_dir_offset=" + layout.segmentDirOffset() +
                ", segment_data_offset=" + layout.segmentDataOffset());
        }
        if (layout.segmentDirOffset() >= footerPos) {
            throw new IOException(
                "segment_dir_offset must be before footer: segment_dir_offset=" + layout.segmentDirOffset() +
                    ", footer_offset=" + footerPos);
        }
    }

    private <T> T decodeDirectory(long offset, long end, DirectoryDecoder<T> decoder) {
        long size = end - offset;
        if (size < 0 || size > Integer.MAX_VALUE) {
            throw new UncheckedIOException(
                new IOException("region size out of range: size=" + size + ", offset=" + offset));
        }

        try {
            ByteBuf buf = source.read(allocator, offset, (int) size);
            try {
                return decoder.decode(buf);
            } finally {
                buf.release();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    private interface DirectoryDecoder<T> {

        T decode(ByteBuf buf) throws IOException;

    }

}
