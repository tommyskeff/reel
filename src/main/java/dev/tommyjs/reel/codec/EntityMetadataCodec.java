package dev.tommyjs.reel.codec;

import org.jetbrains.annotations.NotNull;

/**
 * A codec for encoding and decoding entity metadata.
 *
 * @param <T> the type of the entity data
 */
public interface EntityMetadataCodec<T> {

    byte @NotNull [] encode(@NotNull T data);

    @NotNull T decode(byte @NotNull [] data);

}
