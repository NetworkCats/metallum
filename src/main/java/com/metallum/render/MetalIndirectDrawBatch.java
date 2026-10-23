package com.metallum.render;

import com.metallum.mtl.MTLBuffer;
import com.metallum.mtl.MTLIndexType;
import com.metallum.mtl.MTLIndirectCommandBuffer;
import com.metallum.mtl.MTLPrimitiveType;
import com.metallum.mtl.MTLRenderCommandEncoder;
import com.metallum.mixin.render.FrontendRenderPassAccessor;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;

/** Sodium region batch backed by a reusable Metal indirect command buffer. */
public final class MetalIndirectDrawBatch extends MultiDrawBatch {
    private final int[] indexCounts;
    private final int[] baseVertices;
    private final long[] firstIndices;
    private long revision;
    private long nativeRevision = -1L;
    private MetalGpuBuffer nativeIndexOwner;
    private long nativeIndexGeneration = -1L;
    private long nativeIndexBuffer;
    private int nativeSize = -1;
    private long failedRevision = -1L;
    private MetalGpuBuffer failedIndexOwner;
    private long failedIndexGeneration = -1L;
    private long failedIndexBuffer;
    private MTLIndirectCommandBuffer commands;
    private MetalDevice device;

    public MetalIndirectDrawBatch(final int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Batch capacity must be positive");
        }
        this.indexCounts = new int[capacity];
        this.baseVertices = new int[capacity];
        this.firstIndices = new long[capacity];
    }

    @Override
    public void put(final int index, final int indexCount, final int baseVertex, final long firstIndex) {
        if (index < 0 || index >= this.indexCounts.length) {
            throw new IndexOutOfBoundsException(index);
        }
        if (indexCount < 0 || firstIndex < 0L) {
            throw new IllegalArgumentException(
                    "Indirect indexed draw count and first index must not be negative");
        }
        this.indexCounts[index] = indexCount;
        this.baseVertices[index] = baseVertex;
        this.firstIndices[index] = firstIndex;
        this.revision++;
        updateMaxElementCount(indexCount);
    }

    @Override
    public void draw(final DrawContext context) {
        if (!(context.getPass() instanceof FrontendRenderPassAccessor frontendPass)
                || !(frontendPass.metallum$getBackend() instanceof MetalRenderPass metalPass)) {
            throw new IllegalStateException("Metal batch requires an active Metal render pass");
        }
        metalPass.drawBatch(this);
    }

    boolean execute(
            final MetalDevice device,
            final MTLRenderCommandEncoder encoder,
            final MTLPrimitiveType primitiveType,
            final MTLIndexType indexType,
            final MetalGpuBuffer indexBufferOwner
    ) {
        if (this.size <= 0 || primitiveType == MTLPrimitiveType.TriangleFan) {
            return false;
        }
        MTLBuffer indexBuffer = indexBufferOwner.metalBuffer();
        long generation = indexBufferOwner.backingGeneration();
        long backing = indexBuffer.handle().address();
        if (needsRebuild(indexBufferOwner, generation, backing)) {
            if (this.failedRevision == this.revision
                    && this.failedIndexOwner == indexBufferOwner
                    && this.failedIndexGeneration == generation
                    && this.failedIndexBuffer == backing) {
                return false;
            }
            if (!rebuild(
                    device, primitiveType, indexType,
                    indexBufferOwner, indexBuffer, generation, backing)) {
                this.failedRevision = this.revision;
                this.failedIndexOwner = indexBufferOwner;
                this.failedIndexGeneration = generation;
                this.failedIndexBuffer = backing;
                return false;
            }
        }
        encoder.executeCommands(this.commands, this.size);
        Stats.increment(Stats.Counter.ICB_EXECUTIONS);
        Stats.add(Stats.Counter.ICB_COMMANDS, this.size);
        Stats.add(Stats.Counter.DRAWS, this.size);
        return true;
    }

    void drawFallback(final MetalRenderPass pass) {
        for (int i = 0; i < this.size; i++) {
            if (this.indexCounts[i] > 0) {
                pass.drawIndexed(this.indexCounts[i], 1,
                        Math.toIntExact(this.firstIndices[i]), this.baseVertices[i], 0);
            }
        }
    }

    void validateIndexRanges(final MTLIndexType indexType, final long bufferSize) {
        if (this.size < 0 || this.size > this.indexCounts.length) {
            throw new IllegalStateException(
                    "Indirect draw batch size " + this.size + " exceeds capacity " + this.indexCounts.length);
        }
        for (int i = 0; i < this.size; i++) {
            long offset;
            long length;
            try {
                offset = Math.multiplyExact(this.firstIndices[i], indexType.bytes);
                length = Math.multiplyExact((long) this.indexCounts[i], indexType.bytes);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Indirect indexed draw byte range overflows", exception);
            }
            BufferCopySupport.requireRange(offset, length, bufferSize, "Indirect indexed draw " + i);
        }
    }

    private boolean needsRebuild(
            final MetalGpuBuffer indexBufferOwner,
            final long generation,
            final long backing
    ) {
        return this.commands == null
                || this.nativeRevision != this.revision
                || this.nativeIndexOwner != indexBufferOwner
                || this.nativeIndexGeneration != generation
                || this.nativeIndexBuffer != backing
                || this.nativeSize != this.size;
    }

    private boolean rebuild(
            final MetalDevice device,
            final MTLPrimitiveType primitiveType,
            final MTLIndexType indexType,
            final MetalGpuBuffer indexBufferOwner,
            final MTLBuffer indexBuffer,
            final long generation,
            final long backing
    ) {
        MTLIndirectCommandBuffer fresh = null;
        try {
            fresh = MTLIndirectCommandBuffer.create(device.metalDevice(), this.size);
            for (int i = 0; i < this.size; i++) {
                fresh.encodeIndexed(
                        i,
                        primitiveType,
                        this.indexCounts[i],
                        indexType,
                        indexBuffer,
                        Math.multiplyExact(this.firstIndices[i], indexType.bytes),
                        this.baseVertices[i]
                );
            }
            device.trackAllocation(fresh.handle());
            releaseCommands();
            this.commands = fresh;
            this.device = device;
            this.nativeRevision = this.revision;
            this.nativeIndexOwner = indexBufferOwner;
            this.nativeIndexGeneration = generation;
            this.nativeIndexBuffer = backing;
            this.nativeSize = this.size;
            this.failedRevision = -1L;
            this.failedIndexOwner = null;
            this.failedIndexGeneration = -1L;
            Stats.increment(Stats.Counter.NATIVE_RESOURCES_CREATED);
            return true;
        } catch (RuntimeException exception) {
            if (fresh != null) {
                fresh.close();
            }
            return false;
        }
    }

    private void releaseCommands() {
        if (this.commands == null) {
            return;
        }
        if (this.device != null) {
            this.device.queueAllocationRelease(this.commands.handle());
            this.commands.detach();
        } else {
            this.commands.close();
        }
        this.commands = null;
        this.nativeIndexOwner = null;
        this.nativeIndexGeneration = -1L;
    }

    @Override
    public void delete() {
        releaseCommands();
    }
}
