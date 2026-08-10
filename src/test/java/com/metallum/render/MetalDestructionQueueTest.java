package com.metallum.render;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MetalDestructionQueueTest {
    @Test
    void closeDrainsActionsQueuedByDestroyCallbacks() {
        MetalDestructionQueue queue = new MetalDestructionQueue(3);
        AtomicInteger destroyed = new AtomicInteger();
        queue.add(() -> {
            destroyed.incrementAndGet();
            queue.add(destroyed::incrementAndGet);
        });

        queue.close();

        assertEquals(2, destroyed.get());
    }
}
