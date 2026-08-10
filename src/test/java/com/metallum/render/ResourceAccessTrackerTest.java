package com.metallum.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ResourceAccessTrackerTest {
    private static final long VERTEX = 1L;
    private static final long FRAGMENT = 2L;
    private static final long BLIT = 1L << 28;

    @Test
    void readAfterWriteOnOverlappingBufferNeedsDeviceVisibility() {
        ResourceAccessTracker tracker = new ResourceAccessTracker();
        Object buffer = new Object();
        tracker.beginEncoder();
        assertNoDependency(tracker.accessBuffer(
                buffer, 0, 32, ResourceAccessTracker.Access.WRITE, BLIT));

        tracker.beginEncoder();
        ResourceAccessTracker.Dependency dependency = tracker.accessBuffer(
                buffer, 16, 16, ResourceAccessTracker.Access.READ, VERTEX);

        assertEquals(BLIT, dependency.priorStages());
        assertEquals(VERTEX, dependency.consumerStages());
        assertTrue(dependency.deviceVisibility());
        assertArrayEquals(new long[]{0L}, dependency.producerEncoders());
    }

    @Test
    void writeAfterReadIsExecutionOnly() {
        ResourceAccessTracker tracker = new ResourceAccessTracker();
        Object buffer = new Object();
        tracker.beginEncoder();
        tracker.accessBuffer(buffer, 4, 8, ResourceAccessTracker.Access.READ, FRAGMENT);

        tracker.beginEncoder();
        ResourceAccessTracker.Dependency dependency = tracker.accessBuffer(
                buffer, 0, 16, ResourceAccessTracker.Access.WRITE, BLIT);

        assertEquals(FRAGMENT, dependency.priorStages());
        assertFalse(dependency.deviceVisibility());
    }

    @Test
    void writeAfterWriteNeedsDeviceVisibility() {
        ResourceAccessTracker tracker = new ResourceAccessTracker();
        Object buffer = new Object();
        tracker.beginEncoder();
        tracker.accessBuffer(buffer, 0, 8, ResourceAccessTracker.Access.WRITE, BLIT);

        tracker.beginEncoder();
        ResourceAccessTracker.Dependency dependency = tracker.accessBuffer(
                buffer, 7, 1, ResourceAccessTracker.Access.WRITE, BLIT);

        assertTrue(dependency.hasPriorDependency());
        assertTrue(dependency.deviceVisibility());
    }

    @Test
    void readReadAndDisjointRangesDoNotSynchronize() {
        ResourceAccessTracker tracker = new ResourceAccessTracker();
        Object buffer = new Object();
        tracker.beginEncoder();
        tracker.accessBuffer(buffer, 0, 16, ResourceAccessTracker.Access.READ, VERTEX);
        tracker.accessBuffer(buffer, 32, 16, ResourceAccessTracker.Access.WRITE, BLIT);

        tracker.beginEncoder();
        assertNoDependency(tracker.accessBuffer(
                buffer, 8, 4, ResourceAccessTracker.Access.READ, FRAGMENT));
        assertNoDependency(tracker.accessBuffer(
                buffer, 16, 16, ResourceAccessTracker.Access.READ, FRAGMENT));
    }

    @Test
    void textureMipAndLayerRangesAreIndependent() {
        ResourceAccessTracker tracker = new ResourceAccessTracker();
        Object texture = new Object();
        tracker.beginEncoder();
        tracker.accessTexture(
                texture, 1, 1, 2, 1, ResourceAccessTracker.Access.WRITE, BLIT);

        tracker.beginEncoder();
        assertNoDependency(tracker.accessTexture(
                texture, 0, 1, 2, 1, ResourceAccessTracker.Access.READ, FRAGMENT));
        assertNoDependency(tracker.accessTexture(
                texture, 1, 1, 3, 1, ResourceAccessTracker.Access.READ, FRAGMENT));
        ResourceAccessTracker.Dependency overlap = tracker.accessTexture(
                texture, 1, 1, 2, 1, ResourceAccessTracker.Access.READ, FRAGMENT);
        assertTrue(overlap.hasPriorDependency());
        assertTrue(overlap.deviceVisibility());
    }

    @Test
    void sameEncoderConflictUsesEncoderBarrier() {
        ResourceAccessTracker tracker = new ResourceAccessTracker();
        Object buffer = new Object();
        tracker.beginEncoder();
        tracker.accessBuffer(buffer, 0, 64, ResourceAccessTracker.Access.WRITE, BLIT);
        ResourceAccessTracker.Dependency dependency = tracker.accessBuffer(
                buffer, 0, 4, ResourceAccessTracker.Access.READ, BLIT);

        assertFalse(dependency.hasPriorDependency());
        assertEquals(BLIT, dependency.sameEncoderStages());
        assertTrue(dependency.deviceVisibility());
    }

    private static void assertNoDependency(final ResourceAccessTracker.Dependency dependency) {
        assertFalse(dependency.hasPriorDependency());
        assertFalse(dependency.hasSameEncoderDependency());
        assertEquals(0, dependency.producerEncoders().length);
    }
}
