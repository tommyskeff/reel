package dev.tommyjs.reel.storage.writer;

import org.jetbrains.annotations.NotNull;

public record LayerStrategy(long @NotNull [] strides) {

    public static final LayerStrategy DEFAULT = new LayerStrategy(new long[]{1, 4, 20, 100, 400});

    public LayerStrategy {
        if (strides.length == 0) {
            throw new IllegalArgumentException("at least one layer is required");
        }

        if (strides[0] != 1) {
            throw new IllegalArgumentException("layer 0 must have stride 1");
        }

        for (int i = 1; i < strides.length; i++) {
            if (strides[i] <= strides[i - 1]) {
                throw new IllegalArgumentException("layer strides must be strictly increasing");
            }

            if (strides[i] % strides[i - 1] != 0) {
                throw new IllegalArgumentException(
                    "each stride must be an integer multiple of the previous: stride[" + i + "]=" + strides[i] +
                        ", stride[" + (i - 1) + "]=" + strides[i - 1]);
            }
        }
    }

    public long @NotNull [] strides() {
        return strides.clone();
    }

    public int depth() {
        return strides.length;
    }

    public long stride(int layer) {
        return strides[layer];
    }

    public long maxStride() {
        return strides[strides.length - 1];
    }

}
