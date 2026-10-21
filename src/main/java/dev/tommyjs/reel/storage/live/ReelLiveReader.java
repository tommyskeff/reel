package dev.tommyjs.reel.storage.live;

import dev.tommyjs.reel.storage.model.EntityTypeDirectory;
import dev.tommyjs.reel.storage.model.TrackDirectory;
import dev.tommyjs.reel.storage.reader.ReelReader;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public interface ReelLiveReader extends ReelReader {

    static @NotNull ReelLiveReaderBuilder builder() {
        return new ReelLiveReaderBuilder();
    }

    void accept(@NotNull ReelMessage message);

    void acceptDirectories(@NotNull EntityTypeDirectory types, @NotNull TrackDirectory tracks);

    void acceptSegment(long startFrame, long endFrame, boolean anchor, @NotNull ByteBuf compressed) throws IOException;

    long getHeadFrame();

    void setRetainedFrames(long frames);

    int getPendingMessageCount();

    @NotNull ReelHeadState headState();

    @Override
    void close();

}
