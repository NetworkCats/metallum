package com.metallum.render;

import com.metallum.Metallum;
import com.metallum.mtl.MTLBuffer;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.List;
import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
final class MetalDestructionQueue {
    private final List<DestroyAction>[] queues;
    private int currentQueueIndex;

    @SuppressWarnings("unchecked")
    MetalDestructionQueue(final int queueCount) {
        this.queues = (List<DestroyAction>[]) new List<?>[queueCount];
        for (int i = 0; i < queueCount; i++) {
            this.queues[i] = new ArrayList<>();
        }
    }

    void add(final Runnable destroyAction) {
        if (destroyAction == null) {
            return;
        }
        this.queues[this.currentQueueIndex].add(new CallbackAction(destroyAction));
    }

    void addObjCRelease(final MemorySegment handle) {
        if (!ObjC.isNil(handle)) {
            this.queues[this.currentQueueIndex].add(new ObjCReleaseAction(handle));
        }
    }

    void addAllocationRelease(final MetalDevice device, final MemorySegment handle) {
        if (!ObjC.isNil(handle)) {
            this.queues[this.currentQueueIndex].add(new AllocationReleaseAction(device, handle));
        }
    }

    void addDynamicBackingRecycle(
            final MetalCommandEncoder encoder,
            final MTLBuffer buffer,
            final long size
    ) {
        this.queues[this.currentQueueIndex].add(
                new DynamicBackingRecycleAction(encoder, buffer, size));
    }

    void rotate() {
        this.currentQueueIndex = (this.currentQueueIndex + 1) % this.queues.length;
        List<DestroyAction> toDestroy = this.queues[this.currentQueueIndex];
        for (DestroyAction destroyAction : toDestroy) {
            try {
                destroyAction.destroy();
            } catch (Exception e) {
                Metallum.LOGGER.error("[metallum] Destroy action threw an exception; resource may have leaked", e);
            }
        }
        toDestroy.clear();
    }

    void close() {
        for (int i = 0; i < this.queues.length; i++) {
            this.rotate();
        }
    }

    private sealed interface DestroyAction permits CallbackAction, ObjCReleaseAction,
            AllocationReleaseAction, DynamicBackingRecycleAction {
        void destroy();
    }

    private record CallbackAction(Runnable callback) implements DestroyAction {
        @Override
        public void destroy() {
            this.callback.run();
        }
    }

    private record ObjCReleaseAction(MemorySegment handle) implements DestroyAction {
        @Override
        public void destroy() {
            ObjC.release(this.handle);
        }
    }

    private record AllocationReleaseAction(
            MetalDevice device,
            MemorySegment handle
    ) implements DestroyAction {
        @Override
        public void destroy() {
            this.device.releaseAllocationNow(this.handle);
        }
    }

    private record DynamicBackingRecycleAction(
            MetalCommandEncoder encoder,
            MTLBuffer buffer,
            long size
    ) implements DestroyAction {
        @Override
        public void destroy() {
            this.encoder.recycleDynamicBackingNow(this.buffer, this.size);
        }
    }
}
