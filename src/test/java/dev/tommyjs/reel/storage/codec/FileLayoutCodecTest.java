package dev.tommyjs.reel.storage.codec;

import dev.tommyjs.reel.storage.ReelFormat;
import dev.tommyjs.reel.storage.model.ReelFileLayout;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class FileLayoutCodecTest {

    @Test
    void headerRoundTrip() throws IOException {
        ByteBuf buf = Unpooled.buffer();
        try {
            FileLayoutCodec.encodeHeader(buf);
            FileLayoutCodec.decodeHeader(buf);
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void headerBytes() {
        ByteBuf buf = Unpooled.buffer();
        try {
            FileLayoutCodec.encodeHeader(buf);
            assertEquals(ReelFormat.HEADER_SIZE, buf.readableBytes());

            byte[] expected = new byte[ReelFormat.HEADER_SIZE];
            byte[] magic = ReelFormat.headerMagic();
            System.arraycopy(magic, 0, expected, 0, magic.length);
            expected[magic.length + 1] = (byte) ReelFormat.FORMAT_VERSION;

            assertArrayEquals(expected, ByteBufUtil.getBytes(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void headerRejectsBadMagic() {
        ByteBuf buf = Unpooled.buffer();
        try {
            FileLayoutCodec.encodeHeader(buf);
            buf.setByte(0, 0x00);
            assertThrows(IOException.class, () -> FileLayoutCodec.decodeHeader(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void headerRejectsBadVersion() {
        ByteBuf buf = Unpooled.buffer();
        try {
            FileLayoutCodec.encodeHeader(buf);
            buf.setShort(ReelFormat.HEADER_MAGIC_LENGTH, ReelFormat.FORMAT_VERSION + 1);
            assertThrows(IOException.class, () -> FileLayoutCodec.decodeHeader(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void footerRoundTrip() throws IOException {
        ReelFileLayout layout = new ReelFileLayout(1000, 38, 200, 300, 400);
        ByteBuf buf = Unpooled.buffer();
        try {
            FileLayoutCodec.encodeFooter(buf, layout);
            assertEquals(ReelFormat.FOOTER_SIZE, buf.readableBytes());
            assertEquals(layout, FileLayoutCodec.decodeFooter(buf));
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void footerRejectsBadMagic() {
        ReelFileLayout layout = new ReelFileLayout(1, 2, 3, 4, 5);
        ByteBuf buf = Unpooled.buffer();
        try {
            FileLayoutCodec.encodeFooter(buf, layout);
            buf.setByte(buf.writerIndex() - 1, 0x7F);
            assertThrows(IOException.class, () -> FileLayoutCodec.decodeFooter(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void footerRejectsNegativeOffset() {
        ReelFileLayout layout = new ReelFileLayout(1, 2, 3, 4, 5);
        ByteBuf buf = Unpooled.buffer();
        try {
            FileLayoutCodec.encodeFooter(buf, layout);
            buf.setLong(0, -1L);
            assertThrows(IOException.class, () -> FileLayoutCodec.decodeFooter(buf));
        } finally {
            buf.release();
        }
    }

}
