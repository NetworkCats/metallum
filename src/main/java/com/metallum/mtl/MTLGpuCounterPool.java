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
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** A timestamp counter heap/sample buffer selected at runtime. */
@Environment(EnvType.CLIENT)
public final class MTLGpuCounterPool implements AutoCloseable {
    private static final MemoryLayout NS_RANGE = MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG);
    private static final MemorySegment MTL4_DESCRIPTOR_CLASS = ObjC.findClass("MTL4CounterHeapDescriptor");
    private static final MemorySegment MTL3_DESCRIPTOR_CLASS = ObjC.findClass("MTLCounterSampleBufferDescriptor");
    private static final Msg NEW = Msg.of("new", ADDRESS);
    private static final Msg SET_TYPE = Msg.ofVoid("setType:", JAVA_LONG);
    private static final Msg SET_COUNT = Msg.ofVoid("setCount:", JAVA_LONG);
    private static final Msg NEW_COUNTER_HEAP = Msg.of(
            "newCounterHeapWithDescriptor:error:", ADDRESS, ADDRESS, ADDRESS);
    private static final Msg COUNTER_SETS = Msg.of("counterSets", ADDRESS);
    private static final Msg ARRAY_COUNT = Msg.of("count", JAVA_LONG);
    private static final Msg OBJECT_AT_INDEX = Msg.of("objectAtIndex:", ADDRESS, JAVA_LONG);
    private static final Msg NAME = Msg.of("name", ADDRESS);
    private static final Msg SET_COUNTER_SET = Msg.ofVoid("setCounterSet:", ADDRESS);
    private static final Msg SET_STORAGE_MODE = Msg.ofVoid("setStorageMode:", JAVA_LONG);
    private static final Msg SET_SAMPLE_COUNT = Msg.ofVoid("setSampleCount:", JAVA_LONG);
    private static final Msg NEW_SAMPLE_BUFFER = Msg.of(
            "newCounterSampleBufferWithDescriptor:error:", ADDRESS, ADDRESS, ADDRESS);
    private static final Msg RESOLVE = Msg.of("resolveCounterRange:", ADDRESS, NS_RANGE);
    private static final Msg DATA_BYTES = Msg.of("bytes", ADDRESS);
    private static final Msg DATA_LENGTH = Msg.of("length", JAVA_LONG);
    private static final Msg SAMPLE_TIMESTAMPS = Msg.ofVoid(
            "sampleTimestamps:gpuTimestamp:", ADDRESS, ADDRESS);
    private static final Msg QUERY_TIMESTAMP_FREQUENCY = Msg.of("queryTimestampFrequency", JAVA_LONG);
    private static final Msg SUPPORTS_COUNTER_SAMPLING =
            Msg.of("supportsCounterSampling:", JAVA_BOOLEAN, JAVA_LONG);

    private final MTLDevice device;
    private final boolean metal4;
    private final long frequency;
    private final long initialCpuTimestamp;
    private final long initialGpuTimestamp;
    private MemorySegment handle;

    private MTLGpuCounterPool(
            final MTLDevice device,
            final MemorySegment handle,
            final boolean metal4,
            final long frequency,
            final long initialCpuTimestamp,
            final long initialGpuTimestamp
    ) {
        this.device = device;
        this.handle = handle;
        this.metal4 = metal4;
        this.frequency = frequency;
        this.initialCpuTimestamp = initialCpuTimestamp;
        this.initialGpuTimestamp = initialGpuTimestamp;
    }

    @Nullable
    public static MTLGpuCounterPool create(final MTLDevice device, final int size, final boolean metal4) {
        if (size <= 0) {
            throw new IllegalArgumentException("Timestamp query pool size must be positive");
        }
        try {
            return metal4 ? createMetal4(device, size) : createMetal3(device, size);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static MTLGpuCounterPool createMetal4(final MTLDevice device, final int size) {
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
                long frequency = QUERY_TIMESTAMP_FREQUENCY.sendLong(device.handle());
                return new MTLGpuCounterPool(device, heap, true, frequency, 0L, 0L);
            } finally {
                ObjC.release(descriptor);
            }
        }
    }

    @Nullable
    private static MTLGpuCounterPool createMetal3(final MTLDevice device, final int size) {
        if (ObjC.isNil(MTL3_DESCRIPTOR_CLASS)
                || !ObjC.respondsToSelector(device.handle(), "counterSets")
                || !ObjC.respondsToSelector(device.handle(), "newCounterSampleBufferWithDescriptor:error:")
                || !ObjC.respondsToSelector(device.handle(), "supportsCounterSampling:")
                || !SUPPORTS_COUNTER_SAMPLING.sendBoolean(device.handle(), 1L)
                || !SUPPORTS_COUNTER_SAMPLING.sendBoolean(device.handle(), 4L)) {
            return null;
        }
        try (AutoreleasePool ignored = AutoreleasePool.push(); Arena arena = Arena.ofConfined()) {
            MemorySegment timestampSet = findTimestampCounterSet(device.handle());
            if (ObjC.isNil(timestampSet)) {
                return null;
            }
            MemorySegment descriptor = NEW.sendPtr(MTL3_DESCRIPTOR_CLASS);
            if (ObjC.isNil(descriptor)) {
                return null;
            }
            try {
                SET_COUNTER_SET.send(descriptor, timestampSet);
                SET_STORAGE_MODE.send(descriptor, MTLStorageMode.Shared.value);
                SET_SAMPLE_COUNT.send(descriptor, size);
                MemorySegment sampleBuffer = NEW_SAMPLE_BUFFER.sendPtr(
                        device.handle(), descriptor, arena.allocate(ADDRESS));
                if (ObjC.isNil(sampleBuffer)) {
                    return null;
                }
                long[] timestamps = sampleClockPair(device.handle());
                return new MTLGpuCounterPool(
                        device, sampleBuffer, false, 0L, timestamps[0], timestamps[1]);
            } finally {
                ObjC.release(descriptor);
            }
        }
    }

    public boolean isMetal4() {
        return this.metal4;
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
            long[] finalPair = this.metal4 ? null : sampleClockPair(this.device.handle());
            boolean resolvedAny = false;
            for (int i = 0; i < count; i++) {
                long raw = values.get(JAVA_LONG, (long) i * Long.BYTES);
                if (raw == 0L || raw == -1L) {
                    continue;
                }
                destination[index + i] = toNanoseconds(raw, finalPair);
                resolvedAny = true;
            }
            return resolvedAny;
        }
    }

    private long toNanoseconds(final long raw, @Nullable final long[] finalPair) {
        if (this.metal4) {
            if (this.frequency <= 0L) {
                return raw;
            }
            return Math.round((double) raw * 1_000_000_000.0 / (double) this.frequency);
        }
        if (finalPair == null) {
            return raw;
        }
        long gpuSpan = finalPair[1] - this.initialGpuTimestamp;
        long cpuSpan = finalPair[0] - this.initialCpuTimestamp;
        if (gpuSpan <= 0L || cpuSpan <= 0L) {
            return raw;
        }
        return this.initialCpuTimestamp + Math.round(
                (double) (raw - this.initialGpuTimestamp) * (double) cpuSpan / (double) gpuSpan);
    }

    private static MemorySegment findTimestampCounterSet(final MemorySegment device) {
        MemorySegment sets = COUNTER_SETS.sendPtr(device);
        if (ObjC.isNil(sets)) {
            return MemorySegment.NULL;
        }
        long count = ARRAY_COUNT.sendLong(sets);
        for (long i = 0L; i < count; i++) {
            MemorySegment set = OBJECT_AT_INDEX.sendPtr(sets, i);
            if (ObjC.isNil(set)) {
                continue;
            }
            MemorySegment name = NAME.sendPtr(set);
            if (!ObjC.isNil(name)
                    && ObjC.javaString(name).toLowerCase(Locale.ROOT).contains("timestamp")) {
                return set;
            }
        }
        return MemorySegment.NULL;
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
