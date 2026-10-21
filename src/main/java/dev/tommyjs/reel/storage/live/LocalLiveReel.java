package dev.tommyjs.reel.storage.live;

import dev.tommyjs.reel.storage.ReelCompressor;
import dev.tommyjs.reel.storage.ReelIOUtil;
import dev.tommyjs.reel.storage.writer.LayerStrategy;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;

public final class LocalLiveReel implements Closeable {

    private final ReelLivePublisher publisher;
    private final ReelLiveReader reader;

    public LocalLiveReel(@NotNull LayerStrategy layerStrategy) {
        this(layerStrategy, ReelCompressor.LZ4);
    }

    public LocalLiveReel(@NotNull LayerStrategy layerStrategy, @NotNull ReelCompressor compression) {
        this.reader = ReelLiveReader.builder()
            .setLayerStrategy(layerStrategy)
            .setCompression(compression)
            .build();
        this.publisher = new ReelLivePublisher(layerStrategy, this::relay);
    }

    public @NotNull ReelLivePublisher publisher() {
        return publisher;
    }

    public @NotNull ReelLiveReader reader() {
        return reader;
    }

    public void flush() {
        publisher.flush();
    }

    private void relay(ReelMessage message) {
        ByteBuf buf = ReelIOUtil.DEFAULT_ALLOCATOR.buffer();
        try {
            ReelMessageCodec.encode(buf, message);
            reader.accept(ReelMessageCodec.decode(buf));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            buf.release();
        }
    }

    @Override
    public void close() {
        publisher.release();
        reader.close();
    }

}
