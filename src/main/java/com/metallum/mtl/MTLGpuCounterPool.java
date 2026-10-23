package com.metallum.mtl;

import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** A Metal 4 timestamp counter heap. */
@Environment(EnvType.CLIENT)
public final class MTLGpuCounterPool implements AutoCloseable {
    private static final MemoryLayout NS_RANGE = MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG);
    private static final MemorySegment MTL4_DESCRIPTOR_CLASS = ObjC.findClass("MTL4CounterHeapDescriptor");
    private static final Msg NEW = Msg.of("new", ADDRESS);
    private static final Msg SET_TYPE = Msg.ofVoid("setType:", JAVA_LONG);
    private static final Msg SET_COUNT = Msg.ofVoid("setCount:", JAVA_LONG);
    private static final Msg NEW_COUNTER_HEAP = Msg.of(
            "newCounterHeapWithDescriptor:error:", ADDRESS, ADDRESS, ADDRESS);
    private static final Msg RESOLVE = Msg.of("resolveCounterRange:", ADDRESS, NS_RANGE);
    private static final Msg DATA_BYTES = Msg.of("bytes", ADDRESS);
    private static final Msg DATA_LENGTH = Msg.of("length", JAVA_LONG);
    private static final Msg SAMPLE_TIMESTAMPS = Msg.ofVoid(
            "sampleTimestamps:gpuTimestamp:", ADDRESS, ADDRESS);
    private static final Msg QUERY_TIMESTAMP_FREQUENCY = Msg.of("queryTimestampFrequency", JAVA_LONG);

    private final MTLDevice device;
    private final long frequency;
    private final long initialCpuTimestamp;
    private final long initialGpuTimestamp;
    private MemorySegment handle;

    private MTLGpuCounterPool(
            final MTLDevice device,
            final MemorySegment handle,
            final long frequency,
            final long initialCpuTimestamp,
            final long initialGpuTimestamp
    ) {
        this.device = device;
        this.handle = handle;
        this.frequency = frequency;
        this.initialCpuTimestamp = initialCpuTimestamp;
        this.initialGpuTimestamp = initialGpuTimestamp;
    }

    @Nullable
    public static MTLGpuCounterPool create(final MTLDevice device, final int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Timestamp query pool size must be positive");
        }
        try {
            return createHeap(device, size);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static MTLGpuCounterPool createHeap(final MTLDevice device, final int size) {
        if (ObjC.isNil(MTL4_DESCRIPTOR_CLASS)
                || !ObjC.respondsToSelector(device.handle(), "newCounterHeapWithDescriptor:error:")
                || !ObjC.respondsToSelector(device.handle(), "queryTimestampFrequency")) {
            return null;
        }
        try (AutoreleasePool ignored = AutoreleasePool.push(); Arena arena = Arena.ofConfined()) {
            MemorySegment descriptor = NEW.sendPtr(MTL4_DESCRIPTOR_CLASS);
            if (ObjC.isNil(descriptor)) {
                return null;
            }
            try {
                SET_TYPE.send(descriptor, 1L); // MTL4CounterHeapTypeTimestamp
                SET_COUNT.send(descriptor, size);
                MemorySegment heap = NEW_COUNTER_HEAP.sendPtr(
                        device.handle(), descriptor, arena.allocate(ADDRESS));
                if (ObjC.isNil(heap)) {
                    return null;
                }
                try {
                    long frequency = QUERY_TIMESTAMP_FREQUENCY.sendLong(device.handle());
                    long[] timestamps = sampleClockPair(device.handle());
                    return new MTLGpuCounterPool(
                            device, heap, frequency, timestamps[0], timestamps[1]);
                } catch (RuntimeException exception) {
                    ObjC.release(heap);
                    throw exception;
                }
            } finally {
                ObjC.release(descriptor);
            }
        }
    }

    public MemorySegment handle() {
        if (ObjC.isNil(this.handle)) {
            throw new IllegalStateException("GPU counter pool is closed");
        }
        return this.handle;
    }

    /** Resolves a range and writes nanosecond CPU-timeline values. */
    public synchronized boolean resolve(final long[] destination, final int index, final int count) {
        if (ObjC.isNil(this.handle) || count <= 0) {
            return false;
        }
        try (AutoreleasePool ignored = AutoreleasePool.push(); Arena arena = Arena.ofConfined()) {
            MemorySegment range = arena.allocate(NS_RANGE);
            range.set(JAVA_LONG, 0L, index);
            range.set(JAVA_LONG, Long.BYTES, count);
            MemorySegment data = RESOLVE.sendPtr(this.handle, range);
            if (ObjC.isNil(data) || DATA_LENGTH.sendLong(data) < (long) count * Long.BYTES) {
                return false;
            }
            MemorySegment bytes = DATA_BYTES.sendPtr(data);
            if (ObjC.isNil(bytes)) {
                return false;
            }
            MemorySegment values = bytes.reinterpret((long) count * Long.BYTES);
            boolean resolvedAny = false;
            for (int i = 0; i < count; i++) {
                long raw = values.get(JAVA_LONG, (long) i * Long.BYTES);
                if (raw == 0L || raw == -1L) {
                    continue;
                }
                destination[index + i] = toNanoseconds(raw);
                resolvedAny = true;
            }
            return resolvedAny;
        }
    }

    private long toNanoseconds(final long raw) {
        if (this.frequency <= 0L || this.initialCpuTimestamp == 0L) {
            return raw;
        }
        return this.initialCpuTimestamp + Math.round(
                (double) (raw - this.initialGpuTimestamp)
                        * 1_000_000_000.0 / (double) this.frequency);
    }

    private static long[] sampleClockPair(final MemorySegment device) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cpu = arena.allocate(JAVA_LONG);
            MemorySegment gpu = arena.allocate(JAVA_LONG);
            long before = System.nanoTime();
            SAMPLE_TIMESTAMPS.send(device, cpu, gpu);
            long after = System.nanoTime();
            long midpoint = before + ((after - before) >>> 1);
            return new long[]{midpoint, gpu.get(JAVA_LONG, 0L)};
        }
    }

    @Override
    public synchronized void close() {
        if (!ObjC.isNil(this.handle)) {
            ObjC.release(this.handle);
            this.handle = MemorySegment.NULL;
        }
    }

    public synchronized MemorySegment detach() {
        MemorySegment detached = this.handle;
        this.handle = MemorySegment.NULL;
        return detached;
    }
}
