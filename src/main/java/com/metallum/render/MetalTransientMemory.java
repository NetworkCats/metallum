package com.metallum.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBuffer.Usage;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuBufferSlice.MappedView;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.util.TransientBlockAllocator;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;

@Environment(EnvType.CLIENT)
final class MetalTransientMemory implements TransientMemory {
    private static final long MAX_CPU_ALIGNMENT = 16L;
    private static final long MAX_GPU_ALIGNMENT = Long.highestOneBit(Long.MAX_VALUE);
    private static final int BLOCK_USAGE = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE;

    private final MetalDevice device;
    private final MetalCommandEncoder encoder;
    private final TransientBlockSizingPolicy sizingPolicy = new TransientBlockSizingPolicy();
    private TransientBlockAllocator<Long> cpuBlockAllocator;
    private TransientBlockAllocator<MetalGpuBuffer> gpuBlockAllocator;
    private long blockSize;
    private long submitIndex = 0L;
    private long frameAllocatedBytes;

    MetalTransientMemory(final MetalDevice device, final MetalCommandEncoder encoder) {
        this.device = device;
        this.encoder = encoder;
        this.blockSize = this.sizingPolicy.blockSize();
        this.cpuBlockAllocator = createCpuAllocator(this.blockSize);
        this.gpuBlockAllocator = createGpuAllocator(this.blockSize);
    }

    void rotate() {
        Stats.recordHighWater(Stats.Counter.TRANSIENT_HIGH_WATER_BYTES, this.frameAllocatedBytes);
        Stats.recordHighWater(
                Stats.Counter.TRANSIENT_BLOCKS,
                this.frameAllocatedBytes == 0L
                        ? 0L
                        : 1L + (this.frameAllocatedBytes - 1L) / this.blockSize
        );
        long nextBlockSize = this.sizingPolicy.recordFrame(this.frameAllocatedBytes);
        this.frameAllocatedBytes = 0L;
        Runnable releaseCpu = this.cpuBlockAllocator.rotate();
        Runnable releaseGpu = this.gpuBlockAllocator.rotate();
        releaseCpu.run();
        if (nextBlockSize != this.blockSize) {
            resizeAllocators(nextBlockSize, releaseGpu);
        } else {
            encoder.queueForDestroy(releaseGpu);
        }
        submitIndex++;
    }

    void close() {
        cpuBlockAllocator.close();
        gpuBlockAllocator.close();
    }

    private MetalGpuBuffer allocateGpuBlock(final long size) {
        return (MetalGpuBuffer) device.createBuffer(null, BLOCK_USAGE, size);
    }

    private void freeGpuBlock(final MetalGpuBuffer block) {
        block.close();
    }

    private static TransientBlockAllocator<Long> createCpuAllocator(final long blockSize) {
        return new TransientBlockAllocator<>(
                blockSize,
                MAX_CPU_ALIGNMENT,
                TransientBlockAllocator.Allocator.create(MemoryUtil::nmemAlloc, MemoryUtil::nmemFree)
        );
    }

    private TransientBlockAllocator<MetalGpuBuffer> createGpuAllocator(final long blockSize) {
        return new TransientBlockAllocator<>(
                blockSize,
                MAX_GPU_ALIGNMENT,
                TransientBlockAllocator.Allocator.create(this::allocateGpuBlock, this::freeGpuBlock)
        );
    }

    private void resizeAllocators(final long nextBlockSize, final Runnable releaseGpu) {
        TransientBlockAllocator<Long> retiredCpu = this.cpuBlockAllocator;
        TransientBlockAllocator<MetalGpuBuffer> retiredGpu = this.gpuBlockAllocator;
        retiredCpu.close();
        this.blockSize = nextBlockSize;
        this.cpuBlockAllocator = createCpuAllocator(nextBlockSize);
        this.gpuBlockAllocator = createGpuAllocator(nextBlockSize);
        this.encoder.queueForDestroy(() -> {
            releaseGpu.run();
            retiredGpu.close();
        });
    }

    @Override
    public @NonNull ByteBuffer allocateCpu(final long size, final long alignment, final long minimumAllocation, final long elementSize) {
        validateAllocation(size, alignment, minimumAllocation, elementSize, MAX_CPU_ALIGNMENT, true);
        recordAllocation(size);
        TransientBlockAllocator.Allocation<Long> alloc = cpuBlockAllocator.allocate(size, alignment, minimumAllocation, elementSize);
        return MemoryUtil.memByteBuffer(alloc.block() + alloc.offset(), Math.toIntExact(alloc.size()));
    }

    @Override
    public @NonNull MappedView allocateStaging(final long size, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        return allocateMapped(size, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public @NonNull GpuBufferSlice allocateGpu(final long size, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        validateAllocation(size, alignment, minimumAllocation, elementSize, MAX_GPU_ALIGNMENT, false);
        recordAllocation(size);
        TransientBlockAllocator.Allocation<MetalGpuBuffer> alloc = gpuBlockAllocator.allocate(size, alignment, minimumAllocation, elementSize);
        return new GpuBufferSlice(wrap(alloc.block(), usage), alloc.offset(), alloc.size());
    }

    @Override
    public @NonNull MappedView allocateGpuMapped(final long size, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        return allocateMapped(size, alignment, usage, minimumAllocation, elementSize);
    }

    private MappedView allocateMapped(final long size, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        validateAllocation(size, alignment, minimumAllocation, elementSize, MAX_GPU_ALIGNMENT, false);
        recordAllocation(size);
        TransientBlockAllocator.Allocation<MetalGpuBuffer> alloc = gpuBlockAllocator.allocate(size, alignment, minimumAllocation, elementSize);
        GpuBufferSlice slice = new GpuBufferSlice(wrap(alloc.block(), usage), alloc.offset(), alloc.size());
        ByteBuffer hostView = alloc.block().sliceStorage(alloc.offset(), alloc.size());
        return new MappedView(slice, hostView, () -> {
        });
    }

    private MetalGpuBuffer wrap(final MetalGpuBuffer block, @Usage final int usage) {
        return new TransientGpuBuffer(device, block, usage, block.size(), this, submitIndex);
    }

    @Override
    public @NonNull GpuBufferSlice uploadStaging(final @NonNull List<ByteBuffer> data, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        return upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public @NonNull GpuBufferSlice uploadGpu(final @NonNull List<ByteBuffer> data, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        return upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    private GpuBufferSlice upload(final List<ByteBuffer> data, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        long totalSize = 0L;
        for (ByteBuffer buffer : data) {
            totalSize = checkedRoundUp(
                    Math.addExact(totalSize, buffer.remaining()), alignment);
        }
        Stats.add(Stats.Counter.UPLOAD_BYTES, totalSize);

        GpuBufferSlice result;
        try (MappedView mapped = allocateMapped(totalSize, alignment, usage, minimumAllocation, elementSize)) {
            ByteBuffer destination = mapped.data().duplicate();
            long offset = 0L;
            for (ByteBuffer buffer : data) {
                int length = buffer.remaining();
                destination.position(Math.toIntExact(offset));
                destination.put(buffer.duplicate());
                offset = checkedRoundUp(Math.addExact(offset, length), alignment);
            }
            result = mapped.slice();
        }
        return result;
    }

    @Override
    public @NonNull List<GpuBufferSlice> multiUploadStaging(final @NonNull List<ByteBuffer> data, final long alignment, @Usage final int usage) {
        return multiUpload(data, alignment, usage);
    }

    @Override
    public @NonNull List<GpuBufferSlice> multiUploadGpu(final @NonNull List<ByteBuffer> data, final long alignment, @Usage final int usage) {
        return multiUpload(data, alignment, usage);
    }

    private List<GpuBufferSlice> multiUpload(final List<ByteBuffer> data, final long alignment, @Usage final int usage) {
        ReferenceArrayList<GpuBufferSlice> uploaded = new ReferenceArrayList<>(data.size());
        if (data.isEmpty()) {
            return uploaded;
        }

        long packedSize = 0L;
        long uploadedBytes = 0L;
        for (ByteBuffer buffer : data) {
            packedSize = checkedRoundUp(packedSize, alignment);
            packedSize = Math.addExact(packedSize, buffer.remaining());
            uploadedBytes = Math.addExact(uploadedBytes, buffer.remaining());
        }

        try (MappedView packed = allocateGpuMapped(packedSize, alignment, usage)) {
            GpuBufferSlice allocation = packed.slice();
            ByteBuffer destination = packed.data().duplicate();
            long offset = 0L;
            for (ByteBuffer buffer : data) {
                offset = checkedRoundUp(offset, alignment);
                int length = buffer.remaining();
                destination.position(Math.toIntExact(offset));
                destination.put(buffer.duplicate());
                uploaded.add(new GpuBufferSlice(
                        allocation.buffer(),
                        allocation.offset() + offset,
                        length
                ));
                offset = Math.addExact(offset, length);
            }
        }
        Stats.add(Stats.Counter.UPLOAD_BYTES, uploadedBytes);
        return uploaded;
    }

    private void recordAllocation(final long size) {
        this.frameAllocatedBytes = Math.addExact(this.frameAllocatedBytes, Math.max(size, 0L));
    }

    static long checkedRoundUp(final long value, final long alignment) {
        if (value < 0L) {
            throw new IllegalArgumentException("Transient allocation size must not be negative: " + value);
        }
        if (alignment <= 0L) {
            throw new IllegalArgumentException("Transient allocation alignment must be positive: " + alignment);
        }
        long remainder = value % alignment;
        if (remainder == 0L) {
            return value;
        }
        try {
            return Math.addExact(value, alignment - remainder);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Transient allocation alignment overflows", exception);
        }
    }

    static void validateAllocation(
            final long size,
            final long alignment,
            final long minimumAllocation,
            final long elementSize,
            final long maximumAlignment,
            final boolean cpuAllocation
    ) {
        if (size < 0L || minimumAllocation < 0L) {
            throw new IllegalArgumentException("Transient allocation sizes must not be negative");
        }
        if (alignment <= 0L || alignment > maximumAlignment) {
            throw new IllegalArgumentException(
                    "Transient allocation alignment must be in 1.." + maximumAlignment + ": " + alignment);
        }
        if (elementSize <= 0L) {
            throw new IllegalArgumentException("Transient allocation element size must be positive: " + elementSize);
        }
        if (cpuAllocation && (size > Integer.MAX_VALUE
                || minimumAllocation > Integer.MAX_VALUE
                || elementSize > Integer.MAX_VALUE)) {
            throw new IllegalArgumentException(
                    "CPU transient allocations cannot exceed " + Integer.MAX_VALUE + " bytes");
        }
    }

    private static final class TransientGpuBuffer extends MetalGpuBuffer {
        private final MetalTransientMemory owner;
        private final MetalGpuBuffer physicalBlock;
        private final long bufferSubmitIndex;
        private boolean closed;

        TransientGpuBuffer(
                final MetalDevice device,
                final MetalGpuBuffer physicalBlock,
                @Usage final int usage,
                final long size,
                final MetalTransientMemory owner,
                final long submitIndex
        ) {
            super(device, usage, size, physicalBlock.metalBuffer());
            this.owner = owner;
            this.physicalBlock = physicalBlock;
            this.bufferSubmitIndex = submitIndex;
        }

        @Override
        ByteBuffer indexData(final long offset, final long length) {
            if (this.isClosed()) {
                throw new IllegalStateException("Transient index buffer is closed");
            }
            BufferCopySupport.requireRange(offset, length, this.size(), "Transient index buffer");
            return this.physicalBlock.sliceStorage(offset, length);
        }

        @Override
        ByteBuffer knownCpuData(final long offset, final long length) {
            if (this.isClosed()) {
                return null;
            }
            return this.physicalBlock.sliceStorage(offset, length);
        }

        @Override
        public boolean isClosed() {
            if (closed) {
                return true;
            }
            closed = bufferSubmitIndex < owner.submitIndex;
            return closed;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public GpuBufferSlice.@NonNull MappedView map(final long offset, final long length, final boolean read, final boolean write) {
            throw new IllegalStateException("Cannot map transient buffer");
        }

        @Override
        public @NonNull GpuBufferSlice slice(final long offset, final long length) {
            throw new IllegalStateException("Cannot slice transient buffer");
        }

        @Override
        public @NonNull GpuBufferSlice slice() {
            throw new IllegalStateException("Cannot slice transient buffer");
        }
    }
}
