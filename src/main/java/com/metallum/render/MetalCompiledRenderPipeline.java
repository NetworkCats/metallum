package com.metallum.render;

import com.metallum.Metallum;
import com.metallum.mtl.*;
import com.metallum.objc.ObjC;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Environment(EnvType.CLIENT)
final class MetalCompiledRenderPipeline implements CompiledRenderPipeline, AutoCloseable {
    enum ResourceKind {
        UNIFORM_BUFFER,
        SAMPLED_IMAGE,
        TEXEL_BUFFER
    }

    static final int STAGE_VERTEX = 1;
    static final int STAGE_FRAGMENT = 2;
    static final int STAGE_ALL = STAGE_VERTEX | STAGE_FRAGMENT;

    record ResourceBinding(ResourceKind kind, String name, int bindingIndex, int stageMask,
                           @Nullable GpuFormat texelBufferFormat) {
    }

    private final List<ResourceBinding> resources;
    private final MetalDevice device;
    private final Map<String, ResourceBinding> resourcesByName;
    private final Map<String, Integer> resourceSlotsByName;
    private final long allResourceMask;
    private final int firstAvailableVertexBufferSlot;
    private final MTLCullMode cullMode;
    private final MTLTriangleFillMode fillMode;
    private final float depthBiasScaleFactor;
    private final float depthBiasConstant;
    private final MTLPrimitiveType topology;
    private final int vertexBufferCount;
    private final boolean supportsIndirectCommands;

    private final MemorySegment depthStencilState;
    private final MemorySegment withDepthPipeline;
    private final MemorySegment withoutDepthPipeline;

    MetalCompiledRenderPipeline(
            final MetalDevice device,
            final RenderPipeline info,
            final String vertexMsl,
            final String fragmentMsl,
            final String vertexEntryPoint,
            final String fragmentEntryPoint,
            final List<ResourceBinding> resources
    ) {
        this.device = device;
        String pipelineCacheKey = device.recordPipelineCacheKey(info, vertexMsl, fragmentMsl);
        this.resources = resources;
        this.resourcesByName = resources.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ResourceBinding::name, binding -> binding));
        if (resources.size() > Long.SIZE) {
            throw new IllegalStateException("Pipeline " + info.getLocation() + " has " + resources.size()
                    + " resources; the Metal backend limit is " + Long.SIZE);
        }
        Map<String, Integer> slots = new java.util.HashMap<>(resources.size());
        for (int slot = 0; slot < resources.size(); slot++) {
            ResourceBinding binding = resources.get(slot);
            slots.put(binding.name(), slot);
            validateBindingIndex(info, binding);
        }
        this.resourceSlotsByName = Map.copyOf(slots);
        this.allResourceMask = resources.size() == Long.SIZE
                ? -1L
                : (1L << resources.size()) - 1L;

        this.firstAvailableVertexBufferSlot = firstAvailableVertexBufferSlot(resources);
        this.cullMode = info.isCull() ? MTLCullMode.Back : MTLCullMode.None;
        this.fillMode = info.getPolygonMode() == PolygonMode.WIREFRAME ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill;
        this.topology = MTLPrimitiveType.from(info.getPrimitiveTopology());
        this.vertexBufferCount = info.getVertexFormatBindings().length;
        // MultiDrawBatch is a Sodium terrain facility. Other Minecraft pipelines
        // must not opt into ICB support, and Sodium shaders still need a runtime
        // fallback because Metal can reject individual fragment functions.
        boolean requestIndirectCommands = "sodium".equals(info.getLocation().getNamespace());
        if (this.firstAvailableVertexBufferSlot + this.vertexBufferCount
                > MTL4ArgumentTable.MAX_BUFFERS) {
            throw new IllegalStateException("Pipeline " + info.getLocation()
                    + " needs " + this.vertexBufferCount + " vertex buffers starting at Metal slot "
                    + this.firstAvailableVertexBufferSlot + ", but only "
                    + MTL4ArgumentTable.MAX_BUFFERS + " buffer slots exist");
        }

        MTLCompareFunction depthCompareOp;
        int depthWrite;
        var depthStencilState = info.getDepthStencilState();
        if (depthStencilState == null) {
            depthCompareOp = MTLCompareFunction.Always;
            depthWrite = 0;
            this.depthBiasScaleFactor = 0.0f;
            this.depthBiasConstant = 0.0f;
        } else {
            depthCompareOp = MTLCompareFunction.from(depthStencilState.depthTest());
            depthWrite = depthStencilState.writeDepth() ? 1 : 0;
            this.depthBiasScaleFactor = depthStencilState.depthBiasScaleFactor();
            this.depthBiasConstant = depthStencilState.depthBiasConstant();
        }

        this.depthStencilState = device.depthStencilState(depthCompareOp, depthWrite != 0);

        var colorTarget = info.getColorTargetState();
        MTLPixelFormat colorFormat = colorTarget != null ? MTLPixelFormat.from(colorTarget.format()) : MTLPixelFormat.RGBA8Unorm;

        try (MTLVertexDescriptor vertexDescriptor = buildVertexDescriptor(info, this.firstAvailableVertexBufferSlot)) {
            MTL4Compiler compiler = device.metal4Compiler();
            if (compiler != null) {
                MemorySegment vertexLibrary = device.getOrCompileLibrary(vertexMsl);
                MemorySegment fragmentLibrary = device.getOrCompileLibrary(fragmentMsl);
                MemorySegment pipeline = createMetal4Pipeline(
                        device,
                        compiler,
                        info,
                        vertexLibrary,
                        vertexEntryPoint,
                        fragmentLibrary,
                        fragmentEntryPoint,
                        vertexDescriptor,
                        colorFormat,
                        this.topology,
                        requestIndirectCommands,
                        pipelineCacheKey
                );
                if (ObjC.isNil(pipeline) && requestIndirectCommands) {
                    Metallum.LOGGER.warn("[metallum] Pipeline {} is not ICB-compatible; using direct draws",
                            info.getLocation());
                    requestIndirectCommands = false;
                    pipeline = createMetal4Pipeline(
                            device,
                            compiler,
                            info,
                            vertexLibrary,
                            vertexEntryPoint,
                            fragmentLibrary,
                            fragmentEntryPoint,
                            vertexDescriptor,
                            colorFormat,
                            this.topology,
                            false,
                            pipelineCacheKey
                    );
                }
                this.withDepthPipeline = pipeline;
                this.withoutDepthPipeline = this.withDepthPipeline;
                this.supportsIndirectCommands = requestIndirectCommands && !ObjC.isNil(pipeline);
            } else {
                MemorySegment vertexFunction = device.getOrCompileFunction(vertexMsl, vertexEntryPoint);
                MemorySegment fragmentFunction = device.getOrCompileFunction(fragmentMsl, fragmentEntryPoint);
                MemorySegment withDepth = createPipeline(device, info, vertexFunction, fragmentFunction,
                        vertexDescriptor, colorFormat, MTLPixelFormat.Depth32Float,
                        requestIndirectCommands, pipelineCacheKey);
                if (ObjC.isNil(withDepth) && requestIndirectCommands) {
                    Metallum.LOGGER.warn("[metallum] Pipeline {} is not ICB-compatible; using direct draws",
                            info.getLocation());
                    requestIndirectCommands = false;
                    withDepth = createPipeline(device, info, vertexFunction, fragmentFunction,
                            vertexDescriptor, colorFormat, MTLPixelFormat.Depth32Float,
                            false, pipelineCacheKey);
                }

                MemorySegment withoutDepth = MemorySegment.NULL;
                if (depthStencilState == null) {
                    withoutDepth = createPipeline(device, info, vertexFunction, fragmentFunction,
                            vertexDescriptor, colorFormat, MTLPixelFormat.Invalid,
                            requestIndirectCommands, pipelineCacheKey);
                    if (ObjC.isNil(withoutDepth) && requestIndirectCommands) {
                        requestIndirectCommands = false;
                        withoutDepth = createPipeline(device, info, vertexFunction, fragmentFunction,
                                vertexDescriptor, colorFormat, MTLPixelFormat.Invalid,
                                false, pipelineCacheKey);
                    }
                }
                this.withDepthPipeline = withDepth;
                this.withoutDepthPipeline = withoutDepth;
                this.supportsIndirectCommands = requestIndirectCommands
                        && !ObjC.isNil(withDepth)
                        && (depthStencilState != null || !ObjC.isNil(withoutDepth));
            }
        }
    }

    private static MemorySegment createMetal4Pipeline(
            final MetalDevice device,
            final MTL4Compiler compiler,
            final RenderPipeline info,
            final MemorySegment vertexLibrary,
            final String vertexEntryPoint,
            final MemorySegment fragmentLibrary,
            final String fragmentEntryPoint,
            final MTLVertexDescriptor vertexDescriptor,
            final MTLPixelFormat colorFormat,
            final MTLPrimitiveType topology,
            final boolean supportsIndirectCommands,
            final String pipelineCacheKey
    ) {
        if (ObjC.isNil(vertexLibrary) || ObjC.isNil(fragmentLibrary)) {
            return MemorySegment.NULL;
        }

        ColorTargetState colorTarget = info.getColorTargetState();
        Optional<BlendFunction> blendFunction = colorTarget == null ? Optional.empty() : colorTarget.blendFunction();
        long writeMask = colorTarget == null ? MTLColorWriteMask.All.value : MTLColorWriteMask.from(colorTarget.writeMask());

        try (MTL4RenderPipelineDescriptor descriptor = new MTL4RenderPipelineDescriptor()) {
            descriptor.setLabel(info.getLocation() + " [" + pipelineCacheKey + "]");
            descriptor.setFunctions(
                    vertexLibrary,
                    vertexEntryPoint,
                    fragmentLibrary,
                    fragmentEntryPoint
            );
            descriptor.setVertexDescriptor(vertexDescriptor);
            descriptor.setColorAttachmentFormat(0, colorFormat.value);
            descriptor.setInputPrimitiveTopology(topologyClass(topology));
            descriptor.setSupportIndirectCommandBuffers(supportsIndirectCommands);

            if (blendFunction.isPresent()) {
                BlendFunction function = blendFunction.get();
                descriptor.setBlendState(
                        0,
                        MTLBlendFactor.from(function.color().sourceFactor()),
                        MTLBlendFactor.from(function.color().destFactor()),
                        MTLBlendOperation.from(function.color().op()),
                        MTLBlendFactor.from(function.alpha().sourceFactor()),
                        MTLBlendFactor.from(function.alpha().destFactor()),
                        MTLBlendOperation.from(function.alpha().op()),
                        writeMask
                );
            } else {
                descriptor.disableBlending(0, writeMask);
            }

            MemorySegment pipeline = compiler.newRenderPipelineState(descriptor);
            if (ObjC.isNil(pipeline)) {
                Metallum.LOGGER.error("[metallum] Metal 4 pipeline {} failed to build", info.getLocation());
            } else {
                device.trackAllocation(pipeline);
            }
            return pipeline;
        }
    }

    private static MemorySegment createPipeline(
            final MetalDevice device,
            final RenderPipeline info,
            final MemorySegment vertexFunction,
            final MemorySegment fragmentFunction,
            final MTLVertexDescriptor vertexDescriptor,
            final MTLPixelFormat colorFormat,
            final MTLPixelFormat depthFormat,
            final boolean supportsIndirectCommands,
            final String pipelineCacheKey
    ) {
        if (ObjC.isNil(vertexFunction) || ObjC.isNil(fragmentFunction)) {
            return MemorySegment.NULL;
        }

        ColorTargetState colorTarget = info.getColorTargetState();
        Optional<BlendFunction> blendFunction = colorTarget == null ? Optional.empty() : colorTarget.blendFunction();
        long writeMask = colorTarget == null ? MTLColorWriteMask.All.value : MTLColorWriteMask.from(colorTarget.writeMask());

        try (MTLRenderPipelineDescriptor pipelineDesc = new MTLRenderPipelineDescriptor()) {
            pipelineDesc.setLabel(info.getLocation() + " [" + pipelineCacheKey + "]");
            pipelineDesc.setCompiledFunctions(vertexFunction, fragmentFunction);
            pipelineDesc.setVertexDescriptor(vertexDescriptor);
            pipelineDesc.setColorAttachmentFormat(0, colorFormat);
            pipelineDesc.setDepthStencilFormats(depthFormat, MTLPixelFormat.Invalid);
            pipelineDesc.setSupportIndirectCommandBuffers(supportsIndirectCommands);
            MTLBinaryArchive archive = device.metal3Archive();
            if (archive != null) {
                pipelineDesc.setBinaryArchive(archive.handle());
                archive.addRenderPipeline(pipelineDesc);
            }

            if (blendFunction.isPresent()) {
                var function = blendFunction.get();
                pipelineDesc.setBlendState(
                        0,
                        MTLBlendFactor.from(function.color().sourceFactor()),
                        MTLBlendFactor.from(function.color().destFactor()),
                        MTLBlendOperation.from(function.color().op()),
                        MTLBlendFactor.from(function.alpha().sourceFactor()),
                        MTLBlendFactor.from(function.alpha().destFactor()),
                        MTLBlendOperation.from(function.alpha().op()),
                        writeMask
                );
            } else {
                pipelineDesc.disableBlending(0, writeMask);
            }

            MemorySegment pipeline = device.metalDevice().newRenderPipelineStateAsync(pipelineDesc);
            if (ObjC.isNil(pipeline)) {
                Metallum.LOGGER.error("[metallum] Pipeline {} failed to build with depth format {}", info.getLocation(), depthFormat);
            } else if (device.usesMetal4CommandPath()) {
                device.trackAllocation(pipeline);
            }
            return pipeline;
        }
    }

    @Override
    public boolean isValid() {
        return !ObjC.isNil(this.withDepthPipeline);
    }

    List<ResourceBinding> resources() {
        return this.resources;
    }

    long allResourceMask() {
        return this.allResourceMask;
    }

    boolean supportsIndirectCommands() {
        return this.supportsIndirectCommands;
    }

    @Nullable
    ResourceBinding resource(final String name) {
        return this.resourcesByName.get(name);
    }

    int resourceSlot(final String name) {
        return this.resourceSlotsByName.getOrDefault(name, -1);
    }

    int firstAvailableVertexBufferSlot() {
        return this.firstAvailableVertexBufferSlot;
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

    MemorySegment getNativePipeline(final boolean useDepth) {
        return useDepth ? this.withDepthPipeline : this.withoutDepthPipeline;
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

    int vertexBufferCount() {
        return this.vertexBufferCount;
    }

    private static MTLVertexDescriptor buildVertexDescriptor(
            final RenderPipeline pipeline,
            final int firstMetalVertexBufferSlot
    ) {
        VertexFormat[] bindings = pipeline.getVertexFormatBindings();
        MTLVertexDescriptor vertexDesc = new MTLVertexDescriptor();
        long attrIndex = 0;

        for (int i = 0; i < bindings.length; i++) {
            VertexFormat binding = bindings[i];
            if (binding == null || binding.getElements().isEmpty()) {
                continue;
            }

            int metalSlot = firstMetalVertexBufferSlot + i;

            long stride = binding.getVertexSize();
            long stepRate = binding.getStepRate();
            MTLVertexStepFunction stepFunction = stepRate > 0 ? MTLVertexStepFunction.PerInstance : MTLVertexStepFunction.PerVertex;
            vertexDesc.setLayout(metalSlot, stride, stepFunction, stepRate > 0 ? stepRate : 1);

            for (VertexFormatElement element : binding.getElements()) {
                MTLVertexFormat format = MTLVertexFormat.from(element.format());
                if (format == MTLVertexFormat.Invalid) {
                    throw new IllegalStateException("Unsupported vertex attribute format: " + element.format());
                }
                vertexDesc.setAttribute(attrIndex, format.value, element.offset(), metalSlot);
                attrIndex++;
            }
        }

        return vertexDesc;
    }

    private static int firstAvailableVertexBufferSlot(final List<ResourceBinding> resources) {
        int maxVertexBufferBinding = -1;
        for (ResourceBinding resource : resources) {
            if (resource.kind() == ResourceKind.UNIFORM_BUFFER && (resource.stageMask() & STAGE_VERTEX) != 0) {
                maxVertexBufferBinding = Math.max(maxVertexBufferBinding, resource.bindingIndex());
            }
        }
        return maxVertexBufferBinding + 1;
    }

    private static void validateBindingIndex(
            final RenderPipeline pipeline,
            final ResourceBinding binding
    ) {
        int limit = switch (binding.kind()) {
            case UNIFORM_BUFFER -> MTL4ArgumentTable.MAX_BUFFERS;
            case TEXEL_BUFFER -> MTL4ArgumentTable.MAX_TEXTURES;
            case SAMPLED_IMAGE -> Math.min(
                    MTL4ArgumentTable.MAX_TEXTURES,
                    MTL4ArgumentTable.MAX_SAMPLERS
            );
        };
        if (binding.bindingIndex() < 0 || binding.bindingIndex() >= limit) {
            throw new IllegalStateException("Pipeline " + pipeline.getLocation() + " "
                    + binding.kind().name().toLowerCase(java.util.Locale.ROOT) + " binding "
                    + binding.name() + " uses index " + binding.bindingIndex()
                    + ", but Metal allows indices below " + limit);
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
        if (!ObjC.isNil(this.withDepthPipeline)) {
            releasePipeline(this.withDepthPipeline);
        }
        if (!ObjC.isNil(this.withoutDepthPipeline)
                && this.withoutDepthPipeline.address() != this.withDepthPipeline.address()) {
            releasePipeline(this.withoutDepthPipeline);
        }
    }

    private void releasePipeline(final MemorySegment pipeline) {
        if (this.device.usesMetal4CommandPath()) {
            this.device.queueAllocationRelease(pipeline);
        } else {
            this.device.queueResourceRelease(pipeline);
        }
    }
}
