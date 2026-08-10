package com.metallum.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TransientBlockSizingPolicyTest {
    @Test
    void growsAfterConsecutiveHighP95WindowsAndShrinksAfterIdleFrames() {
        TransientBlockSizingPolicy policy = new TransientBlockSizingPolicy();
        long highUsage = 3L * 1024L * 1024L;

        for (int frame = 0; frame < 180; frame++) {
            policy.recordFrame(highUsage);
        }
        assertEquals(4L * 1024L * 1024L, policy.blockSize());

        for (int frame = 0; frame < 300; frame++) {
            policy.recordFrame(0L);
        }
        assertEquals(2L * 1024L * 1024L, policy.blockSize());
    }

    @Test
    void ignoresSingleHighWindow() {
        TransientBlockSizingPolicy policy = new TransientBlockSizingPolicy();
        for (int frame = 0; frame < 120; frame++) {
            policy.recordFrame(2L * 1024L * 1024L);
        }
        assertEquals(TransientBlockSizingPolicy.MIN_BLOCK_SIZE, policy.blockSize());
    }
}
