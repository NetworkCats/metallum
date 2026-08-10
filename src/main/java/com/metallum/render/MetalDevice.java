package com.metallum.render;

import com.metallum.Metallum;
import com.metallum.mtl.*;
import com.metallum.objc.Cocoa;
import com.metallum.objc.ObjC;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
final class MetalDevice implements GpuDeviceBackend {
    private static final Pattern BLOCK_COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENTS = Pattern.compile("(?m)//[^\\n]*");
    private final MemorySegment metalDeviceHandle;
    private final MTLDevice metalDevice;
    private final CAMetalLayer metalLayer;
    private final Cocoa cocoa;
    private final GpuDebugOptions debugOptions;
    private final MetalCommandEncoder commandEncoder;
    private final DeviceInfo deviceInfo;
    @Nullable
    private final MTLResidencySet residencySet;
    private final boolean metal4Available;
    private final boolean forceMetal3;
    private final boolean metal4CommandPath;
    @Nullable
    private final MTL4Compiler metal4Compiler;
    private final boolean concurrentCompilationEnabled;
    public final MTLCommandQueue commandQueue;
    private final Map<RenderPipeline, MetalCompiledRenderPipeline> compiledPipelines = new ConcurrentHashMap<>();
    private final Map<ShaderCompilationKey, CachedShaderModule> shaderCache = new ConcurrentHashMap<>();
    private final Map<String, MemorySegment> libraryCache = new ConcurrentHashMap<>();
    private final Map<MslFunctionKey, MemorySegment> functionCache = new ConcurrentHashMap<>();
    private final Map<Long, MemorySegment> depthStencilStates = new HashMap<>();
    private final ArrayDeque<String> debugMessages = new ArrayDeque<>();
    private final ShaderSource defaultShaderSource;

    MetalDevice(
            final ShaderSource defaultShaderSource,
            final GpuDebugOptions debugOptions,
            final MemorySegment metalDeviceHandle,
            final CAMetalLayer metalLayer,
            final String deviceName,
            final Cocoa cocoa
    ) {
        this.defaultShaderSource = defaultShaderSource;
        this.debugOptions = debugOptions;
        this.metalDeviceHandle = metalDeviceHandle;
        this.metalDevice = new MTLDevice(metalDeviceHandle);
        this.metalLayer = metalLayer;
        this.cocoa = cocoa;
        this.metal4Available = this.metalDevice.supportsMetal4();
        this.forceMetal3 = Boolean.getBoolean("metallum.forceMetal3");
        this.concurrentCompilationEnabled = this.metalDevice.enableConcurrentCompilation();
        this.residencySet = MTLResidencySet.create(
                this.metalDevice,
                4096L,
                this.useLabels() ? "Metallum live resources" : null
        );
        MTLCommandQueue.setDebugLabelsEnabled(this.useLabels());
        MTLCommandQueue selectedQueue = this.metalDevice.newCommandQueue(
                this.metal4Available && !this.forceMetal3 && this.residencySet != null
        );
        MTL4Compiler selectedCompiler = selectedQueue.isMetal4()
                ? MTL4Compiler.create(
                this.metalDevice,
                this.useLabels() ? "Metallum shader compiler" : null
        )
                : null;
        if (selectedQueue.isMetal4() && selectedCompiler == null) {
            Metallum.LOGGER.warn("[metallum] Metal 4 compiler is unavailable; falling back to the complete Metal 3 path");
            selectedQueue.close();
            selectedQueue = this.metalDevice.newCommandQueue(false);
        }
        this.commandQueue = selectedQueue;
        this.metal4CommandPath = selectedQueue.isMetal4();
        this.metal4Compiler = selectedCompiler;
        if (this.residencySet != null) {
            this.commandQueue.addResidencySet(this.residencySet);
            this.commandQueue.useUnretainedCommandBuffers(true);
        }
        MTLBuiltinPipelines.init(
                this.metalDevice,
                this.metal4CommandPath ? this.residencySet : null,
                this.metal4Compiler
        );
        this.commandEncoder = new MetalCommandEncoder(this);
        this.deviceInfo = buildDeviceInfo(deviceName);
    }

    @Override
    public @NonNull GpuSurfaceBackend createSurface(final long windowHandle) {
        return new MetalSurface(this, this.metalLayer);
    }

    @Override
    public @NonNull MetalCommandEncoder createCommandEncoder() {
        return this.commandEncoder;
    }

    @Override
    public @NonNull GpuSampler createSampler(
            final @NonNull AddressMode addressModeU,
            final @NonNull AddressMode addressModeV,
            final @NonNull FilterMode minFilter,
            final @NonNull FilterMode magFilter,
            final int maxAnisotropy,
            final @NonNull OptionalDouble maxLod
    ) {
        return new MetalGpuSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public @NonNull GpuTexture createTexture(
            @Nullable final Supplier<String> label,
            @GpuTexture.Usage final int usage,
            final @NonNull GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        return this.createTexture(this.resolveDebugLabel(label), usage, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public @NonNull GpuTexture createTexture(
            @Nullable final String label,
            @GpuTexture.Usage final int usage,
            final @NonNull GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        return new MetalGpuTexture(this, usage, label == null ? "" : label, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public @NonNull GpuTextureView createTextureView(final @NonNull GpuTexture texture) {
        return this.createTextureView(texture, 0, texture.getMipLevels());
    }

    @Override
    public @NonNull GpuTextureView createTextureView(final @NonNull GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        return new MetalGpuTextureView(texture, baseMipLevel, mipLevels);
    }

    @Override
    public @NonNull GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final long size) {
        return new MetalGpuBuffer(this, usage, size);
    }

    @Override
    public @NonNull GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final ByteBuffer data) {
        MetalGpuBuffer buffer = (MetalGpuBuffer) this.createBuffer(label, usage | GpuBuffer.USAGE_COPY_DST, data.remaining());
        this.commandEncoder.writeToBuffer(buffer.slice(), data.duplicate());
        return buffer;
    }

    @Override
    public @NonNull List<String> getLastDebugMessages() {
        return List.copyOf(this.debugMessages);
    }

    @Override
    public boolean isDebuggingEnabled() {
        return this.debugOptions.logLevel() > 0 || this.debugOptions.useLabels() || this.debugOptions.useValidationLayers();
    }

    boolean useLabels() {
        return this.debugOptions.useLabels();
    }

    @Override
    public @NonNull CompiledRenderPipeline precompilePipeline(final @NonNull RenderPipeline pipeline, @Nullable final ShaderSource shaderSource) {
        ShaderSource effectiveSource = shaderSource == null ? this.defaultShaderSource : shaderSource;
        MetalCompiledRenderPipeline cached = this.compiledPipelines.get(pipeline);
        if (cached != null) {
            Stats.increment(Stats.Counter.PIPELINE_CACHE_HITS);
            return cached;
        }
        MetalCompiledRenderPipeline compiled = MetalCrossShaderCompiler.compile(this, pipeline, effectiveSource);
        MetalCompiledRenderPipeline raced = this.compiledPipelines.putIfAbsent(pipeline, compiled);
        if (raced != null) {
            compiled.close();
            Stats.increment(Stats.Counter.PIPELINE_CACHE_HITS);
            return raced;
        }
        Stats.increment(Stats.Counter.PIPELINES_COMPILED);
        return compiled;
    }

    @Override
    public void clearPipelineCache() {
        this.waitForSubmittedGpuWork();
        this.compiledPipelines.values().forEach(MetalCompiledRenderPipeline::close);
        this.compiledPipelines.clear();
        this.shaderCache.values().forEach(CachedShaderModule::close);
        this.shaderCache.clear();
        for (MemorySegment function : this.functionCache.values()) {
            if (!ObjC.isNil(function)) {
                ObjC.release(function);
            }
        }
        this.functionCache.clear();
        for (MemorySegment library : this.libraryCache.values()) {
            if (!ObjC.isNil(library)) {
                ObjC.release(library);
            }
        }
        this.libraryCache.clear();
    }

    @Override
    public void close() {
        this.waitForSubmittedGpuWork();
        this.commandEncoder.close();
        this.clearPipelineCache();
        try {
            this.cocoa.clearViewLayer();
        } catch (Throwable ignored) {
        }
        MTLBuiltinPipelines.close();
        if (this.metal4Compiler != null) {
            this.metal4Compiler.close();
        }
        for (MemorySegment state : depthStencilStates.values()) {
            ObjC.release(state);
        }
        depthStencilStates.clear();
        if (this.residencySet != null) {
            this.residencySet.commitIfNeeded();
            this.commandQueue.removeResidencySet(this.residencySet);
            this.residencySet.close();
        }
        this.commandQueue.close();
        ObjC.release(this.metalDeviceHandle);
    }

    @Override
    public @NonNull GpuQueryPool createTimestampQueryPool(final int size) {
        return new MetalGpuQueryPool(this, size);
    }

    @Override
    public long getTimestampNow() {
        return System.nanoTime();
    }

    @Override
    public @NonNull DeviceInfo getDeviceInfo() {
        return this.deviceInfo;
    }

    MemorySegment metalDeviceHandle() {
        return this.metalDeviceHandle;
    }

    MTLDevice metalDevice() {
        return this.metalDevice;
    }

    MemorySegment depthStencilState(final MTLCompareFunction compareFunction, final boolean writeDepth) {
        long key = (compareFunction.value << 1) | (writeDepth ? 1L : 0L);
        MemorySegment cached = depthStencilStates.get(key);
        if (cached != null) {
            return cached;
        }
        try (MTLDepthStencilDescriptor descriptor = MTLDepthStencilDescriptor.create()) {
            descriptor.depthCompareFunction(compareFunction);
            descriptor.depthWriteEnabled(writeDepth);
            MemorySegment state = metalDevice.newDepthStencilState(descriptor);
            depthStencilStates.put(key, state);
            return state;
        }
    }

    void waitForSubmittedGpuWork() {
        this.commandEncoder.waitForSubmittedGpuWork();
    }

    void queueResourceRelease(final MemorySegment handle) {
        this.commandEncoder.queueObjCRelease(handle);
    }

    void trackAllocation(final MemorySegment handle) {
        if (this.residencySet != null) {
            this.residencySet.addAllocation(handle);
        }
    }

    void queueAllocationRelease(final MemorySegment handle) {
        this.commandEncoder.queueAllocationRelease(this, handle);
    }

    void releaseAllocationNow(final MemorySegment handle) {
        if (this.residencySet != null) {
            this.residencySet.removeAllocationAndRelease(handle);
        } else {
            ObjC.release(handle);
        }
    }

    void commitResidencyChanges() {
        if (this.residencySet != null) {
            this.residencySet.commitIfNeeded();
        }
    }

    void recordCommandError(final String message) {
        if (this.debugMessages.size() == 64) {
            this.debugMessages.removeFirst();
        }
        this.debugMessages.addLast(message);
    }

    boolean usesExplicitResidency() {
        return this.residencySet != null;
    }

    boolean metal4Available() {
        return this.metal4Available;
    }

    boolean usesMetal4CommandPath() {
        return this.metal4CommandPath;
    }

    boolean concurrentCompilationEnabled() {
        return this.concurrentCompilationEnabled;
    }

    @Nullable
    MTL4Compiler metal4Compiler() {
        return this.metal4Compiler;
    }

    boolean usesMetal4Compiler() {
        return this.metal4Compiler != null;
    }

    MetalCompiledRenderPipeline getOrCompilePipeline(final RenderPipeline pipeline) {
        MetalCompiledRenderPipeline cached = this.compiledPipelines.get(pipeline);
        if (cached != null) {
            Stats.increment(Stats.Counter.PIPELINE_CACHE_HITS);
            return cached;
        }
        MetalCompiledRenderPipeline compiled = MetalCrossShaderCompiler.compile(
                this, pipeline, this.defaultShaderSource);
        MetalCompiledRenderPipeline raced = this.compiledPipelines.putIfAbsent(pipeline, compiled);
        if (raced != null) {
            compiled.close();
            Stats.increment(Stats.Counter.PIPELINE_CACHE_HITS);
            return raced;
        }
        Stats.increment(Stats.Counter.PIPELINES_COMPILED);
        return compiled;
    }

    CachedShaderModule getOrCompileShader(final Identifier id, final ShaderType type, final ShaderDefines defines, final ShaderSource shaderSource) {
        ShaderCompilationKey key = new ShaderCompilationKey(id, type, defines);
        CachedShaderModule cached = this.shaderCache.get(key);
        if (cached != null) {
            Stats.increment(Stats.Counter.SHADER_CACHE_HITS);
            return cached;
        }
        CachedShaderModule compiled = compileShader(key, shaderSource);
        CachedShaderModule raced = this.shaderCache.putIfAbsent(key, compiled);
        if (raced != null) {
            compiled.close();
            Stats.increment(Stats.Counter.SHADER_CACHE_HITS);
            return raced;
        }
        Stats.increment(Stats.Counter.SHADERS_COMPILED);
        return compiled;
    }

    private CachedShaderModule compileShader(
            final ShaderCompilationKey key,
            final ShaderSource shaderSource
    ) {
        ShaderCompilationKey k = key;
        String source = shaderSource.get(k.id(), k.type());
        if (source == null) {
            return CachedShaderModule.INVALID;
        }
        String sourceWithDefines = prepareShaderSource(source, k.defines());
        try (GlslCompiler glslCompiler = new GlslCompiler()) {
            return CachedShaderModule.takeOwnership(
                    glslCompiler.createIntermediary(
                            k.id().toDebugFileName(),
                            sourceWithDefines,
                            k.type()
                    )
            );
        } catch (ShaderCompileException e) {
            throw new IllegalStateException("Failed to compile shader " + k.id(), e);
        }
    }

    private static String prepareShaderSource(final String source, final ShaderDefines defines) {
        String stripped = BLOCK_COMMENTS.matcher(source).replaceAll("");
        stripped = LINE_COMMENTS.matcher(stripped).replaceAll("").stripLeading();
        return GlslPreprocessor.injectDefines(stripped, defines);
    }

    MemorySegment getOrCompileFunction(final String msl, final String entryPoint) {
        return this.functionCache.computeIfAbsent(
                new MslFunctionKey(msl, entryPoint),
                key -> {
                    MemorySegment library = getOrCompileLibrary(key.msl());
                    return ObjC.isNil(library)
                            ? MemorySegment.NULL
                            : this.metalDevice.newFunction(library, key.entryPoint());
                }
        );
    }

    MemorySegment getOrCompileLibrary(final String msl) {
        return this.libraryCache.computeIfAbsent(
                msl,
                source -> this.metal4Compiler == null
                        ? this.metalDevice.newLibrary(source)
                        : this.metal4Compiler.newLibrary(source)
        );
    }

    private record ShaderCompilationKey(Identifier id, ShaderType type, ShaderDefines defines) {
    }

    private record MslFunctionKey(String msl, String entryPoint) {
    }

    private DeviceInfo buildDeviceInfo(final String deviceName) {
        DeviceType type = DeviceType.INTEGRATED;
        Set<String> extensions = Set.of();
        String osVersion = System.getProperty("os.version", "").trim();
        String driverDescription = "macOS " + osVersion
                + (this.metal4CommandPath
                ? "; Metal 4"
                : this.metal4Available
                ? this.forceMetal3
                    ? "; Metal 4 available; Metal 3 forced by metallum.forceMetal3"
                    : "; Metal 4 available; Metal 3 command path"
                : "; Metal 3")
                + (this.residencySet != null ? "; explicit residency" : "");
        long maxMemoryAllocationSize = Math.min(metalDevice.maxBufferLength(), metalDevice.recommendedMaxWorkingSetSize());
        return new DeviceInfo(
                deviceName,
                "Apple",
                driverDescription,
                true,
                "Metal",
                this.metal4CommandPath ? 4.0F : 3.0F,
                new DeviceLimits(16, 256, 16384, maxMemoryAllocationSize, 0, 1),
                new DeviceFeatures(false, false, true, true, true, false, true),
                extensions,
                new HintsAndWorkarounds(false, false),
                type
        );
    }

    @Nullable
    private String resolveDebugLabel(@Nullable final Supplier<String> label) {
        return this.useLabels() && label != null ? label.get() : null;
    }
}
