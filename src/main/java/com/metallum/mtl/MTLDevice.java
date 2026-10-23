package com.metallum.mtl;

import com.metallum.Metallum;
import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import com.metallum.objc.ObjCBlock;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public record MTLDevice(MemorySegment handle) {
    private static final MethodHandle CREATE_SYSTEM_DEFAULT_DEVICE = ObjC.LINKER.downcallHandle(
            ObjC.METAL.findOrThrow("MTLCreateSystemDefaultDevice"), FunctionDescriptor.of(ADDRESS));

    private static final Msg NEW_BUFFER = Msg.of("newBufferWithLength:options:", ADDRESS, JAVA_LONG, JAVA_LONG);
    private static final Msg NEW_TEXTURE = Msg.of("newTextureWithDescriptor:", ADDRESS, ADDRESS);
    private static final Msg NEW_SAMPLER_STATE = Msg.of("newSamplerStateWithDescriptor:", ADDRESS, ADDRESS);
    private static final Msg NEW_DEPTH_STENCIL_STATE = Msg.of("newDepthStencilStateWithDescriptor:", ADDRESS, ADDRESS);
    private static final Msg NEW_FENCE = Msg.of("newFence", ADDRESS);
    private static final Msg MINIMUM_TEXTURE_BUFFER_ALIGNMENT =
            Msg.of("minimumTextureBufferAlignmentForPixelFormat:", JAVA_LONG, JAVA_LONG);
    private static final Msg NAME = Msg.of("name", ADDRESS);
    private static final Msg MAX_BUFFER_LENGTH = Msg.of("maxBufferLength", JAVA_LONG);
    private static final Msg RECOMMENDED_MAX_WORKING_SET_SIZE = Msg.of("recommendedMaxWorkingSetSize", JAVA_LONG);
    private static final Msg REGISTRY_ID = Msg.of("registryID", JAVA_LONG);
    private static final Msg SET_MAXIMIZE_CONCURRENT_COMPILATION =
            Msg.ofVoid("setShouldMaximizeConcurrentCompilation:", java.lang.foreign.ValueLayout.JAVA_BOOLEAN);
    private static final Msg MAXIMUM_CONCURRENT_COMPILATION_TASK_COUNT =
            Msg.of("maximumConcurrentCompilationTaskCount", JAVA_LONG);
    private static final Msg DEPTH24_STENCIL8_SUPPORTED = Msg.of(
            "isDepth24Stencil8PixelFormatSupported",
            java.lang.foreign.ValueLayout.JAVA_BOOLEAN);

    private static final Msg SUPPORTS_FAMILY = Msg.of(
            "supportsFamily:", java.lang.foreign.ValueLayout.JAVA_BOOLEAN, JAVA_LONG);
    private static final long MTL_GPU_FAMILY_METAL4 = 5002L;
    private static final String METAL_4_QUEUE_SELECTOR = "newMTL4CommandQueue";
    private static final String CONCURRENT_COMPILATION_SELECTOR = "setShouldMaximizeConcurrentCompilation:";

    public MTLDevice {
        if (handle == null || handle.address() == 0L) {
            throw new IllegalArgumentException("MTLDevice handle is null");
        }
    }

    @Nullable
    public static MTLDevice createSystemDefault() {
        try {
            MemorySegment device = (MemorySegment) CREATE_SYSTEM_DEFAULT_DEVICE.invokeExact();
            return ObjC.isNil(device) ? null : new MTLDevice(device);
        } catch (Throwable throwable) {
            throw new IllegalStateException("MTLCreateSystemDefaultDevice failed", throwable);
        }
    }

    public String name() {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            return ObjC.javaString(NAME.sendPtr(handle));
        }
    }

    public long maxBufferLength() {
        return MAX_BUFFER_LENGTH.sendLong(handle);
    }

    public long recommendedMaxWorkingSetSize() {
        return RECOMMENDED_MAX_WORKING_SET_SIZE.sendLong(handle);
    }

    public long registryId() {
        return ObjC.respondsToSelector(handle, "registryID") ? REGISTRY_ID.sendLong(handle) : 0L;
    }

    /** True when the GPU and OS expose the complete Metal 4 feature set (macOS 26+, Apple silicon). */
    public boolean supportsMetal4() {
        return ObjC.respondsToSelector(handle, METAL_4_QUEUE_SELECTOR)
                && SUPPORTS_FAMILY.sendBoolean(handle, MTL_GPU_FAMILY_METAL4);
    }

    public boolean enableConcurrentCompilation() {
        if (!ObjC.respondsToSelector(handle, CONCURRENT_COMPILATION_SELECTOR)) {
            return false;
        }
        SET_MAXIMIZE_CONCURRENT_COMPILATION.send(handle, true);
        return true;
    }

    public int maximumConcurrentCompilationTaskCount() {
        if (!ObjC.respondsToSelector(handle, "maximumConcurrentCompilationTaskCount")) {
            return 0;
        }
        return Math.toIntExact(Math.min(
                Integer.MAX_VALUE,
                MAXIMUM_CONCURRENT_COMPILATION_TASK_COUNT.sendLong(handle)));
    }

    public boolean isDepth24Stencil8PixelFormatSupported() {
        return !ObjC.respondsToSelector(handle, "isDepth24Stencil8PixelFormatSupported")
                || DEPTH24_STENCIL8_SUPPORTED.sendBoolean(handle);
    }

    public MTLBuffer newBuffer(final long length, final long options) {
        MemorySegment buffer = NEW_BUFFER.sendPtr(handle, length, options);
        if (ObjC.isNil(buffer)) {
            throw new IllegalStateException("newBufferWithLength:options: returned nil (length=" + length + ")");
        }
        return new MTLBuffer(buffer);
    }

    public MTLCommandQueue newCommandQueue() {
        return MTLCommandQueue.create(this);
    }

    public MemorySegment newTexture(final MTLTextureDescriptor descriptor) {
        MemorySegment texture = NEW_TEXTURE.sendPtr(handle, descriptor.handle());
        if (ObjC.isNil(texture)) {
            throw new IllegalStateException("newTextureWithDescriptor: returned nil");
        }
        return texture;
    }

    public MemorySegment newSamplerState(final MTLSamplerDescriptor descriptor) {
        MemorySegment sampler = NEW_SAMPLER_STATE.sendPtr(handle, descriptor.handle());
        if (ObjC.isNil(sampler)) {
            throw new IllegalStateException("newSamplerStateWithDescriptor: returned nil");
        }
        return sampler;
    }

    public MemorySegment newDepthStencilState(final MTLDepthStencilDescriptor descriptor) {
        MemorySegment state = NEW_DEPTH_STENCIL_STATE.sendPtr(handle, descriptor.handle());
        if (ObjC.isNil(state)) {
            throw new IllegalStateException("newDepthStencilStateWithDescriptor: returned nil");
        }
        return state;
    }

    public MTLFence newFence() {
        MemorySegment fence = NEW_FENCE.sendPtr(handle);
        if (ObjC.isNil(fence)) {
            throw new IllegalStateException("newFence returned nil");
        }
        return new MTLFence(fence);
    }

    static long minimumTextureBufferAlignment(final MemorySegment device, final long pixelFormat) {
        return MINIMUM_TEXTURE_BUFFER_ALIGNMENT.sendLong(device, pixelFormat);
    }

}
