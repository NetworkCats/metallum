package com.metallum.mtl;

import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public final class MTLCommandBuffer {
    private static final long INLINE_ARENA_SIZE = 64L * 1024L;
    private static final long INLINE_ALIGNMENT = 256L;
    private static final long STATUS_COMPLETED = 4;
    private static final long STATUS_ERROR = 5;

    private static final Msg BLIT_COMMAND_ENCODER = Msg.of("blitCommandEncoder", ADDRESS);
    private static final Msg MTL4_COMPUTE_COMMAND_ENCODER = Msg.of("computeCommandEncoder", ADDRESS);
    private static final Msg RENDER_COMMAND_ENCODER = Msg.of("renderCommandEncoderWithDescriptor:", ADDRESS, ADDRESS);
    private static final Msg PRESENT_DRAWABLE = Msg.ofVoid("presentDrawable:", ADDRESS);
    private static final Msg COMMIT = Msg.ofVoid("commit");
    private static final Msg ADD_COMPLETED_HANDLER = Msg.ofVoid("addCompletedHandler:", ADDRESS);
    private static final Msg STATUS = Msg.of("status", JAVA_LONG);
    private static final Msg ERROR = Msg.of("error", ADDRESS);
    private static final Msg LOCALIZED_DESCRIPTION = Msg.of("localizedDescription", ADDRESS);
    private static final Msg WAIT_UNTIL_COMPLETED = Msg.ofVoid("waitUntilCompleted", true);
    private static final Msg PUSH_DEBUG_GROUP = Msg.ofVoid("pushDebugGroup:", ADDRESS);
    private static final Msg POP_DEBUG_GROUP = Msg.ofVoid("popDebugGroup");
    private static final Msg END_MTL4_COMMAND_BUFFER = Msg.ofVoid("endCommandBuffer");

    private MemorySegment handle;
    private final boolean metal4;
    @Nullable
    private final MTLCommandQueue queue;
    private final MTLCommandQueue.Metal4SubmissionSlot submissionSlot;
    @Nullable
    private final MTL4ArgumentTable argumentTable;
    @Nullable
    private final MTLDevice device;
    @Nullable
    private final MTLResidencySet residencySet;
    private final List<MemorySegment> retainedObjects = new ArrayList<>(1);
    private final List<MemorySegment> inlineAllocations = new ArrayList<>();
    @Nullable
    private MTLBuffer inlineBuffer;
    private long inlineCapacity;
    private long inlineOffset;
    private boolean ended;
    private boolean committed;
    private volatile boolean metal4Completed;
    @Nullable
    private volatile String metal4ErrorDescription;

    private MTLCommandBuffer(
            final MemorySegment handle,
            final boolean metal4,
            @Nullable final MTLCommandQueue queue,
            final MTLCommandQueue.Metal4SubmissionSlot submissionSlot,
            @Nullable final MTL4ArgumentTable argumentTable,
            @Nullable final MTLDevice device,
            @Nullable final MTLResidencySet residencySet
    ) {
        this.handle = handle;
        this.metal4 = metal4;
        this.queue = queue;
        this.submissionSlot = submissionSlot;
        this.argumentTable = argumentTable;
        this.device = device;
        this.residencySet = residencySet;
    }

    static MTLCommandBuffer metal3(final MemorySegment handle) {
        return new MTLCommandBuffer(handle, false, null, null, null, null, null);
    }

    static MTLCommandBuffer metal4(
            final MemorySegment handle,
            final MTLCommandQueue queue,
            final MTLCommandQueue.Metal4SubmissionSlot submissionSlot,
            final MTL4ArgumentTable argumentTable,
            final MTLDevice device,
            @Nullable final MTLResidencySet residencySet
    ) {
        return new MTLCommandBuffer(
                handle, true, queue, submissionSlot, argumentTable, device, residencySet);
    }

    public MTLBlitCommandEncoder makeBlitCommandEncoder() {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MemorySegment encoder = this.metal4
                    ? MTL4_COMPUTE_COMMAND_ENCODER.sendPtr(handle())
                    : BLIT_COMMAND_ENCODER.sendPtr(handle());
            if (ObjC.isNil(encoder)) {
                throw new IllegalStateException(this.metal4
                        ? "Failed to create MTL4ComputeCommandEncoder"
                        : "Failed to create MTLBlitCommandEncoder");
            }
            return new MTLBlitCommandEncoder(ObjC.retain(encoder), this.metal4);
        }
    }

    MTLRenderCommandEncoder makeRenderCommandEncoder(final MTLRenderPassDescriptor descriptor) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MemorySegment encoder = RENDER_COMMAND_ENCODER.sendPtr(handle(), descriptor.handle());
            if (ObjC.isNil(encoder)) {
                throw new IllegalStateException("Failed to create MTLRenderCommandEncoder");
            }
            return new MTLRenderCommandEncoder(
                    ObjC.retain(encoder),
                    this.metal4,
                    this.argumentTable,
                    this
            );
        }
    }

    public MTLRenderCommandEncoder makeRenderCommandEncoder(
            final MemorySegment colorTexture,
            @Nullable final Vector4fc clearColor,
            final MemorySegment depthTexture,
            @Nullable final Double clearDepth,
            final double viewportWidth,
            final double viewportHeight
    ) {
        return makeRenderCommandEncoder(
                colorTexture, 0L, clearColor,
                depthTexture, 0L, clearDepth,
                viewportWidth, viewportHeight);
    }

    public MTLRenderCommandEncoder makeRenderCommandEncoder(
            final MemorySegment colorTexture,
            final long colorMipLevel,
            @Nullable final Vector4fc clearColor,
            final MemorySegment depthTexture,
            final long depthMipLevel,
            @Nullable final Double clearDepth,
            final double viewportWidth,
            final double viewportHeight
    ) {
        return makeRenderCommandEncoder(
                colorTexture, colorMipLevel, 0L, clearColor,
                depthTexture, depthMipLevel, 0L, clearDepth,
                viewportWidth, viewportHeight);
    }

    public MTLRenderCommandEncoder makeRenderCommandEncoder(
            final MemorySegment colorTexture,
            final long colorMipLevel,
            final long colorSlice,
            @Nullable final Vector4fc clearColor,
            final MemorySegment depthTexture,
            final long depthMipLevel,
            final long depthSlice,
            @Nullable final Double clearDepth,
            final double viewportWidth,
            final double viewportHeight
    ) {
        if (ObjC.isNil(colorTexture) && ObjC.isNil(depthTexture)) {
            throw new IllegalStateException("Render pass requires a color or depth attachment");
        }
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLRenderCommandEncoder encoder;
            try (MTLRenderPassDescriptor renderPass = new MTLRenderPassDescriptor(this.metal4)) {
                if (!ObjC.isNil(colorTexture)) {
                    renderPass.colorAttachment(
                            0,
                            colorTexture,
                            colorMipLevel,
                            colorSlice,
                            clearColor != null ? MTLRenderPassDescriptor.LOAD_ACTION_CLEAR : MTLRenderPassDescriptor.LOAD_ACTION_LOAD,
                            MTLRenderPassDescriptor.STORE_ACTION_STORE,
                            clearColor
                    );
                }
                if (!ObjC.isNil(depthTexture)) {
                    renderPass.depthAttachment(
                            depthTexture,
                            depthMipLevel,
                            depthSlice,
                            clearDepth != null ? MTLRenderPassDescriptor.LOAD_ACTION_CLEAR : MTLRenderPassDescriptor.LOAD_ACTION_LOAD,
                            MTLRenderPassDescriptor.STORE_ACTION_STORE,
                            clearDepth
                    );
                    if (MTLPixelFormat.hasStencil(MTLTexture.pixelFormat(depthTexture))) {
                        renderPass.stencilAttachment(
                                depthTexture,
                                depthMipLevel,
                                depthSlice,
                                MTLRenderPassDescriptor.LOAD_ACTION_DONT_CARE,
                                MTLRenderPassDescriptor.STORE_ACTION_DONT_CARE
                        );
                    }
                }
                encoder = makeRenderCommandEncoder(renderPass);
            }
            encoder.setViewport(0.0, 0.0, viewportWidth, viewportHeight, 0.0, 1.0);
            return encoder;
        }
    }

    public void clearColorDepthTexturesRegion(
            final MemorySegment colorTexture,
            final Vector4fc clearColor,
            final MemorySegment depthTexture,
            final double clearDepth,
            final int regionX,
            final int regionY,
            final int regionWidth,
            final int regionHeight,
            final MTLFence globalFence,
            @Nullable final Consumer<MTLRenderCommandEncoder> beginEncoder,
            @Nullable final Consumer<MTLRenderCommandEncoder> finishEncoder
    ) {
        MTLBuiltinPipelines.clearColorDepthTexturesRegion(
                this,
                colorTexture,
                clearColor,
                depthTexture,
                clearDepth,
                regionX,
                regionY,
                regionWidth,
                regionHeight,
                globalFence,
                beginEncoder,
                finishEncoder
        );
    }

    public void encodePresentTextureToDrawable(
            final CAMetalLayer layer,
            final MemorySegment sourceTexture,
            final MTLFence globalFence,
            @Nullable final Consumer<MTLRenderCommandEncoder> beginEncoder,
            @Nullable final Consumer<MTLRenderCommandEncoder> finishEncoder
    ) {
        MTLBuiltinPipelines.encodePresentTextureToDrawable(
                this, layer, sourceTexture, globalFence, beginEncoder, finishEncoder);
    }

    void presentDrawable(final CAMetalDrawable drawable) {
        if (this.metal4) {
            if (this.queue == null) {
                throw new IllegalStateException("Metal 4 command buffer has no command queue");
            }
            this.queue.waitForDrawable(drawable);
            this.retainedObjects.add(ObjC.retain(drawable.handle()));
            return;
        }
        PRESENT_DRAWABLE.send(handle(), drawable.handle());
        this.retainedObjects.add(ObjC.retain(drawable.handle()));
    }

    public void commit() {
        if (this.metal4) {
            commitMetal4();
            return;
        }
        COMMIT.send(handle());
        this.committed = true;
    }

    public void commitWithCompletionBlock(final MemorySegment completedHandlerBlock) {
        if (this.metal4) {
            commitMetal4();
            return;
        }
        ADD_COMPLETED_HANDLER.send(handle(), completedHandlerBlock);
        COMMIT.send(handle());
        this.committed = true;
    }

    private void commitMetal4() {
        if (this.committed) {
            throw new IllegalStateException("MTL4CommandBuffer was already committed");
        }
        if (!this.ended) {
            END_MTL4_COMMAND_BUFFER.send(handle());
            this.ended = true;
        }
        if (this.queue == null) {
            throw new IllegalStateException("Metal 4 command buffer has no command queue");
        }
        CAMetalDrawable drawable = this.retainedObjects.isEmpty()
                ? null
                : new CAMetalDrawable(this.retainedObjects.getLast());
        this.queue.commitMetal4(this, drawable);
        this.committed = true;
    }

    public boolean isCompleted() {
        if (ObjC.isNil(handle)) {
            return true;
        }
        if (this.metal4) {
            return this.metal4Completed;
        }
        long status = STATUS.sendLong(handle);
        return status == STATUS_COMPLETED || status == STATUS_ERROR;
    }

    @Nullable
    public String errorDescription() {
        if (ObjC.isNil(handle)) {
            return null;
        }
        if (this.metal4) {
            return this.metal4ErrorDescription;
        }
        if (STATUS.sendLong(handle) != STATUS_ERROR) {
            return null;
        }
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MemorySegment error = ERROR.sendPtr(handle);
            if (ObjC.isNil(error)) {
                return "Metal command buffer failed without an NSError";
            }
            MemorySegment description = LOCALIZED_DESCRIPTION.sendPtr(error);
            return ObjC.isNil(description)
                    ? "Metal command buffer failed without a localized description"
                    : ObjC.javaString(description);
        }
    }

    public boolean waitUntilCompleted(final long timeoutMs) {
        if (ObjC.isNil(handle)) {
            return true;
        }
        if (isCompleted()) {
            return true;
        }
        if (timeoutMs <= 0L) {
            return false;
        }
        if (this.metal4) {
            if (this.submissionSlot == null || !this.submissionSlot.await(timeoutMs)) {
                return false;
            }
            return this.metal4Completed;
        }
        WAIT_UNTIL_COMPLETED.send(handle);
        return isCompleted();
    }

    InlineBufferSlice makeInlineBuffer(final MemorySegment bytes, final long length) {
        if (!this.metal4 || this.device == null || length <= 0L) {
            throw new IllegalStateException("Inline Metal 4 buffer allocation is unavailable");
        }
        long alignedOffset = alignUp(this.inlineOffset, INLINE_ALIGNMENT);
        if (this.inlineBuffer == null || length > this.inlineCapacity - alignedOffset) {
            long capacity = Math.max(INLINE_ARENA_SIZE, alignUp(length, INLINE_ALIGNMENT));
            MTLBuffer created = this.device.newBuffer(
                    capacity,
                    MTLResourceOptions.of(MTLStorageMode.Shared, MTLHazardTrackingMode.Untracked)
            );
            boolean tracked = false;
            try {
                if (this.residencySet != null) {
                    this.residencySet.addAllocation(created.handle());
                    tracked = true;
                }
                this.inlineAllocations.add(created.handle());
            } catch (RuntimeException | Error exception) {
                if (tracked) {
                    this.residencySet.removeAllocationAndRelease(created.handle());
                } else {
                    ObjC.release(created.handle());
                }
                throw exception;
            }
            this.inlineBuffer = created;
            this.inlineCapacity = capacity;
            alignedOffset = 0L;
        }
        MemorySegment destination = this.inlineBuffer.contents();
        if (ObjC.isNil(destination)) {
            throw new IllegalStateException("Metal 4 inline MTLBuffer.contents returned nil");
        }
        destination.reinterpret(this.inlineCapacity)
                .asSlice(alignedOffset, length)
                .copyFrom(bytes.asSlice(0L, length));
        this.inlineOffset = Math.addExact(alignedOffset, length);
        return new InlineBufferSlice(this.inlineBuffer, alignedOffset);
    }

    public boolean isMetal4() {
        return this.metal4;
    }

    MTLCommandQueue.Metal4SubmissionSlot metal4SubmissionSlot() {
        if (this.submissionSlot == null) {
            throw new IllegalStateException("Not a Metal 4 command buffer");
        }
        return this.submissionSlot;
    }

    void completeMetal4(@Nullable final String errorDescription) {
        this.metal4ErrorDescription = errorDescription;
        this.metal4Completed = true;
    }

    public void pushDebugGroup(final String label) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MemorySegment nsLabel = ObjC.nsString(label == null ? "" : label);
            try {
                PUSH_DEBUG_GROUP.send(handle(), nsLabel);
            } finally {
                ObjC.release(nsLabel);
            }
        }
    }

    public void popDebugGroup() {
        POP_DEBUG_GROUP.send(handle());
    }

    public void close() {
        if (ObjC.isNil(handle)) {
            return;
        }
        if (this.metal4 && !this.ended) {
            END_MTL4_COMMAND_BUFFER.send(this.handle);
            this.ended = true;
        }
        ObjC.release(handle);
        handle = MemorySegment.NULL;
        for (MemorySegment object : this.retainedObjects) {
            ObjC.release(object);
        }
        this.retainedObjects.clear();
        for (MemorySegment allocation : this.inlineAllocations) {
            if (this.residencySet != null) {
                this.residencySet.removeAllocationAndRelease(allocation);
            } else {
                ObjC.release(allocation);
            }
        }
        this.inlineAllocations.clear();
        this.inlineBuffer = null;
        this.inlineCapacity = 0L;
        this.inlineOffset = 0L;
    }

    public MemorySegment handle() {
        if (ObjC.isNil(handle)) {
            throw new IllegalStateException("MTLCommandBuffer is closed");
        }
        return handle;
    }

    private static long alignUp(final long value, final long alignment) {
        return Math.addExact(value, alignment - 1L) & -alignment;
    }

    record InlineBufferSlice(MTLBuffer buffer, long offset) {
    }
}
