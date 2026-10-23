package com.metallum.render;

import com.metallum.mtl.*;
import com.metallum.objc.ObjC;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.device.*;
import com.mojang.renderpearl.backend.api.*;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
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

@Environment(EnvType.CLIENT)
final class MetalCommandEncoder implements CommandEncoderBackend {
    public static final int MAX_SUBMITS_IN_FLIGHT = 3;
    private static final MemorySegment[] NO_ATTACHMENTS = new MemorySegment[0];
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
    private final MetalDestructionQueue destroyQueue = new MetalDestructionQueue(MAX_SUBMITS_IN_FLIGHT);
    private final MetalTransientMemory transientMemory;
    private final Map<MetalGpuTexture, Vector4fc> pendingColorClears = new IdentityHashMap<>();
    private final Map<MetalGpuTexture, Double> pendingDepthClears = new IdentityHashMap<>();
    private final MTLFence fence;
    private final ResourceAccessTracker accessTracker = new ResourceAccessTracker();
    private long activeEncoderId = -1L;
    private long previousSubmissionStages;
    private long currentSubmissionStages;
    private boolean currentSubmissionHasEncoder;
    @Nullable
    private MetalRenderPass currentRenderPass;
    @Nullable
    private MTLCommandBuffer commandBuffer;
    @Nullable
    private MTLCommandEncoder currentEncoder;
    private MemorySegment[] renderColorAttachments = NO_ATTACHMENTS;
    private MemorySegment renderDepthAttachment = MemorySegment.NULL;
    private final Long2ObjectOpenHashMap<ArrayDeque<MTLBuffer>> dynamicBackingPool = new Long2ObjectOpenHashMap<>();
    private final Set<MetalGpuBuffer> pendingDynamicBuffers =
            Collections.newSetFromMap(new IdentityHashMap<>());

    MetalCommandEncoder(final MetalDevice device) {
        this.device = device;
        this.transientMemory = new MetalTransientMemory(device, this);
        fence = device.metalDevice().newFence();
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
                } else {
                    renderEncoder.updateFence(fence, MTLRenderStages.VertexAndFragment);
                    Stats.increment(Stats.Counter.FENCE_UPDATES);
                }
                if (currentRenderPass != null) {
                    currentRenderPass.invalidateEncoderState();
                }
            } else if (currentEncoder instanceof MTLBlitCommandEncoder blitEncoder) {
                if (trackerEnabled()) {
                    finishTrackedBlitEncoder(blitEncoder);
                } else {
                    blitEncoder.updateFence(fence);
                    Stats.increment(Stats.Counter.FENCE_UPDATES);
                }
            }
            currentEncoder.endEncoding();
            currentEncoder = null;
            activeEncoderId = -1L;
        }
        renderColorAttachments = NO_ATTACHMENTS;
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
            device.commitResidencyChanges();
            commandBuffer.commit();

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
        device.flushPipelineArchiveIfIdle();

        if (commandError != null) {
            device.recordCommandError(commandError);
            throw new IllegalStateException("Metal command buffer failed: " + commandError);
        }
    }

    /**
     * Returns a render encoder bound to the given attachments, reusing the open one when the
     * attachments match. Clears outside {@code renderArea} must not touch other pixels, so
     * partial-area clears load the attachments and draw a scissored clear instead.
     */
    MTLRenderCommandEncoder renderCommandEncoder(
            final MetalGpuTextureView[] colorTextureViews,
            @Nullable final MetalGpuTextureView depthTextureView,
            final int viewportWidth,
            final int viewportHeight,
            @Nullable final Vector4fc[] clearColors,
            @Nullable final Double clearDepth,
            final RenderPass.RenderArea renderArea
    ) {
        MemorySegment[] colorAttachments = new MemorySegment[colorTextureViews.length];
        long[] colorFormats = new long[colorTextureViews.length];
        long[] colorMipLevels = new long[colorTextureViews.length];
        boolean clear = clearDepth != null;
        for (int i = 0; i < colorTextureViews.length; i++) {
            MetalGpuTextureView view = colorTextureViews[i];
            colorAttachments[i] = view == null ? MemorySegment.NULL : view.nativeHandle();
            colorFormats[i] = view == null
                    ? MTLPixelFormat.Invalid.value
                    : ((MetalGpuTexture) view.texture()).mtlPixelFormat().value;
            clear |= view != null && clearColors != null && clearColors[i] != null;
        }
        MemorySegment depthAttachment = depthTextureView == null ? MemorySegment.NULL : depthTextureView.nativeHandle();

        if (currentEncoder instanceof MTLRenderCommandEncoder enc
                && sameAttachments(renderColorAttachments, colorAttachments)
                && MetalPipelineSupport.sameHandle(renderDepthAttachment, depthAttachment)) {
            if (clear) {
                clearDraw(enc, colorFormats, depthTextureView != null, viewportWidth, viewportHeight,
                        clearColors, clearDepth, renderArea);
            }
            return enc;
        }

        boolean fillsArea = renderArea.x() <= 0 && renderArea.y() <= 0
                && renderArea.x() + renderArea.width() >= viewportWidth
                && renderArea.y() + renderArea.height() >= viewportHeight;
        boolean loadThenClear = clear && !fillsArea;

        endEncoder();
        MTLRenderCommandEncoder encoder = commandBuffer().makeRenderCommandEncoder(
                colorAttachments,
                colorMipLevels,
                loadThenClear ? null : clearColors,
                depthAttachment,
                0L,
                0L,
                loadThenClear ? null : clearDepth,
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
            for (MetalGpuTextureView view : colorTextureViews) {
                if (view == null) {
                    continue;
                }
                textureAccess(
                        (MetalGpuTexture) view.texture(),
                        view.baseMipLevel(),
                        1,
                        0,
                        1,
                        ResourceAccessTracker.Access.WRITE,
                        STAGE_FRAGMENT
                );
            }
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
        renderColorAttachments = colorAttachments;
        renderDepthAttachment = depthAttachment;
        if (loadThenClear) {
            clearDraw(encoder, colorFormats, depthTextureView != null, viewportWidth, viewportHeight,
                    clearColors, clearDepth, renderArea);
        }
        return encoder;
    }

    private static void clearDraw(
            final MTLRenderCommandEncoder encoder,
            final long[] colorFormats,
            final boolean hasDepth,
            final int targetWidth,
            final int targetHeight,
            @Nullable final Vector4fc[] clearColors,
            @Nullable final Double clearDepth,
            final RenderPass.RenderArea area
    ) {
        MTLBuiltinPipelines.clearDraw(
                encoder,
                colorFormats,
                hasDepth,
                targetWidth,
                targetHeight,
                clearColors,
                clearDepth,
                area.x(),
                area.y(),
                area.width(),
                area.height()
        );
    }

    private static boolean sameAttachments(final MemorySegment[] left, final MemorySegment[] right) {
        if (left.length != right.length) {
            return false;
        }
        for (int i = 0; i < left.length; i++) {
            if (!MetalPipelineSupport.sameHandle(left[i], right[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public @NonNull RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
        RenderPass.RenderArea renderArea = descriptor.renderArea();
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments = descriptor.colorAttachments();
        if (attachments.size() > MTLBuiltinPipelines.MAX_COLOR_ATTACHMENTS) {
            throw new IllegalArgumentException("Metal supports at most " + MTLBuiltinPipelines.MAX_COLOR_ATTACHMENTS
                    + " color attachments, got " + attachments.size());
        }
        GpuTextureView[] colorTextures = new GpuTextureView[attachments.size()];
        Vector4fc[] colorClears = new Vector4fc[attachments.size()];
        for (int i = 0; i < attachments.size(); i++) {
            RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = attachments.get(i);
            if (attachment == null) {
                continue;
            }
            GpuTextureView colorTexture = attachment.textureView();
            MetalGpuTexture colorTex = requireTextureView(colorTexture, "Color attachment " + i);
            // A pending whole-texture clear can only fold into this pass when the pass covers
            // the full texture; otherwise materialize it first (upstream "Fix scissors").
            if (!renderArea.fillsTexture(colorTexture) || colorTexture.baseMipLevel() != 0
                    || colorTex.getMipLevels() != 1 || colorTex.getDepthOrLayers() != 1) {
                flushPendingClear(colorTex);
            }
            Vector4fc pendingColor = pendingColorClears.remove(colorTex);
            colorClears[i] = attachment.clearValue().orElse(pendingColor);
            colorTextures[i] = colorTexture;
            colorTex.markContentsDirty();
        }

        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
        GpuTextureView depthTexture = null;
        Double depthClear = null;
        if (depthAttachment != null) {
            depthTexture = depthAttachment.textureView();
            MetalGpuTexture metalDepth = requireTextureView(depthTexture, "Depth attachment");
            if (!renderArea.fillsTexture(depthTexture) || depthTexture.baseMipLevel() != 0
                    || metalDepth.getMipLevels() != 1 || metalDepth.getDepthOrLayers() != 1) {
                flushPendingClear(metalDepth);
            }
            Double pendingDepth = pendingDepthClears.remove(metalDepth);
            OptionalDouble attachmentClear = depthAttachment.clearValue();
            depthClear = attachmentClear.isPresent() ? Double.valueOf(attachmentClear.getAsDouble()) : pendingDepth;
            metalDepth.markContentsDirty();
        }

        MetalRenderPass renderPass = new MetalRenderPass(
                device,
                this,
                descriptor.label(),
                colorTextures,
                depthTexture,
                renderArea,
                colorClears,
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
            final int regionHeight,
            final int mipLevel
    ) {
        MetalGpuTexture color = requireTexture(colorTexture, "Color clear texture");
        MetalGpuTexture depth = requireTexture(depthTexture, "Depth clear texture");
        Vector4fc clearColorCopy = new Vector4f(clearColor);
        BufferCopySupport.requireRange(mipLevel, 1L, color.getMipLevels(), "Color clear mip level");
        BufferCopySupport.requireRange(mipLevel, 1L, depth.getMipLevels(), "Depth clear mip level");
        BufferCopySupport.requireRange(regionX, regionWidth, color.getWidth(mipLevel), "Color clear X region");
        BufferCopySupport.requireRange(regionY, regionHeight, color.getHeight(mipLevel), "Color clear Y region");
        BufferCopySupport.requireRange(regionX, regionWidth, depth.getWidth(mipLevel), "Depth clear X region");
        BufferCopySupport.requireRange(regionY, regionHeight, depth.getHeight(mipLevel), "Depth clear Y region");
        if (regionWidth == 0 || regionHeight == 0) {
            return;
        }
        if (mipLevel == 0 && color.getMipLevels() == 1 && depth.getMipLevels() == 1
                && isFullTextureRegion(color, depth, regionX, regionY, regionWidth, regionHeight)) {
            pendingColorClears.put(color, clearColorCopy);
            pendingDepthClears.put(depth, clearDepth);
            return;
        }
        flushPendingClearForWrite(color);
        flushPendingClearForWrite(depth);
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
                mipLevel,
                trackerEnabled() ? null : fence,
                trackerEnabled() ? encoder -> beginExternalRenderEncoder(
                        encoder,
                        () -> {
                            textureAccess(
                                    color, mipLevel, 1, 0, 1,
                                    ResourceAccessTracker.Access.WRITE, STAGE_FRAGMENT);
                            textureAccess(
                                    depth, mipLevel, 1, 0, 1,
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
    }

    private void applyDependency(final ResourceAccessTracker.Dependency dependency) {
        MTLCommandEncoder encoder = this.currentEncoder;
        if (encoder == null) {
            throw new IllegalStateException("Resource access declared without an active Metal encoder");
        }

        if (dependency.hasSameEncoderDependency()) {
            encoder.barrierAfterEncoderStages(
                    dependency.sameEncoderStages(),
                    dependency.consumerStages(),
                    dependency.deviceVisibility()
            );
            Stats.increment(Stats.Counter.BARRIERS);
        }
        if (dependency.hasPriorDependency()) {
            encoder.barrierAfterQueueStages(
                    dependency.priorStages(),
                    dependency.consumerStages(),
                    dependency.deviceVisibility()
            );
            Stats.increment(Stats.Counter.BARRIERS);
        }
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
        }
    }

    private void finishTrackedRenderEncoder(final MTLRenderCommandEncoder encoder) {
        this.currentSubmissionStages |= this.accessTracker.stagesForEncoder(this.activeEncoderId);
    }

    private void finishTrackedBlitEncoder(final MTLBlitCommandEncoder encoder) {
        this.currentSubmissionStages |= this.accessTracker.stagesForEncoder(this.activeEncoderId);
    }

    private void waitForPreviousSubmission(final MTLCommandEncoder encoder) {
        if (this.previousSubmissionStages == 0L) {
            return;
        }
        long consumerStages = encoder instanceof MTLRenderCommandEncoder
                ? STAGE_VERTEX | STAGE_FRAGMENT
                : STAGE_BLIT;
        encoder.barrierAfterQueueStages(this.previousSubmissionStages, consumerStages, true);
        Stats.increment(Stats.Counter.BARRIERS);
    }

    private void finishTrackedSubmission() {
        if (!trackerEnabled()) {
            return;
        }
        if (this.currentSubmissionStages != 0L) {
            this.previousSubmissionStages = this.currentSubmissionStages;
        }
        this.currentSubmissionStages = 0L;
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
            return f.buffer.waitUntilCompleted(timeoutMs);
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
