package com.metallum.render;

import com.metallum.mtl.MTLBuffer;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBuffer.Usage;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuBufferSlice.MappedView;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.util.TransientBlockAllocator;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Mth;
import org.jspecify.annotations.NonNull;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;

@Environment(EnvType.CLIENT)
final class MetalTransientMemory implements TransientMemory {
    private static final long BLOCK_SIZE = 524288L;
    private static final long MAX_CPU_ALIGNMENT = 16L;
    private static final long MAX_GPU_ALIGNMENT = Long.highestOneBit(Long.MAX_VALUE);
    private static final int BLOCK_USAGE = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE;

    private final MetalDevice device;
    private final MetalCommandEncoder encoder;
    private final TransientBlockAllocator<Long> cpuBlockAllocator = new TransientBlockAllocator<>(
            BLOCK_SIZE, MAX_CPU_ALIGNMENT, TransientBlockAllocator.Allocator.create(MemoryUtil::nmemAlloc, MemoryUtil::nmemFree)
    );
    private final TransientBlockAllocator<MetalGpuBuffer> gpuBlockAllocator;
    private long submitIndex = 0L;
    private long frameAllocatedBytes;

    MetalTransientMemory(final MetalDevice device, final MetalCommandEncoder encoder) {
        this.device = device;
        this.encoder = encoder;
        this.gpuBlockAllocator = new TransientBlockAllocator<>(
                BLOCK_SIZE, MAX_GPU_ALIGNMENT, TransientBlockAllocator.Allocator.create(this::allocateGpuBlock, this::freeGpuBlock)
        );
    }

    void rotate() {
        Stats.recordHighWater(Stats.Counter.TRANSIENT_HIGH_WATER_BYTES, this.frameAllocatedBytes);
        Stats.recordHighWater(
                Stats.Counter.TRANSIENT_BLOCKS,
                (this.frameAllocatedBytes + BLOCK_SIZE - 1L) / BLOCK_SIZE
        );
        this.frameAllocatedBytes = 0L;
        cpuBlockAllocator.rotate().run();
        encoder.queueForDestroy(gpuBlockAllocator.rotate());
        submitIndex++;
    }

    void close() {
        cpuBlockAllocator.close();
        gpuBlockAllocator.close();
    }

    private MetalGpuBuffer allocateGpuBlock(final long size) {
        return new MetalGpuBuffer(device, BLOCK_USAGE, size);
    }

    private void freeGpuBlock(final MetalGpuBuffer block) {
        block.close();
    }

    @Override
    public @NonNull ByteBuffer allocateCpu(final long size, final long alignment, final long minimumAllocation, final long elementSize) {
        recordAllocation(size);
        TransientBlockAllocator.Allocation<Long> alloc = cpuBlockAllocator.allocate(size, alignment, minimumAllocation, elementSize);
        return MemoryUtil.memByteBuffer(alloc.block() + alloc.offset(), (int) alloc.size());
    }

    @Override
    public @NonNull MappedView allocateStaging(final long size, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        return allocateMapped(size, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public @NonNull GpuBufferSlice allocateGpu(final long size, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        recordAllocation(size);
        TransientBlockAllocator.Allocation<MetalGpuBuffer> alloc = gpuBlockAllocator.allocate(size, alignment, minimumAllocation, elementSize);
        return new GpuBufferSlice(wrap(alloc.block(), usage), alloc.offset(), alloc.size());
    }

    @Override
    public @NonNull MappedView allocateGpuMapped(final long size, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        return allocateMapped(size, alignment, usage, minimumAllocation, elementSize);
    }

    private MappedView allocateMapped(final long size, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        recordAllocation(size);
        TransientBlockAllocator.Allocation<MetalGpuBuffer> alloc = gpuBlockAllocator.allocate(size, alignment, minimumAllocation, elementSize);
        GpuBufferSlice slice = new GpuBufferSlice(wrap(alloc.block(), usage), alloc.offset(), alloc.size());
        ByteBuffer hostView = alloc.block().sliceStorage(alloc.offset(), alloc.size());
        return new MappedView(slice, hostView, () -> {
        });
    }

    private MetalGpuBuffer wrap(final MetalGpuBuffer block, @Usage final int usage) {
        return new TransientGpuBuffer(device, block.metalBuffer(), usage, block.size(), this, submitIndex);
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
            totalSize += buffer.remaining();
            totalSize = Mth.roundToward(totalSize, alignment);
        }
        Stats.add(Stats.Counter.UPLOAD_BYTES, totalSize);

        GpuBufferSlice result;
        try (MappedView mapped = allocateMapped(totalSize, alignment, usage, minimumAllocation, elementSize)) {
            long mappedPtr = MemoryUtil.memAddress(mapped.data());
            long offset = 0L;
            for (ByteBuffer buffer : data) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), mappedPtr + offset, Math.min(mapped.slice().length() - offset, buffer.remaining()));
                offset += buffer.remaining();
                offset = Mth.roundToward(offset, alignment);
                if (offset >= mapped.slice().length()) {
                    break;
                }
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
        long uploadedBytes = 0L;
        for (ByteBuffer currentBuffer : data) {
            int length = currentBuffer.remaining();
            try (MappedView view = allocateGpuMapped(length, alignment, usage)) {
                MemoryUtil.memCopy(currentBuffer, view.data());
                uploaded.add(view.slice());
            }
            uploadedBytes += length;
        }
        Stats.add(Stats.Counter.UPLOAD_BYTES, uploadedBytes);
        return uploaded;
    }

    private void recordAllocation(final long size) {
        this.frameAllocatedBytes = Math.addExact(this.frameAllocatedBytes, Math.max(size, 0L));
    }

    private static final class TransientGpuBuffer extends MetalGpuBuffer {
        private final MetalTransientMemory owner;
        private final long bufferSubmitIndex;
        private boolean closed;

        TransientGpuBuffer(
                final MetalDevice device,
                final MTLBuffer handle,
                @Usage final int usage,
                final long size,
                final MetalTransientMemory owner,
                final long submitIndex
        ) {
            super(device, usage, size, handle);
            this.owner = owner;
            this.bufferSubmitIndex = submitIndex;
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
