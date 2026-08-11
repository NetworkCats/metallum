package com.metallum.render;

import java.nio.ByteBuffer;

/** Checked byte-layout calculations shared by buffer and texture transfers. */
final class BufferCopySupport {
    private BufferCopySupport() {
    }

    static ImageLayout imageLayout(final int width, final int height, final int bytesPerPixel) {
        if (width < 0 || height < 0 || bytesPerPixel <= 0) {
            throw new IllegalArgumentException(
                    "Invalid image layout " + width + "x" + height + " at " + bytesPerPixel + " bytes per pixel");
        }
        try {
            long rowBytes = Math.multiplyExact((long) width, bytesPerPixel);
            return new ImageLayout(rowBytes, Math.multiplyExact(rowBytes, height));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Image byte layout overflows a 64-bit size", exception);
        }
    }

    static ByteBuffer exactSlice(final ByteBuffer source, final long byteCount, final String operation) {
        if (byteCount < 0L || byteCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(operation + " requires an unsupported byte count: " + byteCount);
        }
        if (byteCount > source.remaining()) {
            throw new IllegalArgumentException(
                    operation + " requires " + byteCount + " bytes, but only " + source.remaining() + " remain");
        }
        ByteBuffer result = source.slice();
        result.limit((int) byteCount);
        return result;
    }

    static void requireRange(
            final long offset,
            final long length,
            final long capacity,
            final String operation
    ) {
        if (offset < 0L || length < 0L || capacity < 0L || offset > capacity || length > capacity - offset) {
            throw new IndexOutOfBoundsException(
                    operation + " range " + offset + "+" + length + " exceeds " + capacity);
        }
    }

    record ImageLayout(long rowBytes, long imageBytes) {
    }
}
