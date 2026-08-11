package com.metallum.render;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CpuBufferMirrorTest {
    @Test
    void adjacentWritesMakeTheCombinedRangeReadable() {
        CpuBufferMirror mirror = new CpuBufferMirror(8);
        mirror.write(2L, ByteBuffer.wrap(new byte[]{1, 2}));
        mirror.write(4L, ByteBuffer.wrap(new byte[]{3, 4}));

        ByteBuffer bytes = mirror.read(2L, 4L);
        byte[] result = new byte[4];
        bytes.get(result);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, result);
    }

    @Test
    void unknownCopiesInvalidateOnlyTheOverwrittenRange() {
        CpuBufferMirror mirror = new CpuBufferMirror(8);
        mirror.write(0L, ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));
        mirror.copyFrom(null, 0L, 3L, 2L);

        assertArrayEquals(new byte[]{1, 2, 3}, readBytes(mirror, 0L, 3L));
        assertArrayEquals(new byte[]{6, 7, 8}, readBytes(mirror, 5L, 3L));
        assertThrows(IllegalStateException.class, () -> mirror.read(2L, 4L));
    }

    @Test
    void knownMirrorCopiesPreserveOverlappingBytes() {
        CpuBufferMirror mirror = new CpuBufferMirror(6);
        mirror.write(0L, ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5, 6}));
        mirror.copyFrom(mirror, 0L, 2L, 4L);
        assertArrayEquals(new byte[]{1, 2, 1, 2, 3, 4}, readBytes(mirror, 0L, 6L));
    }

    private static byte[] readBytes(final CpuBufferMirror mirror, final long offset, final long length) {
        ByteBuffer bytes = mirror.read(offset, length);
        byte[] result = new byte[bytes.remaining()];
        bytes.get(result);
        return result;
    }
}
