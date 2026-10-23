package com.metallum.render;

import com.metallum.mtl.*;
import com.metallum.objc.ObjC;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;
import org.lwjgl.vulkan.VkDrawIndirectCommand;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
final class MetalRenderPass implements RenderPassBackend {
    static final int MAX_VERTEX_BUFFERS = RenderPass.MAX_VERTEX_BUFFERS;
    private static final int MAX_PUSH_CONSTANT_BYTES = 4096;
    private final MetalDevice device;
    private final MetalCommandEncoder commandEncoder;
    private final MetalGpuTextureView[] colorTextures;
    @Nullable
    private final MetalGpuTextureView depthTexture;
    private final int targetWidth;
    private final int targetHeight;
    private final RenderPass.RenderArea renderArea;
    @Nullable
    private Vector4fc[] clearColors;
    @Nullable
    private Double clearDepth;
    private final ScissorState scissorState = new ScissorState();
    private final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[MAX_VERTEX_BUFFERS];
    private final long[] vertexBufferBackings = new long[MAX_VERTEX_BUFFERS];
    private final GpuBufferSlice[] descriptorBuffers = new GpuBufferSlice[Long.SIZE];
    private final long[] descriptorBackings = new long[Long.SIZE];
    private final TextureViewAndSampler[] descriptorTextures = new TextureViewAndSampler[Long.SIZE];
    private long dirtyDescriptorMask;
    private final MemorySegment pushConstantBytes;
    private int pushConstantLength;
    private boolean pushConstantsDirty;
    @Nullable
    private MetalCompiledRenderPipeline compiledPipeline;
    @Nullable
    private GpuBuffer indexBuffer;
    private MTLIndexType indexType = MTLIndexType.UInt16;
    private int pushedDebugGroups = 0;
    private boolean scissorDirty = true;
    private boolean vertexBuffersDirty = true;
    private boolean pipelineDirty = true;
    @Nullable
    private MTLBuffer trackedIndexBuffer;

    MetalRenderPass(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView[] colorTextures,
            @Nullable final GpuTextureView depthTexture,
            final RenderPass.RenderArea renderArea,
            final Vector4fc[] clearColors,
            @Nullable final Double clearDepth
    ) {
        this.device = device;
        this.commandEncoder = encoder;
        this.colorTextures = new MetalGpuTextureView[colorTextures.length];
        GpuTextureView sizeSource = depthTexture;
        for (int i = 0; i < colorTextures.length; i++) {
            this.colorTextures[i] = (MetalGpuTextureView) colorTextures[i];
            if (colorTextures[i] != null) {
                sizeSource = colorTextures[i];
            }
        }
        if (sizeSource == null) {
            throw new IllegalArgumentException("Render pass requires at least one attachment");
        }
        this.depthTexture = (MetalGpuTextureView) depthTexture;
        this.targetWidth = sizeSource.getWidth(0);
        this.targetHeight = sizeSource.getHeight(0);
        this.renderArea = renderArea;
        boolean anyColorClear = false;
        for (Vector4fc clear : clearColors) {
            anyColorClear |= clear != null;
        }
        this.clearColors = anyColorClear ? clearColors : null;
        this.clearDepth = clearDepth;
        this.pushConstantBytes = MemorySegment.ofArray(new byte[MAX_PUSH_CONSTANT_BYTES]);
    }

    @Override
    public void pushDebugGroup(final @NonNull Supplier<String> label) {
        pushedDebugGroups++;
        if (device.useLabels()) {
            commandEncoder.commandBuffer().pushDebugGroup(label.get());
        }
    }

    @Override
    public void popDebugGroup() {
        if (pushedDebugGroups == 0) {
            throw new IllegalStateException("Can't pop more debug groups than was pushed!");
        }
        pushedDebugGroups--;
        if (device.useLabels()) {
            commandEncoder.commandBuffer().popDebugGroup();
        }
    }

    @Override
    public void setPipeline(final @NonNull BackendRenderPipeline pipeline) {
        if (!(pipeline instanceof MetalCompiledRenderPipeline compiled)) {
            throw new IllegalArgumentException("Metal render pass cannot bind a foreign pipeline");
        }
        if (this.compiledPipeline != compiled) {
            this.compiledPipeline = compiled;
            // The frontend re-sends every uniform the new pipeline uses right after this call.
            Arrays.fill(this.descriptorBuffers, null);
            Arrays.fill(this.descriptorBackings, 0L);
            Arrays.fill(this.descriptorTextures, null);
            this.dirtyDescriptorMask = 0L;
            vertexBuffersDirty = true;
            pipelineDirty = true;
            pushConstantsDirty = this.pushConstantLength > 0;
        }
    }

    @Override
    public void setUniform(final int index, @Nullable final Object value) {
        if (index < 0 || index >= Long.SIZE) {
            throw new IllegalArgumentException("Unsupported Metal uniform index: " + index);
        }
        switch (value) {
            case null -> {
                this.descriptorBuffers[index] = null;
                this.descriptorBackings[index] = 0L;
                this.descriptorTextures[index] = null;
            }
            case GpuBufferSlice slice -> {
                MetalGpuBuffer buffer = requireBufferSlice(slice, "Uniform " + index);
                long backing = buffer.nativeHandle().address();
                if (sameSlice(this.descriptorBuffers[index], slice) && this.descriptorBackings[index] == backing) {
                    return;
                }
                this.descriptorBuffers[index] = slice;
                this.descriptorBackings[index] = backing;
                this.descriptorTextures[index] = null;
            }
            case TextureViewAndSampler binding -> {
                MetalGpuTexture texture = requireTextureView(binding.view(), "Sampler " + index);
                requireSampler(binding.sampler(), "Sampler " + index);
                commandEncoder.flushPendingClear(texture);
                if (sameTextureBinding(this.descriptorTextures[index], binding.view(), binding.sampler())) {
                    return;
                }
                this.descriptorTextures[index] = binding;
                this.descriptorBuffers[index] = null;
                this.descriptorBackings[index] = 0L;
            }
            default -> throw new IllegalArgumentException("Unsupported uniform value: " + value.getClass().getSimpleName());
        }
        this.dirtyDescriptorMask |= 1L << index;
    }

    @Override
    public void pushConstants(final @NonNull ByteBuffer value) {
        int length = value.remaining();
        if (length > MAX_PUSH_CONSTANT_BYTES) {
            throw new IllegalArgumentException("Push constants exceed " + MAX_PUSH_CONSTANT_BYTES + " bytes: " + length);
        }
        MemorySegment source = MemorySegment.ofBuffer(value);
        if (length == this.pushConstantLength
                && MemorySegment.mismatch(source, 0L, length, this.pushConstantBytes, 0L, length) == -1L) {
            return;
        }
        MemorySegment.copy(source, 0L, this.pushConstantBytes, 0L, length);
        this.pushConstantLength = length;
        this.pushConstantsDirty = true;
    }

    @Override
    public void enableScissor(final int x, final int y, final int width, final int height) {
        if (scissorState.enabled()
                && scissorState.x() == x
                && scissorState.y() == y
                && scissorState.width() == width
                && scissorState.height() == height) {
            return;
        }
        scissorState.enable(x, y, width, height);
        scissorDirty = true;
    }

    @Override
    public void disableScissor() {
        if (!scissorState.enabled()) {
            return;
        }
        scissorState.disable();
        scissorDirty = true;
    }

    @Override
    public void setVertexBuffer(final int slot, @Nullable final GpuBufferSlice vertexBuffer) {
        if (slot < 0 || slot >= MAX_VERTEX_BUFFERS) {
            throw new IllegalArgumentException("Unsupported Metal vertex buffer slot: " + slot);
        }

        long backing = vertexBuffer == null
                ? 0L
                : requireBufferSlice(vertexBuffer, "Vertex buffer at slot " + slot).nativeHandle().address();
        if (!sameSlice(vertexBuffers[slot], vertexBuffer)
                || this.vertexBufferBackings[slot] != backing) {
            vertexBuffers[slot] = vertexBuffer;
            this.vertexBufferBackings[slot] = backing;
            vertexBuffersDirty = true;
        }
    }

    @Override
    public void setIndexBuffer(final @NonNull GpuBuffer indexBuffer, final @NonNull IndexType indexType) {
        requireBuffer(indexBuffer, "Index buffer");
        this.indexBuffer = indexBuffer;
        this.indexType = MTLIndexType.from(indexType);
    }

    @Override
    public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
        requireNonNegative(indexCount, "Index count");
        requireNonNegative(instanceCount, "Instance count");
        requireNonNegative(firstIndex, "First index");
        requireNonNegative(firstInstance, "First instance");
        if (indexCount == 0 || instanceCount == 0) {
            return;
        }
        MetalGpuBuffer nativeIndexBuffer = requireIndexBuffer();
        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);
        drawIndexedNative(enc, nativeIndexBuffer, firstIndex, indexCount, vertexOffset, instanceCount, indexType, firstInstance);
    }

    void drawBatch(final MetalIndirectDrawBatch batch) {
        if (this.indexBuffer == null) {
            throw new IllegalStateException("Index buffer is missing");
        }
        MetalGpuBuffer nativeIndexBuffer = requireIndexBuffer();
        batch.validateIndexRanges(this.indexType, nativeIndexBuffer.size());
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);
        trackIndexBuffer(nativeIndexBuffer);
        if (!this.compiledPipeline.supportsIndirectCommands()
                || !batch.execute(this.device, enc, primitiveTopology(), this.indexType, nativeIndexBuffer)) {
            batch.drawFallback(this);
        }
    }

    @Override
    public void multiDrawIndexed(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        requireNonNegative(instanceCount, "Instance count");
        requireNonNegative(firstInstance, "First instance");
        requireNonNegative(drawCount, "Draw count");
        if (instanceCount == 0 || drawCount == 0) {
            return;
        }
        requireRemaining(drawParameters.remaining(), drawCount, 3, "Indexed multi-draw parameters");
        MetalGpuBuffer nativeIndexBuffer = requireIndexBuffer();
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        int parameterBase = drawParameters.position();
        for (int i = 0; i < drawCount; i++) {
            int offset = parameterBase + i * 3;
            int firstIndex = drawParameters.get(offset);
            int indexCount = drawParameters.get(offset + 1);
            int baseVertex = drawParameters.get(offset + 2);
            requireNonNegative(firstIndex, "First index");
            requireNonNegative(indexCount, "Index count");
            if (indexCount > 0) {
                drawIndexedNative(enc, nativeIndexBuffer, firstIndex, indexCount, baseVertex, instanceCount, indexType, firstInstance);
            }
        }
    }

    @Override
    public void multiDrawIndexed(@NonNull PointerBuffer firstIndexOffsets, @NonNull IntBuffer indexCounts, @NonNull IntBuffer vertexOffsets, int drawCount) {
        requireNonNegative(drawCount, "Draw count");
        if (drawCount == 0) {
            return;
        }
        requireRemaining(firstIndexOffsets.remaining(), drawCount, 1, "Indexed multi-draw offsets");
        requireRemaining(indexCounts.remaining(), drawCount, 1, "Indexed multi-draw counts");
        requireRemaining(vertexOffsets.remaining(), drawCount, 1, "Indexed multi-draw vertex offsets");
        MTLPrimitiveType primitiveType = primitiveTopology();

        MetalGpuBuffer nativeIndexBuffer = requireIndexBuffer();
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        MTLBuffer indexBufferHandle = nativeIndexBuffer.metalBuffer();
        trackIndexBuffer(nativeIndexBuffer);
        MemorySegment offsets = MemorySegment.ofAddress(MemoryUtil.memAddress(firstIndexOffsets)).reinterpret(drawCount * 8L);
        MemorySegment counts = MemorySegment.ofAddress(MemoryUtil.memAddress(indexCounts)).reinterpret(drawCount * 4L);
        MemorySegment vertices = MemorySegment.ofAddress(MemoryUtil.memAddress(vertexOffsets)).reinterpret(drawCount * 4L);
        for (int i = 0; i < drawCount; i++) {
            int indexCount = counts.get(ValueLayout.JAVA_INT, i * 4L);
            requireNonNegative(indexCount, "Index count");
            if (indexCount <= 0) {
                continue;
            }
            long firstIndexOffset = offsets.get(ValueLayout.JAVA_LONG, i * 8L);
            int baseVertex = vertices.get(ValueLayout.JAVA_INT, i * 4L);
            requireIndexRange(nativeIndexBuffer, firstIndexOffset, indexCount, indexType);
            if (primitiveType == MTLPrimitiveType.TriangleFan) {
                if (firstIndexOffset < 0L || firstIndexOffset % indexType.bytes != 0L) {
                    throw new IllegalArgumentException("Triangle fan index offset is not aligned to the index type: " + firstIndexOffset);
                }
                int firstIndex = Math.toIntExact(firstIndexOffset / indexType.bytes);
                drawIndexedNative(enc, nativeIndexBuffer, firstIndex, indexCount, baseVertex, 1, indexType, 0);
            } else {
                enc.drawIndexedPrimitives(primitiveType, indexCount, indexType, indexBufferHandle, firstIndexOffset, 1, baseVertex, 0);
            }
        }
    }

    @Override
    public void drawIndexedIndirect(final @NonNull GpuBufferSlice commands, final int drawCount) {
        requireNonNegative(drawCount, "Draw count");
        if (drawCount == 0) {
            return;
        }
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan indirect draws");
        }

        MetalGpuBuffer nativeIndexBuffer = requireIndexBuffer();
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        MTLBuffer indexBufferHandle = nativeIndexBuffer.metalBuffer();
        MetalGpuBuffer nativeIndirectBuffer = requireBufferSlice(commands, "Indexed indirect command buffer");
        long commandBytes = Math.multiplyExact(drawCount, (long) VkDrawIndexedIndirectCommand.SIZEOF);
        BufferCopySupport.requireRange(
                commands.offset(), commands.length(), nativeIndirectBuffer.size(), "Indexed indirect command slice");
        BufferCopySupport.requireRange(0L, commandBytes, commands.length(), "Indexed indirect commands");
        commandEncoder.markGpuUse(nativeIndirectBuffer);
        MTLBuffer indirectBuffer = nativeIndirectBuffer.metalBuffer();
        long indirectOffset = commands.offset();
        trackIndexBuffer(nativeIndexBuffer);
        commandEncoder.bufferAccess(
                indirectBuffer,
                indirectOffset,
                commandBytes,
                ResourceAccessTracker.Access.READ,
                MetalCommandEncoder.STAGE_VERTEX
        );
        for (int i = 0; i < drawCount; i++) {
            enc.drawIndexedPrimitivesIndirect(primitiveType, indexType, indexBufferHandle, indirectBuffer, indirectOffset);
            indirectOffset += VkDrawIndexedIndirectCommand.SIZEOF;
        }
    }

    @Override
    public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
        requireNonNegative(vertexCount, "Vertex count");
        requireNonNegative(instanceCount, "Instance count");
        requireNonNegative(firstVertex, "First vertex");
        requireNonNegative(firstInstance, "First instance");
        if (vertexCount == 0 || instanceCount == 0) {
            return;
        }
        MTLPrimitiveType primitiveType = primitiveTopology();
        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);

        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            drawTriangleFan(enc, firstVertex, vertexCount, instanceCount, firstInstance);
        } else {
            enc.drawPrimitives(primitiveType, firstVertex, vertexCount, instanceCount, firstInstance);
        }
    }

    @Override
    public void multiDraw(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        requireNonNegative(instanceCount, "Instance count");
        requireNonNegative(firstInstance, "First instance");
        requireNonNegative(drawCount, "Draw count");
        if (instanceCount == 0 || drawCount == 0) {
            return;
        }
        requireRemaining(drawParameters.remaining(), drawCount, 2, "Multi-draw parameters");

        MTLPrimitiveType primitiveType = primitiveTopology();
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        int parameterBase = drawParameters.position();
        for (int i = 0; i < drawCount; i++) {
            int offset = parameterBase + i * 2;
            int firstVertex = drawParameters.get(offset);
            int vertexCount = drawParameters.get(offset + 1);
            requireNonNegative(firstVertex, "First vertex");
            requireNonNegative(vertexCount, "Vertex count");
            if (vertexCount <= 0) {
                continue;
            }
            if (primitiveType == MTLPrimitiveType.TriangleFan) {
                drawTriangleFan(enc, firstVertex, vertexCount, instanceCount, firstInstance);
            } else {
                enc.drawPrimitives(primitiveType, firstVertex, vertexCount, instanceCount, firstInstance);
            }
        }
    }

    @Override
    public void multiDraw(@NonNull IntBuffer firstVertices, @NonNull IntBuffer vertexCounts, int drawCount) {
        requireNonNegative(drawCount, "Draw count");
        if (drawCount == 0) {
            return;
        }
        requireRemaining(firstVertices.remaining(), drawCount, 1, "Multi-draw first vertices");
        requireRemaining(vertexCounts.remaining(), drawCount, 1, "Multi-draw vertex counts");

        MTLPrimitiveType primitiveType = primitiveTopology();
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        int firstVertexBase = firstVertices.position();
        int vertexCountBase = vertexCounts.position();
        for (int i = 0; i < drawCount; i++) {
            int vertexCount = vertexCounts.get(vertexCountBase + i);
            requireNonNegative(vertexCount, "Vertex count");
            if (vertexCount <= 0) {
                continue;
            }
            int firstVertex = firstVertices.get(firstVertexBase + i);
            requireNonNegative(firstVertex, "First vertex");
            if (primitiveType == MTLPrimitiveType.TriangleFan) {
                drawTriangleFan(enc, firstVertex, vertexCount, 1, 0);
            } else {
                enc.drawPrimitives(primitiveType, firstVertex, vertexCount, 1, 0);
            }
        }
    }

    @Override
    public void drawIndirect(final @NonNull GpuBufferSlice commands, final int drawCount) {
        requireNonNegative(drawCount, "Draw count");
        if (drawCount == 0) {
            return;
        }
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan indirect draws");
        }

        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        MetalGpuBuffer nativeIndirectBuffer = requireBufferSlice(commands, "Indirect command buffer");
        long commandBytes = Math.multiplyExact(drawCount, (long) VkDrawIndirectCommand.SIZEOF);
        BufferCopySupport.requireRange(
                commands.offset(), commands.length(), nativeIndirectBuffer.size(), "Indirect command slice");
        BufferCopySupport.requireRange(0L, commandBytes, commands.length(), "Indirect commands");
        commandEncoder.markGpuUse(nativeIndirectBuffer);
        MTLBuffer indirectBuffer = nativeIndirectBuffer.metalBuffer();
        long indirectOffset = commands.offset();
        commandEncoder.bufferAccess(
                indirectBuffer,
                indirectOffset,
                commandBytes,
                ResourceAccessTracker.Access.READ,
                MetalCommandEncoder.STAGE_VERTEX
        );
        for (int i = 0; i < drawCount; i++) {
            enc.drawPrimitivesIndirect(primitiveType, indirectBuffer, indirectOffset);
            indirectOffset += VkDrawIndirectCommand.SIZEOF;
        }
    }

    @Override
    public void writeTimestamp(final @NonNull GpuQueryPool pool, final int index) {
        if (!(pool instanceof MetalGpuQueryPool metalPool)) {
            throw new IllegalArgumentException("Metal render pass cannot use a foreign timestamp query pool");
        }
        if (!metalPool.belongsTo(this.device)) {
            throw new IllegalArgumentException("Timestamp query pool belongs to another Metal device");
        }
        metalPool.write(renderEncoder(), index);
    }

    void materializePendingClear() {
        if (clearColors != null || clearDepth != null) {
            renderEncoder();
        }
    }

    private MTLRenderCommandEncoder renderEncoder() {
        materializeDynamicUniforms();
        boolean clearing = clearColors != null || clearDepth != null;
        MTLRenderCommandEncoder encoder = commandEncoder.renderCommandEncoder(
                colorTextures,
                depthTexture,
                targetWidth,
                targetHeight,
                clearColors,
                clearDepth,
                renderArea
        );
        clearColors = null;
        clearDepth = null;
        if (clearing) {
            // Clear draws replace the pipeline, depth state and scissor of the open encoder.
            invalidateEncoderState();
        }
        return encoder;
    }

    private void materializeDynamicUniforms() {
        for (int slot = 0; slot < this.descriptorBuffers.length; slot++) {
            GpuBufferSlice slice = this.descriptorBuffers[slot];
            if (slice == null || !(slice.buffer() instanceof MetalGpuBuffer buffer) || !buffer.isDynamic()) {
                continue;
            }
            if (this.commandEncoder.materializeDynamicWrites(buffer)) {
                this.dirtyDescriptorMask |= 1L << slot;
                this.descriptorBackings[slot] = buffer.nativeHandle().address();
            }
        }
    }

    void invalidateEncoderState() {
        pipelineDirty = true;
        scissorDirty = true;
        vertexBuffersDirty = true;
        pushConstantsDirty = this.pushConstantLength > 0;
        trackedIndexBuffer = null;
    }

    GpuBufferSlice.MappedView allocateTransient(final long size, final long alignment, @GpuBuffer.Usage final int usage) {
        return commandEncoder.transientMemory().allocateGpuMapped(size, alignment, usage);
    }

    private void pushVertexBuffers(final MTLRenderCommandEncoder enc) {
        int firstSlot = compiledPipeline.firstVertexBufferSlot();
        int count = Math.min(compiledPipeline.vertexBufferCount(), MAX_VERTEX_BUFFERS);
        for (int slot = 0; slot < count; slot++) {
            GpuBufferSlice vertexBuffer = vertexBuffers[slot];
            if (vertexBuffer == null) {
                continue;
            }
            if (vertexBuffer.buffer().isClosed()) {
                throw new IllegalStateException("Vertex buffer at slot " + slot + " has been closed");
            }

            MetalGpuBuffer nativeVertexBuffer = (MetalGpuBuffer) vertexBuffer.buffer();
            commandEncoder.markGpuUse(nativeVertexBuffer);
            commandEncoder.bufferAccess(
                    nativeVertexBuffer.metalBuffer(),
                    vertexBuffer.offset(),
                    vertexBuffer.length(),
                    ResourceAccessTracker.Access.READ,
                    MetalCommandEncoder.STAGE_VERTEX
            );
            enc.setBuffer(nativeVertexBuffer.metalBuffer(), vertexBuffer.offset(), firstSlot + slot);
        }
    }

    private void drawTriangleFan(MTLRenderCommandEncoder encoder, final int firstVertex, final int vertexCount, final int instanceCount, final int baseInstance) {
        if (vertexCount < 3 || instanceCount <= 0) {
            return;
        }
        int triangleCount = vertexCount - 2;
        int indexCount = Math.multiplyExact(triangleCount, 3);
        MTLIndexType fanIndexType = vertexCount - 1 <= 0xFFFF ? MTLIndexType.UInt16 : MTLIndexType.UInt32;

        try (GpuBufferSlice.MappedView mapped = commandEncoder.transientMemory().allocateGpuMapped((long) indexCount * fanIndexType.bytes, fanIndexType.bytes, GpuBuffer.USAGE_INDEX)) {
            if (fanIndexType == MTLIndexType.UInt16) {
                ShortBuffer indices = mapped.data().asShortBuffer();
                for (int i = 0; i < triangleCount; i++) {
                    indices.put((short) 0);
                    indices.put((short) (i + 1));
                    indices.put((short) (i + 2));
                }
            } else {
                IntBuffer indices = mapped.data().asIntBuffer();
                for (int i = 0; i < triangleCount; i++) {
                    indices.put(0);
                    indices.put(i + 1);
                    indices.put(i + 2);
                }
            }
            GpuBufferSlice slice = mapped.slice();
            MetalGpuBuffer generated = (MetalGpuBuffer) slice.buffer();
            commandEncoder.bufferAccess(
                    generated.metalBuffer(), slice.offset(), slice.length(),
                    ResourceAccessTracker.Access.READ, MetalCommandEncoder.STAGE_VERTEX);
            encoder.drawIndexedPrimitives(
                    MTLPrimitiveType.Triangle, indexCount, fanIndexType,
                    generated.metalBuffer(), slice.offset(), instanceCount, firstVertex, baseInstance);
        }
    }

    private void drawIndexedNative(
            final MTLRenderCommandEncoder enc,
            final MetalGpuBuffer nativeIndexBuffer,
            final int firstIndex,
            final int indexCount,
            final int baseVertex,
            final int instanceCount,
            final MTLIndexType indexType,
            final int baseInstance
    ) {
        requireNonNegative(firstIndex, "First index");
        requireNonNegative(indexCount, "Index count");
        requireNonNegative(instanceCount, "Instance count");
        requireNonNegative(baseInstance, "Base instance");
        if (indexCount == 0 || instanceCount == 0) {
            return;
        }
        MTLPrimitiveType primitiveType = primitiveTopology();

        long indexOffsetBytes = Math.multiplyExact((long) firstIndex, indexType.bytes);
        requireIndexRange(nativeIndexBuffer, indexOffsetBytes, indexCount, indexType);
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            if (indexCount < 3) {
                return;
            }
            int generatedIndexCount = Math.multiplyExact(indexCount - 2, 3);
            try (GpuBufferSlice.MappedView mapped = commandEncoder.transientMemory().allocateGpuMapped((long) generatedIndexCount * Integer.BYTES, Integer.BYTES, GpuBuffer.USAGE_INDEX)) {
                expandTriangleFan(mapped.data().asIntBuffer(), nativeIndexBuffer, indexOffsetBytes, indexCount, indexType);
                GpuBufferSlice slice = mapped.slice();
                MetalGpuBuffer generated = (MetalGpuBuffer) slice.buffer();
                commandEncoder.bufferAccess(
                        generated.metalBuffer(), slice.offset(), slice.length(),
                        ResourceAccessTracker.Access.READ, MetalCommandEncoder.STAGE_VERTEX);
                enc.drawIndexedPrimitives(
                        MTLPrimitiveType.Triangle, generatedIndexCount, MTLIndexType.UInt32,
                        generated.metalBuffer(), slice.offset(), instanceCount, baseVertex, baseInstance);
            }
        } else {
            trackIndexBuffer(nativeIndexBuffer);
            enc.drawIndexedPrimitives(primitiveType, indexCount, indexType, nativeIndexBuffer.metalBuffer(), indexOffsetBytes, instanceCount, baseVertex, baseInstance);
        }
    }

    private static void expandTriangleFan(final IntBuffer out, final MetalGpuBuffer indexBuffer, final long indexOffsetBytes, final int indexCount, final MTLIndexType indexType) {
        ByteBuffer indices = indexBuffer.indexData(indexOffsetBytes, (long) indexCount * indexType.bytes);
        int center = readIndex(indices, 0, indexType);
        for (int i = 1; i < indexCount - 1; i++) {
            out.put(center).put(readIndex(indices, i, indexType)).put(readIndex(indices, i + 1, indexType));
        }
    }

    private static int readIndex(final ByteBuffer indices, final int index, final MTLIndexType indexType) {
        if (indexType == MTLIndexType.UInt16) {
            return Short.toUnsignedInt(indices.getShort(index * Short.BYTES));
        }
        return indices.getInt(index * Integer.BYTES);
    }

    private void bindDrawState(final MTLRenderCommandEncoder enc) {
        if (compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }

        if (pipelineDirty) {
            MemorySegment pipelineHandle = compiledPipeline.nativePipeline();
            if (ObjC.isNil(pipelineHandle)) {
                throw new IllegalStateException("Native pipeline is unavailable");
            }
            enc.setRenderPipelineState(pipelineHandle);
            pipelineDirty = false;

            if (depthTexture != null) {
                MemorySegment depthState = compiledPipeline.getDepthStencilState();
                if (ObjC.isNil(depthState)) {
                    throw new IllegalStateException("Native depth state is unavailable");
                }
                enc.setDepthStencilState(depthState);
                enc.setDepthBias(
                        compiledPipeline.depthBiasConstant(),
                        compiledPipeline.depthBiasScaleFactor(),
                        0.0f
                );
            }

            enc.setFrontFacingWinding(MTLWinding.Clockwise);
            enc.setCullMode(compiledPipeline.cullMode());
            enc.setTriangleFillMode(compiledPipeline.fillMode());

            dirtyDescriptorMask |= compiledPipeline.allResourceMask();
            pushConstantsDirty = this.pushConstantLength > 0;
        }

        if (scissorDirty) {
            pushEffectiveScissor(enc);
            scissorDirty = false;
        }

        if (vertexBuffersDirty) {
            pushVertexBuffers(enc);
            vertexBuffersDirty = false;
        }

        if (pushConstantsDirty) {
            MetalCompiledRenderPipeline.PushConstants pushConstants = compiledPipeline.pushConstants();
            if (pushConstants != null && this.pushConstantLength > 0) {
                enc.setBytes(this.pushConstantBytes, this.pushConstantLength, pushConstants.bufferIndex());
            }
            pushConstantsDirty = false;
        }

        long dirty = dirtyDescriptorMask & compiledPipeline.allResourceMask();
        if (dirty != 0L) {
            List<MetalCompiledRenderPipeline.ResourceBinding> resources = compiledPipeline.resources();
            for (int slot = 0; slot < resources.size(); slot++) {
                if ((dirty & (1L << slot)) != 0L) {
                    pushDescriptor(enc, resources.get(slot), slot);
                }
            }
        }

        dirtyDescriptorMask = 0L;
    }

    private MTLPrimitiveType primitiveTopology() {
        if (compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }
        return compiledPipeline.topology();
    }

    private void pushEffectiveScissor(final MTLRenderCommandEncoder enc) {
        long areaLeft = Math.max(renderArea.x(), 0);
        long areaTop = Math.max(renderArea.y(), 0);
        long areaRight = Math.min((long) renderArea.x() + renderArea.width(), targetWidth);
        long areaBottom = Math.min((long) renderArea.y() + renderArea.height(), targetHeight);
        long left = areaLeft;
        long top = areaTop;
        long right = areaRight;
        long bottom = areaBottom;
        if (scissorState.enabled()) {
            left = Math.max(areaLeft, (long) scissorState.x());
            top = Math.max(areaTop, (long) scissorState.y());
            right = Math.min(areaRight, (long) scissorState.x() + scissorState.width());
            bottom = Math.min(areaBottom, (long) scissorState.y() + scissorState.height());
        }
        if (right <= left || bottom <= top) {
            enc.setScissorRect(0, 0, 0, 0);
        } else {
            enc.setScissorRect(left, top, right - left, bottom - top);
        }
    }

    private void pushDescriptor(
            final MTLRenderCommandEncoder enc,
            final MetalCompiledRenderPipeline.ResourceBinding binding,
            final int slot
    ) {
        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
            TextureViewAndSampler textureBinding = this.descriptorTextures[slot];
            if (textureBinding == null) {
                throw new IllegalStateException("Missing sampler " + binding.name());
            }
            if (textureBinding.sampler() instanceof MetalGpuSampler sampler && sampler.isClosed()) {
                throw new IllegalStateException("Sampler " + binding.name() + " has been closed");
            }

            MetalGpuTextureView textureView = (MetalGpuTextureView) textureBinding.view();
            MetalGpuSampler sampler = (MetalGpuSampler) textureBinding.sampler();
            commandEncoder.textureAccess(
                    (MetalGpuTexture) textureView.texture(),
                    textureView.baseMipLevel(),
                    textureView.mipLevels(),
                    0,
                    textureView.texture().getDepthOrLayers(),
                    ResourceAccessTracker.Access.READ,
                    binding.stageMask()
            );
            enc.setTexture(textureView.nativeHandle(), binding.bindingIndex());
            enc.setSamplerState(sampler.nativeHandle(), binding.samplerIndex());
            return;
        }

        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER) {
            pushTexelBufferDescriptor(enc, binding, slot);
            return;
        }

        GpuBufferSlice uniformSlice = this.descriptorBuffers[slot];
        if (uniformSlice == null) {
            throw new IllegalStateException("Missing uniform " + binding.name());
        }
        if (uniformSlice.buffer().isClosed()) {
            throw new IllegalStateException("Uniform " + binding.name() + " buffer has been closed");
        }

        MetalGpuBuffer uniformBuffer = (MetalGpuBuffer) uniformSlice.buffer();
        commandEncoder.markGpuUse(uniformBuffer);
        commandEncoder.bufferAccess(
                uniformBuffer.metalBuffer(),
                uniformSlice.offset(),
                uniformSlice.length(),
                ResourceAccessTracker.Access.READ,
                binding.stageMask()
        );
        enc.setBuffer(uniformBuffer.metalBuffer(), uniformSlice.offset(), binding.bindingIndex());
    }

    private void pushTexelBufferDescriptor(
            final MTLRenderCommandEncoder enc,
            final MetalCompiledRenderPipeline.ResourceBinding binding,
            final int slot
    ) {
        GpuBufferSlice texelSlice = this.descriptorBuffers[slot];
        if (texelSlice == null) {
            throw new IllegalStateException("Missing texel buffer " + binding.name());
        }
        if (texelSlice.buffer().isClosed()) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " has been closed");
        }

        GpuFormat texelFormat = binding.texelBufferFormat();
        if (texelFormat == null) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " is missing a format");
        }

        MetalGpuBuffer texelBuffer = (MetalGpuBuffer) texelSlice.buffer();
        commandEncoder.markGpuUse(texelBuffer);
        commandEncoder.bufferAccess(
                texelBuffer.metalBuffer(),
                texelSlice.offset(),
                texelSlice.length(),
                ResourceAccessTracker.Access.READ,
                binding.stageMask()
        );
        MTLPixelFormat pixelFormat = MTLPixelFormat.from(texelFormat);
        int pixelSize = texelFormat.blockSize();
        long texelByteLength = texelSlice.length();
        if (texelByteLength <= 0L || texelByteLength % pixelSize != 0L) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " length " + texelByteLength + " is not a valid " + texelFormat + " range");
        }
        MemorySegment texelTexture = texelBuffer.texelTextureView(
                pixelFormat,
                texelSlice.offset(),
                texelByteLength,
                pixelSize
        );
        if (ObjC.isNil(texelTexture)) {
            throw new IllegalStateException("Failed to create Metal texel buffer texture for " + binding.name());
        }

        enc.setTexture(texelTexture, binding.bindingIndex());
    }

    private MetalGpuBuffer requireIndexBuffer() {
        if (!(this.indexBuffer instanceof MetalGpuBuffer buffer)) {
            throw new IllegalStateException("Metal index buffer is missing or belongs to another backend");
        }
        if (!buffer.belongsTo(this.device)) {
            throw new IllegalStateException("Index buffer belongs to another Metal device");
        }
        if (buffer.isClosed()) {
            throw new IllegalStateException("Index buffer has been closed");
        }
        return buffer;
    }

    private MetalGpuBuffer requireBuffer(final GpuBuffer buffer, final String operation) {
        if (!(buffer instanceof MetalGpuBuffer metalBuffer)) {
            throw new IllegalArgumentException(operation + " does not belong to the Metal backend");
        }
        if (!metalBuffer.belongsTo(this.device)) {
            throw new IllegalArgumentException(operation + " belongs to another Metal device");
        }
        if (metalBuffer.isClosed()) {
            throw new IllegalStateException(operation + " is closed");
        }
        return metalBuffer;
    }

    private MetalGpuBuffer requireBufferSlice(final GpuBufferSlice slice, final String operation) {
        MetalGpuBuffer buffer = requireBuffer(slice.buffer(), operation);
        BufferCopySupport.requireRange(slice.offset(), slice.length(), buffer.size(), operation);
        return buffer;
    }

    private MetalGpuTexture requireTextureView(final GpuTextureView view, final String operation) {
        if (!(view instanceof MetalGpuTextureView)) {
            throw new IllegalArgumentException(operation + " texture view does not belong to the Metal backend");
        }
        if (!(view.texture() instanceof MetalGpuTexture texture) || !texture.belongsTo(this.device)) {
            throw new IllegalArgumentException(operation + " texture belongs to another Metal device");
        }
        return texture;
    }

    private MetalGpuSampler requireSampler(final GpuSampler sampler, final String operation) {
        if (!(sampler instanceof MetalGpuSampler metalSampler)) {
            throw new IllegalArgumentException(operation + " does not belong to the Metal backend");
        }
        if (!metalSampler.belongsTo(this.device)) {
            throw new IllegalArgumentException(operation + " belongs to another Metal device");
        }
        if (metalSampler.isClosed()) {
            throw new IllegalStateException(operation + " is closed");
        }
        return metalSampler;
    }

    private static void requireIndexRange(
            final MetalGpuBuffer buffer,
            final long offset,
            final int indexCount,
            final MTLIndexType indexType
    ) {
        if (offset % indexType.bytes != 0L) {
            throw new IllegalArgumentException(
                    "Indexed draw offset is not aligned to " + indexType.bytes + " bytes: " + offset);
        }
        long byteLength = Math.multiplyExact((long) indexCount, indexType.bytes);
        BufferCopySupport.requireRange(offset, byteLength, buffer.size(), "Indexed draw");
    }

    private static void requireRemaining(
            final int remaining,
            final int elementCount,
            final int elementsPerDraw,
            final String operation
    ) {
        int required = Math.multiplyExact(elementCount, elementsPerDraw);
        if (remaining < required) {
            throw new IllegalArgumentException(
                    operation + " requires " + required + " elements, but only " + remaining + " remain");
        }
    }

    private static void requireNonNegative(final int value, final String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value);
        }
    }

    private void trackIndexBuffer(final MetalGpuBuffer buffer) {
        commandEncoder.markGpuUse(buffer);
        MTLBuffer backing = buffer.metalBuffer();
        if (this.trackedIndexBuffer == backing) {
            return;
        }
        commandEncoder.bufferAccess(
                backing,
                0L,
                backing.length(),
                ResourceAccessTracker.Access.READ,
                MetalCommandEncoder.STAGE_VERTEX
        );
        this.trackedIndexBuffer = backing;
    }


    private static boolean sameSlice(@Nullable final GpuBufferSlice left, @Nullable final GpuBufferSlice right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.buffer() == right.buffer()
                && left.offset() == right.offset()
                && left.length() == right.length();
    }

    private static boolean sameTextureBinding(
            @Nullable final TextureViewAndSampler previous,
            final GpuTextureView textureView,
            final GpuSampler sampler
    ) {
        if (previous == null) {
            return false;
        }
        if (previous.view() == textureView && previous.sampler() == sampler) {
            return true;
        }
        if (!(previous.view() instanceof MetalGpuTextureView oldView)
                || !(textureView instanceof MetalGpuTextureView newView)
                || !(previous.sampler() instanceof MetalGpuSampler oldSampler)
                || !(sampler instanceof MetalGpuSampler newSampler)) {
            return false;
        }
        if (oldSampler.isClosed() || newSampler.isClosed()) {
            return false;
        }
        return oldView.nativeHandle().address() == newView.nativeHandle().address()
                && oldSampler.nativeHandle().address() == newSampler.nativeHandle().address();
    }
}
