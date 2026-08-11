package com.metallum.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MetalFenceTest {
    @Test
    void positiveNanosecondTimeoutsRoundUpToMetalMilliseconds() {
        assertEquals(0L, MetalFence.timeoutMillis(0L));
        assertEquals(1L, MetalFence.timeoutMillis(1L));
        assertEquals(1L, MetalFence.timeoutMillis(1_000_000L));
        assertEquals(2L, MetalFence.timeoutMillis(1_000_001L));
        assertEquals(9_223_372_036_855L, MetalFence.timeoutMillis(Long.MAX_VALUE));
    }
}
