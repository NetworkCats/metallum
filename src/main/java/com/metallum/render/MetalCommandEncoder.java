package com.metallum.render;

import com.metallum.mtl.*;
import com.metallum.objc.ObjC;
import com.metallum.objc.ObjCBlock;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Environment(EnvType.CLIENT)
final class MetalCommandEncoder implements CommandEncoderBackend {
    public static final int MAX_SUBMITS_IN_FLIGHT = 3;
    static final long STAGE_VERTEX = 1L;
    static final long STAGE_FRAGMENT = 1L << 1;
    static final long STAGE_BLIT = 1L << 28;
    // The tracker performs fine-grained synchronization inside a submission and
    // preserves ordering across submission boundaries. Keep the global fence path
    // available as a conservative diagnostics fallback.
    private static final boolean RESOURCE_TRACKER_ENABLED =
            !Boolean.getBoolean("metallum.globalFence")
                    && !"false".equalsIgnoreCase(System.getProperty("metallum.resourceTracker"));
    private final MetalDevice device;
    private long currentSubmitIndex = MAX_SUBMITS_IN_FLIGHT;
    private final InFlight[] inFlight = new InFlight[MAX_SUBMITS_IN_FLIGHT];
    private final Semaphore[] submitSemaphores = new Semaphore[MAX_SUBMITS_IN_FLIGHT];
    private final MemorySegment[] submitSignalBlocks = new MemorySegment[MAX_SUBMITS_IN_FLIGHT];
    private final MetalDestructionQueue destroyQueue = new MetalDestructionQueue(MAX_SUBMITS_IN_FLIGHT);
    private final MetalTransientMemory transientMemory;
    private final Map<MetalGpuTexture, Vector4fc> pendingColorClears = new IdentityHashMap<>();
    private final Map<MetalGpuTexture, Double> pendingDepthClears = new IdentityHashMap<>();
    private final MTLFence fence;
    private final ResourceAccessTracker accessTracker = new ResourceAccessTracker();
    private final List<MTLFence>[] dependencyFences;
    private long activeEncoderId = -1L;
    @Nullable
    private MTLFence activeDependencyFence;
    @Nullable
    private MTLFence previousSubmissionFence;
    @Nullable
    private MTLFence currentSubmissionLastFence;
    private long previousSubmissionStages;
    private long currentSubmissionStages;
    private boolean currentSubmissionHasEncoder;
    @Nullable
    private MetalRenderPass currentRenderPass;
    @Nullable
    private MTLCommandBuffer commandBuffer;
    @Nullable
    private MTLCommandEncoder currentEncoder;
    private MemorySegment renderColorAttachment = MemorySegment.NULL;
    private MemorySegment renderDepthAttachment = MemorySegment.NULL;
    private final Long2ObjectOpenHashMap<ArrayDeque<MTLBuffer>> dynamicBackingPool = new Long2ObjectOpenHashMap<>();
    private final Set<MetalGpuBuffer> pendingDynamicBuffers =
            Collections.newSetFromMap(new IdentityHashMap<>());

    MetalCommandEncoder(final MetalDevice device) {
        this.device = device;
        this.transientMemory = new MetalTransientMemory(device, this);
        fence = device.metalDevice().newFence();
        @SuppressWarnings("unchecked")
        List<MTLFence>[] fences = (List<MTLFence>[]) new List<?>[MAX_SUBMITS_IN_FLIGHT];
        this.dependencyFences = fences;
        for (int slot = 0; slot < MAX_SUBMITS_IN_FLIGHT; slot++) {
            this.dependencyFences[slot] = new ArrayList<>();
            Semaphore semaphore = new Semaphore(0);
            submitSemaphores[slot] = semaphore;
            submitSignalBlocks[slot] = ObjCBlock.withRunnable(semaphore::release);
        }
    }

    boolean belongsTo(final MetalDevice device) {
        return this.device == device;
    }

    MTLCommandBuffer commandBuffer() {
        if (commandBuffer != null) {
            return commandBuffer;
        }
        return commandBuffer = device.commandQueue.makeCommandBuffer(
                device.useLabels() ? "Metallum frame " + currentSubmitIndex : null
        );
    }

    MTLBlitCommandEncoder blitCommandEncoder() {
        if (currentEncoder instanceof MTLBlitCommandEncoder encoder) {
            return encoder;
        }
        endEncoder();
        MTLBlitCommandEncoder encoder = commandBuffer().makeBlitCommandEncoder();
        Stats.increment(Stats.Counter.ENCODER_SWITCHES);
        beginTrackedEncoder(encoder);
        if (!trackerEnabled()) {
            encoder.waitForFence(fence);
            Stats.increment(Stats.Counter.FENCE_WAITS);
        }
        currentEncoder = encoder;
        return encoder;
    }

    void endEncoder() {
        if (currentEncoder != null) {
            if (currentEncoder instanceof MTLRenderCommandEncoder renderEncoder) {
                if (trackerEnabled()) {
                    finishTrackedRenderEncoder(renderEncoder);
                } else if (!trackerEnabled()) {
                    renderEncoder.updateFence(fence, MTLRenderStages.VertexAndFragment);
                    Stats.increment(Stats.Counter.FENCE_UPDATES);
                }
                if (currentRenderPass != null) {
                    currentRenderPass.invalidateEncoderState();
                }
            } else if (currentEncoder instanceof MTLBlitCommandEncoder blitEncoder) {
                if (trackerEnabled()) {
                    finishTrackedBlitEncoder(blitEncoder);
                } else if (!trackerEnabled()) {
                    blitEncoder.updateFence(fence);
                    Stats.increment(Stats.Counter.FENCE_UPDATES);
                }
            }
            currentEncoder.endEncoding();
            currentEncoder = null;
            activeEncoderId = -1L;
            activeDependencyFence = null;
        }
        renderColorAttachment = MemorySegment.NULL;
        renderDepthAttachment = MemorySegment.NULL;
    }

    @Override
    public @NonNull TransientMemory transientMemory() {
        return transientMemory;
    }

    @Override
    public void submit() {
        InFlight toClose = null;
        submitRenderPass();
        materializeAllDynamicWrites();
        if (commandBuffer != null) {
            endEncoder();

            int slot = (int) (currentSubmitIndex % MAX_SUBMITS_IN_FLIGHT);
            submitSemaphores[slot].drainPermits();
            device.commitResidencyChanges();
            commandBuffer.commitWithCompletionBlock(submitSignalBlocks[slot]);

            toClose = inFlight[slot];
            inFlight[slot] = new InFlight(currentSubmitIndex, commandBuffer);
            commandBuffer = null;
            finishTrackedSubmission();
        }
        currentSubmitIndex++;

        String commandError = null;
        if (toClose != null) {
            commandError = toClose.buffer.errorDescription();
            toClose.buffer.close();
        }

        if (!awaitSubmitCompletion(currentSubmitIndex - MAX_SUBMITS_IN_FLIGHT, 5000L)) {
            throw new IllegalStateException("5s timeout reached when waiting for Metal submit completion");
        }

        transientMemory.rotate();
        destroyQueue.rotate();
        accessTracker.reset();

        if (commandError != null) {
            device.recordCommandError(commandError);
            throw new IllegalStateException("Metal command buffer failed: " + commandError);
        }
    }

    MTLRenderCommandEncoder renderCommandEncoder(
            final MetalGpuTextureView colorTextureView,
            @Nullable final MetalGpuTextureView depthTextureView,
            final int viewportWidth,
            final int viewportHeight,
            @Nullable final Vector4fc clearColor,
            @Nullable final Double clearDepth
    ) {
        MemorySegment colorAttachment = colorTextureView.nativeHandle();
        MemorySegment depthAttachment = depthTextureView == null ? MemorySegment.NULL : depthTextureView.nativeHandle();
        if (currentEncoder instanceof MTLRenderCommandEncoder enc
                && MetalPipelineSupport.sameHandle(renderColorAttachment, colorAttachment)
                && MetalPipelineSupport.sameHandle(renderDepthAttachment, depthAttachment)) {
            if (clearColor != null || clearDepth != null) {
                enc.clearDraw(
                        colorAttachment,
                        depthAttachment,
                        viewportWidth,
                        viewportHeight,
                        clearColor,
                        clearDepth
                );
            }
            return enc;
        }

        endEncoder();
        MTLRenderCommandEncoder encoder = commandBuffer().makeRenderCommandEncoder(
                colorAttachment,
                clearColor,
                depthAttachment,
                clearDepth,
                viewportWidth,
                viewportHeight
        );
        Stats.increment(Stats.Counter.ENCODER_SWITCHES);
        beginTrackedEncoder(encoder);
        currentEncoder = encoder;
        if (!trackerEnabled()) {
            encoder.waitForFence(fence, MTLRenderStages.VertexAndFragment);
            Stats.increment(Stats.Counter.FENCE_WAITS);
        } else {
            textureAccess(
                    ((MetalGpuTexture) colorTextureView.texture()),
                    colorTextureView.baseMipLevel(),
                    1,
                    0,
                    1,
                    ResourceAccessTracker.Access.WRITE,
                    STAGE_FRAGMENT
            );
            if (depthTextureView != null) {
                textureAccess(
                        ((MetalGpuTexture) depthTextureView.texture()),
                        depthTextureView.baseMipLevel(),
                        1,
                        0,
                        1,
                        ResourceAccessTracker.Access.WRITE,
                        STAGE_FRAGMENT
                );
            }
        }
        renderColorAttachment = colorAttachment;
        renderDepthAttachment = depthAttachment;
        return encoder;
    }

    @Override
    public @NonNull RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
        RenderPassDescriptor.Attachment<Optional<Vector4fc>> colorAttachment = descriptor.colorAttachments().getFirst();
        GpuTextureView colorTexture = colorAttachment.textureView();
        MetalGpuTexture colorTex = requireTextureView(colorTexture, "Color attachment");
        Vector4fc colorClear = colorAttachment.clearValue().orElse(null);
        Vector4fc pendingColor = pendingColorClears.get(colorTex);
        if (pendingColor != null && colorClear == null) {
            if (isFullTextureView(colorTexture)) {
                pendingColorClears.remove(colorTex);
                colorClear = pendingColor;
            } else {
                flushPendingClear(colorTex);
            }
        } else {
            pendingColorClears.remove(colorTex);
        }
        colorTex.markContentsDirty();

        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
        GpuTextureView depthTexture = null;
        Double depthClear = null;
        if (depthAttachment != null) {
            depthTexture = depthAttachment.textureView();
            MetalGpuTexture metalDepth = requireTextureView(depthTexture, "Depth attachment");
            OptionalDouble attachmentClear = depthAttachment.clearValue();
            depthClear = attachmentClear.isPresent() ? attachmentClear.getAsDouble() : null;

            Double pendingDepth = pendingDepthClears.get(metalDepth);
            if (pendingDepth != null && depthClear == null) {
                if (isFullTextureView(depthTexture)) {
                    pendingDepthClears.remove(metalDepth);
                    depthClear = pendingDepth;
                } else {
                    flushPendingClear(metalDepth);
                }
            } else {
                pendingDepthClears.remove(metalDepth);
            }
            metalDepth.markContentsDirty();
        }

        assert descriptor.renderArea != null;
        RenderPass.RenderArea renderArea = descriptor.renderArea;
        MetalRenderPass renderPass = new MetalRenderPass(
                device,
                this,
                descriptor.label(),
                colorTexture,
                depthTexture,
                renderArea,
                colorClear,
                depthClear
        );
        currentRenderPass = renderPass;
        renderPass.pushDebugGroup(descriptor.label());
        return renderPass;
    }

    @Override
    public void submitRenderPass() {
        if (currentRenderPass != null) {
            currentRenderPass.materializePendingClear();
            currentRenderPass.popDebugGroup();
            currentRenderPass = null;
        }
    }

    void presentTextureToDrawable(final CAMetalLayer layer, final GpuTextureView textureView) {
        MetalGpuTexture source = requireTextureView(textureView, "Presented texture");
        MetalGpuTextureView sourceView = (MetalGpuTextureView) textureView;
        flushPendingClear(source);
        submitRenderPass();
        endEncoder();
        MTLCommandBuffer commandBuffer = commandBuffer();
        commandBuffer.encodePresentTextureToDrawable(
                layer,
                sourceView.nativeHandle(),
                trackerEnabled() ? null : fence,
                trackerEnabled() ? encoder -> beginExternalRenderEncoder(
                        encoder,
                        () -> textureAccess(
                                source,
                                textureView.baseMipLevel(),
                                1,
                                0,
                                1,
                                ResourceAccessTracker.Access.READ,
                                STAGE_FRAGMENT)) : null,
                trackerEnabled() ? this::finishExternalRenderEncoder : null
        );
    }

    @Override
    public void clearColorTexture(final @NonNull GpuTexture colorTexture, final @NonNull Vector4fc clearColor) {
        pendingColorClears.put(requireTexture(colorTexture, "Color clear texture"), new Vector4f(clearColor));
    }

    @Override
    public void clearColorAndDepthTextures(final @NonNull GpuTexture colorTexture, final @NonNull Vector4fc clearColor, final @NonNull GpuTexture depthTexture, final double clearDepth) {
        MetalGpuTexture color = requireTexture(colorTexture, "Color clear texture");
        MetalGpuTexture depth = requireTexture(depthTexture, "Depth clear texture");
        pendingColorClears.put(color, new Vector4f(clearColor));
        pendingDepthClears.put(depth, clearDepth);
    }

    @Override
    public void clearColorAndDepthTextures(
            final @NonNull GpuTexture colorTexture,
            final @NonNull Vector4fc clearColor,
            final @NonNull GpuTexture depthTexture,
            final double clearDepth,
            final int regionX,
            final int regionY,
            final int regionWidth,
            final int regionHeight
    ) {
        MetalGpuTexture color = requireTexture(colorTexture, "Color clear texture");
        MetalGpuTexture depth = requireTexture(depthTexture, "Depth clear texture");
        Vector4fc clearColorCopy = new Vector4f(clearColor);
        BufferCopySupport.requireRange(regionX, regionWidth, color.getWidth(0), "Color clear X region");
        BufferCopySupport.requireRange(regionY, regionHeight, color.getHeight(0), "Color clear Y region");
        BufferCopySupport.requireRange(regionX, regionWidth, depth.getWidth(0), "Depth clear X region");
        BufferCopySupport.requireRange(regionY, regionHeight, depth.getHeight(0), "Depth clear Y region");
        if (regionWidth == 0 || regionHeight == 0) {
            return;
        }
        if (isFullTextureRegion(color, depth, regionX, regionY, regionWidth, regionHeight)) {
            pendingColorClears.put(color, clearColorCopy);
            pendingDepthClears.put(depth, clearDepth);
            return;
        }
        color.markContentsDirty();
        depth.markContentsDirty();
        submitRenderPass();
        endEncoder();
        commandBuffer().clearColorDepthTexturesRegion(
                color.nativeHandle(),
                clearColorCopy,
                depth.nativeHandle(),
                clearDepth,
                regionX,
                regionY,
                regionWidth,
                regionHeight,
                trackerEnabled() ? null : fence,
                trackerEnabled() ? encoder -> beginExternalRenderEncoder(
                        encoder,
                        () -> {
                            textureAccess(
                                    color, 0, 1, 0, 1,
                                    ResourceAccessTracker.Access.WRITE, STAGE_FRAGMENT);
                            textureAccess(
                                    depth, 0, 1, 0, 1,
                                    ResourceAccessTracker.Access.WRITE, STAGE_FRAGMENT);
                        }) : null,
                trackerEnabled() ? this::finishExternalRenderEncoder : null
        );
    }

    @Override
    public void clearDepthTexture(final @NonNull GpuTexture depthTexture, final double clearDepth) {
        pendingDepthClears.put(requireTexture(depthTexture, "Depth clear texture"), clearDepth);
    }

    @Override
    public void writeToBuffer(final GpuBufferSlice destination, final ByteBuffer data) {
        MetalGpuBuffer buffer = requireBuffer(destination.buffer(), "Buffer write destination");
        int length = data.remaining();
        BufferCopySupport.requireRange(
                destination.offset(), destination.length(), buffer.size(), "Buffer write destination slice");
        BufferCopySupport.requireRange(0L, length, destination.length(), "Buffer write");
        if (length == 0) {
            return;
        }
        buffer.mirrorWrite(destination.offset(), data);

        if (buffer.isDynamic()) {
            queueDynamicWrite(buffer, destination.offset(), data);
            return;
        }

        GpuBufferSlice staging = transientMemory.uploadStaging(data, 4L, GpuBuffer.USAGE_COPY_SRC);
        MetalGpuBuffer stagingBuffer = (MetalGpuBuffer) staging.buffer();

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        bufferAccess(stagingBuffer.metalBuffer(), staging.offset(), length,
                ResourceAccessTracker.Access.READ, STAGE_BLIT);
        bufferAccess(buffer.metalBuffer(), destination.offset(), length,
                ResourceAccessTracker.Access.WRITE, STAGE_BLIT);
        blit.copyFromBufferToBuffer(
                stagingBuffer.metalBuffer(),
                staging.offset(),
                buffer.metalBuffer(),
                destination.offset(),
                length
        );
    }

    private void queueDynamicWrite(final MetalGpuBuffer buffer, final long offset, final ByteBuffer data) {
        if (buffer.canUpdateBackingInPlace(this.currentSubmitIndex)) {
            ByteBuffer destination = buffer.currentStorage();
            destination.position(Math.toIntExact(offset));
            destination.put(data.duplicate());
            return;
        }
        buffer.queueDynamicWrite(this.currentSubmitIndex, offset, data);
        this.pendingDynamicBuffers.add(buffer);
    }

    boolean materializeDynamicWrites(final MetalGpuBuffer buffer) {
        if (!buffer.hasPendingWrites(this.currentSubmitIndex)) {
            return false;
        }
        long size = buffer.allocationSize();
        MTLBuffer old = buffer.metalBuffer();
        MTLBuffer fresh = acquireDynamicBacking(size, buffer.resourceOptions());
        ByteBuffer freshStorage = ObjC.byteBufferView(fresh.contents(), size).order(ByteOrder.nativeOrder());

        if (!buffer.pendingWritesCoverWholeBuffer()) {
            ByteBuffer previous = buffer.currentStorage();
            previous.clear();
            freshStorage.duplicate().put(previous);
        }
        buffer.applyPendingWrites(freshStorage);

        buffer.swapBacking(fresh, freshStorage, this.currentSubmitIndex);
        recycleDynamicBacking(old, size);
        this.pendingDynamicBuffers.remove(buffer);
        return true;
    }

    private void materializeAllDynamicWrites() {
        if (this.pendingDynamicBuffers.isEmpty()) {
            return;
        }
        MetalGpuBuffer[] pending = this.pendingDynamicBuffers.toArray(MetalGpuBuffer[]::new);
        for (MetalGpuBuffer buffer : pending) {
            materializeDynamicWrites(buffer);
        }
        this.pendingDynamicBuffers.clear();
    }

    private MTLBuffer acquireDynamicBacking(final long size, final long resourceOptions) {
        ArrayDeque<MTLBuffer> bucket = dynamicBackingPool.get(size);
        if (bucket != null && !bucket.isEmpty()) {
            return bucket.pop();
        }
        MTLBuffer buffer = device.metalDevice().newBuffer(size, resourceOptions);
        try {
            device.trackAllocation(buffer.handle());
        } catch (RuntimeException exception) {
            ObjC.release(buffer.handle());
            throw exception;
        }
        return buffer;
    }

    private void recycleDynamicBacking(final MTLBuffer buffer, final long size) {
        this.destroyQueue.addDynamicBackingRecycle(this, buffer, size);
    }

    void recycleDynamicBackingNow(final MTLBuffer buffer, final long size) {
        this.dynamicBackingPool.computeIfAbsent(size, _ -> new ArrayDeque<>()).push(buffer);
    }

    @Override
    public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
        MetalGpuBuffer sourceBuffer = requireBuffer(source.buffer(), "Buffer copy source");
        MetalGpuBuffer targetBuffer = requireBuffer(target.buffer(), "Buffer copy destination");
        BufferCopySupport.requireRange(
                source.offset(), source.length(), source.buffer().size(), "Buffer copy source slice");
        BufferCopySupport.requireRange(
                target.offset(), target.length(), target.buffer().size(), "Buffer copy destination slice");
        BufferCopySupport.requireRange(0L, source.length(), target.length(), "Buffer copy destination");
        if (source.length() == 0L) {
            return;
        }
        targetBuffer.mirrorCopyFrom(
                sourceBuffer, source.offset(), target.offset(), source.length());
        markGpuUse(sourceBuffer);
        markGpuUse(targetBuffer);
        MTLBlitCommandEncoder blit = blitCommandEncoder();
        bufferAccess(sourceBuffer.metalBuffer(), source.offset(), source.length(),
                ResourceAccessTracker.Access.READ, STAGE_BLIT);
        bufferAccess(targetBuffer.metalBuffer(), target.offset(), source.length(),
                ResourceAccessTracker.Access.WRITE, STAGE_BLIT);
        blit.copyFromBufferToBuffer(
                sourceBuffer.metalBuffer(),
                source.offset(),
                targetBuffer.metalBuffer(),
                target.offset(),
                source.length()
        );
    }

    @Override
    public void writeToTexture(
            final @NonNull GpuTexture destination,
            final @NonNull ByteBuffer source,
            final int mipLevel,
            final int depthOrLayer,
            final int destX,
            final int destY,
            final int width,
            final int height
    ) {
        MetalGpuTexture metalDst = requireTexture(destination, "Texture upload destination");
        int pixelSize = metalDst.pixelSize();
        BufferCopySupport.requireRange(mipLevel, 1L, metalDst.getMipLevels(), "Texture upload mip level");
        BufferCopySupport.requireRange(depthOrLayer, 1L, metalDst.getDepthOrLayers(), "Texture upload layer");
        BufferCopySupport.requireRange(destX, width, metalDst.getWidth(mipLevel), "Texture upload X region");
        BufferCopySupport.requireRange(destY, height, metalDst.getHeight(mipLevel), "Texture upload Y region");
        BufferCopySupport.ImageLayout layout = BufferCopySupport.imageLayout(width, height, pixelSize);
        if (layout.imageBytes() == 0L) {
            return;
        }
        ByteBuffer upload = BufferCopySupport.exactSlice(source, layout.imageBytes(), "Texture upload");
        flushPendingClearForWrite(metalDst);
        GpuBufferSlice slice = transientMemory.uploadStaging(upload, pixelSize, GpuBuffer.USAGE_COPY_SRC);

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        MetalGpuBuffer uploadBuffer = (MetalGpuBuffer) slice.buffer();
        bufferAccess(uploadBuffer.metalBuffer(), slice.offset(), layout.imageBytes(),
                ResourceAccessTracker.Access.READ, STAGE_BLIT);
        textureAccess(metalDst, mipLevel, 1, depthOrLayer, 1,
                ResourceAccessTracker.Access.WRITE, STAGE_BLIT);
        blit.copyFromBufferToTexture(
                uploadBuffer.metalBuffer(),
                slice.offset(),
                layout.rowBytes(),
                layout.imageBytes(),
                width,
                height,
                metalDst.nativeHandle(),
                depthOrLayer,
                mipLevel,
                destX,
                destY
        );
    }

    @Override
    public void copyBufferToTexture(
            final @NonNull GpuBufferSlice source,
            final int sourceX,
            final int sourceY,
            final int sourceWidth,
            final int sourceHeight,
            final @NonNull GpuTexture destination,
            final int destinationX,
            final int destinationY,
            final int copyWidth,
            final int copyHeight,
            final int mipLevel,
            final int arrayLayer
    ) {
        MetalGpuTexture metalDst = requireTexture(destination, "Buffer-to-texture destination");
        MetalGpuBuffer sourceBuffer = requireBuffer(source.buffer(), "Buffer-to-texture source");
        int texelSize = destination.getFormat().blockSize();
        BufferCopySupport.requireRange(sourceX, copyWidth, sourceWidth, "Buffer-to-texture source X region");
        BufferCopySupport.requireRange(sourceY, copyHeight, sourceHeight, "Buffer-to-texture source Y region");
        BufferCopySupport.requireRange(mipLevel, 1L, metalDst.getMipLevels(), "Buffer-to-texture mip level");
        BufferCopySupport.requireRange(arrayLayer, 1L, metalDst.getDepthOrLayers(), "Buffer-to-texture layer");
        BufferCopySupport.requireRange(destinationX, copyWidth, metalDst.getWidth(mipLevel), "Buffer-to-texture destination X region");
        BufferCopySupport.requireRange(destinationY, copyHeight, metalDst.getHeight(mipLevel), "Buffer-to-texture destination Y region");
        BufferCopySupport.ImageLayout sourceLayout = BufferCopySupport.imageLayout(sourceWidth, sourceHeight, texelSize);
        if (copyWidth == 0 || copyHeight == 0) {
            return;
        }
        long skipBytes;
        long sourceLength;
        try {
            skipBytes = Math.addExact(
                    Math.multiplyExact((long) sourceY, sourceLayout.rowBytes()),
                    Math.multiplyExact((long) sourceX, texelSize));
            sourceLength = Math.addExact(
                    Math.multiplyExact((long) copyHeight - 1L, sourceLayout.rowBytes()),
                    Math.multiplyExact((long) copyWidth, texelSize));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Buffer-to-texture copy byte range overflows", exception);
        }
        BufferCopySupport.requireRange(skipBytes, sourceLength, source.length(), "Buffer-to-texture source");
        BufferCopySupport.requireRange(
                source.offset(), source.length(), source.buffer().size(), "Buffer-to-texture source slice");
        flushPendingClearForWrite(metalDst);

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        markGpuUse(sourceBuffer);
        long sourceOffset = source.offset() + skipBytes;
        bufferAccess(sourceBuffer.metalBuffer(), sourceOffset, sourceLength,
                ResourceAccessTracker.Access.READ, STAGE_BLIT);
        textureAccess(metalDst, mipLevel, 1, arrayLayer, 1,
                ResourceAccessTracker.Access.WRITE, STAGE_BLIT);
        blit.copyFromBufferToTexture(
                sourceBuffer.metalBuffer(),
                sourceOffset,
                sourceLayout.rowBytes(),
                sourceLayout.imageBytes(),
                copyWidth,
                copyHeight,
                metalDst.nativeHandle(),
                arrayLayer,
                mipLevel,
                destinationX,
                destinationY
        );
    }

    @Override
    public void copyTextureToBuffer(final @NonNull GpuTexture source, final @NonNull GpuBuffer destination, final long offset, final @NonNull Runnable callback, final int mipLevel) {
        copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
    }

    @Override
    public void copyTextureToBuffer(
            final @NonNull GpuTexture source,
            final @NonNull GpuBuffer destination,
            final long offset,
            final @NonNull Runnable callback,
            final int mipLevel,
            final int x,
            final int y,
            final int width,
            final int height
    ) {
        MetalGpuTexture texture = requireTexture(source, "Texture readback source");
        MetalGpuBuffer buffer = requireBuffer(destination, "Texture readback destination");
        int bytesPerPixel = texture.pixelSize();
        BufferCopySupport.requireRange(mipLevel, 1L, texture.getMipLevels(), "Texture readback mip level");
        BufferCopySupport.requireRange(x, width, texture.getWidth(mipLevel), "Texture readback X region");
        BufferCopySupport.requireRange(y, height, texture.getHeight(mipLevel), "Texture readback Y region");
        BufferCopySupport.ImageLayout layout = BufferCopySupport.imageLayout(width, height, bytesPerPixel);
        BufferCopySupport.requireRange(offset, layout.imageBytes(), buffer.size(), "Texture readback destination");
        if (layout.imageBytes() == 0L) {
            queueForDestroy(callback);
            return;
        }
        buffer.invalidateMirror(offset, layout.imageBytes());
        flushPendingClear(texture);
        markGpuUse(buffer);

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        textureAccess(texture, mipLevel, 1, 0, 1,
                ResourceAccessTracker.Access.READ, STAGE_BLIT);
        bufferAccess(buffer.metalBuffer(), offset, layout.imageBytes(),
                ResourceAccessTracker.Access.WRITE, STAGE_BLIT);
        blit.copyFromTextureToBuffer(
                texture.nativeHandle(),
                0,
                mipLevel,
                x,
                y,
                width,
                height,
                buffer.metalBuffer(),
                offset,
                layout.rowBytes(),
                layout.imageBytes()
        );
        queueForDestroy(callback);
    }

    @Override
    public void copyTextureToTexture(
            final @NonNull GpuTexture source,
            final @NonNull GpuTexture destination,
            final int mipLevel,
            final int destX,
            final int destY,
            final int sourceX,
            final int sourceY,
            final int width,
            final int height
    ) {
        MetalGpuTexture srcTexture = requireTexture(source, "Texture copy source");
        MetalGpuTexture dstTexture = requireTexture(destination, "Texture copy destination");
        if (srcTexture.getFormat() != dstTexture.getFormat()) {
            throw new IllegalArgumentException(
                    "Metal texture copies require matching formats: "
                            + srcTexture.getFormat() + " and " + dstTexture.getFormat());
        }
        BufferCopySupport.requireRange(mipLevel, 1L, srcTexture.getMipLevels(), "Texture copy source mip level");
        BufferCopySupport.requireRange(mipLevel, 1L, dstTexture.getMipLevels(), "Texture copy destination mip level");
        BufferCopySupport.requireRange(sourceX, width, srcTexture.getWidth(mipLevel), "Texture copy source X region");
        BufferCopySupport.requireRange(sourceY, height, srcTexture.getHeight(mipLevel), "Texture copy source Y region");
        BufferCopySupport.requireRange(destX, width, dstTexture.getWidth(mipLevel), "Texture copy destination X region");
        BufferCopySupport.requireRange(destY, height, dstTexture.getHeight(mipLevel), "Texture copy destination Y region");
        if (width == 0 || height == 0) {
            return;
        }
        flushPendingClear(srcTexture);
        flushPendingClearForWrite(dstTexture);
        MTLBlitCommandEncoder blit = blitCommandEncoder();
        textureAccess(srcTexture, mipLevel, 1, 0, 1,
                ResourceAccessTracker.Access.READ, STAGE_BLIT);
        textureAccess(dstTexture, mipLevel, 1, 0, 1,
                ResourceAccessTracker.Access.WRITE, STAGE_BLIT);
        blit.copyFromTextureToTexture(
                srcTexture.nativeHandle(),
                0,
                mipLevel,
                sourceX,
                sourceY,
                width,
                height,
                dstTexture.nativeHandle(),
                0,
                mipLevel,
                destX,
                destY
        );
    }

    @Override
    public @NonNull GpuFence createFence() {
        return new MetalFence(this, currentSubmitIndex);
    }

    void queueForDestroy(final Runnable destroyAction) {
        destroyQueue.add(destroyAction);
    }

    void queueObjCRelease(final MemorySegment handle) {
        this.destroyQueue.addObjCRelease(handle);
    }

    void queueAllocationRelease(final MetalDevice owner, final MemorySegment handle) {
        this.destroyQueue.addAllocationRelease(owner, handle);
    }

    void bufferAccess(
            final MTLBuffer buffer,
            final long offset,
            final long length,
            final ResourceAccessTracker.Access access,
            final long stages
    ) {
        if (!trackerEnabled() || length <= 0L) {
            return;
        }
        applyDependency(this.accessTracker.accessBuffer(buffer, offset, length, access, stages));
    }

    void markGpuUse(final MetalGpuBuffer buffer) {
        materializeDynamicWrites(buffer);
        buffer.markGpuUse(this.currentSubmitIndex);
    }

    void textureAccess(
            final MetalGpuTexture texture,
            final int baseMip,
            final int mipCount,
            final int baseLayer,
            final int layerCount,
            final ResourceAccessTracker.Access access,
            final long stages
    ) {
        if (!trackerEnabled() || mipCount <= 0 || layerCount <= 0) {
            return;
        }
        applyDependency(this.accessTracker.accessTexture(
                texture, baseMip, mipCount, baseLayer, layerCount, access, stages));
    }

    private void beginTrackedEncoder(final MTLCommandEncoder encoder) {
        if (!trackerEnabled()) {
            return;
        }
        if (!this.currentSubmissionHasEncoder) {
            waitForPreviousSubmission(encoder);
            this.currentSubmissionHasEncoder = true;
        }
        this.activeEncoderId = this.accessTracker.beginEncoder();
        this.activeDependencyFence = encoder.isMetal4()
                ? null
                : dependencyFence(this.activeEncoderId);
    }

    private void applyDependency(final ResourceAccessTracker.Dependency dependency) {
        MTLCommandEncoder encoder = this.currentEncoder;
        if (encoder == null) {
            throw new IllegalStateException("Resource access declared without an active Metal encoder");
        }

        if (dependency.hasSameEncoderDependency() && encoder.isMetal4()) {
            encoder.barrierAfterEncoderStages(
                    dependency.sameEncoderStages(),
                    dependency.consumerStages(),
                    dependency.deviceVisibility()
            );
            Stats.increment(Stats.Counter.BARRIERS);
        }
        if (!dependency.hasPriorDependency()) {
            return;
        }
        if (encoder.isMetal4()) {
            encoder.barrierAfterQueueStages(
                    dependency.priorStages(),
                    dependency.consumerStages(),
                    dependency.deviceVisibility()
            );
            Stats.increment(Stats.Counter.BARRIERS);
            return;
        }

        for (int index = 0; index < dependency.producerCount(); index++) {
            MTLFence producerFence = dependencyFence(dependency.producerEncoder(index));
            if (encoder instanceof MTLRenderCommandEncoder renderEncoder) {
                renderEncoder.waitForFence(producerFence, renderStages(dependency.consumerStages()));
            } else if (encoder instanceof MTLBlitCommandEncoder blitEncoder) {
                blitEncoder.waitForFence(producerFence);
            }
            Stats.increment(Stats.Counter.FENCE_WAITS);
        }
    }

    private MTLFence dependencyFence(final long encoderId) {
        int index = Math.toIntExact(encoderId);
        int slot = Math.floorMod(this.currentSubmitIndex, MAX_SUBMITS_IN_FLIGHT);
        List<MTLFence> fences = this.dependencyFences[slot];
        while (fences.size() <= index) {
            fences.add(this.device.metalDevice().newFence());
            Stats.increment(Stats.Counter.NATIVE_RESOURCES_CREATED);
        }
        return fences.get(index);
    }

    private MTLFence requireActiveDependencyFence() {
        if (this.activeDependencyFence == null) {
            throw new IllegalStateException("Metal 3 encoder has no resource dependency fence");
        }
        return this.activeDependencyFence;
    }

    private void beginExternalRenderEncoder(
            final MTLRenderCommandEncoder encoder,
            final Runnable declareAccesses
    ) {
        if (this.currentEncoder != null) {
            throw new IllegalStateException("External Metal encoder started while another encoder is active");
        }
        Stats.increment(Stats.Counter.ENCODER_SWITCHES);
        beginTrackedEncoder(encoder);
        this.currentEncoder = encoder;
        try {
            declareAccesses.run();
        } catch (RuntimeException | Error failure) {
            try {
                finishTrackedRenderEncoder(encoder);
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            } finally {
                this.currentEncoder = null;
                this.activeEncoderId = -1L;
                this.activeDependencyFence = null;
            }
            throw failure;
        }
    }

    private void finishExternalRenderEncoder(final MTLRenderCommandEncoder encoder) {
        if (this.currentEncoder != encoder) {
            throw new IllegalStateException("Unexpected external Metal encoder completion");
        }
        try {
            finishTrackedRenderEncoder(encoder);
        } finally {
            this.currentEncoder = null;
            this.activeEncoderId = -1L;
            this.activeDependencyFence = null;
        }
    }

    private void finishTrackedRenderEncoder(final MTLRenderCommandEncoder encoder) {
        long stages = this.accessTracker.stagesForEncoder(this.activeEncoderId);
        if (stages == 0L) {
            return;
        }
        this.currentSubmissionStages |= stages;
        if (!encoder.isMetal4()) {
            MTLFence dependencyFence = requireActiveDependencyFence();
            encoder.updateFence(dependencyFence, renderStages(stages));
            this.currentSubmissionLastFence = dependencyFence;
            Stats.increment(Stats.Counter.FENCE_UPDATES);
        }
    }

    private void finishTrackedBlitEncoder(final MTLBlitCommandEncoder encoder) {
        long stages = this.accessTracker.stagesForEncoder(this.activeEncoderId);
        if (stages == 0L) {
            return;
        }
        this.currentSubmissionStages |= stages;
        if (!encoder.isMetal4()) {
            MTLFence dependencyFence = requireActiveDependencyFence();
            encoder.updateFence(dependencyFence);
            this.currentSubmissionLastFence = dependencyFence;
            Stats.increment(Stats.Counter.FENCE_UPDATES);
        }
    }

    private void waitForPreviousSubmission(final MTLCommandEncoder encoder) {
        if (encoder.isMetal4()) {
            if (this.previousSubmissionStages != 0L) {
                long consumerStages = encoder instanceof MTLRenderCommandEncoder
                        ? STAGE_VERTEX | STAGE_FRAGMENT
                        : STAGE_BLIT;
                encoder.barrierAfterQueueStages(
                        this.previousSubmissionStages,
                        consumerStages,
                        true
                );
                Stats.increment(Stats.Counter.BARRIERS);
            }
            return;
        }
        if (this.previousSubmissionFence == null) {
            return;
        }
        if (encoder instanceof MTLRenderCommandEncoder renderEncoder) {
            renderEncoder.waitForFence(
                    this.previousSubmissionFence,
                    MTLRenderStages.VertexAndFragment
            );
        } else if (encoder instanceof MTLBlitCommandEncoder blitEncoder) {
            blitEncoder.waitForFence(this.previousSubmissionFence);
        } else {
            throw new IllegalStateException("Unsupported Metal encoder at submission boundary");
        }
        Stats.increment(Stats.Counter.FENCE_WAITS);
    }

    private void finishTrackedSubmission() {
        if (!trackerEnabled()) {
            return;
        }
        if (this.currentSubmissionStages != 0L) {
            this.previousSubmissionStages = this.currentSubmissionStages;
            if (this.currentSubmissionLastFence != null) {
                this.previousSubmissionFence = this.currentSubmissionLastFence;
            }
        }
        this.currentSubmissionStages = 0L;
        this.currentSubmissionLastFence = null;
        this.currentSubmissionHasEncoder = false;
    }

    private static MTLRenderStages renderStages(final long stages) {
        boolean vertex = (stages & STAGE_VERTEX) != 0L;
        boolean fragment = (stages & STAGE_FRAGMENT) != 0L;
        if (vertex && fragment) {
            return MTLRenderStages.VertexAndFragment;
        }
        return fragment ? MTLRenderStages.Fragment : MTLRenderStages.Vertex;
    }

    private boolean trackerEnabled() {
        return RESOURCE_TRACKER_ENABLED;
    }

    boolean awaitSubmitCompletion(final long submitIndex, final long timeoutMs) {
        if (submitIndex == currentSubmitIndex) {
            if (timeoutMs == 0L) {
                return false;
            }
            throw new IllegalStateException("Cannot wait on a fence for the current submit");
        }
        int slot = (int) (submitIndex % MAX_SUBMITS_IN_FLIGHT);
        InFlight f = inFlight[slot];
        if (f != null && f.index == submitIndex) {
            if (f.buffer.isMetal4()) {
                return f.buffer.waitUntilCompleted(timeoutMs);
            }
            Semaphore semaphore = submitSemaphores[slot];
            try {
                if (!semaphore.tryAcquire(Math.max(timeoutMs, 0L), TimeUnit.MILLISECONDS)) {
                    return false;
                }
                semaphore.release();
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Render thread interrupted while waiting for Metal submit completion", e);
            }
        }
        return true;
    }

    void close() {
        submitRenderPass();
        endEncoder();
        for (int slot = 0; slot < inFlight.length; slot++) {
            InFlight f = inFlight[slot];
            if (f != null) {
                f.buffer.close();
                inFlight[slot] = null;
            }
        }
        if (commandBuffer != null) {
            commandBuffer.close();
            commandBuffer = null;
        }
        transientMemory.close();
        device.queueResourceRelease(fence.handle());
        for (List<MTLFence> fences : this.dependencyFences) {
            for (MTLFence dependencyFence : fences) {
                device.queueResourceRelease(dependencyFence.handle());
            }
            fences.clear();
        }
        destroyQueue.close();
        for (ArrayDeque<MTLBuffer> bucket : dynamicBackingPool.values()) {
            for (MTLBuffer buffer : bucket) {
                device.releaseAllocationNow(buffer.handle());
            }
        }
        dynamicBackingPool.clear();
        pendingDynamicBuffers.clear();
        pendingColorClears.clear();
        pendingDepthClears.clear();
    }

    void waitForSubmittedGpuWork() {
        if (commandBuffer != null || currentRenderPass != null || currentEncoder != null) {
            submit();
        } else {
            endEncoder();
        }
        long latestSubmit = -1L;
        for (InFlight submission : this.inFlight) {
            if (submission != null) {
                latestSubmit = Math.max(latestSubmit, submission.index);
            }
        }
        if (latestSubmit >= 0L) {
            awaitSubmitCompletion(latestSubmit, Long.MAX_VALUE);
        }
    }

    @Override
    public void writeTimestamp(final @NonNull GpuQueryPool pool, final int index) {
        if (!(pool instanceof MetalGpuQueryPool metalPool)) {
            throw new IllegalArgumentException("Metal command encoder cannot use a foreign timestamp query pool");
        }
        if (!metalPool.belongsTo(this.device)) {
            throw new IllegalArgumentException("Timestamp query pool belongs to another Metal device");
        }
        if (currentEncoder instanceof MTLRenderCommandEncoder renderEncoder) {
            metalPool.write(renderEncoder, index);
        } else {
            metalPool.write(blitCommandEncoder(), index);
        }
    }

    private void flushPendingClearForWrite(final MetalGpuTexture texture) {
        flushPendingClear(texture);
        texture.markContentsDirty();
    }

    void flushPendingClear(final MetalGpuTexture texture) {
        Vector4fc colorClear = pendingColorClears.remove(texture);
        Double depthClear = pendingDepthClears.remove(texture);
        if (colorClear == null && depthClear == null) {
            return;
        }

        if (texture.clearIsRedundant(colorClear, depthClear)) {
            return;
        }

        endEncoder();
        int layers = texture.getDepthOrLayers();
        for (int mipLevel = 0; mipLevel < texture.getMipLevels(); mipLevel++) {
            for (int layer = 0; layer < layers; layer++) {
                MTLRenderCommandEncoder encoder = commandBuffer().makeRenderCommandEncoder(
                        colorClear != null ? texture.nativeHandle() : MemorySegment.NULL,
                        mipLevel,
                        layer,
                        colorClear,
                        depthClear != null ? texture.nativeHandle() : MemorySegment.NULL,
                        mipLevel,
                        layer,
                        depthClear,
                        texture.getWidth(mipLevel),
                        texture.getHeight(mipLevel)
                );
                Stats.increment(Stats.Counter.ENCODER_SWITCHES);
                beginTrackedEncoder(encoder);
                currentEncoder = encoder;
                if (!trackerEnabled()) {
                    encoder.waitForFence(fence, MTLRenderStages.VertexAndFragment);
                    Stats.increment(Stats.Counter.FENCE_WAITS);
                } else {
                    textureAccess(
                            texture,
                            mipLevel,
                            1,
                            layer,
                            1,
                            ResourceAccessTracker.Access.WRITE,
                            STAGE_FRAGMENT
                    );
                }
                if (mipLevel + 1 < texture.getMipLevels() || layer + 1 < layers) {
                    endEncoder();
                }
            }
        }
        texture.recordMaterializedClear(colorClear, depthClear);
    }

    void discardPendingClears(final MetalGpuTexture texture) {
        this.pendingColorClears.remove(texture);
        this.pendingDepthClears.remove(texture);
    }

    private static boolean isFullTextureView(final GpuTextureView textureView) {
        return textureView.baseMipLevel() == 0
                && textureView.texture().getMipLevels() == 1
                && textureView.mipLevels() >= 1
                && textureView.texture().getDepthOrLayers() == 1;
    }

    private MetalGpuBuffer requireBuffer(final GpuBuffer buffer, final String operation) {
        if (!(buffer instanceof MetalGpuBuffer metalBuffer)) {
            throw new IllegalArgumentException(operation + " does not belong to the Metal backend");
        }
        if (!metalBuffer.belongsTo(this.device)) {
            throw new IllegalArgumentException(operation + " belongs to another Metal device");
        }
        if (metalBuffer.isClosed()) {
            throw new IllegalStateException(operation + " buffer is closed");
        }
        return metalBuffer;
    }

    private MetalGpuTexture requireTexture(final GpuTexture texture, final String operation) {
        if (!(texture instanceof MetalGpuTexture metalTexture)) {
            throw new IllegalArgumentException(operation + " does not belong to the Metal backend");
        }
        if (!metalTexture.belongsTo(this.device)) {
            throw new IllegalArgumentException(operation + " belongs to another Metal device");
        }
        if (metalTexture.isClosed()) {
            throw new IllegalStateException(operation + " texture is closed");
        }
        return metalTexture;
    }

    private MetalGpuTexture requireTextureView(final GpuTextureView view, final String operation) {
        if (!(view instanceof MetalGpuTextureView)) {
            throw new IllegalArgumentException(operation + " view does not belong to the Metal backend");
        }
        if (view.isClosed()) {
            throw new IllegalStateException(operation + " view is closed");
        }
        if (!(view.texture() instanceof MetalGpuTexture texture) || !texture.belongsTo(this.device)) {
            throw new IllegalArgumentException(operation + " belongs to another Metal device");
        }
        return texture;
    }

    private static boolean isFullTextureRegion(
            final MetalGpuTexture color,
            final MetalGpuTexture depth,
            final int x,
            final int y,
            final int width,
            final int height
    ) {
        return x == 0
                && y == 0
                && width == color.getWidth(0)
                && height == color.getHeight(0)
                && width == depth.getWidth(0)
                && height == depth.getHeight(0);
    }

    private record InFlight(long index, MTLCommandBuffer buffer) {
    }
}
