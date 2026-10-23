package com.metallum.render;

import com.metallum.Metallum;
import com.metallum.mtl.*;
import com.metallum.objc.ObjC;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Environment(EnvType.CLIENT)
final class MetalCompiledRenderPipeline implements BackendRenderPipeline {
    enum ResourceKind {
        UNIFORM_BUFFER,
        SAMPLED_IMAGE,
        TEXEL_BUFFER
    }

    static final int STAGE_VERTEX = 1;
    static final int STAGE_FRAGMENT = 2;
    static final int STAGE_ALL = STAGE_VERTEX | STAGE_FRAGMENT;

    /**
     * One frontend uniform. {@code bindingIndex} is the Metal buffer slot for uniform buffers and
     * the texture slot otherwise; {@code samplerIndex} is only meaningful for sampled images.
     */
    record ResourceBinding(ResourceKind kind, String name, int bindingIndex, int samplerIndex, int stageMask,
                           @Nullable GpuFormat texelBufferFormat) {
    }

    record PushConstants(int bufferIndex, int stageMask) {
    }

    private final List<ResourceBinding> resources;
    private final MetalDevice device;
    @Nullable
    private final PushConstants pushConstants;
    private final long allResourceMask;
    private final int firstVertexBufferSlot;
    private final int vertexBufferCount;
    private final MTLCullMode cullMode;
    private final MTLTriangleFillMode fillMode;
    private final float depthBiasScaleFactor;
    private final float depthBiasConstant;
    private final MTLPrimitiveType topology;
    private final boolean supportsIndirectCommands;
    private final MemorySegment depthStencilState;
    private MemorySegment pipeline;

    MetalCompiledRenderPipeline(
            final MetalDevice device,
            final BackendRenderPipeline.CreateInfo info,
            final MetalCrossShaderCompiler.Compiled compiled
    ) {
        this.device = device;
        this.resources = List.copyOf(compiled.resources());
        this.pushConstants = compiled.pushConstants();
        if (this.resources.size() > Long.SIZE) {
            throw new IllegalStateException("Pipeline " + info.name() + " has " + this.resources.size()
                    + " resources; the Metal backend limit is " + Long.SIZE);
        }
        for (ResourceBinding binding : this.resources) {
            validateBindingIndex(info, binding);
        }
        this.allResourceMask = this.resources.size() == Long.SIZE
                ? -1L
                : (1L << this.resources.size()) - 1L;

        this.firstVertexBufferSlot = compiled.firstVertexBufferSlot();
        int vertexBuffers = 0;
        for (BackendRenderPipeline.CreateInfo.VertexBuffer buffer : info.vertexBuffers()) {
            vertexBuffers = Math.max(vertexBuffers, buffer.bufferSlot() + 1);
        }
        this.vertexBufferCount = vertexBuffers;
        if (this.firstVertexBufferSlot + this.vertexBufferCount > MTL4ArgumentTable.MAX_BUFFERS) {
            throw new IllegalStateException("Pipeline " + info.name()
                    + " needs " + this.vertexBufferCount + " vertex buffers starting at Metal slot "
                    + this.firstVertexBufferSlot + ", but only "
                    + MTL4ArgumentTable.MAX_BUFFERS + " buffer slots exist");
        }

        this.cullMode = info.cull() ? MTLCullMode.Back : MTLCullMode.None;
        this.fillMode = info.polygonMode() == PolygonMode.WIREFRAME ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill;
        this.topology = MTLPrimitiveType.from(info.primitiveTopology());

        DepthStencilState depthState = info.depthStencilState();
        MTLCompareFunction depthCompareOp;
        boolean depthWrite;
        if (depthState == null) {
            depthCompareOp = MTLCompareFunction.Always;
            depthWrite = false;
            this.depthBiasScaleFactor = 0.0f;
            this.depthBiasConstant = 0.0f;
        } else {
            depthCompareOp = MTLCompareFunction.from(depthState.depthTest());
            depthWrite = depthState.writeDepth();
            this.depthBiasScaleFactor = depthState.depthBiasScaleFactor();
            this.depthBiasConstant = depthState.depthBiasConstant();
        }
        this.depthStencilState = device.depthStencilState(depthCompareOp, depthWrite);

        String pipelineCacheKey = device.recordPipelineCacheKey(info, compiled.vertexSource(), compiled.fragmentSource());
        // MultiDrawBatch is a Sodium terrain facility. Other pipelines must not opt into ICB
        // support, and Sodium shaders still need a runtime fallback because Metal can reject
        // individual fragment functions.
        boolean requestIndirectCommands = info.name().startsWith("sodium:");

        MemorySegment vertexLibrary = device.getOrCompileLibrary(compiled.vertexSource());
        MemorySegment fragmentLibrary = device.getOrCompileLibrary(compiled.fragmentSource());
        MemorySegment built = MemorySegment.NULL;
        try (MTLVertexDescriptor vertexDescriptor = buildVertexDescriptor(info, this.firstVertexBufferSlot)) {
            built = createPipeline(device, info, vertexLibrary, compiled.vertexEntryPoint(),
                    fragmentLibrary, compiled.fragmentEntryPoint(), vertexDescriptor,
                    requestIndirectCommands, pipelineCacheKey);
            if (ObjC.isNil(built) && requestIndirectCommands) {
                Metallum.LOGGER.debug("[metallum] Pipeline {} is not ICB-compatible; using direct draws", info.name());
                requestIndirectCommands = false;
                built = createPipeline(device, info, vertexLibrary, compiled.vertexEntryPoint(),
                        fragmentLibrary, compiled.fragmentEntryPoint(), vertexDescriptor,
                        false, pipelineCacheKey);
            }
        }
        this.pipeline = built;
        this.supportsIndirectCommands = requestIndirectCommands && !ObjC.isNil(built);
    }

    private static MemorySegment createPipeline(
            final MetalDevice device,
            final BackendRenderPipeline.CreateInfo info,
            final MemorySegment vertexLibrary,
            final String vertexEntryPoint,
            final MemorySegment fragmentLibrary,
            final String fragmentEntryPoint,
            final MTLVertexDescriptor vertexDescriptor,
            final boolean supportsIndirectCommands,
            final String pipelineCacheKey
    ) {
        if (ObjC.isNil(vertexLibrary) || ObjC.isNil(fragmentLibrary)) {
            return MemorySegment.NULL;
        }

        try (MTL4RenderPipelineDescriptor descriptor = new MTL4RenderPipelineDescriptor()) {
            descriptor.setLabel(info.name() + " [" + pipelineCacheKey + "]");
            descriptor.setFunctions(vertexLibrary, vertexEntryPoint, fragmentLibrary, fragmentEntryPoint);
            descriptor.setVertexDescriptor(vertexDescriptor);
            descriptor.setInputPrimitiveTopology(topologyClass(MTLPrimitiveType.from(info.primitiveTopology())));
            descriptor.setSupportIndirectCommandBuffers(supportsIndirectCommands);

            List<ColorTargetState> colorTargets = info.colorTargetStates();
            for (int i = 0; i < colorTargets.size(); i++) {
                ColorTargetState colorTarget = colorTargets.get(i);
                if (colorTarget == null) {
                    continue;
                }
                descriptor.setColorAttachmentFormat(i, MTLPixelFormat.from(colorTarget.format()).value);
                long writeMask = MTLColorWriteMask.from(colorTarget.writeMask());
                Optional<BlendFunction> blendFunction = colorTarget.blendFunction();
                if (blendFunction.isPresent()) {
                    BlendFunction function = blendFunction.get();
                    descriptor.setBlendState(
                            i,
                            MTLBlendFactor.from(function.color().sourceFactor()),
                            MTLBlendFactor.from(function.color().destFactor()),
                            MTLBlendOperation.from(function.color().op()),
                            MTLBlendFactor.from(function.alpha().sourceFactor()),
                            MTLBlendFactor.from(function.alpha().destFactor()),
                            MTLBlendOperation.from(function.alpha().op()),
                            writeMask
                    );
                } else {
                    descriptor.disableBlending(i, writeMask);
                }
            }

            // An ICB-enabled attempt is allowed to fail quietly; the caller retries without ICBs.
            MemorySegment pipeline = device.metal4Compiler().newRenderPipelineState(descriptor, !supportsIndirectCommands);
            if (ObjC.isNil(pipeline)) {
                if (!supportsIndirectCommands) {
                    Metallum.LOGGER.error("[metallum] Metal 4 pipeline {} failed to build", info.name());
                }
            } else {
                try {
                    device.trackAllocation(pipeline);
                } catch (RuntimeException exception) {
                    ObjC.release(pipeline);
                    throw exception;
                }
            }
            return pipeline;
        }
    }

    boolean isValid() {
        return !ObjC.isNil(this.pipeline);
    }

    @Override
    public boolean isClosed() {
        return ObjC.isNil(this.pipeline);
    }

    List<ResourceBinding> resources() {
        return this.resources;
    }

    @Nullable
    PushConstants pushConstants() {
        return this.pushConstants;
    }

    long allResourceMask() {
        return this.allResourceMask;
    }

    boolean supportsIndirectCommands() {
        return this.supportsIndirectCommands;
    }

    int firstVertexBufferSlot() {
        return this.firstVertexBufferSlot;
    }

    int vertexBufferCount() {
        return this.vertexBufferCount;
    }

    float depthBiasScaleFactor() {
        return this.depthBiasScaleFactor;
    }

    float depthBiasConstant() {
        return this.depthBiasConstant;
    }

    MemorySegment getDepthStencilState() {
        return this.depthStencilState;
    }

    MemorySegment nativePipeline() {
        return this.pipeline;
    }

    MTLCullMode cullMode() {
        return this.cullMode;
    }

    MTLTriangleFillMode fillMode() {
        return this.fillMode;
    }

    MTLPrimitiveType topology() {
        return this.topology;
    }

    private static MTLVertexDescriptor buildVertexDescriptor(
            final BackendRenderPipeline.CreateInfo info,
            final int firstMetalVertexBufferSlot
    ) {
        MTLVertexDescriptor vertexDesc = new MTLVertexDescriptor();
        for (BackendRenderPipeline.CreateInfo.VertexBuffer buffer : info.vertexBuffers()) {
            int metalSlot = firstMetalVertexBufferSlot + buffer.bufferSlot();
            long stepRate = buffer.stepRate();
            MTLVertexStepFunction stepFunction = stepRate > 0 ? MTLVertexStepFunction.PerInstance : MTLVertexStepFunction.PerVertex;
            vertexDesc.setLayout(metalSlot, buffer.stride(), stepFunction, stepRate > 0 ? stepRate : 1);
        }
        for (BackendRenderPipeline.CreateInfo.AttribBinding attribute : info.attribBindings()) {
            MTLVertexFormat format = MTLVertexFormat.from(attribute.format());
            if (format == MTLVertexFormat.Invalid) {
                throw new IllegalStateException("Unsupported vertex attribute format: " + attribute.format());
            }
            vertexDesc.setAttribute(
                    attribute.location(),
                    format.value,
                    attribute.offset(),
                    firstMetalVertexBufferSlot + attribute.bufferSlot()
            );
        }
        return vertexDesc;
    }

    private static void validateBindingIndex(
            final BackendRenderPipeline.CreateInfo info,
            final ResourceBinding binding
    ) {
        int limit = switch (binding.kind()) {
            case UNIFORM_BUFFER -> MTL4ArgumentTable.MAX_BUFFERS;
            case TEXEL_BUFFER, SAMPLED_IMAGE -> MTL4ArgumentTable.MAX_TEXTURES;
        };
        if (binding.bindingIndex() < 0 || binding.bindingIndex() >= limit) {
            throw new IllegalStateException("Pipeline " + info.name() + " "
                    + binding.kind().name().toLowerCase(Locale.ROOT) + " binding "
                    + binding.name() + " uses index " + binding.bindingIndex()
                    + ", but Metal allows indices below " + limit);
        }
        if (binding.kind() == ResourceKind.SAMPLED_IMAGE
                && (binding.samplerIndex() < 0 || binding.samplerIndex() >= MTL4ArgumentTable.MAX_SAMPLERS)) {
            throw new IllegalStateException("Pipeline " + info.name() + " sampler " + binding.name()
                    + " uses index " + binding.samplerIndex() + ", but Metal allows indices below "
                    + MTL4ArgumentTable.MAX_SAMPLERS);
        }
    }

    private static long topologyClass(final MTLPrimitiveType topology) {
        return switch (topology) {
            case Point -> 1L;
            case Line, LineStrip -> 2L;
            case Triangle, TriangleStrip, TriangleFan -> 3L;
        };
    }

    @Override
    public void close() {
        if (ObjC.isNil(this.pipeline)) {
            return;
        }
        MemorySegment released = this.pipeline;
        this.pipeline = MemorySegment.NULL;
        this.device.queueAllocationRelease(released);
    }
}
