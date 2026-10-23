package com.metallum.render;

import com.metallum.Metallum;
import com.metallum.mtl.*;
import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.ObjC;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.util.ShaderCompileException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
final class MetalDevice implements GpuDeviceBackend {
    /** Quiet period after the last pipeline compile before the native archive is persisted. */
    private static final long ARCHIVE_FLUSH_DELAY_NANOS = 5_000_000_000L;

    private final MemorySegment metalDeviceHandle;
    private final MTLDevice metalDevice;
    private final long maxBufferLength;
    private final GpuDebugOptions debugOptions;
    private final MetalCommandEncoder commandEncoder;
    private final DeviceInfo deviceInfo;
    private final MTLResidencySet residencySet;
    private final MTL4Compiler metal4Compiler;
    private final PipelineCacheIdentity pipelineCacheIdentity;
    private final boolean concurrentCompilationEnabled;
    public final MTLCommandQueue commandQueue;
    private final Map<Long, MemorySegment> depthStencilStates = new ConcurrentHashMap<>();
    private final Map<String, MemorySegment> libraryCache = new ConcurrentHashMap<>();
    private final ArrayDeque<String> debugMessages = new ArrayDeque<>();
    private volatile long lastPipelineCompileNanos;
    private volatile boolean archiveDirty;
    private boolean closed;

    MetalDevice(
            final GpuDebugOptions debugOptions,
            final MemorySegment metalDeviceHandle,
            final String deviceName
    ) {
        this.debugOptions = debugOptions;
        this.metalDeviceHandle = metalDeviceHandle;
        this.metalDevice = new MTLDevice(metalDeviceHandle);
        this.maxBufferLength = this.metalDevice.maxBufferLength();

        MTLResidencySet selectedResidencySet = null;
        MTLCommandQueue selectedQueue = null;
        MTL4Compiler selectedCompiler = null;
        MetalCommandEncoder selectedEncoder = null;
        boolean residencyAttached = false;
        boolean builtinsInitialized = false;
        try {
            this.concurrentCompilationEnabled = this.metalDevice.enableConcurrentCompilation();
            selectedResidencySet = MTLResidencySet.create(
                    this.metalDevice,
                    4096L,
                    this.useLabels() ? "Metallum live resources" : null
            );
            if (selectedResidencySet == null) {
                throw new IllegalStateException("Metal residency sets are unavailable");
            }
            this.residencySet = selectedResidencySet;
            MTLCommandQueue.setDebugLabelsEnabled(this.useLabels());
            selectedQueue = this.metalDevice.newCommandQueue();
            this.pipelineCacheIdentity = PipelineCacheIdentity.create(this.metalDevice);
            selectedCompiler = MTL4Compiler.create(
                    this.metalDevice,
                    this.useLabels() ? "Metallum shader compiler" : null,
                    this.pipelineCacheIdentity.archivePath()
            );
            this.commandQueue = selectedQueue;
            this.metal4Compiler = selectedCompiler;
            selectedQueue.addResidencySet(selectedResidencySet);
            residencyAttached = true;
            MTLBuiltinPipelines.init(this.metalDevice, selectedResidencySet, selectedCompiler);
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
            closeAfterFailedInitialization(selectedCompiler, throwable);
            if (residencyAttached) {
                try {
                    selectedQueue.removeResidencySet(selectedResidencySet);
                } catch (Throwable cleanupFailure) {
                    throwable.addSuppressed(cleanupFailure);
                }
            }
            closeAfterFailedInitialization(selectedResidencySet, throwable);
            closeAfterFailedInitialization(selectedQueue, throwable);
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
    public @NonNull GpuSurfaceBackend createSurface(final long windowHandle, final @NonNull BooleanSupplier isIconified) {
        return new MetalSurface(this, windowHandle);
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
        String nativeLabel = this.useLabels() && label != null ? label : "";
        return new MetalGpuTexture(this, usage, nativeLabel, format, width, height, depthOrLayers, mipLevels);
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
        synchronized (this.debugMessages) {
            return List.copyOf(this.debugMessages);
        }
    }

    @Override
    public boolean isDebuggingEnabled() {
        return this.debugOptions.logLevel() > 0 || this.debugOptions.useLabels() || this.debugOptions.useValidationLayers();
    }

    boolean useLabels() {
        return this.debugOptions.useLabels();
    }

    /**
     * Runs on the frontend's background executor: cross-compiles SPIR-V to MSL and builds the
     * native pipeline, so {@link BackendRenderPipeline.Pending#finishCompile()} only hands it over.
     */
    @Override
    public BackendRenderPipeline.@NonNull Pending compilePipeline(final BackendRenderPipeline.@NonNull CreateInfo createInfo) {
        MetalCompiledRenderPipeline pipeline;
        try (AutoreleasePool ignored = AutoreleasePool.push()) {
            MetalCrossShaderCompiler.Compiled compiled = MetalCrossShaderCompiler.compile(createInfo);
            pipeline = new MetalCompiledRenderPipeline(this, createInfo, compiled);
        } catch (ShaderCompileException | RuntimeException exception) {
            Metallum.LOGGER.error("[metallum] Failed to compile pipeline {}", createInfo.name(), exception);
            return BackendRenderPipeline.Pending.NULL;
        }
        this.lastPipelineCompileNanos = System.nanoTime();
        this.archiveDirty = true;
        if (!pipeline.isValid()) {
            pipeline.close();
            return BackendRenderPipeline.Pending.NULL;
        }
        Stats.increment(Stats.Counter.PIPELINES_COMPILED);
        return () -> pipeline;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.waitForSubmittedGpuWork();
        this.closed = true;
        MTLBuiltinPipelines.close();
        this.metal4Compiler.close();
        this.pipelineCacheIdentity.flushManifest();
        for (MemorySegment library : this.libraryCache.values()) {
            releaseIfPresent(library);
        }
        this.libraryCache.clear();
        for (MemorySegment state : depthStencilStates.values()) {
            ObjC.release(state);
        }
        depthStencilStates.clear();
        this.commandEncoder.close();
        this.residencySet.commitIfNeeded();
        this.commandQueue.removeResidencySet(this.residencySet);
        this.residencySet.close();
        this.commandQueue.close();
        ObjC.release(this.metalDeviceHandle);
    }

    @Override
    public @NonNull GpuQueryPool createTimestampQueryPool(final int size) {
        return new MetalGpuQueryPool(this, size);
    }

    /** Resolved GPU timestamps are already expressed on the {@link System#nanoTime()} timeline. */
    @Override
    public long getTimestampCalibrationOffset() {
        return 0L;
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
        this.residencySet.addAllocation(handle);
    }

    void queueAllocationRelease(final MemorySegment handle) {
        this.commandEncoder.queueAllocationRelease(this, handle);
    }

    void releaseAllocationNow(final MemorySegment handle) {
        this.residencySet.removeAllocationAndRelease(handle);
    }

    void commitResidencyChanges() {
        this.residencySet.commitIfNeeded();
    }

    void discardPendingClears(final MetalGpuTexture texture) {
        this.commandEncoder.discardPendingClears(texture);
    }

    void recordCommandError(final String message) {
        synchronized (this.debugMessages) {
            if (this.debugMessages.size() == 64) {
                this.debugMessages.removeFirst();
            }
            this.debugMessages.addLast(message);
        }
    }

    boolean concurrentCompilationEnabled() {
        return this.concurrentCompilationEnabled;
    }

    MTL4Compiler metal4Compiler() {
        return this.metal4Compiler;
    }

    String recordPipelineCacheKey(
            final BackendRenderPipeline.CreateInfo pipeline,
            final String vertexMsl,
            final String fragmentMsl
    ) {
        return this.pipelineCacheIdentity.recordPipeline(pipeline, vertexMsl, fragmentMsl);
    }

    /** Persists newly compiled pipelines once compilation has been quiet for a few seconds. */
    void flushPipelineArchiveIfIdle() {
        if (!this.archiveDirty || System.nanoTime() - this.lastPipelineCompileNanos < ARCHIVE_FLUSH_DELAY_NANOS) {
            return;
        }
        this.archiveDirty = false;
        this.metal4Compiler.flushArchive();
        this.pipelineCacheIdentity.flushManifest();
    }

    MemorySegment getOrCompileLibrary(final String msl) {
        return this.libraryCache.computeIfAbsent(msl, this.metal4Compiler::newLibrary);
    }

    private static void releaseIfPresent(final MemorySegment handle) {
        if (!ObjC.isNil(handle)) {
            ObjC.release(handle);
        }
    }

    private DeviceInfo buildDeviceInfo(final String deviceName) {
        String osVersion = System.getProperty("os.version", "").trim();
        String driverDescription = "macOS " + osVersion + "; Metal 4; MSL " + MetalCrossShaderCompiler.MSL_VERSION_NAME;
        long maxMemoryAllocationSize = Math.min(this.maxBufferLength, metalDevice.recommendedMaxWorkingSetSize());
        return new DeviceInfo(
                deviceName,
                "Apple",
                driverDescription,
                true,
                "Metal",
                1.0F,
                new DeviceLimits(
                        16,
                        256,
                        16384,
                        maxMemoryAllocationSize,
                        Integer.MAX_VALUE,
                        MTLBuiltinPipelines.MAX_COLOR_ATTACHMENTS,
                        Integer.MAX_VALUE
                ),
                new DeviceFeatures(true, true, true, true, true, true, true, true),
                Set.of(),
                new HintsAndWorkarounds(false, false, true, false),
                DeviceType.INTEGRATED
        );
    }
}
