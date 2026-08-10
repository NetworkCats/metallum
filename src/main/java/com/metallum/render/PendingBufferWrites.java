package com.metallum.render;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Coalesces ordered CPU writes so a dynamic buffer needs at most one new backing per submit. */
final class PendingBufferWrites {
    private final NavigableMap<Long, byte[]> ranges = new TreeMap<>();
    private long submitIndex = -1L;

    void add(
            final long submitIndex,
            final long bufferSize,
            final long offset,
            final ByteBuffer source
    ) {
        int length = source.remaining();
        if (offset < 0L || offset > bufferSize - length) {
            throw new IndexOutOfBoundsException(
                    "Dynamic buffer write " + offset + "+" + length + " exceeds " + bufferSize);
        }
        if (length == 0) {
            return;
        }
        if (this.submitIndex != submitIndex) {
            clear();
            this.submitIndex = submitIndex;
        }

        long start = offset;
        long end = offset + length;
        Map.Entry<Long, byte[]> floor = this.ranges.floorEntry(start);
        if (floor != null && floor.getKey() + floor.getValue().length >= start) {
            start = floor.getKey();
        }
        Map.Entry<Long, byte[]> next = this.ranges.ceilingEntry(start);
        while (next != null && next.getKey() <= end) {
            end = Math.max(end, next.getKey() + next.getValue().length);
            next = this.ranges.higherEntry(next.getKey());
        }

        byte[] merged = new byte[Math.toIntExact(end - start)];
        NavigableMap<Long, byte[]> overlaps = this.ranges.subMap(start, true, end, false);
        for (Map.Entry<Long, byte[]> entry : overlaps.entrySet()) {
            System.arraycopy(entry.getValue(), 0, merged,
                    Math.toIntExact(entry.getKey() - start), entry.getValue().length);
        }
        overlaps.clear();
        source.duplicate().get(merged, Math.toIntExact(offset - start), length);
        this.ranges.put(start, merged);
    }

    boolean hasWrites(final long submitIndex) {
        return this.submitIndex == submitIndex && !this.ranges.isEmpty();
    }

    boolean coversWholeBuffer(final long size) {
        Map.Entry<Long, byte[]> first = this.ranges.firstEntry();
        return this.ranges.size() == 1
                && first != null
                && first.getKey() == 0L
                && first.getValue().length == size;
    }

    void applyTo(final ByteBuffer destination) {
        for (Map.Entry<Long, byte[]> entry : this.ranges.entrySet()) {
            destination.put(Math.toIntExact(entry.getKey()), entry.getValue());
        }
        clear();
    }

    void clear() {
        this.ranges.clear();
        this.submitIndex = -1L;
    }
}
