package dev.tommyjs.reel.track;

import dev.tommyjs.reel.TestFixtures;
import dev.tommyjs.reel.track.codec.VoidCodec;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrackKindTest {

    @Test
    void deltaModelDefaultsToDeltaKind() {
        assertEquals(TrackKind.DELTA, TestFixtures.intModel().kind());
    }

    @Test
    void snapshotModelReportsSnapshotKind() {
        SnapshotTrackModel<Integer> model = new SnapshotTrackModel<>() {
        };
        assertEquals(TrackKind.SNAPSHOT, model.kind());
    }

    @Test
    void eventModelReportsEventKind() {
        assertEquals(TrackKind.EVENT, TestFixtures.signalModel().kind());
    }

    @Test
    void eventModelRejectsStateOperations() {
        EventTrackModel<TestFixtures.Signal> model = TestFixtures.signalModel();
        assertThrows(UnsupportedOperationException.class, () -> model.cloneState(null));
        assertThrows(UnsupportedOperationException.class, () -> model.reverse(new TestFixtures.Signal(1)));
        assertThrows(UnsupportedOperationException.class, () -> model.applyDelta(null, new TestFixtures.Signal(1)));
    }

    @Test
    void voidCodecRejectsSerialization() {
        assertThrows(UnsupportedOperationException.class, () -> VoidCodec.INSTANCE.decode(Unpooled.buffer()));
        assertThrows(UnsupportedOperationException.class, () -> VoidCodec.INSTANCE.encode(Unpooled.buffer(), null));
    }

}
