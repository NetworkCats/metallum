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
    private static final boolean RESOURCE_TRACKER_ENABLED =
            Boolean.getBoolean("metallum.resourceTracker");
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
    private MetalRenderPass currentRenderPass;
    @Nullable
    private MTLCommandBuffer commandBuffer;
    @Nullable
    private MTLCommandEncoder currentEncoder;
    private MemorySegment renderColorAttachment = MemorySegment.NULL;
    private MemorySegment renderDepthAttachment = MemorySegment.NULL;
    private final Long2ObjectOpenHashMap<ArrayDeque<MTLBuffer>> dynamicBackingPool = new Long2ObjectOpenHashMap<>();

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
                if (trackerEnabled() && !renderEncoder.isMetal4()) {
                    renderEncoder.updateFence(requireActiveDependencyFence(), MTLRenderStages.VertexAndFragment);
                    Stats.increment(Stats.Counter.FENCE_UPDATES);
                } else if (!trackerEnabled()) {
                    renderEncoder.updateFence(fence, MTLRenderStages.VertexAndFragment);
                    Stats.increment(Stats.Counter.FENCE_UPDATES);
                }
                if (currentRenderPass != null) {
                    currentRenderPass.invalidateEncoderState();
                }
            } else if (currentEncoder instanceof MTLBlitCommandEncoder blitEncoder) {
                if (trackerEnabled() && !blitEncoder.isMetal4()) {
                    blitEncoder.updateFence(requireActiveDependencyFence());
                    Stats.increment(Stats.Counter.FENCE_UPDATES);
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
        if (commandBuffer != null) {
            submitRenderPass();
            endEncoder();

            int slot = (int) (currentSubmitIndex % MAX_SUBMITS_IN_FLIGHT);
            submitSemaphores[slot].drainPermits();
            device.commitResidencyChanges();
            commandBuffer.commitWithCompletionBlock(submitSignalBlocks[slot]);

            toClose = inFlight[slot];
            inFlight[slot] = new InFlight(currentSubmitIndex, commandBuffer);
            commandBuffer = null;
        }
        currentSubmitIndex++;

        if (!awaitSubmitCompletion(currentSubmitIndex - MAX_SUBMITS_IN_FLIGHT, 5000L)) {
            throw new IllegalStateException("5s timeout reached when waiting for Metal submit completion");
        }

        String commandError = null;
        if (toClose != null) {
            commandError = toClose.buffer.errorDescription();
            toClose.buffer.close();
        }

        transientMemory.rotate();
        destroyQueue.rotate();
        accessTracker.reset();
        Stats.endFrame();

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
                    colorTextureView.mipLevels(),
                    0,
                    colorTextureView.texture().getDepthOrLayers(),
                    ResourceAccessTracker.Access.WRITE,
                    STAGE_FRAGMENT
            );
            if (depthTextureView != null) {
                textureAccess(
                        ((MetalGpuTexture) depthTextureView.texture()),
                        depthTextureView.baseMipLevel(),
                        depthTextureView.mipLevels(),
                        0,
                        depthTextureView.texture().getDepthOrLayers(),
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
        MetalGpuTexture colorTex = (MetalGpuTexture) colorTexture.texture();
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
            OptionalDouble attachmentClear = depthAttachment.clearValue();
            depthClear = attachmentClear.isPresent() ? attachmentClear.getAsDouble() : null;

            MetalGpuTexture metalDepth = (MetalGpuTexture) depthTexture.texture();
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
        MetalGpuTexture source = (MetalGpuTexture) textureView.texture();
        flushPendingClear(source);
        submitRenderPass();
        endEncoder();
        MTLCommandBuffer commandBuffer = commandBuffer();
        commandBuffer.encodePresentTextureToDrawable(
                layer,
                source.nativeHandle(),
                trackerEnabled() ? null : fence
        );
    }

    @Override
    public void clearColorTexture(final @NonNull GpuTexture colorTexture, final @NonNull Vector4fc clearColor) {
        pendingColorClears.put((MetalGpuTexture) colorTexture, new Vector4f(clearColor));
    }

    @Override
    public void clearColorAndDepthTextures(final @NonNull GpuTexture colorTexture, final @NonNull Vector4fc clearColor, final @NonNull GpuTexture depthTexture, final double clearDepth) {
        MetalGpuTexture color = (MetalGpuTexture) colorTexture;
        MetalGpuTexture depth = (MetalGpuTexture) depthTexture;
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
        MetalGpuTexture color = (MetalGpuTexture) colorTexture;
        MetalGpuTexture depth = (MetalGpuTexture) depthTexture;
        Vector4fc clearColorCopy = new Vector4f(clearColor);
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
                trackerEnabled() ? null : fence
        );
        if (trackerEnabled()) {
            this.accessTracker.reset();
            this.accessTracker.beginEncoder();
            this.accessTracker.accessTexture(
                    color, 0, color.getMipLevels(), 0, color.getDepthOrLayers(),
                    ResourceAccessTracker.Access.WRITE, STAGE_FRAGMENT);
            this.accessTracker.accessTexture(
                    depth, 0, depth.getMipLevels(), 0, depth.getDepthOrLayers(),
                    ResourceAccessTracker.Access.WRITE, STAGE_FRAGMENT);
        }
    }

    @Override
    public void clearDepthTexture(final @NonNull GpuTexture depthTexture, final double clearDepth) {
        pendingDepthClears.put((MetalGpuTexture) depthTexture, clearDepth);
    }

    @Override
    public void writeToBuffer(final GpuBufferSlice destination, final ByteBuffer data) {
        MetalGpuBuffer buffer = (MetalGpuBuffer) destination.buffer();
        int length = data.remaining();

        if (buffer.isDynamic()) {
            orphanWrite(buffer, destination.offset(), data);
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

    private void orphanWrite(final MetalGpuBuffer buffer, final long offset, final ByteBuffer data) {
        if (buffer.canUpdateBackingInPlace(this.currentSubmitIndex)) {
            ByteBuffer destination = buffer.currentStorage();
            destination.position(Math.toIntExact(offset));
            destination.put(data.duplicate());
            return;
        }
        long size = buffer.allocationSize();
        MTLBuffer old = buffer.metalBuffer();
        MTLBuffer fresh = acquireDynamicBacking(size, buffer.resourceOptions());
        ByteBuffer freshStorage = ObjC.byteBufferView(fresh.contents(), size).order(ByteOrder.nativeOrder());

        if (offset != 0 || data.remaining() != buffer.size()) {
            ByteBuffer previous = buffer.currentStorage();
            previous.clear();
            freshStorage.duplicate().put(previous);
        }

        ByteBuffer dst = freshStorage.duplicate().order(ByteOrder.nativeOrder());
        dst.position(Math.toIntExact(offset));
        dst.put(data.duplicate());

        buffer.swapBacking(fresh, freshStorage, this.currentSubmitIndex);
        recycleDynamicBacking(old, size);
    }

    private MTLBuffer acquireDynamicBacking(final long size, final long resourceOptions) {
        ArrayDeque<MTLBuffer> bucket = dynamicBackingPool.get(size);
        if (bucket != null && !bucket.isEmpty()) {
            return bucket.pop();
        }
        MTLBuffer buffer = device.metalDevice().newBuffer(size, resourceOptions);
        device.trackAllocation(buffer.handle());
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
        MetalGpuBuffer sourceBuffer = (MetalGpuBuffer) source.buffer();
        MetalGpuBuffer targetBuffer = (MetalGpuBuffer) target.buffer();
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
        MetalGpuTexture metalDst = (MetalGpuTexture) destination;
        flushPendingClearForWrite(metalDst);

        int pixelSize = metalDst.pixelSize();
        int rowBytes = width * pixelSize;
        int bytesPerImage = rowBytes * height;
        GpuBufferSlice slice = transientMemory.uploadStaging(source.duplicate().limit(bytesPerImage), pixelSize, GpuBuffer.USAGE_COPY_SRC);

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        MetalGpuBuffer uploadBuffer = (MetalGpuBuffer) slice.buffer();
        bufferAccess(uploadBuffer.metalBuffer(), slice.offset(), bytesPerImage,
                ResourceAccessTracker.Access.READ, STAGE_BLIT);
        textureAccess(metalDst, mipLevel, 1, depthOrLayer, 1,
                ResourceAccessTracker.Access.WRITE, STAGE_BLIT);
        blit.copyFromBufferToTexture(
                uploadBuffer.metalBuffer(),
                slice.offset(),
                rowBytes,
                bytesPerImage,
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
        MetalGpuTexture metalDst = (MetalGpuTexture) destination;
        flushPendingClearForWrite(metalDst);

        int texelSize = destination.getFormat().blockSize();
        long skipBytes = (sourceX + (long) sourceY * sourceWidth) * texelSize;
        long rowBytes = (long) sourceWidth * texelSize;

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        MetalGpuBuffer sourceBuffer = (MetalGpuBuffer) source.buffer();
        markGpuUse(sourceBuffer);
        long sourceOffset = source.offset() + skipBytes;
        long sourceLength = Math.min(source.length() - skipBytes, rowBytes * sourceHeight);
        bufferAccess(sourceBuffer.metalBuffer(), sourceOffset, sourceLength,
                ResourceAccessTracker.Access.READ, STAGE_BLIT);
        textureAccess(metalDst, mipLevel, 1, arrayLayer, 1,
                ResourceAccessTracker.Access.WRITE, STAGE_BLIT);
        blit.copyFromBufferToTexture(
                sourceBuffer.metalBuffer(),
                sourceOffset,
                rowBytes,
                rowBytes * sourceHeight,
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
        MetalGpuTexture texture = (MetalGpuTexture) source;
        flushPendingClear(texture);
        MetalGpuBuffer buffer = (MetalGpuBuffer) destination;
        markGpuUse(buffer);
        int bytesPerPixel = texture.pixelSize();
        int rowBytes = width * bytesPerPixel;
        int bytesPerImage = rowBytes * height;

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        textureAccess(texture, mipLevel, 1, 0, 1,
                ResourceAccessTracker.Access.READ, STAGE_BLIT);
        bufferAccess(buffer.metalBuffer(), offset, bytesPerImage,
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
                rowBytes,
                bytesPerImage
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
        MetalGpuTexture srcTexture = (MetalGpuTexture) source;
        MetalGpuTexture dstTexture = (MetalGpuTexture) destination;
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

        for (long producer : dependency.producerEncoders()) {
            MTLFence producerFence = dependencyFence(producer);
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

    private static MTLRenderStages renderStages(final long stages) {
        boolean vertex = (stages & STAGE_VERTEX) != 0L;
        boolean fragment = (stages & STAGE_FRAGMENT) != 0L;
        if (vertex && fragment) {
            return MTLRenderStages.VertexAndFragment;
        }
        return fragment ? MTLRenderStages.Fragment : MTLRenderStages.Vertex;
    }

    private boolean trackerEnabled() {
        // Keep Metal 3 on its validated global-fence path until the range-based
        // fence pool has completed hardware validation.
        return RESOURCE_TRACKER_ENABLED && this.device.usesMetal4CommandPath();
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
    }

    void waitForSubmittedGpuWork() {
        if (commandBuffer != null || currentRenderPass != null || currentEncoder != null) {
            submit();
        } else {
            endEncoder();
        }
        long latestSubmit = currentSubmitIndex - 1L;
        if (latestSubmit >= MAX_SUBMITS_IN_FLIGHT) {
            awaitSubmitCompletion(latestSubmit, Long.MAX_VALUE);
        }
    }

    @Override
    public void writeTimestamp(final @NonNull GpuQueryPool pool, final int index) {
        if (pool instanceof MetalGpuQueryPool metalPool && index >= 0 && index < pool.size()) {
            if (currentEncoder instanceof MTLRenderCommandEncoder renderEncoder) {
                metalPool.write(renderEncoder, index);
            } else {
                metalPool.write(blitCommandEncoder(), index);
            }
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
        MTLRenderCommandEncoder encoder = commandBuffer().makeRenderCommandEncoder(
                colorClear != null ? texture.nativeHandle() : MemorySegment.NULL,
                colorClear,
                depthClear != null ? texture.nativeHandle() : MemorySegment.NULL,
                depthClear,
                1.0, 1.0
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
                    0,
                    texture.getMipLevels(),
                    0,
                    texture.getDepthOrLayers(),
                    ResourceAccessTracker.Access.WRITE,
                    STAGE_FRAGMENT
            );
        }
        texture.recordMaterializedClear(colorClear, depthClear);
    }

    private static boolean isFullTextureView(final GpuTextureView textureView) {
        return textureView.baseMipLevel() == 0
                && textureView.mipLevels() >= textureView.texture().getMipLevels()
                && textureView.texture().getDepthOrLayers() == 1;
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
