package com.metallum.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MetalTransientMemoryTest {
    @Test
    void alignmentIsOverflowSafe() {
        assertEquals(0L, MetalTransientMemory.checkedRoundUp(0L, 16L));
        assertEquals(16L, MetalTransientMemory.checkedRoundUp(1L, 16L));
        assertEquals(32L, MetalTransientMemory.checkedRoundUp(32L, 16L));
        assertThrows(IllegalArgumentException.class,
                () -> MetalTransientMemory.checkedRoundUp(Long.MAX_VALUE, 16L));
        assertThrows(IllegalArgumentException.class,
                () -> MetalTransientMemory.checkedRoundUp(1L, 0L));
    }

    @Test
    void invalidAllocatorArgumentsAreRejectedBeforeNativeAllocation() {
        MetalTransientMemory.validateAllocation(1L, 16L, 0L, 1L, 16L, true);
        assertThrows(IllegalArgumentException.class,
                () -> MetalTransientMemory.validateAllocation(-1L, 1L, 0L, 1L, 16L, false));
        assertThrows(IllegalArgumentException.class,
                () -> MetalTransientMemory.validateAllocation(1L, 32L, 0L, 1L, 16L, false));
        assertThrows(IllegalArgumentException.class,
                () -> MetalTransientMemory.validateAllocation(1L, 1L, 0L, 0L, 16L, false));
        assertThrows(IllegalArgumentException.class,
                () -> MetalTransientMemory.validateAllocation(
                        (long) Integer.MAX_VALUE + 1L, 1L, 0L, 1L, 16L, true));
    }
}
