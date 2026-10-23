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
import java.util.Arrays;
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

    /** Maximum color attachments the clear shader can address. */
    public static final int MAX_COLOR_ATTACHMENTS = 8;
    private static final int CLEAR_UNIFORMS_SIZE = 16 + 16 * MAX_COLOR_ATTACHMENTS;
    private static final String CLEAR_MSL = """
            #include <metal_stdlib>
            using namespace metal;
            
            struct ClearUniforms {
              float4 z;
              float4 colors[8];
            };
            
            struct ClearVertexOut {
              float4 position [[position]];
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
              out.position = float4(positions[vertexId], u.z.x, 1.0);
              return out;
            }
            """;

    private static MTLDevice device;
    private static MTLResidencySet residencySet;
    private static MTL4Compiler compiler;
    private static MemorySegment presentPipeline = MemorySegment.NULL;
    private static MemorySegment presentLinearSampler = MemorySegment.NULL;
    private static MemorySegment presentNearestSampler = MemorySegment.NULL;
    private static final Map<ClearPipelineKey, MemorySegment> clearPipelines = new HashMap<>();
    private static final Map<Long, MemorySegment> depthStencilStates = new HashMap<>();
    private static final Map<String, MemorySegment> libraries = new HashMap<>();

    private MTLBuiltinPipelines() {
    }

    public static void init(
            final MTLDevice mtlDevice,
            final MTLResidencySet liveResidencySet,
            final MTL4Compiler metalCompiler
    ) {
        device = mtlDevice;
        residencySet = liveResidencySet;
        compiler = metalCompiler;
        try {
            presentPipeline = buildPipeline(PRESENT_MSL, "metallum_present_vs", "metallum_present_fs",
                    new long[]{MTLPixelFormat.BGRA8Unorm.value}, 1);
            presentLinearSampler = buildPresentSampler(MTLSamplerMinMagFilter.Linear);
            presentNearestSampler = buildPresentSampler(MTLSamplerMinMagFilter.Nearest);
            ensureClearPipeline(new long[]{MTLPixelFormat.BGRA8Unorm.value}, 1);
            ensureClearPipeline(new long[]{MTLPixelFormat.RGBA8Unorm.value}, 1);
        } catch (Throwable throwable) {
            try {
                close();
            } catch (Throwable cleanupFailure) {
                throwable.addSuppressed(cleanupFailure);
            }
            throw throwable;
        }
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
        libraries.values().forEach(MTLBuiltinPipelines::releaseIfPresent);
        libraries.clear();
        if (residencySet != null) {
            residencySet.commitIfNeeded();
        }
        residencySet = null;
        compiler = null;
        device = null;
    }

    /**
     * Clears a region of every attachment of the currently open render encoder by drawing a
     * full-screen triangle clipped to the region. {@code clearColors[i] == null} leaves attachment
     * {@code i} untouched.
     */
    public static void clearDraw(
            final MTLRenderCommandEncoder encoder,
            final long[] colorFormats,
            final boolean hasDepth,
            final long targetWidth,
            final long targetHeight,
            @Nullable final Vector4fc[] clearColors,
            @Nullable final Double clearDepth,
            final long x,
            final long y,
            final long width,
            final long height
    ) {
        long left = Math.max(x, 0L);
        long top = Math.max(y, 0L);
        long right = Math.min(x + width, targetWidth);
        long bottom = Math.min(y + height, targetHeight);
        if (left >= right || top >= bottom) {
            return;
        }
        if (clearDepth == null && writeBits(clearColors) == 0) {
            return;
        }
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MemorySegment pipeline = ensureClearPipeline(colorFormats, writeBits(clearColors));
            if (ObjC.isNil(pipeline)) {
                return;
            }
            MemorySegment depthState = hasDepth
                    ? ensureDepthStencilState(MTLCompareFunction.Always, clearDepth != null)
                    : MemorySegment.NULL;
            encodeClearDraw(
                    encoder, pipeline,
                    targetWidth, targetHeight,
                    clearColors,
                    left, top, right - left, bottom - top,
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
            final int mipLevel,
            @Nullable final MTLFence globalFence,
            @Nullable final Consumer<MTLRenderCommandEncoder> beginEncoder,
            @Nullable final Consumer<MTLRenderCommandEncoder> finishEncoder
    ) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            if (width <= 0 || height <= 0) {
                return;
            }

            long textureWidth = Math.max(1L, Math.min(MTLTexture.width(colorTexture), MTLTexture.width(depthTexture)) >> mipLevel);
            long textureHeight = Math.max(1L, Math.min(MTLTexture.height(colorTexture), MTLTexture.height(depthTexture)) >> mipLevel);
            long clampedX = Math.max(x, 0);
            long clampedY = Math.max(y, 0);
            long clampedMaxX = Math.min((long) x + width, textureWidth);
            long clampedMaxY = Math.min((long) y + height, textureHeight);
            if (clampedX >= clampedMaxX || clampedY >= clampedMaxY) {
                return;
            }
            boolean fullRegion = clampedX == 0 && clampedY == 0 && clampedMaxX == textureWidth && clampedMaxY == textureHeight;

            MTLRenderCommandEncoder encoder;
            try (MTLRenderPassDescriptor renderPass = new MTLRenderPassDescriptor()) {
                renderPass.colorAttachment(
                        0,
                        colorTexture,
                        mipLevel,
                        fullRegion ? MTLRenderPassDescriptor.LOAD_ACTION_CLEAR : MTLRenderPassDescriptor.LOAD_ACTION_LOAD,
                        MTLRenderPassDescriptor.STORE_ACTION_STORE,
                        clearColor
                );
                renderPass.depthAttachment(
                        depthTexture,
                        mipLevel,
                        0L,
                        fullRegion ? MTLRenderPassDescriptor.LOAD_ACTION_CLEAR : MTLRenderPassDescriptor.LOAD_ACTION_LOAD,
                        MTLRenderPassDescriptor.STORE_ACTION_STORE,
                        clearDepth
                );
                if (MTLPixelFormat.hasStencil(MTLTexture.pixelFormat(depthTexture))) {
                    renderPass.stencilAttachment(
                            depthTexture,
                            mipLevel,
                            0L,
                            MTLRenderPassDescriptor.LOAD_ACTION_DONT_CARE,
                            MTLRenderPassDescriptor.STORE_ACTION_DONT_CARE
                    );
                }
                encoder = commandBuffer.makeRenderCommandEncoder(renderPass);
            }
            encodeExternalRenderPass(encoder, beginEncoder, finishEncoder, () -> {
                if (globalFence != null) {
                    encoder.waitForFence(globalFence, MTLRenderStages.Fragment);
                }

                if (!fullRegion) {
                    clearDraw(
                            encoder,
                            new long[]{MTLTexture.pixelFormat(colorTexture)},
                            true,
                            textureWidth, textureHeight,
                            new Vector4fc[]{clearColor},
                            clearDepth,
                            clampedX, clampedY, clampedMaxX - clampedX, clampedMaxY - clampedY
                    );
                }

                if (globalFence != null) {
                    encoder.updateFence(globalFence, MTLRenderStages.Fragment);
                }
            });
        }
    }

    static void encodePresentTextureToDrawable(
            final MTLCommandBuffer commandBuffer,
            final CAMetalLayer layer,
            final MemorySegment sourceTexture,
            @Nullable final MTLFence globalFence,
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
            try (MTLRenderPassDescriptor renderPass = new MTLRenderPassDescriptor()) {
                renderPass.colorAttachment(
                        0,
                        drawableTexture,
                        MTLRenderPassDescriptor.LOAD_ACTION_DONT_CARE,
                        MTLRenderPassDescriptor.STORE_ACTION_STORE,
                        null
                );
                encoder = commandBuffer.makeRenderCommandEncoder(renderPass);
            }

            encodeExternalRenderPass(encoder, beginEncoder, finishEncoder, () -> {
                if (globalFence != null) {
                    encoder.waitForFence(globalFence, MTLRenderStages.Fragment);
                }

                long drawableWidth = MTLTexture.width(drawableTexture);
                long drawableHeight = MTLTexture.height(drawableTexture);
                encoder.setViewport(0.0, 0.0, drawableWidth, drawableHeight, 0.0, 1.0);
                encoder.setRenderPipelineState(presentPipeline);
                encoder.setTexture(sourceTexture, 0L);

                boolean requiresScaling = MTLTexture.width(sourceTexture) != drawableWidth
                        || MTLTexture.height(sourceTexture) != drawableHeight;
                encoder.setSamplerState(requiresScaling ? presentLinearSampler : presentNearestSampler, 0L);

                encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);

                if (globalFence != null) {
                    encoder.updateFence(globalFence, MTLRenderStages.Fragment);
                }
            });
            commandBuffer.presentDrawable(drawable);
        }
    }

    private static void encodeExternalRenderPass(
            final MTLRenderCommandEncoder encoder,
            @Nullable final Consumer<MTLRenderCommandEncoder> beginEncoder,
            @Nullable final Consumer<MTLRenderCommandEncoder> finishEncoder,
            final Runnable encode
    ) {
        boolean began = false;
        boolean finishAttempted = false;
        boolean endAttempted = false;
        Throwable failure = null;
        try {
            if (beginEncoder != null) {
                beginEncoder.accept(encoder);
                began = true;
            }
            encode.run();
            if (finishEncoder != null) {
                finishAttempted = true;
                finishEncoder.accept(encoder);
            }
            endAttempted = true;
            encoder.endEncoding();
        } catch (RuntimeException | Error throwable) {
            failure = throwable;
            throw throwable;
        } finally {
            if (began && !finishAttempted && finishEncoder != null) {
                try {
                    finishEncoder.accept(encoder);
                } catch (RuntimeException | Error cleanupFailure) {
                    if (failure != null) {
                        failure.addSuppressed(cleanupFailure);
                    } else {
                        failure = cleanupFailure;
                    }
                }
            }
            if (!endAttempted) {
                try {
                    encoder.endEncoding();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (failure != null) {
                        failure.addSuppressed(cleanupFailure);
                    } else {
                        failure = cleanupFailure;
                    }
                }
            }
        }
    }

    private static void encodeClearDraw(
            final MTLRenderCommandEncoder encoder,
            final MemorySegment pipeline,
            final long viewportWidth,
            final long viewportHeight,
            @Nullable final Vector4fc[] clearColors,
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
        encoder.setCullMode(MTLCullMode.None);
        encoder.setTriangleFillMode(MTLTriangleFillMode.Fill);
        if (!ObjC.isNil(depthState)) {
            encoder.setDepthStencilState(depthState);
            encoder.setDepthBias(0.0f, 0.0f, 0.0f);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            MemorySegment uniforms = MemorySegment.ofAddress(stack.ncalloc(16, 1, CLEAR_UNIFORMS_SIZE))
                    .reinterpret(CLEAR_UNIFORMS_SIZE);
            float z = ObjC.isNil(depthState) || clearDepth == null ? 0.0f : (float) Math.clamp(clearDepth, 0.0, 1.0);
            uniforms.set(JAVA_FLOAT, 0, z);
            for (int i = 0; clearColors != null && i < clearColors.length && i < MAX_COLOR_ATTACHMENTS; i++) {
                Vector4fc color = clearColors[i];
                if (color == null) {
                    continue;
                }
                long offset = 16L + 16L * i;
                uniforms.set(JAVA_FLOAT, offset, color.x());
                uniforms.set(JAVA_FLOAT, offset + 4, color.y());
                uniforms.set(JAVA_FLOAT, offset + 8, color.z());
                uniforms.set(JAVA_FLOAT, offset + 12, color.w());
            }
            encoder.setBytes(uniforms, CLEAR_UNIFORMS_SIZE, 1L);
        }

        encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);
        // Built-in draws replace the client's pipeline and bindings.
        encoder.invalidateBindingCache();
    }

    private static MemorySegment ensureClearPipeline(final long[] colorFormats, final int writeBits) {
        ClearPipelineKey key = new ClearPipelineKey(Arrays.toString(colorFormats), writeBits);
        MemorySegment cached = clearPipelines.get(key);
        if (cached != null) {
            return cached;
        }
        MemorySegment pipeline = buildPipeline(CLEAR_MSL + clearFragmentMsl(colorFormats), "metallum_clear_vs", "metallum_clear_fs",
                colorFormats, writeBits);
        if (!ObjC.isNil(pipeline)) {
            clearPipelines.put(key, pipeline);
        }
        return pipeline;
    }

    private static String clearFragmentMsl(final long[] colorFormats) {
        StringBuilder outputs = new StringBuilder();
        StringBuilder writes = new StringBuilder();
        for (int i = 0; i < colorFormats.length; i++) {
            if (colorFormats[i] != MTLPixelFormat.Invalid.value) {
                outputs.append("  float4 color%d [[color(%d)]];\n".formatted(i, i));
                writes.append("  out.color%d = u.colors[%d];\n".formatted(i, i));
            }
        }
        if (outputs.isEmpty()) {
            return "fragment void metallum_clear_fs() {}\n";
        }
        return """
                struct ClearFragmentOut {
                %s};
                
                fragment ClearFragmentOut metallum_clear_fs(constant ClearUniforms& u [[buffer(1)]]) {
                  ClearFragmentOut out;
                %s  return out;
                }
                """.formatted(outputs, writes);
    }

    private static int writeBits(@Nullable final Vector4fc[] clearColors) {
        int bits = 0;
        for (int i = 0; clearColors != null && i < clearColors.length; i++) {
            if (clearColors[i] != null) {
                bits |= 1 << i;
            }
        }
        return bits;
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
            final long[] colorFormats,
            final int writeBits
    ) {
        MemorySegment library = libraries.computeIfAbsent(mslSource, compiler::newLibrary);
        if (ObjC.isNil(library)) {
            return MemorySegment.NULL;
        }
        MemorySegment pipeline;
        try (MTL4RenderPipelineDescriptor descriptor = new MTL4RenderPipelineDescriptor()) {
            descriptor.setFunctions(library, vertexEntry, library, fragmentEntry);
            for (int i = 0; i < colorFormats.length; i++) {
                if (colorFormats[i] == MTLPixelFormat.Invalid.value) {
                    continue;
                }
                descriptor.setColorAttachmentFormat(i, colorFormats[i]);
                descriptor.disableBlending(i, (writeBits & (1 << i)) != 0
                        ? MTLColorWriteMask.All.value
                        : MTLColorWriteMask.None.value);
            }
            descriptor.setInputPrimitiveTopology(3L);
            pipeline = compiler.newRenderPipelineState(descriptor);
        }
        if (!ObjC.isNil(pipeline)) {
            residencySet.addAllocation(pipeline);
        }
        return pipeline;
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

    private record ClearPipelineKey(String colorFormats, int writeBits) {
    }
}
