package com.metallum.render;

import com.metallum.mtl.*;
import com.metallum.objc.ObjC;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;
import org.lwjgl.vulkan.VkDrawIndirectCommand;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
final class MetalRenderPass implements RenderPassBackend {
    static final int MAX_VERTEX_BUFFERS = RenderPass.MAX_VERTEX_BUFFERS;
    private final MetalDevice device;
    private final MetalCommandEncoder commandEncoder;
    @Nullable
    private final String label;
    private final GpuTextureView colorTexture;
    @Nullable
    private final GpuTextureView depthTexture;
    private final RenderPass.RenderArea renderArea;
    @Nullable
    private Vector4fc clearColor;
    @Nullable
    private Double clearDepth;
    private final ScissorState scissorState = new ScissorState();
    private final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[MAX_VERTEX_BUFFERS];
    private final long[] vertexBufferBackings = new long[MAX_VERTEX_BUFFERS];
    private final HashMap<String, GpuBufferSlice> uniforms = new HashMap<>();
    private final HashMap<String, Long> uniformBackings = new HashMap<>();
    private final HashMap<String, TextureViewAndSampler> samplers = new HashMap<>();
    private final GpuBufferSlice[] descriptorBuffers = new GpuBufferSlice[Long.SIZE];
    private final TextureViewAndSampler[] descriptorTextures = new TextureViewAndSampler[Long.SIZE];
    private long dirtyDescriptorMask;
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
    private boolean sodiumPushConstantsSet;
    private float sodiumPushX;
    private float sodiumPushY;
    private float sodiumPushZ;
    private int sodiumPushAge;
    private int sodiumPushRegionId;

    MetalRenderPass(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView colorTexture,
            @Nullable final GpuTextureView depthTexture,
            final RenderPass.RenderArea renderArea,
            @Nullable final Vector4fc clearColor,
            @Nullable final Double clearDepth
    ) {
        this.device = device;
        this.commandEncoder = encoder;
        this.label = device.useLabels() ? label.get() : null;
        this.colorTexture = colorTexture;
        this.depthTexture = depthTexture;
        this.renderArea = renderArea;
        this.clearColor = clearColor;
        this.clearDepth = clearDepth;
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
    public void setPipeline(final @NonNull RenderPipeline pipeline) {
        MetalCompiledRenderPipeline compiled = device.getOrCompilePipeline(pipeline);
        if (this.compiledPipeline != compiled) {
            this.compiledPipeline = compiled;
            rebuildDescriptorSlots();
            vertexBuffersDirty = true;
            pipelineDirty = true;
        }
    }

    @Override
    public void bindTexture(final @NonNull String name, @Nullable final GpuTextureView textureView, @Nullable final GpuSampler sampler) {
        if (textureView != null && sampler != null) {
            MetalGpuTexture texture = requireTextureView(textureView, "Sampler " + name);
            requireSampler(sampler, "Sampler " + name);
            commandEncoder.flushPendingClear(texture);
            TextureViewAndSampler previous = samplers.get(name);
            if (sameTextureBinding(previous, textureView, sampler)) {
                return;
            }
            TextureViewAndSampler binding = new TextureViewAndSampler(textureView, sampler);
            samplers.put(name, binding);
            int slot = descriptorSlot(name);
            if (slot >= 0) {
                this.descriptorTextures[slot] = binding;
            }
            markDescriptorDirty(name);
        } else if (textureView == null && sampler == null) {
            if (samplers.remove(name) != null) {
                int slot = descriptorSlot(name);
                if (slot >= 0) {
                    this.descriptorTextures[slot] = null;
                }
                markDescriptorDirty(name);
            }
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public void setUniform(final @NonNull String name, final GpuBuffer value) {
        setUniform(name, value.slice());
    }

    @Override
    public void setUniform(final @NonNull String name, final @NonNull GpuBufferSlice value) {
        MetalGpuBuffer buffer = requireBufferSlice(value, "Uniform " + name);
        GpuBufferSlice previous = uniforms.get(name);
        long backing = buffer.nativeHandle().address();
        if (sameSlice(previous, value)
                && this.uniformBackings.getOrDefault(name, 0L) == backing) {
            return;
        }
        uniforms.put(name, value);
        this.uniformBackings.put(name, backing);
        int slot = descriptorSlot(name);
        if (slot >= 0) {
            this.descriptorBuffers[slot] = value;
        }
        markDescriptorDirty(name);
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
    public void setIndexBuffer(@Nullable final GpuBuffer indexBuffer, final @NonNull IndexType indexType) {
        setIndexBuffer(indexBuffer, MTLIndexType.from(indexType));
    }

    private void setIndexBuffer(@Nullable final GpuBuffer indexBuffer, final MTLIndexType indexType) {
        if (indexBuffer != null) {
            requireBuffer(indexBuffer, "Index buffer");
        }
        if (this.indexBuffer != indexBuffer || this.indexType != indexType) {
            this.indexBuffer = indexBuffer;
            this.indexType = indexType;
        }
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
        MemorySegment offsets = MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(firstIndexOffsets)).reinterpret(drawCount * 8L);
        MemorySegment counts = MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(indexCounts)).reinterpret(drawCount * 4L);
        MemorySegment vertices = MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(vertexOffsets)).reinterpret(drawCount * 4L);
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
    public <T> void drawMultipleIndexed(
            final Collection<RenderPass.Draw<T>> draws,
            @Nullable final GpuBuffer defaultIndexBuffer,
            @Nullable final IndexType defaultIndexType,
            final @NonNull Collection<String> dynamicUniforms,
            final @NonNull T uniformArgument
    ) {
        IndexType fallbackIndexType = defaultIndexType == null ? IndexType.SHORT : defaultIndexType;

        for (RenderPass.Draw<T> draw : draws) {
            MTLIndexType drawIndexType = MTLIndexType.from(draw.indexType() == null ? fallbackIndexType : draw.indexType());
            GpuBuffer currentIndexBuffer = draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer();

            setIndexBuffer(currentIndexBuffer, drawIndexType);
            setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());

            if (draw.uniformUploaderConsumer() != null) {
                draw.uniformUploaderConsumer().accept(uniformArgument, this::setUniform);
            }

            MTLRenderCommandEncoder enc = renderEncoder();
            if (scissorDirty || vertexBuffersDirty || dirtyDescriptorMask != 0L || pipelineDirty) {
                bindDrawState(enc);
            }
            MetalGpuBuffer nativeIndexBuffer = requireIndexBuffer();
            drawIndexedNative(enc, nativeIndexBuffer, draw.firstIndex(), draw.indexCount(), draw.baseVertex(), 1, drawIndexType, 0);
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

    MTLPixelFormat colorAttachmentFormat() {
        return ((MetalGpuTexture) colorTexture.texture()).mtlPixelFormat();
    }

    MTLPixelFormat depthAttachmentFormat() {
        if (depthTexture == null) {
            return MTLPixelFormat.Invalid;
        }
        return ((MetalGpuTexture) depthTexture.texture()).mtlPixelFormat();
    }

    MTLPixelFormat stencilAttachmentFormat() {
        if (depthTexture == null) {
            return MTLPixelFormat.Invalid;
        }
        return ((MetalGpuTexture) depthTexture.texture()).mtlStencilPixelFormat();
    }

    void materializePendingClear() {
        if (clearColor != null || clearDepth != null) {
            renderEncoder();
        }
    }

    private MTLRenderCommandEncoder renderEncoder() {
        materializeDynamicUniforms();
        MetalGpuTextureView colorTextureView = (MetalGpuTextureView) colorTexture;
        MetalGpuTextureView depthTextureView = depthTexture == null ? null : (MetalGpuTextureView) depthTexture;
        MTLRenderCommandEncoder encoder = commandEncoder.renderCommandEncoder(
                colorTextureView,
                depthTextureView,
                colorTexture.getWidth(0),
                colorTexture.getHeight(0),
                clearColor,
                clearDepth
        );
        clearColor = null;
        clearDepth = null;
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
                this.uniformBackings.put(
                        this.compiledPipeline.resources().get(slot).name(),
                        buffer.nativeHandle().address());
            }
        }
    }

    void invalidateEncoderState() {
        pipelineDirty = true;
        scissorDirty = true;
        vertexBuffersDirty = true;
        trackedIndexBuffer = null;
    }

    GpuBufferSlice.MappedView allocateTransient(final long size, final long alignment, @GpuBuffer.Usage final int usage) {
        return commandEncoder.transientMemory().allocateGpuMapped(size, alignment, usage);
    }

    void setSodiumPushConstants(
            final float x,
            final float y,
            final float z,
            final int age,
            final int regionId
    ) {
        if (this.sodiumPushConstantsSet
                && Float.floatToRawIntBits(this.sodiumPushX) == Float.floatToRawIntBits(x)
                && Float.floatToRawIntBits(this.sodiumPushY) == Float.floatToRawIntBits(y)
                && Float.floatToRawIntBits(this.sodiumPushZ) == Float.floatToRawIntBits(z)
                && this.sodiumPushAge == age
                && this.sodiumPushRegionId == regionId) {
            return;
        }
        this.sodiumPushX = x;
        this.sodiumPushY = y;
        this.sodiumPushZ = z;
        this.sodiumPushAge = age;
        this.sodiumPushRegionId = regionId;
        this.sodiumPushConstantsSet = true;
        markDescriptorDirty("push_constants");
    }

    private void pushVertexBuffers(final MTLRenderCommandEncoder enc) {
        int firstSlot = compiledPipeline.firstAvailableVertexBufferSlot();
        int count = compiledPipeline.vertexBufferCount();
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
            int metalSlot = firstSlot + slot;
            commandEncoder.bufferAccess(
                    nativeVertexBuffer.metalBuffer(),
                    vertexBuffer.offset(),
                    vertexBuffer.length(),
                    ResourceAccessTracker.Access.READ,
                    MetalCommandEncoder.STAGE_VERTEX
            );
            enc.setVertexBuffer(nativeVertexBuffer.metalBuffer(), vertexBuffer.offset(), metalSlot);
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

    private static void bindBuffer(final MTLRenderCommandEncoder enc, final MTLBuffer buffer, final long offset, final long index, final int stageMask) {
        if ((stageMask & MetalCompiledRenderPipeline.STAGE_VERTEX) != 0) {
            enc.setVertexBuffer(buffer, offset, index);
        }
        if ((stageMask & MetalCompiledRenderPipeline.STAGE_FRAGMENT) != 0) {
            enc.setFragmentBuffer(buffer, offset, index);
        }
    }

    private static void bindTexture(final MTLRenderCommandEncoder enc, final MemorySegment texture, final long index, final int stageMask) {
        if ((stageMask & MetalCompiledRenderPipeline.STAGE_VERTEX) != 0) {
            enc.setVertexTexture(texture, index);
        }
        if ((stageMask & MetalCompiledRenderPipeline.STAGE_FRAGMENT) != 0) {
            enc.setFragmentTexture(texture, index);
        }
    }

    private static void bindTextureAndSampler(final MTLRenderCommandEncoder enc, final MemorySegment texture, final MemorySegment sampler, final long index, final int stageMask) {
        if ((stageMask & MetalCompiledRenderPipeline.STAGE_VERTEX) != 0) {
            enc.setVertexTexture(texture, index);
            enc.setVertexSamplerState(sampler, index);
        }
        if ((stageMask & MetalCompiledRenderPipeline.STAGE_FRAGMENT) != 0) {
            enc.setFragmentTexture(texture, index);
            enc.setFragmentSamplerState(sampler, index);
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
            boolean useDepth = depthAttachmentFormat().value != MTLPixelFormat.Invalid.value;
            MemorySegment pipelineHandle = compiledPipeline.getNativePipeline(useDepth);
            if (ObjC.isNil(pipelineHandle)) {
                throw new IllegalStateException("Native pipeline is unavailable");
            }
            enc.setRenderPipelineState(pipelineHandle);
            pipelineDirty = false;

            if (useDepth) {
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
        }

        if (scissorDirty) {
            pushEffectiveScissor(enc);
            scissorDirty = false;
        }

        if (vertexBuffersDirty) {
            pushVertexBuffers(enc);
            vertexBuffersDirty = false;
        }

        if (dirtyDescriptorMask != 0) {
            List<MetalCompiledRenderPipeline.ResourceBinding> resources = compiledPipeline.resources();
            for (int slot = 0; slot < resources.size(); slot++) {
                if ((dirtyDescriptorMask & (1L << slot)) != 0L) {
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
        long areaLeft = renderArea.x();
        long areaTop = renderArea.y();
        if (!scissorState.enabled()) {
            if (renderArea.fillsTexture(colorTexture)) {
                enc.setScissorRect(0L, 0L, colorTexture.getWidth(0), colorTexture.getHeight(0));
                return;
            }
            enc.setScissorRect(areaLeft, areaTop, renderArea.width(), renderArea.height());
            return;
        }

        long areaRight = areaLeft + renderArea.width();
        long areaBottom = areaTop + renderArea.height();
        long left = Math.max(areaLeft, (long) scissorState.x());
        long top = Math.max(areaTop, (long) scissorState.y());
        long right = Math.min(areaRight, (long) scissorState.x() + scissorState.width());
        long bottom = Math.min(areaBottom, (long) scissorState.y() + scissorState.height());
        if (right <= left || bottom <= top) {
            enc.setScissorRect(0, 0, 0, 0);
        } else {
            enc.setScissorRect(left, top, right - left, bottom - top);
        }
    }

    private void markDescriptorDirty(final String name) {
        int slot = descriptorSlot(name);
        if (slot >= 0) {
            dirtyDescriptorMask |= 1L << slot;
        }
    }

    private int descriptorSlot(final String name) {
        return this.compiledPipeline == null ? -1 : this.compiledPipeline.resourceSlot(name);
    }

    private void rebuildDescriptorSlots() {
        java.util.Arrays.fill(this.descriptorBuffers, null);
        java.util.Arrays.fill(this.descriptorTextures, null);
        if (this.compiledPipeline == null) {
            return;
        }
        List<MetalCompiledRenderPipeline.ResourceBinding> resources = this.compiledPipeline.resources();
        for (int slot = 0; slot < resources.size(); slot++) {
            MetalCompiledRenderPipeline.ResourceBinding binding = resources.get(slot);
            if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
                this.descriptorTextures[slot] = this.samplers.get(binding.name());
            } else {
                this.descriptorBuffers[slot] = this.uniforms.get(binding.name());
            }
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

            if (textureBinding.textureView().isClosed()) {
                throw new IllegalStateException("Sampler " + binding.name() + " texture view has been closed");
            }
            if (textureBinding.sampler() instanceof MetalGpuSampler sampler && sampler.isClosed()) {
                throw new IllegalStateException("Sampler " + binding.name() + " has been closed");
            }

            MetalGpuTextureView textureView = (MetalGpuTextureView) textureBinding.textureView();
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
            bindTextureAndSampler(enc, textureView.nativeHandle(), sampler.nativeHandle(), binding.bindingIndex(), binding.stageMask());
            return;
        }

        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER) {
            pushTexelBufferDescriptor(enc, binding, slot);
            return;
        }

        if (binding.name().equals("push_constants") && this.sodiumPushConstantsSet) {
            if ((binding.stageMask() & MetalCompiledRenderPipeline.STAGE_FRAGMENT) != 0) {
                throw new IllegalStateException("Sodium push constants unexpectedly require fragment-stage binding");
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                MemorySegment bytes = MemorySegment.ofAddress(stack.nmalloc(4, 20)).reinterpret(20);
                bytes.set(ValueLayout.JAVA_FLOAT, 0L, this.sodiumPushX);
                bytes.set(ValueLayout.JAVA_FLOAT, 4L, this.sodiumPushY);
                bytes.set(ValueLayout.JAVA_FLOAT, 8L, this.sodiumPushZ);
                bytes.set(ValueLayout.JAVA_INT, 12L, this.sodiumPushAge);
                bytes.set(ValueLayout.JAVA_INT, 16L, this.sodiumPushRegionId);
                enc.setVertexBytes(bytes, 20L, binding.bindingIndex());
            }
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
        bindBuffer(enc, uniformBuffer.metalBuffer(), uniformSlice.offset(), binding.bindingIndex(), binding.stageMask());
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

        bindTexture(enc, texelTexture, binding.bindingIndex(), binding.stageMask());
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
        if (view.isClosed()) {
            throw new IllegalStateException(operation + " texture view is closed");
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

    record TextureViewAndSampler(GpuTextureView textureView, GpuSampler sampler) {
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
        if (previous.textureView() == textureView && previous.sampler() == sampler) {
            return true;
        }
        if (!(previous.textureView() instanceof MetalGpuTextureView oldView)
                || !(textureView instanceof MetalGpuTextureView newView)
                || !(previous.sampler() instanceof MetalGpuSampler oldSampler)
                || !(sampler instanceof MetalGpuSampler newSampler)) {
            return false;
        }
        if (oldView.isClosed() || newView.isClosed() || oldSampler.isClosed() || newSampler.isClosed()) {
            return false;
        }
        return oldView.nativeHandle().address() == newView.nativeHandle().address()
                && oldSampler.nativeHandle().address() == newSampler.nativeHandle().address();
    }
}
