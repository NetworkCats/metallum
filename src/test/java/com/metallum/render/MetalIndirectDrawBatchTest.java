package com.metallum.render;

import com.metallum.mtl.MTLIndexType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MetalIndirectDrawBatchTest {
    @Test
    void validatesEveryEncodedIndexRange() {
        MetalIndirectDrawBatch batch = new MetalIndirectDrawBatch(2);
        batch.size = 2;
        batch.put(0, 3, 0, 0L);
        batch.put(1, 2, 0, 3L);

        assertDoesNotThrow(() -> batch.validateIndexRanges(MTLIndexType.UInt16, 10L));
        assertThrows(IndexOutOfBoundsException.class,
                () -> batch.validateIndexRanges(MTLIndexType.UInt16, 9L));
    }

    @Test
    void rejectsOverflowingIndexOffsets() {
        MetalIndirectDrawBatch batch = new MetalIndirectDrawBatch(1);
        batch.size = 1;
        batch.put(0, 1, 0, Long.MAX_VALUE);

        assertThrows(IllegalArgumentException.class,
                () -> batch.validateIndexRanges(MTLIndexType.UInt32, Long.MAX_VALUE));
    }
}
