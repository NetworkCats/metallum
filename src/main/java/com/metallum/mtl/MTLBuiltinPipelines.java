package com.metallum.mtl;

import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.ObjC;
import com.metallum.render.Stats;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

@Environment(EnvType.CLIENT)
public final class MTLBuiltinPipelines {
    private static final String PRESENT_MSL = """
            #include <metal_stdlib>
            using namespace metal;
            
            struct PresentVertexOut {
              float4 position [[position]];
              float2 uv;
            };
            
            vertex PresentVertexOut metallum_present_vs(uint vertexId [[vertex_id]]) {
              const float2 positions[3] = {
                float2(-1.0,  1.0),
                float2( 3.0,  1.0),
                float2(-1.0, -3.0)
              };
            
              // Y-flip version:
              // old equivalent was uvMin=(0,1), uvMax=(1,0)
              const float2 uvs[3] = {
                float2(0.0,  1.0),
                float2(2.0,  1.0),
                float2(0.0, -1.0)
              };
            
              PresentVertexOut out;
              out.position = float4(positions[vertexId], 0.0, 1.0);
              out.uv = uvs[vertexId];
              return out;
            }
            
            fragment float4 metallum_present_fs(
              PresentVertexOut in [[stage_in]],
              texture2d<float> tex [[texture(0)]],
              sampler smp [[sampler(0)]]
            ) {
              return tex.sample(smp, in.uv);
            }
            """;

    private static final String CLEAR_MSL = """
            #include <metal_stdlib>
            using namespace metal;
            
            struct ClearUniforms {
              float z;
              float3 _padding0;
              float4 color;
            };
            
            struct ClearVertexOut {
              float4 position [[position]];
              float4 color;
            };
            
            vertex ClearVertexOut metallum_clear_vs(
              uint vertexId [[vertex_id]],
              constant ClearUniforms& u [[buffer(1)]]
            ) {
              const float2 positions[3] = {
                float2(-1.0,  1.0),
                float2( 3.0,  1.0),
                float2(-1.0, -3.0)
              };
            
              ClearVertexOut out;
              out.position = float4(positions[vertexId], u.z, 1.0);
              out.color = u.color;
              return out;
            }
            
            fragment float4 metallum_clear_fs(ClearVertexOut in [[stage_in]]) {
              return in.color;
            }
            """;

    private static MTLDevice device;
    @Nullable
    private static MTLResidencySet residencySet;
    @Nullable
    private static MTL4Compiler metal4Compiler;
    @Nullable
    private static MTLBinaryArchive metal3Archive;
    private static MemorySegment presentPipeline = MemorySegment.NULL;
    private static MemorySegment presentLinearSampler = MemorySegment.NULL;
    private static MemorySegment presentNearestSampler = MemorySegment.NULL;
    private static final Map<Long, MemorySegment> clearPipelines = new HashMap<>();
    private static final Map<Long, MemorySegment> depthStencilStates = new HashMap<>();
    private static final Map<String, MemorySegment> libraries = new HashMap<>();
    private static final Map<BuiltinFunctionKey, MemorySegment> functions = new HashMap<>();

    private MTLBuiltinPipelines() {
    }

    public static void init(
            final MTLDevice mtlDevice,
            @Nullable final MTLResidencySet liveResidencySet,
            @Nullable final MTL4Compiler compiler,
            @Nullable final MTLBinaryArchive binaryArchive
    ) {
        device = mtlDevice;
        residencySet = liveResidencySet;
        metal4Compiler = compiler;
        metal3Archive = binaryArchive;
        presentPipeline = buildPipeline(PRESENT_MSL, "metallum_present_vs", "metallum_present_fs",
                MTLPixelFormat.BGRA8Unorm.value, MTLPixelFormat.Invalid.value, MTLColorWriteMask.All.value);
        presentLinearSampler = buildPresentSampler(MTLSamplerMinMagFilter.Linear);
        presentNearestSampler = buildPresentSampler(MTLSamplerMinMagFilter.Nearest);
        ensureClearPipeline(MTLPixelFormat.BGRA8Unorm.value, MTLPixelFormat.Depth32Float.value, true);
        ensureClearPipeline(MTLPixelFormat.RGBA8Unorm.value, MTLPixelFormat.Depth32Float.value, true);
        ensureClearPipeline(MTLPixelFormat.BGRA8Unorm.value, MTLPixelFormat.Invalid.value, true);
    }

    public static void close() {
        if (!ObjC.isNil(presentPipeline)) {
            releasePipeline(presentPipeline);
            presentPipeline = MemorySegment.NULL;
        }
        if (!ObjC.isNil(presentLinearSampler)) {
            ObjC.release(presentLinearSampler);
            presentLinearSampler = MemorySegment.NULL;
        }
        if (!ObjC.isNil(presentNearestSampler)) {
            ObjC.release(presentNearestSampler);
            presentNearestSampler = MemorySegment.NULL;
        }
        clearPipelines.values().forEach(MTLBuiltinPipelines::releasePipeline);
        clearPipelines.clear();
        depthStencilStates.values().forEach(ObjC::release);
        depthStencilStates.clear();
        functions.values().forEach(MTLBuiltinPipelines::releaseIfPresent);
        functions.clear();
        libraries.values().forEach(MTLBuiltinPipelines::releaseIfPresent);
        libraries.clear();
        if (residencySet != null) {
            residencySet.commitIfNeeded();
        }
        residencySet = null;
        metal4Compiler = null;
        metal3Archive = null;
        device = null;
    }

    static void clearDraw(
            final MTLRenderCommandEncoder encoder,
            final MemorySegment colorTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            @Nullable final Vector4fc clearColor,
            @Nullable final Double clearDepth
    ) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MemorySegment sizeTexture = ObjC.isNil(colorTexture) ? depthTexture : colorTexture;
            if (ObjC.isNil(sizeTexture)) {
                return;
            }

            long colorFormat = ObjC.isNil(colorTexture) ? MTLPixelFormat.Invalid.value : MTLTexture.pixelFormat(colorTexture);
            long depthFormat = ObjC.isNil(depthTexture) ? MTLPixelFormat.Invalid.value : MTLTexture.pixelFormat(depthTexture);
            MemorySegment pipeline = ensureClearPipeline(colorFormat, depthFormat, clearColor != null);
            if (ObjC.isNil(pipeline)) {
                return;
            }

            MemorySegment depthState = depthFormat != MTLPixelFormat.Invalid.value
                    ? ensureDepthStencilState(MTLCompareFunction.Always, clearDepth != null)
                    : MemorySegment.NULL;

            long width = MTLTexture.width(sizeTexture);
            long height = MTLTexture.height(sizeTexture);
            if (width <= 0 || height <= 0) {
                return;
            }

            encodeClearDraw(
                    encoder, pipeline,
                    (long) viewportWidth, (long) viewportHeight,
                    clearColor,
                    0L, 0L, width, height,
                    depthState, clearDepth
            );
        }
    }

    static void clearColorDepthTexturesRegion(
            final MTLCommandBuffer commandBuffer,
            final MemorySegment colorTexture,
            final Vector4fc clearColor,
            final MemorySegment depthTexture,
            final double clearDepth,
            final int x,
            final int y,
            final int width,
            final int height,
            final MTLFence globalFence,
            @Nullable final Consumer<MTLRenderCommandEncoder> beginEncoder,
            @Nullable final Consumer<MTLRenderCommandEncoder> finishEncoder
    ) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            if (width <= 0 || height <= 0) {
                return;
            }

            long textureWidth = Math.min(MTLTexture.width(colorTexture), MTLTexture.width(depthTexture));
            long textureHeight = Math.min(MTLTexture.height(colorTexture), MTLTexture.height(depthTexture));
            long clampedX = Math.max(x, 0);
            long clampedY = Math.max(y, 0);
            long clampedMaxX = Math.min((long) x + width, textureWidth);
            long clampedMaxY = Math.min((long) y + height, textureHeight);
            if (clampedX >= clampedMaxX || clampedY >= clampedMaxY) {
                return;
            }
            boolean fullRegion = clampedX == 0 && clampedY == 0 && clampedMaxX == textureWidth && clampedMaxY == textureHeight;

            MTLRenderCommandEncoder encoder;
            try (MTLRenderPassDescriptor renderPass = new MTLRenderPassDescriptor(commandBuffer.isMetal4())) {
                renderPass.colorAttachment(
                        0,
                        colorTexture,
                        fullRegion ? MTLRenderPassDescriptor.LOAD_ACTION_CLEAR : MTLRenderPassDescriptor.LOAD_ACTION_LOAD,
                        MTLRenderPassDescriptor.STORE_ACTION_STORE,
                        clearColor
                );
                renderPass.depthAttachment(
                        depthTexture,
                        fullRegion ? MTLRenderPassDescriptor.LOAD_ACTION_CLEAR : MTLRenderPassDescriptor.LOAD_ACTION_LOAD,
                        MTLRenderPassDescriptor.STORE_ACTION_STORE,
                        clearDepth
                );
                if (MTLPixelFormat.hasStencil(MTLTexture.pixelFormat(depthTexture))) {
                    renderPass.stencilAttachment(
                            depthTexture,
                            MTLRenderPassDescriptor.LOAD_ACTION_DONT_CARE,
                            MTLRenderPassDescriptor.STORE_ACTION_DONT_CARE
                    );
                }
                encoder = commandBuffer.makeRenderCommandEncoder(renderPass);
            }

            if (beginEncoder != null) {
                beginEncoder.accept(encoder);
            }

            if (globalFence != null) {
                encoder.waitForFence(globalFence, MTLRenderStages.Fragment);
            }

            if (!fullRegion) {
                MemorySegment pipeline = ensureClearPipeline(MTLTexture.pixelFormat(colorTexture), MTLTexture.pixelFormat(depthTexture), true);
                MemorySegment depthState = ensureDepthStencilState(MTLCompareFunction.Always, true);
                if (ObjC.isNil(pipeline) || ObjC.isNil(depthState)) {
                    if (finishEncoder != null) {
                        finishEncoder.accept(encoder);
                    }
                    encoder.endEncoding();
                    return;
                }
                encodeClearDraw(
                        encoder, pipeline,
                        textureWidth, textureHeight,
                        clearColor,
                        clampedX, clampedY, clampedMaxX - clampedX, clampedMaxY - clampedY,
                        depthState, clearDepth
                );
            }

            if (globalFence != null) {
                encoder.updateFence(globalFence, MTLRenderStages.Fragment);
            }

            if (finishEncoder != null) {
                finishEncoder.accept(encoder);
            }

            encoder.endEncoding();
        }
    }

    static void encodePresentTextureToDrawable(
            final MTLCommandBuffer commandBuffer,
            final CAMetalLayer layer,
            final MemorySegment sourceTexture,
            final MTLFence globalFence,
            @Nullable final Consumer<MTLRenderCommandEncoder> beginEncoder,
            @Nullable final Consumer<MTLRenderCommandEncoder> finishEncoder
    ) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            long drawableStart = Stats.enabled() ? System.nanoTime() : 0L;
            CAMetalDrawable drawable = layer.nextDrawable();
            if (Stats.enabled()) {
                Stats.add(Stats.Counter.NEXT_DRAWABLE_WAIT_NANOS, System.nanoTime() - drawableStart);
            }
            if (drawable == null) {
                return;
            }
            MemorySegment drawableTexture = drawable.texture();

            MTLRenderCommandEncoder encoder;
            try (MTLRenderPassDescriptor renderPass = new MTLRenderPassDescriptor(commandBuffer.isMetal4())) {
                renderPass.colorAttachment(
                        0,
                        drawableTexture,
                        MTLRenderPassDescriptor.LOAD_ACTION_DONT_CARE,
                        MTLRenderPassDescriptor.STORE_ACTION_STORE,
                        null
                );
                encoder = commandBuffer.makeRenderCommandEncoder(renderPass);
            }

            if (beginEncoder != null) {
                beginEncoder.accept(encoder);
            }

            if (globalFence != null) {
                encoder.waitForFence(globalFence, MTLRenderStages.Fragment);
            }

            long drawableWidth = MTLTexture.width(drawableTexture);
            long drawableHeight = MTLTexture.height(drawableTexture);
            encoder.setViewport(0.0, 0.0, drawableWidth, drawableHeight, 0.0, 1.0);
            encoder.setRenderPipelineState(presentPipeline);
            encoder.setFragmentTexture(sourceTexture, 0L);

            boolean requiresScaling = MTLTexture.width(sourceTexture) != drawableWidth
                    || MTLTexture.height(sourceTexture) != drawableHeight;
            encoder.setFragmentSamplerState(requiresScaling ? presentLinearSampler : presentNearestSampler, 0L);

            encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);

            if (globalFence != null) {
                encoder.updateFence(globalFence, MTLRenderStages.Fragment);
            }

            if (finishEncoder != null) {
                finishEncoder.accept(encoder);
            }

            encoder.endEncoding();
            commandBuffer.presentDrawable(drawable);
        }
    }

    private static void encodeClearDraw(
            final MTLRenderCommandEncoder encoder,
            final MemorySegment pipeline,
            final long viewportWidth,
            final long viewportHeight,
            @Nullable final Vector4fc clearColor,
            final long scissorX,
            final long scissorY,
            final long scissorWidth,
            final long scissorHeight,
            final MemorySegment depthState,
            @Nullable final Double clearDepth
    ) {
        encoder.setViewport(0.0, 0.0, viewportWidth, viewportHeight, 0.0, 1.0);
        encoder.setScissorRect(scissorX, scissorY, scissorWidth, scissorHeight);
        encoder.setRenderPipelineState(pipeline);
        if (!ObjC.isNil(depthState)) {
            encoder.setDepthStencilState(depthState);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            MemorySegment uniforms = MemorySegment.ofAddress(stack.nmalloc(16, 48)).reinterpret(48);
            float z = ObjC.isNil(depthState) || clearDepth == null ? 0.0f : (float) Math.clamp(clearDepth, 0.0, 1.0);
            uniforms.set(JAVA_FLOAT, 0, z);
            uniforms.set(JAVA_FLOAT, 32, clearColor == null ? 0.0f : clearColor.x());
            uniforms.set(JAVA_FLOAT, 36, clearColor == null ? 0.0f : clearColor.y());
            uniforms.set(JAVA_FLOAT, 40, clearColor == null ? 0.0f : clearColor.z());
            uniforms.set(JAVA_FLOAT, 44, clearColor == null ? 0.0f : clearColor.w());
            encoder.setVertexBytes(uniforms, 48L, 1L);
        }

        encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);
    }

    private static MemorySegment ensureClearPipeline(final long colorFormat, final long depthFormat, final boolean writeColor) {
        long key = (colorFormat << 32) | (depthFormat << 1) | (writeColor ? 1L : 0L);
        MemorySegment cached = clearPipelines.get(key);
        if (cached != null) {
            return cached;
        }
        MemorySegment pipeline = buildPipeline(CLEAR_MSL, "metallum_clear_vs", "metallum_clear_fs",
                colorFormat, depthFormat, writeColor ? MTLColorWriteMask.All.value : MTLColorWriteMask.None.value);
        if (!ObjC.isNil(pipeline)) {
            clearPipelines.put(key, pipeline);
        }
        return pipeline;
    }

    private static MemorySegment ensureDepthStencilState(final MTLCompareFunction compareOp, final boolean writeDepth) {
        long key = (compareOp.value << 1) | (writeDepth ? 1L : 0L);
        MemorySegment cached = depthStencilStates.get(key);
        if (cached != null) {
            return cached;
        }
        try (MTLDepthStencilDescriptor descriptor = MTLDepthStencilDescriptor.create()) {
            descriptor.depthCompareFunction(compareOp);
            descriptor.depthWriteEnabled(writeDepth);
            MemorySegment state = device.newDepthStencilState(descriptor);
            if (!ObjC.isNil(state)) {
                depthStencilStates.put(key, state);
            }
            return state;
        }
    }

    private static MemorySegment buildPipeline(
            final String mslSource,
            final String vertexEntry,
            final String fragmentEntry,
            final long colorFormat,
            final long depthFormat,
            final long writeMask
    ) {
        if (metal4Compiler != null) {
            MemorySegment library = libraries.computeIfAbsent(mslSource, metal4Compiler::newLibrary);
            if (ObjC.isNil(library)) {
                return MemorySegment.NULL;
            }
            MemorySegment pipeline;
            try (MTL4RenderPipelineDescriptor descriptor = new MTL4RenderPipelineDescriptor()) {
                descriptor.setFunctions(library, vertexEntry, library, fragmentEntry);
                descriptor.setColorAttachmentFormat(0, colorFormat);
                descriptor.setInputPrimitiveTopology(3L);
                descriptor.disableBlending(0, writeMask);
                pipeline = metal4Compiler.newRenderPipelineState(descriptor);
            }
            if (!ObjC.isNil(pipeline) && residencySet != null) {
                residencySet.addAllocation(pipeline);
            }
            return pipeline;
        }

        MemorySegment vertexFunction = function(mslSource, vertexEntry);
        MemorySegment fragmentFunction = function(mslSource, fragmentEntry);
        if (ObjC.isNil(vertexFunction) || ObjC.isNil(fragmentFunction)) {
            return MemorySegment.NULL;
        }
        MemorySegment pipeline;
        try (MTLRenderPipelineDescriptor descriptor = new MTLRenderPipelineDescriptor()) {
            descriptor.setLabel(vertexEntry + "/" + fragmentEntry);
            descriptor.setCompiledFunctions(vertexFunction, fragmentFunction);
            descriptor.setColorAttachmentFormat(0, colorFormat);
            descriptor.setDepthStencilFormats(depthFormat, MTLPixelFormat.Invalid.value);
            descriptor.disableBlending(0, writeMask);
            if (metal3Archive != null) {
                descriptor.setBinaryArchive(metal3Archive.handle());
                metal3Archive.addRenderPipeline(descriptor);
            }
            pipeline = device.newRenderPipelineStateAsync(descriptor);
        }
        if (!ObjC.isNil(pipeline) && residencySet != null) {
            residencySet.addAllocation(pipeline);
        }
        return pipeline;
    }

    private static MemorySegment function(final String mslSource, final String entryPoint) {
        return functions.computeIfAbsent(new BuiltinFunctionKey(mslSource, entryPoint), key -> {
            MemorySegment library = libraries.computeIfAbsent(key.mslSource(), device::newLibrary);
            return ObjC.isNil(library)
                    ? MemorySegment.NULL
                    : device.newFunction(library, key.entryPoint());
        });
    }

    private static MemorySegment buildPresentSampler(final MTLSamplerMinMagFilter filter) {
        try (MTLSamplerDescriptor descriptor = MTLSamplerDescriptor.create()) {
            descriptor.minFilter(filter);
            descriptor.magFilter(filter);
            descriptor.mipFilter(MTLSamplerMipFilter.NotMipmapped);
            descriptor.sAddressMode(MTLSamplerAddressMode.ClampToEdge);
            descriptor.tAddressMode(MTLSamplerAddressMode.ClampToEdge);
            return device.newSamplerState(descriptor);
        }
    }

    private static void releaseIfPresent(final MemorySegment object) {
        if (!ObjC.isNil(object)) {
            ObjC.release(object);
        }
    }

    private static void releasePipeline(final MemorySegment pipeline) {
        if (ObjC.isNil(pipeline)) {
            return;
        }
        if (residencySet != null) {
            residencySet.removeAllocationAndRelease(pipeline);
        } else {
            ObjC.release(pipeline);
        }
    }

    private record BuiltinFunctionKey(String mslSource, String entryPoint) {
    }

}
