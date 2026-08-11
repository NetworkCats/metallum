package com.metallum.render;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BufferCopySupportTest {
    @Test
    void exactSliceStartsAtTheCurrentSourcePosition() {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{9, 8, 1, 2, 3, 4, 7});
        source.position(2);

        ByteBuffer result = BufferCopySupport.exactSlice(source, 4L, "test upload");

        byte[] bytes = new byte[result.remaining()];
        result.get(bytes);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, bytes);
        assertEquals(2, source.position());
    }

    @Test
    void exactSliceRejectsAnUndersizedSource() {
        ByteBuffer source = ByteBuffer.allocate(8).position(5);
        assertThrows(IllegalArgumentException.class,
                () -> BufferCopySupport.exactSlice(source, 4L, "test upload"));
    }

    @Test
    void imageLayoutUsesCheckedLongArithmetic() {
        BufferCopySupport.ImageLayout layout = BufferCopySupport.imageLayout(16_384, 16_384, 4);
        assertEquals(65_536L, layout.rowBytes());
        assertEquals(1_073_741_824L, layout.imageBytes());
        assertThrows(IllegalArgumentException.class,
                () -> BufferCopySupport.imageLayout(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void rangeValidationCannotOverflowAtTheEnd() {
        BufferCopySupport.requireRange(Long.MAX_VALUE - 1L, 1L, Long.MAX_VALUE, "test copy");
        assertThrows(IndexOutOfBoundsException.class,
                () -> BufferCopySupport.requireRange(Long.MAX_VALUE - 1L, 2L, Long.MAX_VALUE, "test copy"));
    }
}
