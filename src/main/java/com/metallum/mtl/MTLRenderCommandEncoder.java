package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import com.metallum.render.Stats;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.system.MemoryStack;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.MemoryLayout;
import java.util.Arrays;

import static java.lang.foreign.ValueLayout.*;

@Environment(EnvType.CLIENT)
public final class MTLRenderCommandEncoder extends MTLCommandEncoder {
    private static final MemoryLayout NS_RANGE = MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG);
    private static final Msg SET_RENDER_PIPELINE_STATE = Msg.ofVoid("setRenderPipelineState:", ADDRESS);
    private static final Msg SET_DEPTH_STENCIL_STATE = Msg.ofVoid("setDepthStencilState:", ADDRESS);
    private static final Msg SET_DEPTH_BIAS = Msg.ofVoid("setDepthBias:slopeScale:clamp:", JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);
    private static final Msg SET_FRONT_FACING_WINDING = Msg.ofVoid("setFrontFacingWinding:", JAVA_LONG);
    private static final Msg SET_CULL_MODE = Msg.ofVoid("setCullMode:", JAVA_LONG);
    private static final Msg SET_TRIANGLE_FILL_MODE = Msg.ofVoid("setTriangleFillMode:", JAVA_LONG);
    private static final Msg SET_MTL4_ARGUMENT_TABLE =
            Msg.ofVoid("setArgumentTable:atStages:", ADDRESS, JAVA_LONG);
    private static final Msg SET_SCISSOR_RECT = Msg.ofVoid("setScissorRect:", ADDRESS);
    private static final Msg SET_VIEWPORT = Msg.ofVoid("setViewport:", ADDRESS);
    private static final Msg DRAW_PRIMITIVES = Msg.ofVoid("drawPrimitives:vertexStart:vertexCount:instanceCount:baseInstance:",
            JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    private static final Msg MTL4_UPDATE_FENCE =
            Msg.ofVoid("updateFence:afterEncoderStages:", ADDRESS, JAVA_LONG);
    private static final Msg MTL4_WAIT_FOR_FENCE =
            Msg.ofVoid("waitForFence:beforeEncoderStages:", ADDRESS, JAVA_LONG);
    private static final Msg MTL4_DRAW_INDEXED = Msg.ofVoid(
            "drawIndexedPrimitives:indexCount:indexType:indexBuffer:indexBufferLength:instanceCount:baseVertex:baseInstance:",
            JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    private static final Msg MTL4_DRAW_INDEXED_INDIRECT = Msg.ofVoid(
            "drawIndexedPrimitives:indexType:indexBuffer:indexBufferLength:indirectBuffer:",
            JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    private static final Msg MTL4_DRAW_INDIRECT = Msg.ofVoid(
            "drawPrimitives:indirectBuffer:", JAVA_LONG, JAVA_LONG);
    private static final Msg MTL4_WRITE_TIMESTAMP = Msg.ofVoid(
            "writeTimestampWithGranularity:afterStage:intoHeap:atIndex:",
            JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final Msg EXECUTE_ICB = Msg.ofVoid(
            "executeCommandsInBuffer:withRange:", ADDRESS, NS_RANGE);

    private final MTL4ArgumentTable argumentTable;
    private final MTLCommandBuffer commandBuffer;
    private final long[] bufferBaseAddresses = new long[MTL4ArgumentTable.MAX_BUFFERS];
    private final long[] bufferAddresses = new long[MTL4ArgumentTable.MAX_BUFFERS];
    private final long[] textures = new long[MTL4ArgumentTable.MAX_TEXTURES];
    private final long[] samplers = new long[MTL4ArgumentTable.MAX_SAMPLERS];

    MTLRenderCommandEncoder(
            final MemorySegment handle,
            final MTL4ArgumentTable argumentTable,
            final MTLCommandBuffer commandBuffer
    ) {
        super(handle);
        this.argumentTable = argumentTable;
        this.commandBuffer = commandBuffer;
        invalidateBindingCache();
        SET_MTL4_ARGUMENT_TABLE.send(
                handle,
                argumentTable.handle(),
                MTLRenderStages.VertexAndFragment.value
        );
    }

    public void setRenderPipelineState(final MemorySegment pipeline) {
        SET_RENDER_PIPELINE_STATE.send(handle(), ObjC.orNil(pipeline));
        invalidateBindingCache();
    }

    public void setDepthStencilState(final MemorySegment depthStencilState) {
        SET_DEPTH_STENCIL_STATE.send(handle(), ObjC.orNil(depthStencilState));
    }

    public void setDepthBias(final float depthBias, final float slopeScale, final float clamp) {
        SET_DEPTH_BIAS.send(handle(), depthBias, slopeScale, clamp);
    }

    public void setFrontFacingWinding(final MTLWinding winding) {
        SET_FRONT_FACING_WINDING.send(handle(), winding.value);
    }

    public void setCullMode(final MTLCullMode cullMode) {
        SET_CULL_MODE.send(handle(), cullMode.value);
    }

    public void setTriangleFillMode(final MTLTriangleFillMode fillMode) {
        SET_TRIANGLE_FILL_MODE.send(handle(), fillMode.value);
    }

    /** Binds a buffer in the shared vertex/fragment argument table. */
    public void setBuffer(final MTLBuffer buffer, final long offset, final long index) {
        int slot = checkedIndex(index, this.bufferAddresses.length, "buffer");
        if (offset < 0L || offset > buffer.length()) {
            throw new IllegalArgumentException("Buffer offset out of range: " + offset);
        }
        long base = buffer.gpuAddress();
        this.bufferBaseAddresses[slot] = base;
        bindBufferAddress(slot, base + offset);
    }

    public void setBufferOffset(final long offset, final long index) {
        int slot = checkedIndex(index, this.bufferAddresses.length, "buffer");
        long base = this.bufferBaseAddresses[slot];
        if (base == 0L) {
            throw new IllegalStateException("Buffer binding " + index + " has no base address");
        }
        bindBufferAddress(slot, base + offset);
    }

    public void setTexture(final MemorySegment texture, final long index) {
        int slot = checkedIndex(index, this.textures.length, "texture");
        long resource = handleAddress(texture);
        if (this.textures[slot] == resource) {
            Stats.increment(Stats.Counter.BINDING_CACHE_HITS);
            return;
        }
        boolean changed = this.argumentTable.setTexture(texture, index);
        this.textures[slot] = resource;
        Stats.increment(changed ? Stats.Counter.BINDING_CALLS : Stats.Counter.BINDING_CACHE_HITS);
    }

    public void setSamplerState(final MemorySegment sampler, final long index) {
        int slot = checkedIndex(index, this.samplers.length, "sampler");
        long resource = handleAddress(sampler);
        if (this.samplers[slot] == resource) {
            Stats.increment(Stats.Counter.BINDING_CACHE_HITS);
            return;
        }
        boolean changed = this.argumentTable.setSampler(sampler, index);
        this.samplers[slot] = resource;
        Stats.increment(changed ? Stats.Counter.BINDING_CALLS : Stats.Counter.BINDING_CACHE_HITS);
    }

    public void setScissorRect(final long x, final long y, final long width, final long height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SET_SCISSOR_RECT.send(handle(), MTLScissorRect.on(stack, x, y, width, height));
        }
    }

    public void setViewport(final double originX, final double originY, final double width, final double height, final double znear, final double zfar) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            MemorySegment viewport = MemorySegment.ofAddress(stack.nmalloc(8, 48)).reinterpret(48);
            viewport.set(JAVA_DOUBLE, 0, originX);
            viewport.set(JAVA_DOUBLE, 8, originY);
            viewport.set(JAVA_DOUBLE, 16, width);
            viewport.set(JAVA_DOUBLE, 24, height);
            viewport.set(JAVA_DOUBLE, 32, znear);
            viewport.set(JAVA_DOUBLE, 40, zfar);
            SET_VIEWPORT.send(handle(), viewport);
        }
    }

    /** Copies small constant data into the command buffer's inline arena and binds it. */
    public void setBytes(final MemorySegment bytes, final long length, final long index) {
        MTLCommandBuffer.InlineBufferSlice allocation = this.commandBuffer.makeInlineBuffer(bytes, length);
        setBuffer(allocation.buffer(), allocation.offset(), index);
    }

    public void drawPrimitives(final MTLPrimitiveType primitiveType, final int firstVertex, final int vertexCount, final int instanceCount, final int baseInstance) {
        DRAW_PRIMITIVES.send(handle(), primitiveType.value, firstVertex, vertexCount, instanceCount, baseInstance);
        Stats.increment(Stats.Counter.DRAWS);
    }

    public void drawIndexedPrimitives(final MTLPrimitiveType primitiveType, final int indexCount, final MTLIndexType indexType, final MTLBuffer indexBuffer, final long offset, final int instanceCount, final int baseVertex, final int baseInstance) {
        long indexBufferLength = checkedRemainingLength(indexBuffer, offset);
        MTL4_DRAW_INDEXED.send(
                handle(),
                primitiveType.value,
                indexCount,
                indexType.value,
                indexBuffer.gpuAddress() + offset,
                indexBufferLength,
                instanceCount,
                baseVertex,
                baseInstance
        );
        Stats.increment(Stats.Counter.DRAWS);
    }

    public void drawIndexedPrimitivesIndirect(final MTLPrimitiveType primitiveType, final MTLIndexType indexType, final MTLBuffer indexBuffer, final MTLBuffer indirectBuffer, final long indirectBufferOffset) {
        MTL4_DRAW_INDEXED_INDIRECT.send(
                handle(),
                primitiveType.value,
                indexType.value,
                indexBuffer.gpuAddress(),
                indexBuffer.length(),
                indirectBuffer.gpuAddress() + indirectBufferOffset
        );
        Stats.increment(Stats.Counter.DRAWS);
    }

    public void drawPrimitivesIndirect(final MTLPrimitiveType primitiveType, final MTLBuffer indirectBuffer, final long indirectBufferOffset) {
        MTL4_DRAW_INDIRECT.send(
                handle(), primitiveType.value, indirectBuffer.gpuAddress() + indirectBufferOffset);
        Stats.increment(Stats.Counter.DRAWS);
    }

    public void writeTimestamp(final MTLGpuCounterPool pool, final long index) {
        MTL4_WRITE_TIMESTAMP.send(
                handle(), 0L, MTLRenderStages.Fragment.value, pool.handle(), index);
    }

    public void executeCommands(final MTLIndirectCommandBuffer commands, final int commandCount) {
        if (commandCount <= 0) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            MemorySegment range = MemorySegment.ofAddress(stack.nmalloc(8, 16)).reinterpret(16);
            range.set(JAVA_LONG, 0L, 0L);
            range.set(JAVA_LONG, Long.BYTES, commandCount);
            EXECUTE_ICB.send(handle(), commands.handle(), range);
        }
    }

    public void updateFence(final MTLFence fence, final MTLRenderStages stages) {
        MTL4_UPDATE_FENCE.send(handle(), fence.handle(), stages.value);
    }

    public void waitForFence(final MTLFence fence, final MTLRenderStages stages) {
        MTL4_WAIT_FOR_FENCE.send(handle(), fence.handle(), stages.value);
    }

    private void bindBufferAddress(final int slot, final long address) {
        if (this.bufferAddresses[slot] == address) {
            Stats.increment(Stats.Counter.BINDING_CACHE_HITS);
            return;
        }
        boolean changed = this.argumentTable.setBufferAddress(address, slot);
        this.bufferAddresses[slot] = address;
        Stats.increment(changed ? Stats.Counter.BINDING_CALLS : Stats.Counter.BINDING_CACHE_HITS);
    }

    public void invalidateBindingCache() {
        Arrays.fill(this.bufferAddresses, Long.MIN_VALUE);
        Arrays.fill(this.textures, Long.MIN_VALUE);
        Arrays.fill(this.samplers, Long.MIN_VALUE);
    }

    private static long checkedRemainingLength(final MTLBuffer buffer, final long offset) {
        long length = buffer.length();
        if (offset < 0L || offset > length) {
            throw new IllegalArgumentException("Index buffer offset " + offset + " exceeds length " + length);
        }
        return length - offset;
    }

    private static int checkedIndex(final long index, final int length, final String kind) {
        if (index < 0L || index >= length) {
            throw new IllegalArgumentException(kind + " binding index out of range: " + index);
        }
        return (int) index;
    }

    private static long handleAddress(final MemorySegment handle) {
        return ObjC.isNil(handle) ? 0L : handle.address();
    }
}
