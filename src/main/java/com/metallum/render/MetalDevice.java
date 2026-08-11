package com.metallum.render;

import com.metallum.Metallum;
import com.metallum.mtl.*;
import com.metallum.objc.AutoreleasePool;
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
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
final class MetalDevice implements GpuDeviceBackend {
    private static final Pattern BLOCK_COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENTS = Pattern.compile("(?m)//[^\\n]*");
    private static final AtomicInteger COMPILER_THREAD_IDS = new AtomicInteger();
    private final MemorySegment metalDeviceHandle;
    private final MTLDevice metalDevice;
    private final long maxBufferLength;
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
    @Nullable
    private final MTLBinaryArchive metal3Archive;
    private final PipelineCacheIdentity pipelineCacheIdentity;
    private final boolean concurrentCompilationEnabled;
    private final ExecutorService compilationExecutor;
    private final ThreadLocal<CacheGeneration> compilationTarget = new ThreadLocal<>();
    private final Object compilationBatchLock = new Object();
    public final MTLCommandQueue commandQueue;
    private volatile CacheGeneration activeGeneration = new CacheGeneration();
    @Nullable
    private CompilationBatch compilationBatch;
    private boolean recoverFromFailedBatch;
    private boolean closing;
    private final Map<Long, MemorySegment> depthStencilStates = new ConcurrentHashMap<>();
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
        this.maxBufferLength = this.metalDevice.maxBufferLength();
        this.metalLayer = metalLayer;
        this.cocoa = cocoa;
        this.metal4Available = this.metalDevice.supportsMetal4();
        this.forceMetal3 = Boolean.getBoolean("metallum.forceMetal3");

        ExecutorService selectedExecutor = null;
        MTLResidencySet selectedResidencySet = null;
        MTLCommandQueue selectedQueue = null;
        MTL4Compiler selectedCompiler = null;
        MTLBinaryArchive selectedArchive = null;
        MetalCommandEncoder selectedEncoder = null;
        boolean residencyAttached = false;
        boolean builtinsInitialized = false;
        try {
            this.concurrentCompilationEnabled = this.metalDevice.enableConcurrentCompilation();
            selectedExecutor = newCompilationExecutor();
            this.compilationExecutor = selectedExecutor;
            selectedResidencySet = MTLResidencySet.create(
                    this.metalDevice,
                    4096L,
                    this.useLabels() ? "Metallum live resources" : null
            );
            this.residencySet = selectedResidencySet;
            MTLCommandQueue.setDebugLabelsEnabled(this.useLabels());
            selectedQueue = this.metalDevice.newCommandQueue(
                    this.metal4Available && !this.forceMetal3 && selectedResidencySet != null
            );
            PipelineCacheIdentity selectedCacheIdentity = PipelineCacheIdentity.create(
                    this.metalDevice, selectedQueue.isMetal4());
            selectedCompiler = selectedQueue.isMetal4()
                    ? MTL4Compiler.create(
                    this.metalDevice,
                    this.useLabels() ? "Metallum shader compiler" : null,
                    selectedCacheIdentity.archivePath()
            )
                    : null;
            if (selectedQueue.isMetal4() && selectedCompiler == null) {
                Metallum.LOGGER.warn("[metallum] Metal 4 compiler is unavailable; falling back to the complete Metal 3 path");
                selectedQueue.close();
                selectedQueue = this.metalDevice.newCommandQueue(false);
                selectedCacheIdentity = PipelineCacheIdentity.create(this.metalDevice, false);
            }
            this.commandQueue = selectedQueue;
            this.metal4CommandPath = selectedQueue.isMetal4();
            this.metal4Compiler = selectedCompiler;
            this.pipelineCacheIdentity = selectedCacheIdentity;
            selectedArchive = this.metal4CommandPath
                    ? null
                    : MTLBinaryArchive.create(this.metalDevice, selectedCacheIdentity.archivePath());
            this.metal3Archive = selectedArchive;
            if (selectedResidencySet != null) {
                selectedQueue.addResidencySet(selectedResidencySet);
                residencyAttached = true;
                selectedQueue.useUnretainedCommandBuffers(true);
            }
            MTLBuiltinPipelines.init(
                    this.metalDevice,
                    this.metal4CommandPath ? selectedResidencySet : null,
                    selectedCompiler,
                    selectedArchive
            );
            builtinsInitialized = true;
            selectedEncoder = new MetalCommandEncoder(this);
            this.commandEncoder = selectedEncoder;
            this.deviceInfo = buildDeviceInfo(deviceName);
        } catch (Throwable throwable) {
            if (selectedEncoder != null) {
                try {
                    selectedEncoder.close();
                } catch (Throwable cleanupFailure) {
                    throwable.addSuppressed(cleanupFailure);
                }
            }
            if (builtinsInitialized) {
                try {
                    MTLBuiltinPipelines.close();
                } catch (Throwable cleanupFailure) {
                    throwable.addSuppressed(cleanupFailure);
                }
            }
            closeAfterFailedInitialization(selectedArchive, throwable);
            closeAfterFailedInitialization(selectedCompiler, throwable);
            if (residencyAttached && selectedQueue != null && selectedResidencySet != null) {
                try {
                    selectedQueue.removeResidencySet(selectedResidencySet);
                } catch (Throwable cleanupFailure) {
                    throwable.addSuppressed(cleanupFailure);
                }
            }
            closeAfterFailedInitialization(selectedResidencySet, throwable);
            closeAfterFailedInitialization(selectedQueue, throwable);
            if (selectedExecutor != null) {
                selectedExecutor.shutdownNow();
            }
            throw throwable;
        }
    }

    private static void closeAfterFailedInitialization(
            @Nullable final AutoCloseable resource,
            final Throwable failure
    ) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Throwable cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
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
        MetalResourceValidation.texture(usage, width, height, depthOrLayers, mipLevels);
        if (format == GpuFormat.D24_UNORM_S8_UINT
                && !this.metalDevice.isDepth24Stencil8PixelFormatSupported()) {
            throw new IllegalArgumentException(
                    "This Metal device does not support D24_UNORM_S8_UINT; use D32_FLOAT_S8_UINT");
        }
        return new MetalGpuTexture(this, usage, label == null ? "" : label, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public @NonNull GpuTextureView createTextureView(final @NonNull GpuTexture texture) {
        return this.createTextureView(texture, 0, texture.getMipLevels());
    }

    @Override
    public @NonNull GpuTextureView createTextureView(final @NonNull GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        if (!(texture instanceof MetalGpuTexture metalTexture)) {
            throw new IllegalArgumentException("Metal device cannot create a view of a foreign texture");
        }
        if (!metalTexture.belongsTo(this)) {
            throw new IllegalArgumentException("Cannot create a texture view for another Metal device");
        }
        if (metalTexture.isClosed()) {
            throw new IllegalStateException("Cannot create a view of a closed texture");
        }
        MetalResourceValidation.textureView(texture.getMipLevels(), baseMipLevel, mipLevels);
        return new MetalGpuTextureView(texture, baseMipLevel, mipLevels);
    }

    @Override
    public @NonNull GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final long size) {
        long allocationSize = MetalResourceValidation.bufferAllocationSize(usage, size, this.maxBufferLength);
        return new MetalGpuBuffer(this, usage, size, allocationSize);
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
        CompilationBatch batch;
        CacheGeneration directGeneration = null;
        synchronized (this.compilationBatchLock) {
            batch = this.compilationBatch;
            if (batch != null) {
                CompletableFuture<MetalCompiledRenderPipeline> future = batch.schedule(
                        pipeline,
                        () -> compileAndCache(batch.generation, pipeline, effectiveSource));
                boolean finish = batch.scheduledCount() == batch.expectedCount;
                if (!finish) {
                    return new BatchCompiledPipeline(batch, future);
                }
                // Finish outside the lock below.
                batch.lastScheduledFuture = future;
            } else {
                directGeneration = this.activeGeneration;
                directGeneration.retain();
            }
        }
        if (batch != null) {
            finishCompilationBatch(batch);
            return new BatchCompiledPipeline(batch, batch.lastScheduledFuture);
        }

        try {
            return compileAndCache(directGeneration, pipeline, effectiveSource);
        } finally {
            directGeneration.release();
        }
    }

    @Override
    public void clearPipelineCache() {
        this.waitForSubmittedGpuWork();
        CacheGeneration emptyReplacement = null;
        synchronized (this.compilationBatchLock) {
            if (this.closing) {
                return;
            }
            if (this.recoverFromFailedBatch) {
                // ShaderManager immediately calls clear again before loading its
                // critical fallback shaders. Keep the last good generation live.
                this.recoverFromFailedBatch = false;
                return;
            }
            if (this.compilationBatch != null) {
                throw new IllegalStateException("Pipeline cache reload already in progress");
            }
            int expected = new HashSet<>(RenderPipelines.getStaticPipelines()).size();
            if (expected == 0) {
                emptyReplacement = this.activeGeneration;
                this.activeGeneration = new CacheGeneration();
            } else {
                this.compilationBatch = new CompilationBatch(new CacheGeneration(), expected);
            }
        }
        if (emptyReplacement != null) {
            emptyReplacement.retire();
        }
    }

    @Override
    public void close() {
        synchronized (this.compilationBatchLock) {
            if (this.closing) {
                return;
            }
            this.closing = true;
        }
        try {
            this.waitForSubmittedGpuWork();
        } catch (Throwable throwable) {
            synchronized (this.compilationBatchLock) {
                this.closing = false;
            }
            throw throwable;
        }
        closePipelineCaches();
        try {
            this.cocoa.clearViewLayer();
        } catch (Throwable ignored) {
        }
        this.metalLayer.close();
        MTLBuiltinPipelines.close();
        if (this.metal4Compiler != null) {
            this.metal4Compiler.close();
        }
        if (this.metal3Archive != null) {
            this.metal3Archive.close();
        }
        this.pipelineCacheIdentity.flushManifest();
        for (MemorySegment state : depthStencilStates.values()) {
            ObjC.release(state);
        }
        depthStencilStates.clear();
        this.commandEncoder.close();
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
        return depthStencilStates.computeIfAbsent(key, _ -> {
            try (MTLDepthStencilDescriptor descriptor = MTLDepthStencilDescriptor.create()) {
                descriptor.depthCompareFunction(compareFunction);
                descriptor.depthWriteEnabled(writeDepth);
                return metalDevice.newDepthStencilState(descriptor);
            }
        });
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

    void discardPendingClears(final MetalGpuTexture texture) {
        this.commandEncoder.discardPendingClears(texture);
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

    @Nullable
    MTLBinaryArchive metal3Archive() {
        return this.metal3Archive;
    }

    String recordPipelineCacheKey(
            final RenderPipeline pipeline,
            final String vertexMsl,
            final String fragmentMsl
    ) {
        return this.pipelineCacheIdentity.recordPipeline(pipeline, vertexMsl, fragmentMsl);
    }

    void flushPipelineArchive() {
        if (this.metal4Compiler != null) {
            this.metal4Compiler.flushArchive();
        }
        if (this.metal3Archive != null) {
            this.metal3Archive.flush();
        }
        this.pipelineCacheIdentity.flushManifest();
    }

    MetalCompiledRenderPipeline getOrCompilePipeline(final RenderPipeline pipeline) {
        CacheGeneration generation;
        CompletableFuture<MetalCompiledRenderPipeline> pending = null;
        synchronized (this.compilationBatchLock) {
            CompilationBatch batch = this.compilationBatch;
            if (batch != null) {
                pending = batch.future(pipeline);
            }
            if (pending == null) {
                generation = this.activeGeneration;
                generation.retain();
            } else {
                generation = null;
            }
        }
        if (pending != null) {
            return joinCompiledPipeline(pipeline, pending);
        }
        try {
            MetalCompiledRenderPipeline cached = generation.compiledPipelines.get(pipeline);
            if (cached != null) {
                Stats.increment(Stats.Counter.PIPELINE_CACHE_HITS);
                return cached;
            }
            return compileAndCache(generation, pipeline, this.defaultShaderSource);
        } finally {
            generation.release();
        }
    }

    CachedShaderModule getOrCompileShader(final Identifier id, final ShaderType type, final ShaderDefines defines, final ShaderSource shaderSource) {
        CacheGeneration generation = cacheGeneration();
        ShaderCompilationKey key = new ShaderCompilationKey(id, type, defines);
        CachedShaderModule cached = generation.shaderCache.get(key);
        if (cached != null) {
            Stats.increment(Stats.Counter.SHADER_CACHE_HITS);
            return cached;
        }
        CachedShaderModule compiled = compileShader(key, shaderSource);
        CachedShaderModule raced = generation.shaderCache.putIfAbsent(key, compiled);
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
        CacheGeneration generation = cacheGeneration();
        return generation.functionCache.computeIfAbsent(
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
        CacheGeneration generation = cacheGeneration();
        return generation.libraryCache.computeIfAbsent(
                msl,
                source -> this.metal4Compiler == null
                        ? this.metalDevice.newLibrary(source)
                        : this.metal4Compiler.newLibrary(source)
        );
    }

    private ExecutorService newCompilationExecutor() {
        int driverThreads = this.metalDevice.maximumConcurrentCompilationTaskCount();
        int practicalLimit = driverThreads > 0 ? Math.min(16, driverThreads) : 16;
        int defaultThreads = Math.max(1, Math.min(
                Math.min(4, practicalLimit),
                Runtime.getRuntime().availableProcessors() / 2));
        int requestedThreads = Integer.getInteger(
                "metallum.shaderCompileThreads", defaultThreads);
        int threads = Math.max(1, Math.min(practicalLimit, requestedThreads));
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "Metallum shader compiler " + COMPILER_THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        });
    }

    private CacheGeneration cacheGeneration() {
        CacheGeneration target = this.compilationTarget.get();
        return target == null ? this.activeGeneration : target;
    }

    private MetalCompiledRenderPipeline compileAndCache(
            final CacheGeneration generation,
            final RenderPipeline pipeline,
            final ShaderSource shaderSource
    ) {
        MetalCompiledRenderPipeline cached = generation.compiledPipelines.get(pipeline);
        if (cached != null) {
            Stats.increment(Stats.Counter.PIPELINE_CACHE_HITS);
            return cached;
        }

        CacheGeneration previous = this.compilationTarget.get();
        this.compilationTarget.set(generation);
        MetalCompiledRenderPipeline compiled;
        try (AutoreleasePool ignored = AutoreleasePool.push()) {
            compiled = MetalCrossShaderCompiler.compile(this, pipeline, shaderSource);
        } finally {
            if (previous == null) {
                this.compilationTarget.remove();
            } else {
                this.compilationTarget.set(previous);
            }
        }
        MetalCompiledRenderPipeline raced = generation.compiledPipelines.putIfAbsent(pipeline, compiled);
        if (raced != null) {
            compiled.close();
            Stats.increment(Stats.Counter.PIPELINE_CACHE_HITS);
            return raced;
        }
        Stats.increment(Stats.Counter.PIPELINES_COMPILED);
        return compiled;
    }

    private void finishCompilationBatch(final CompilationBatch batch) {
        boolean failed = false;
        for (Map.Entry<RenderPipeline, CompletableFuture<MetalCompiledRenderPipeline>> entry
                : batch.futures.entrySet()) {
            try {
                MetalCompiledRenderPipeline pipeline = entry.getValue().join();
                failed |= pipeline == null || !pipeline.isValid();
            } catch (CompletionException exception) {
                failed = true;
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                Metallum.LOGGER.error("[metallum] Failed to compile pipeline {}",
                        entry.getKey().getLocation(), cause);
            }
        }

        CacheGeneration previous = null;
        synchronized (this.compilationBatchLock) {
            if (this.compilationBatch != batch) {
                return;
            }
            batch.failed = failed;
            batch.completed = true;
            this.compilationBatch = null;
            if (failed) {
                this.recoverFromFailedBatch = true;
            } else {
                previous = this.activeGeneration;
                this.activeGeneration = batch.generation;
            }
        }

        if (failed) {
            batch.generation.retire();
        } else {
            if (previous != null) {
                previous.retire();
            }
            flushPipelineArchive();
        }
    }

    private static MetalCompiledRenderPipeline joinCompiledPipeline(
            final RenderPipeline pipeline,
            final CompletableFuture<MetalCompiledRenderPipeline> future
    ) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException(
                    "Failed to compile pipeline " + pipeline.getLocation(), cause);
        }
    }

    private void closePipelineCaches() {
        CompilationBatch pending;
        CacheGeneration active;
        synchronized (this.compilationBatchLock) {
            pending = this.compilationBatch;
            this.compilationBatch = null;
            active = this.activeGeneration;
            this.activeGeneration = new CacheGeneration();
        }
        if (pending != null) {
            for (CompletableFuture<MetalCompiledRenderPipeline> future : pending.futures.values()) {
                try {
                    future.join();
                } catch (CompletionException ignored) {
                }
            }
            pending.generation.retire();
        }
        active.retire();
        this.compilationExecutor.shutdown();
    }

    private final class CompilationBatch {
        private final CacheGeneration generation;
        private final int expectedCount;
        private final Map<RenderPipeline, CompletableFuture<MetalCompiledRenderPipeline>> futures =
                new ConcurrentHashMap<>();
        private volatile boolean completed;
        private volatile boolean failed;
        private CompletableFuture<MetalCompiledRenderPipeline> lastScheduledFuture;

        private CompilationBatch(final CacheGeneration generation, final int expectedCount) {
            this.generation = generation;
            this.expectedCount = expectedCount;
        }

        private CompletableFuture<MetalCompiledRenderPipeline> schedule(
                final RenderPipeline pipeline,
                final Callable<MetalCompiledRenderPipeline> compile
        ) {
            return this.futures.computeIfAbsent(pipeline, _ -> CompletableFuture.supplyAsync(() -> {
                try {
                    return compile.call();
                } catch (RuntimeException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            }, compilationExecutor));
        }

        @Nullable
        private CompletableFuture<MetalCompiledRenderPipeline> future(final RenderPipeline pipeline) {
            return this.futures.get(pipeline);
        }

        private int scheduledCount() {
            return this.futures.size();
        }
    }

    private static final class BatchCompiledPipeline implements CompiledRenderPipeline {
        private final CompilationBatch batch;
        private final CompletableFuture<MetalCompiledRenderPipeline> future;

        private BatchCompiledPipeline(
                final CompilationBatch batch,
                final CompletableFuture<MetalCompiledRenderPipeline> future
        ) {
            this.batch = batch;
            this.future = future;
        }

        @Override
        public boolean isValid() {
            if (!this.batch.completed && !this.future.isDone()) {
                return true;
            }
            if (this.batch.failed || this.future.isCompletedExceptionally()) {
                return false;
            }
            MetalCompiledRenderPipeline pipeline = this.future.getNow(null);
            return pipeline != null && pipeline.isValid();
        }
    }

    private final class CacheGeneration implements AutoCloseable {
        private final Map<RenderPipeline, MetalCompiledRenderPipeline> compiledPipelines =
                new ConcurrentHashMap<>();
        private final Map<ShaderCompilationKey, CachedShaderModule> shaderCache =
                new ConcurrentHashMap<>();
        private final Map<String, MemorySegment> libraryCache = new ConcurrentHashMap<>();
        private final Map<MslFunctionKey, MemorySegment> functionCache = new ConcurrentHashMap<>();
        private int users;
        private boolean retired;
        private boolean resourcesClosed;

        private synchronized void retain() {
            if (this.retired) {
                throw new IllegalStateException("Pipeline cache generation is retired");
            }
            this.users++;
        }

        private void release() {
            boolean closeResources;
            synchronized (this) {
                if (this.users <= 0) {
                    throw new IllegalStateException("Pipeline cache generation retain imbalance");
                }
                this.users--;
                closeResources = this.retired && this.users == 0 && !this.resourcesClosed;
                if (closeResources) {
                    this.resourcesClosed = true;
                }
            }
            if (closeResources) {
                closeResources();
            }
        }

        private void retire() {
            boolean closeResources;
            synchronized (this) {
                if (this.retired) {
                    return;
                }
                this.retired = true;
                closeResources = this.users == 0;
                if (closeResources) {
                    this.resourcesClosed = true;
                }
            }
            if (closeResources) {
                closeResources();
            }
        }

        @Override
        public void close() {
            retire();
        }

        private void closeResources() {
            this.compiledPipelines.values().forEach(MetalCompiledRenderPipeline::close);
            this.compiledPipelines.clear();
            this.shaderCache.values().forEach(CachedShaderModule::close);
            this.shaderCache.clear();
            this.functionCache.values().forEach(MetalDevice::releaseIfPresent);
            this.functionCache.clear();
            this.libraryCache.values().forEach(MetalDevice::releaseIfPresent);
            this.libraryCache.clear();
        }
    }

    private static void releaseIfPresent(final MemorySegment handle) {
        if (!ObjC.isNil(handle)) {
            ObjC.release(handle);
        }
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
        long maxMemoryAllocationSize = Math.min(this.maxBufferLength, metalDevice.recommendedMaxWorkingSetSize());
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
