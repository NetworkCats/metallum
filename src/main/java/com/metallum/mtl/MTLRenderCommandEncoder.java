package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import com.metallum.render.Stats;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
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
    private static final Msg SET_VERTEX_BUFFER = Msg.ofVoid("setVertexBuffer:offset:atIndex:", ADDRESS, JAVA_LONG, JAVA_LONG);
    private static final Msg SET_FRAGMENT_BUFFER = Msg.ofVoid("setFragmentBuffer:offset:atIndex:", ADDRESS, JAVA_LONG, JAVA_LONG);
    private static final Msg SET_VERTEX_BUFFER_OFFSET = Msg.ofVoid("setVertexBufferOffset:atIndex:", JAVA_LONG, JAVA_LONG);
    private static final Msg SET_FRAGMENT_BUFFER_OFFSET = Msg.ofVoid("setFragmentBufferOffset:atIndex:", JAVA_LONG, JAVA_LONG);
    private static final Msg SET_VERTEX_TEXTURE = Msg.ofVoid("setVertexTexture:atIndex:", ADDRESS, JAVA_LONG);
    private static final Msg SET_FRAGMENT_TEXTURE = Msg.ofVoid("setFragmentTexture:atIndex:", ADDRESS, JAVA_LONG);
    private static final Msg SET_VERTEX_SAMPLER = Msg.ofVoid("setVertexSamplerState:atIndex:", ADDRESS, JAVA_LONG);
    private static final Msg SET_FRAGMENT_SAMPLER = Msg.ofVoid("setFragmentSamplerState:atIndex:", ADDRESS, JAVA_LONG);
    private static final Msg SET_MTL4_ARGUMENT_TABLE =
            Msg.ofVoid("setArgumentTable:atStages:", ADDRESS, JAVA_LONG);
    private static final Msg SET_SCISSOR_RECT = Msg.ofVoid("setScissorRect:", ADDRESS);
    private static final Msg SET_VIEWPORT = Msg.ofVoid("setViewport:", ADDRESS);
    private static final Msg SET_VERTEX_BYTES = Msg.ofVoid("setVertexBytes:length:atIndex:", ADDRESS, JAVA_LONG, JAVA_LONG);
    private static final Msg DRAW_PRIMITIVES = Msg.ofVoid("drawPrimitives:vertexStart:vertexCount:instanceCount:baseInstance:",
            JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    private static final Msg DRAW_INDEXED = Msg.ofVoid("drawIndexedPrimitives:indexCount:indexType:indexBuffer:indexBufferOffset:instanceCount:baseVertex:baseInstance:",
            JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    private static final Msg DRAW_INDEXED_INDIRECT = Msg.ofVoid("drawIndexedPrimitives:indexType:indexBuffer:indexBufferOffset:indirectBuffer:indirectBufferOffset:",
            JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final Msg DRAW_INDIRECT = Msg.ofVoid("drawPrimitives:indirectBuffer:indirectBufferOffset:", JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final Msg UPDATE_FENCE = Msg.ofVoid("updateFence:afterStages:", ADDRESS, JAVA_LONG);
    private static final Msg WAIT_FOR_FENCE = Msg.ofVoid("waitForFence:beforeStages:", ADDRESS, JAVA_LONG);
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
    private static final Msg SAMPLE_COUNTERS = Msg.ofVoid(
            "sampleCountersInBuffer:atSampleIndex:withBarrier:", ADDRESS, JAVA_LONG, JAVA_BOOLEAN);
    private static final Msg MTL4_WRITE_TIMESTAMP = Msg.ofVoid(
            "writeTimestampWithGranularity:afterStage:intoHeap:atIndex:",
            JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final Msg EXECUTE_ICB = Msg.ofVoid(
            "executeCommandsInBuffer:withRange:", ADDRESS, NS_RANGE);
    private static final Msg USE_RESOURCE = Msg.ofVoid(
            "useResource:usage:stages:", ADDRESS, JAVA_LONG, JAVA_LONG);

    @Nullable
    private final MTL4ArgumentTable argumentTable;
    @Nullable
    private final MTLCommandBuffer commandBuffer;
    private final long[] bufferBaseAddresses = new long[MTL4ArgumentTable.MAX_BUFFERS];
    private final long[] metal4BufferAddresses = new long[MTL4ArgumentTable.MAX_BUFFERS];
    private final long[] metal4Textures = new long[MTL4ArgumentTable.MAX_TEXTURES];
    private final long[] metal4Samplers = new long[MTL4ArgumentTable.MAX_SAMPLERS];
    private final long[] vertexBuffers = new long[64];
    private final long[] vertexBufferOffsets = new long[64];
    private final long[] fragmentBuffers = new long[64];
    private final long[] fragmentBufferOffsets = new long[64];
    private final long[] vertexTextures = new long[128];
    private final long[] fragmentTextures = new long[128];
    private final long[] vertexSamplers = new long[16];
    private final long[] fragmentSamplers = new long[16];

    MTLRenderCommandEncoder(
            final MemorySegment handle,
            final boolean metal4,
            @Nullable final MTL4ArgumentTable argumentTable,
            @Nullable final MTLCommandBuffer commandBuffer
    ) {
        super(handle, metal4);
        this.argumentTable = argumentTable;
        this.commandBuffer = commandBuffer;
        invalidateBindingCache();
        if (metal4) {
            SET_MTL4_ARGUMENT_TABLE.send(
                    handle,
                    requireArgumentTable().handle(),
                    MTLRenderStages.VertexAndFragment.value
            );
        }
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

    public void setVertexBuffer(final MTLBuffer buffer, final long offset, final long index) {
        if (this.metal4) {
            setMetal4Buffer(buffer, offset, index);
        } else {
            int slot = checkedIndex(index, this.vertexBuffers.length, "vertex buffer");
            long resource = handleAddress(buffer == null ? MemorySegment.NULL : buffer.handle());
            if (this.vertexBuffers[slot] == resource && this.vertexBufferOffsets[slot] == offset) {
                Stats.increment(Stats.Counter.BINDING_CACHE_HITS);
                return;
            }
            SET_VERTEX_BUFFER.send(handle(), seg(buffer), offset, index);
            this.vertexBuffers[slot] = resource;
            this.vertexBufferOffsets[slot] = offset;
            Stats.increment(Stats.Counter.BINDING_CALLS);
        }
    }

    public void setFragmentBuffer(final MTLBuffer buffer, final long offset, final long index) {
        if (this.metal4) {
            setMetal4Buffer(buffer, offset, index);
        } else {
            int slot = checkedIndex(index, this.fragmentBuffers.length, "fragment buffer");
            long resource = handleAddress(buffer == null ? MemorySegment.NULL : buffer.handle());
            if (this.fragmentBuffers[slot] == resource && this.fragmentBufferOffsets[slot] == offset) {
                Stats.increment(Stats.Counter.BINDING_CACHE_HITS);
                return;
            }
            SET_FRAGMENT_BUFFER.send(handle(), seg(buffer), offset, index);
            this.fragmentBuffers[slot] = resource;
            this.fragmentBufferOffsets[slot] = offset;
            Stats.increment(Stats.Counter.BINDING_CALLS);
        }
    }

    public void setVertexBufferOffset(final long offset, final long index) {
        if (this.metal4) {
            setMetal4BufferOffset(offset, index);
        } else {
            int slot = checkedIndex(index, this.vertexBuffers.length, "vertex buffer");
            if (this.vertexBufferOffsets[slot] == offset) {
                Stats.increment(Stats.Counter.BINDING_CACHE_HITS);
                return;
            }
            SET_VERTEX_BUFFER_OFFSET.send(handle(), offset, index);
            this.vertexBufferOffsets[slot] = offset;
            Stats.increment(Stats.Counter.BINDING_CALLS);
        }
    }

    public void setFragmentBufferOffset(final long offset, final long index) {
        if (this.metal4) {
            setMetal4BufferOffset(offset, index);
        } else {
            int slot = checkedIndex(index, this.fragmentBuffers.length, "fragment buffer");
            if (this.fragmentBufferOffsets[slot] == offset) {
                Stats.increment(Stats.Counter.BINDING_CACHE_HITS);
                return;
            }
            SET_FRAGMENT_BUFFER_OFFSET.send(handle(), offset, index);
            this.fragmentBufferOffsets[slot] = offset;
            Stats.increment(Stats.Counter.BINDING_CALLS);
        }
    }

    public void setVertexTexture(final MemorySegment texture, final long index) {
        if (this.metal4) {
            setMetal4Texture(texture, index);
        } else {
            int slot = checkedIndex(index, this.vertexTextures.length, "vertex texture");
            long resource = handleAddress(texture);
            if (this.vertexTextures[slot] == resource) {
                Stats.increment(Stats.Counter.BINDING_CACHE_HITS);
                return;
            }
            SET_VERTEX_TEXTURE.send(handle(), ObjC.orNil(texture), index);
            this.vertexTextures[slot] = resource;
            Stats.increment(Stats.Counter.BINDING_CALLS);
        }
    }

    public void setFragmentTexture(final MemorySegment texture, final long index) {
        if (this.metal4) {
            setMetal4Texture(texture, index);
        } else {
            int slot = checkedIndex(index, this.fragmentTextures.length, "fragment texture");
            long resource = handleAddress(texture);
            if (this.fragmentTextures[slot] == resource) {
                Stats.increment(Stats.Counter.BINDING_CACHE_HITS);
                return;
            }
            SET_FRAGMENT_TEXTURE.send(handle(), ObjC.orNil(texture), index);
            this.fragmentTextures[slot] = resource;
            Stats.increment(Stats.Counter.BINDING_CALLS);
        }
    }

    public void setVertexSamplerState(final MemorySegment sampler, final long index) {
        if (this.metal4) {
            setMetal4Sampler(sampler, index);
        } else {
            int slot = checkedIndex(index, this.vertexSamplers.length, "vertex sampler");
            long resource = handleAddress(sampler);
            if (this.vertexSamplers[slot] == resource) {
                Stats.increment(Stats.Counter.BINDING_CACHE_HITS);
                return;
            }
            SET_VERTEX_SAMPLER.send(handle(), ObjC.orNil(sampler), index);
            this.vertexSamplers[slot] = resource;
            Stats.increment(Stats.Counter.BINDING_CALLS);
        }
    }

    public void setFragmentSamplerState(final MemorySegment sampler, final long index) {
        if (this.metal4) {
            setMetal4Sampler(sampler, index);
        } else {
            int slot = checkedIndex(index, this.fragmentSamplers.length, "fragment sampler");
            long resource = handleAddress(sampler);
            if (this.fragmentSamplers[slot] == resource) {
                Stats.increment(Stats.Counter.BINDING_CACHE_HITS);
                return;
            }
            SET_FRAGMENT_SAMPLER.send(handle(), ObjC.orNil(sampler), index);
            this.fragmentSamplers[slot] = resource;
            Stats.increment(Stats.Counter.BINDING_CALLS);
        }
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

    public void setVertexBytes(final MemorySegment bytes, final long length, final long index) {
        if (this.metal4) {
            if (this.commandBuffer == null) {
                throw new IllegalStateException("Metal 4 render encoder has no command buffer");
            }
            MTLCommandBuffer.InlineBufferSlice allocation =
                    this.commandBuffer.makeInlineBuffer(bytes, length);
            setMetal4Buffer(allocation.buffer(), allocation.offset(), index);
        } else {
            SET_VERTEX_BYTES.send(handle(), bytes, length, index);
        }
    }

    public void clearDraw(
            final MemorySegment colorTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            @Nullable final Vector4fc clearColor,
            @Nullable final Double clearDepth
    ) {
        MTLBuiltinPipelines.clearDraw(
                this,
                colorTexture,
                depthTexture,
                viewportWidth,
                viewportHeight,
                clearColor,
                clearDepth
        );
    }

    public void drawPrimitives(final MTLPrimitiveType primitiveType, final int firstVertex, final int vertexCount, final int instanceCount, final int baseInstance) {
        DRAW_PRIMITIVES.send(handle(), primitiveType.value, firstVertex, vertexCount, instanceCount, baseInstance);
        Stats.increment(Stats.Counter.DRAWS);
    }

    public void drawIndexedPrimitives(final MTLPrimitiveType primitiveType, final int indexCount, final MTLIndexType indexType, final MTLBuffer indexBuffer, final long offset, final int instanceCount, final int baseVertex, final int baseInstance) {
        if (this.metal4) {
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
        } else {
            DRAW_INDEXED.send(handle(), primitiveType.value, indexCount, indexType.value, indexBuffer.handle(), offset, instanceCount, baseVertex, baseInstance);
        }
        Stats.increment(Stats.Counter.DRAWS);
    }

    public void drawIndexedPrimitivesIndirect(final MTLPrimitiveType primitiveType, final MTLIndexType indexType, final MTLBuffer indexBuffer, final MTLBuffer indirectBuffer, final long indirectBufferOffset) {
        if (this.metal4) {
            MTL4_DRAW_INDEXED_INDIRECT.send(
                    handle(),
                    primitiveType.value,
                    indexType.value,
                    indexBuffer.gpuAddress(),
                    indexBuffer.length(),
                    indirectBuffer.gpuAddress() + indirectBufferOffset
            );
        } else {
            DRAW_INDEXED_INDIRECT.send(handle(), primitiveType.value, indexType.value, indexBuffer.handle(), 0L, indirectBuffer.handle(), indirectBufferOffset);
        }
        Stats.increment(Stats.Counter.DRAWS);
    }

    public void drawPrimitivesIndirect(final MTLPrimitiveType primitiveType, final MTLBuffer indirectBuffer, final long indirectBufferOffset) {
        if (this.metal4) {
            MTL4_DRAW_INDIRECT.send(
                    handle(), primitiveType.value, indirectBuffer.gpuAddress() + indirectBufferOffset);
        } else {
            DRAW_INDIRECT.send(handle(), primitiveType.value, indirectBuffer.handle(), indirectBufferOffset);
        }
        Stats.increment(Stats.Counter.DRAWS);
    }

    public void writeTimestamp(final MTLGpuCounterPool pool, final long index) {
        if (this.metal4) {
            MTL4_WRITE_TIMESTAMP.send(
                    handle(), 0L, MTLRenderStages.Fragment.value, pool.handle(), index);
        } else {
            SAMPLE_COUNTERS.send(handle(), pool.handle(), index, false);
        }
    }

    public void executeCommands(
            final MTLIndirectCommandBuffer commands,
            final int commandCount,
            final MTLBuffer indexBuffer
    ) {
        if (commandCount <= 0) {
            return;
        }
        if (!this.metal4) {
            USE_RESOURCE.send(handle(), commands.handle(), 1L, MTLRenderStages.Vertex.value);
            USE_RESOURCE.send(handle(), indexBuffer.handle(), 1L, MTLRenderStages.Vertex.value);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            MemorySegment range = MemorySegment.ofAddress(stack.nmalloc(8, 16)).reinterpret(16);
            range.set(JAVA_LONG, 0L, 0L);
            range.set(JAVA_LONG, Long.BYTES, commandCount);
            EXECUTE_ICB.send(handle(), commands.handle(), range);
        }
    }

    public void updateFence(final MTLFence fence, final MTLRenderStages stages) {
        if (this.metal4) {
            MTL4_UPDATE_FENCE.send(handle(), fence.handle(), stages.value);
        } else {
            UPDATE_FENCE.send(handle(), fence.handle(), stages.value);
        }
    }

    public void waitForFence(final MTLFence fence, final MTLRenderStages stages) {
        if (this.metal4) {
            MTL4_WAIT_FOR_FENCE.send(handle(), fence.handle(), stages.value);
        } else {
            WAIT_FOR_FENCE.send(handle(), fence.handle(), stages.value);
        }
    }

    private void setMetal4Buffer(final MTLBuffer buffer, final long offset, final long index) {
        if (index < 0L || index >= this.bufferBaseAddresses.length) {
            throw new IllegalArgumentException("Metal 4 buffer binding index out of range: " + index);
        }
        if (offset < 0L || offset > buffer.length()) {
            throw new IllegalArgumentException("Metal 4 buffer offset out of range: " + offset);
        }
        long base = buffer.gpuAddress();
        this.bufferBaseAddresses[(int) index] = base;
        long address = base + offset;
        if (this.metal4BufferAddresses[(int) index] == address) {
            Stats.increment(Stats.Counter.BINDING_CACHE_HITS);
            return;
        }
        requireArgumentTable().setBufferAddress(address, index);
        this.metal4BufferAddresses[(int) index] = address;
        Stats.increment(Stats.Counter.BINDING_CALLS);
    }

    private void setMetal4BufferOffset(final long offset, final long index) {
        if (index < 0L || index >= this.bufferBaseAddresses.length) {
            throw new IllegalArgumentException("Metal 4 buffer binding index out of range: " + index);
        }
        long base = this.bufferBaseAddresses[(int) index];
        if (base == 0L) {
            throw new IllegalStateException("Metal 4 buffer binding " + index + " has no base address");
        }
        long address = base + offset;
        if (this.metal4BufferAddresses[(int) index] == address) {
            Stats.increment(Stats.Counter.BINDING_CACHE_HITS);
            return;
        }
        requireArgumentTable().setBufferAddress(address, index);
        this.metal4BufferAddresses[(int) index] = address;
        Stats.increment(Stats.Counter.BINDING_CALLS);
    }

    private void setMetal4Texture(final MemorySegment texture, final long index) {
        int slot = checkedIndex(index, this.metal4Textures.length, "Metal 4 texture");
        long resource = handleAddress(texture);
        if (this.metal4Textures[slot] == resource) {
            Stats.increment(Stats.Counter.BINDING_CACHE_HITS);
            return;
        }
        requireArgumentTable().setTexture(texture, index);
        this.metal4Textures[slot] = resource;
        Stats.increment(Stats.Counter.BINDING_CALLS);
    }

    private void setMetal4Sampler(final MemorySegment sampler, final long index) {
        int slot = checkedIndex(index, this.metal4Samplers.length, "Metal 4 sampler");
        long resource = handleAddress(sampler);
        if (this.metal4Samplers[slot] == resource) {
            Stats.increment(Stats.Counter.BINDING_CACHE_HITS);
            return;
        }
        requireArgumentTable().setSampler(sampler, index);
        this.metal4Samplers[slot] = resource;
        Stats.increment(Stats.Counter.BINDING_CALLS);
    }

    public void invalidateBindingCache() {
        Arrays.fill(this.metal4BufferAddresses, Long.MIN_VALUE);
        Arrays.fill(this.metal4Textures, Long.MIN_VALUE);
        Arrays.fill(this.metal4Samplers, Long.MIN_VALUE);
        Arrays.fill(this.vertexBuffers, Long.MIN_VALUE);
        Arrays.fill(this.vertexBufferOffsets, Long.MIN_VALUE);
        Arrays.fill(this.fragmentBuffers, Long.MIN_VALUE);
        Arrays.fill(this.fragmentBufferOffsets, Long.MIN_VALUE);
        Arrays.fill(this.vertexTextures, Long.MIN_VALUE);
        Arrays.fill(this.fragmentTextures, Long.MIN_VALUE);
        Arrays.fill(this.vertexSamplers, Long.MIN_VALUE);
        Arrays.fill(this.fragmentSamplers, Long.MIN_VALUE);
    }

    private MTL4ArgumentTable requireArgumentTable() {
        if (this.argumentTable == null) {
            throw new IllegalStateException("Metal 4 argument table is unavailable");
        }
        return this.argumentTable;
    }

    private static long checkedRemainingLength(final MTLBuffer buffer, final long offset) {
        long length = buffer.length();
        if (offset < 0L || offset > length) {
            throw new IllegalArgumentException("Index buffer offset " + offset + " exceeds length " + length);
        }
        return length - offset;
    }

    private static MemorySegment seg(final MTLBuffer buffer) {
        return buffer == null ? MemorySegment.NULL : buffer.handle();
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
