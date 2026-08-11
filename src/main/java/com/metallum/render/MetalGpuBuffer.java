package com.metallum.render;

import com.metallum.mtl.MTLBuffer;
import com.metallum.mtl.MTLHazardTrackingMode;
import com.metallum.mtl.MTLPixelFormat;
import com.metallum.mtl.MTLResourceOptions;
import com.metallum.mtl.MTLStorageMode;
import com.metallum.mtl.MTLTexture;
import com.metallum.objc.ObjC;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

@Environment(EnvType.CLIENT)
class MetalGpuBuffer extends GpuBuffer {
    private final MetalDevice device;
    private final boolean cpuAccessible;
    private final boolean dynamic;
    private final long resourceOptions;
    private final long allocationSize;
    @Nullable
    private MTLBuffer nativeBuffer;
    @Nullable
    private ByteBuffer storage;
    private boolean closed;
    private long backingGeneration;
    private final Map<TexelViewKey, MemorySegment> texelViews = new HashMap<>();
    private long writableSubmit = -1L;
    private boolean backingUsedByGpu;
    private final PendingBufferWrites pendingWrites = new PendingBufferWrites();
    @Nullable
    private CpuBufferMirror indexMirror;

    MetalGpuBuffer(
            final MetalDevice device,
            @GpuBuffer.Usage final int usage,
            final long size,
            final long allocationSize
    ) {
        super(usage, size);
        this.device = device;

        this.dynamic = isDynamic(usage);
        this.cpuAccessible = isCpuAccessible(usage) || this.dynamic;
        this.resourceOptions = toMtlResourceOptions(usage);
        this.allocationSize = allocationSize;
        this.indexMirror = (usage & GpuBuffer.USAGE_INDEX) != 0 && size <= Integer.MAX_VALUE
                ? new CpuBufferMirror((int) size)
                : null;
        MTLBuffer created = device.metalDevice().newBuffer(this.allocationSize, this.resourceOptions);
        try {
            device.trackAllocation(created.handle());
        } catch (RuntimeException exception) {
            ObjC.release(created.handle());
            throw exception;
        }
        this.nativeBuffer = created;
        Stats.increment(Stats.Counter.NATIVE_RESOURCES_CREATED);

        if (this.cpuAccessible) {
            MemorySegment contents = this.nativeBuffer.contents();
            if (ObjC.isNil(contents)) {
                this.device.releaseAllocationNow(this.nativeBuffer.handle());
                this.nativeBuffer = null;
                throw new IllegalStateException("MTLBuffer.contents returned null");
            }

            this.storage = ObjC.byteBufferView(contents, this.allocationSize).order(ByteOrder.nativeOrder());
        } else {
            this.storage = null;
        }
    }

    MetalGpuBuffer(final MetalDevice device, @GpuBuffer.Usage final int usage, final long size, final @Nullable MTLBuffer wrappedBuffer) {
        super(usage, size);
        this.device = device;
        this.cpuAccessible = false;
        this.dynamic = false;
        this.resourceOptions = 0L;
        this.allocationSize = size;
        this.nativeBuffer = wrappedBuffer;
        this.storage = null;
        this.indexMirror = null;
    }

    ByteBuffer sliceStorage(final long offset, final long length) {
        if (this.storage == null) {
            throw new IllegalStateException("Buffer is not CPU-accessible");
        }
        BufferCopySupport.requireRange(offset, length, this.size(), "CPU buffer mapping");

        return storage.duplicate()
                .position(Math.toIntExact(offset))
                .limit(Math.toIntExact(Math.addExact(offset, length)))
                .slice()
                .order(this.storage.order());
    }

    MTLBuffer metalBuffer() {
        if (this.nativeBuffer == null) {
            throw new IllegalStateException("Native Metal buffer is closed");
        }
        return this.nativeBuffer;
    }

    boolean belongsTo(final MetalDevice device) {
        return this.device == device;
    }

    MemorySegment nativeHandle() {
        return metalBuffer().handle();
    }

    boolean isDynamic() {
        return this.dynamic;
    }

    long allocationSize() {
        return this.allocationSize;
    }

    long backingGeneration() {
        return this.backingGeneration;
    }

    long resourceOptions() {
        return this.resourceOptions;
    }

    ByteBuffer currentStorage() {
        if (this.storage == null) {
            throw new IllegalStateException("Buffer is not CPU-accessible");
        }
        return this.storage.duplicate().order(this.storage.order());
    }

    void swapBacking(final MTLBuffer buffer, final ByteBuffer storage, final long submitIndex) {
        releaseTexelViews();
        this.nativeBuffer = buffer;
        this.storage = storage;
        this.backingGeneration++;
        this.writableSubmit = submitIndex;
        this.backingUsedByGpu = false;
    }

    boolean canUpdateBackingInPlace(final long submitIndex) {
        return this.writableSubmit == submitIndex && !this.backingUsedByGpu;
    }

    void markGpuUse(final long submitIndex) {
        this.backingUsedByGpu = true;
    }

    void queueDynamicWrite(final long submitIndex, final long offset, final ByteBuffer source) {
        this.pendingWrites.add(submitIndex, this.size(), offset, source);
    }

    boolean hasPendingWrites(final long submitIndex) {
        return this.pendingWrites.hasWrites(submitIndex);
    }

    boolean pendingWritesCoverWholeBuffer() {
        return this.pendingWrites.coversWholeBuffer(this.size());
    }

    void applyPendingWrites(final ByteBuffer destination) {
        this.pendingWrites.applyTo(destination);
    }

    void mirrorWrite(final long offset, final ByteBuffer source) {
        if (this.indexMirror != null) {
            this.indexMirror.write(offset, source);
        }
    }

    void mirrorCopyFrom(
            final MetalGpuBuffer source,
            final long sourceOffset,
            final long targetOffset,
            final long length
    ) {
        if (this.indexMirror != null) {
            if (source.indexMirror != null) {
                this.indexMirror.copyFrom(source.indexMirror, sourceOffset, targetOffset, length);
            } else {
                ByteBuffer knownSource = source.knownCpuData(sourceOffset, length);
                if (knownSource == null) {
                    this.indexMirror.invalidate(targetOffset, length);
                } else {
                    this.indexMirror.write(targetOffset, knownSource);
                }
            }
        }
    }

    @Nullable
    ByteBuffer knownCpuData(final long offset, final long length) {
        return this.indexMirror == null ? null : this.indexMirror.readIfInitialized(offset, length);
    }

    void invalidateMirror(final long offset, final long length) {
        if (this.indexMirror != null) {
            this.indexMirror.invalidate(offset, length);
        }
    }

    ByteBuffer indexData(final long offset, final long length) {
        if (this.isClosed()) {
            throw new IllegalStateException("Index buffer is closed");
        }
        if (this.indexMirror == null) {
            throw new IllegalStateException("Index buffer is too large to maintain a CPU mirror");
        }
        return this.indexMirror.read(offset, length);
    }

    MemorySegment texelTextureView(
            final MTLPixelFormat format,
            final long offset,
            final long byteLength,
            final int pixelSize
    ) {
        if (byteLength <= 0L || byteLength % pixelSize != 0L) {
            return MemorySegment.NULL;
        }
        TexelViewKey key = new TexelViewKey(
                this.backingGeneration,
                format.value,
                offset,
                byteLength
        );
        MemorySegment cached = this.texelViews.get(key);
        if (cached != null) {
            return cached;
        }
        MemorySegment created = MTLTexture.newBufferTextureView(
                nativeHandle(),
                format.value,
                offset,
                byteLength / pixelSize,
                byteLength
        );
        if (!ObjC.isNil(created)) {
            try {
                this.texelViews.put(key, created);
            } catch (RuntimeException exception) {
                ObjC.release(created);
                throw exception;
            }
            Stats.increment(Stats.Counter.NATIVE_RESOURCES_CREATED);
        }
        return created;
    }

    @Override
    public boolean isClosed() {
        return this.closed || this.nativeBuffer == null;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.storage = null;
        this.indexMirror = null;
        this.backingUsedByGpu = true;
        this.pendingWrites.clear();
        releaseTexelViews();
        if (this.nativeBuffer != null) {
            MemorySegment handle = this.nativeBuffer.handle();
            this.nativeBuffer = null;
            this.device.queueAllocationRelease(handle);
        }
    }

    @Override
    public GpuBufferSlice.@NonNull MappedView map(final long offset, final long length, final boolean read, final boolean write) {
        if (this.isClosed()) {
            throw new IllegalStateException("Buffer already closed");
        }
        if (!read && !write) {
            throw new IllegalArgumentException("At least read or write must be true");
        }
        if (read && (this.usage() & GpuBuffer.USAGE_MAP_READ) == 0) {
            throw new IllegalStateException("Buffer is not readable");
        }
        if (write && (this.usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) {
            throw new IllegalStateException("Buffer is not writable");
        }
        ByteBuffer mapped = this.sliceStorage(offset, length);
        ByteBuffer mappedForMirror = mapped.duplicate();
        return new GpuBufferSlice.MappedView(this.slice(offset, length), mapped, () -> {
            if (write) {
                mirrorWrite(offset, mappedForMirror);
            }
        });
    }

    public int getUsage() {
        return this.usage();
    }

    private static boolean isCpuAccessible(@GpuBuffer.Usage final int usage) {
        return (usage & GpuBuffer.USAGE_MAP_READ) != 0
                || (usage & GpuBuffer.USAGE_MAP_WRITE) != 0
                || (usage & GpuBuffer.USAGE_HINT_CLIENT_STORAGE) != 0;
    }

    private static boolean isDynamic(@GpuBuffer.Usage final int usage) {
        return (usage & GpuBuffer.USAGE_UNIFORM) != 0 && (usage & GpuBuffer.USAGE_COPY_DST) != 0;
    }

    private static long toMtlResourceOptions(@GpuBuffer.Usage final int usage) {
        MTLStorageMode storageMode = isCpuAccessible(usage) || isDynamic(usage) ? MTLStorageMode.Shared : MTLStorageMode.Private;
        return MTLResourceOptions.of(storageMode, MTLHazardTrackingMode.Untracked);
    }

    private void releaseTexelViews() {
        if (this.texelViews.isEmpty()) {
            return;
        }
        for (MemorySegment view : this.texelViews.values()) {
            this.device.queueResourceRelease(view);
        }
        this.texelViews.clear();
    }

    private record TexelViewKey(long generation, long format, long offset, long length) {
    }
}
