package dev.tommyjs.reel.storage.model;

import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

public sealed interface RawPayload {

    @NotNull ByteBuf body();

    default @NotNull RawPayload duplicate() {
        if (this instanceof Delta d) {
            return new Delta(d.body().duplicate());
        } else if (this instanceof State s) {
            return new State(s.hasPrevious(), s.body().duplicate());
        } else if (this instanceof Close c) {
            return new Close(c.body().duplicate());
        } else if (this instanceof Event e) {
            return new Event(e.body().duplicate());
        } else {
            throw new IllegalStateException("unhandled RawPayload type: " + this.getClass());
        }
    }

    default @NotNull RawPayload retainedDuplicate() {
        if (this instanceof Delta d) {
            return new Delta(d.body().retainedDuplicate());
        } else if (this instanceof State s) {
            return new State(s.hasPrevious(), s.body().retainedDuplicate());
        } else if (this instanceof Close c) {
            return new Close(c.body().retainedDuplicate());
        } else if (this instanceof Event e) {
            return new Event(e.body().retainedDuplicate());
        } else {
            throw new IllegalStateException("unhandled RawPayload type: " + this.getClass());
        }
    }

    record Delta(@NotNull ByteBuf body) implements RawPayload {
    }

    record State(boolean hasPrevious, @NotNull ByteBuf body) implements RawPayload {
    }

    record Close(@NotNull ByteBuf body) implements RawPayload {
    }

    record Event(@NotNull ByteBuf body) implements RawPayload {
    }

}
