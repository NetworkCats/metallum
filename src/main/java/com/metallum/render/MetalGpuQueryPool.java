package com.metallum.render;

import com.metallum.mtl.MTLBlitCommandEncoder;
import com.metallum.mtl.MTLGpuCounterPool;
import com.metallum.mtl.MTLRenderCommandEncoder;
import com.mojang.blaze3d.systems.GpuQueryPool;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.OptionalLong;

@Environment(EnvType.CLIENT)
final class MetalGpuQueryPool implements GpuQueryPool {
    private final MetalDevice device;
    private final long[] values;
    private final long[] written;
    private final long[] resolved;
    private MTLGpuCounterPool nativePool;
    private boolean closed;

    MetalGpuQueryPool(final MetalDevice device, final int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Timestamp query pool size must be positive");
        }
        this.device = device;
        this.values = new long[size];
        this.written = new long[Math.toIntExact(((long) size + Long.SIZE - 1L) / Long.SIZE)];
        this.resolved = new long[this.written.length];
        this.nativePool = MTLGpuCounterPool.create(
                device.metalDevice(), size, device.usesMetal4CommandPath());
    }

    void write(final MTLRenderCommandEncoder encoder, final int index) {
        checkOpen();
        checkIndex(index);
        this.values[index] = 0L;
        clear(this.resolved, index);
        if (this.nativePool != null) {
            encoder.writeTimestamp(this.nativePool, index);
        } else {
            this.values[index] = System.nanoTime();
            set(this.resolved, index);
        }
        set(this.written, index);
    }

    void write(final MTLBlitCommandEncoder encoder, final int index) {
        checkOpen();
        checkIndex(index);
        this.values[index] = 0L;
        clear(this.resolved, index);
        if (this.nativePool != null) {
            encoder.writeTimestamp(this.nativePool, index);
        } else {
            this.values[index] = System.nanoTime();
            set(this.resolved, index);
        }
        set(this.written, index);
    }

    @Override
    public int size() {
        return this.values.length;
    }

    @Override
    public @NonNull OptionalLong getValue(final int index) {
        checkOpen();
        checkIndex(index);
        resolveRange(index, 1);
        return isSet(this.resolved, index)
                ? OptionalLong.of(this.values[index])
                : OptionalLong.empty();
    }

    @Override
    public OptionalLong @NonNull [] getValues(final int index, final int count) {
        checkOpen();
        if (index < 0 || count < 0 || index > this.values.length - count) {
            throw new IndexOutOfBoundsException("Invalid timestamp query range " + index + "+" + count);
        }
        resolveRange(index, count);
        OptionalLong[] result = new OptionalLong[count];
        for (int i = 0; i < count; i++) {
            int query = index + i;
            result[i] = isSet(this.resolved, query)
                    ? OptionalLong.of(this.values[query])
                    : OptionalLong.empty();
        }
        return result;
    }

    private void resolveRange(final int index, final int count) {
        if (this.nativePool == null || count == 0) {
            return;
        }
        boolean hasWrittenQuery = false;
        for (int i = index; i < index + count; i++) {
            if (isSet(this.written, i) && !isSet(this.resolved, i)) {
                hasWrittenQuery = true;
                break;
            }
        }
        if (!hasWrittenQuery || !this.nativePool.resolve(this.values, index, count)) {
            return;
        }
        for (int i = index; i < index + count; i++) {
            if (isSet(this.written, i) && this.values[i] != 0L) {
                set(this.resolved, i);
            }
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        Arrays.fill(this.values, 0L);
        Arrays.fill(this.written, 0L);
        Arrays.fill(this.resolved, 0L);
        if (this.nativePool != null) {
            MemorySegment handle = this.nativePool.detach();
            this.nativePool = null;
            this.device.queueResourceRelease(handle);
        }
    }

    boolean belongsTo(final MetalDevice candidate) {
        return this.device == candidate;
    }

    private void checkOpen() {
        if (this.closed) {
            throw new IllegalStateException("Timestamp query pool is closed");
        }
    }

    private void checkIndex(final int index) {
        if (index < 0 || index >= this.values.length) {
            throw new IndexOutOfBoundsException(index);
        }
    }

    private static boolean isSet(final long[] bits, final int index) {
        return (bits[index >>> 6] & (1L << index)) != 0L;
    }

    private static void set(final long[] bits, final int index) {
        bits[index >>> 6] |= 1L << index;
    }

    private static void clear(final long[] bits, final int index) {
        bits[index >>> 6] &= ~(1L << index);
    }
}
