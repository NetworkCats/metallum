package com.metallum.render;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PendingBufferWritesTest {
    @Test
    void overlappingWritesMergeWithLatestBytesWinning() {
        PendingBufferWrites writes = new PendingBufferWrites();
        writes.add(3L, 8L, 1L, ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));
        writes.add(3L, 8L, 3L, ByteBuffer.wrap(new byte[]{9, 8, 7}));

        assertFalse(writes.coversWholeBuffer(8L));
        ByteBuffer output = ByteBuffer.allocate(8);
        writes.applyTo(output);
        assertArrayEquals(new byte[]{0, 1, 2, 9, 8, 7, 0, 0}, output.array());
    }

    @Test
    void adjacentWritesBecomeAFullReplacement() {
        PendingBufferWrites writes = new PendingBufferWrites();
        writes.add(9L, 4L, 0L, ByteBuffer.wrap(new byte[]{1, 2}));
        writes.add(9L, 4L, 2L, ByteBuffer.wrap(new byte[]{3, 4}));

        assertTrue(writes.coversWholeBuffer(4L));
        ByteBuffer output = ByteBuffer.allocate(4);
        writes.applyTo(output);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, output.array());
    }
}
