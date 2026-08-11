package com.metallum.render;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** CPU copy of selected GPU buffers, with exact tracking of initialized byte ranges. */
final class CpuBufferMirror {
    private final int size;
    @Nullable
    private byte[] bytes;
    private final NavigableMap<Integer, Integer> initialized = new TreeMap<>();

    CpuBufferMirror(final int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Mirror size must not be negative");
        }
        this.size = size;
    }

    void write(final long offset, final ByteBuffer source) {
        int start = checkedRange(offset, source.remaining());
        int length = source.remaining();
        if (length == 0) {
            return;
        }
        source.duplicate().get(bytes(), start, length);
        markInitialized(start, start + length);
    }

    void copyFrom(
            @Nullable final CpuBufferMirror source,
            final long sourceOffset,
            final long targetOffset,
            final long length
    ) {
        int targetStart = checkedRange(targetOffset, length);
        int byteCount = Math.toIntExact(length);
        if (byteCount == 0) {
            return;
        }
        if (source == null || !source.hasRange(sourceOffset, length)) {
            invalidate(targetStart, targetStart + byteCount);
            return;
        }
        int sourceStart = source.checkedRange(sourceOffset, length);
        System.arraycopy(source.bytes(), sourceStart, bytes(), targetStart, byteCount);
        markInitialized(targetStart, targetStart + byteCount);
    }

    void invalidate(final long offset, final long length) {
        int start = checkedRange(offset, length);
        invalidate(start, start + Math.toIntExact(length));
    }

    ByteBuffer read(final long offset, final long length) {
        ByteBuffer result = readIfInitialized(offset, length);
        if (result != null) {
            return result;
        }
        throw new IllegalStateException(
                "Index data range " + offset + "+" + length + " is not available in the CPU mirror");
    }

    @Nullable
    ByteBuffer readIfInitialized(final long offset, final long length) {
        int start = checkedRange(offset, length);
        if (!hasRange(offset, length)) {
            return null;
        }
        return ByteBuffer.wrap(bytes(), start, Math.toIntExact(length))
                .slice()
                .order(ByteOrder.nativeOrder());
    }

    private boolean hasRange(final long offset, final long length) {
        int start = checkedRange(offset, length);
        if (length == 0L) {
            return true;
        }
        Map.Entry<Integer, Integer> range = this.initialized.floorEntry(start);
        return range != null && range.getValue() >= start + Math.toIntExact(length);
    }

    private int checkedRange(final long offset, final long length) {
        BufferCopySupport.requireRange(offset, length, this.size, "CPU buffer mirror");
        return Math.toIntExact(offset);
    }

    private byte[] bytes() {
        if (this.bytes == null) {
            this.bytes = new byte[this.size];
        }
        return this.bytes;
    }

    private void markInitialized(final int start, final int end) {
        int mergedStart = start;
        int mergedEnd = end;
        Map.Entry<Integer, Integer> floor = this.initialized.floorEntry(start);
        if (floor != null && floor.getValue() >= start) {
            mergedStart = floor.getKey();
            mergedEnd = Math.max(mergedEnd, floor.getValue());
            this.initialized.remove(floor.getKey());
        }
        Map.Entry<Integer, Integer> next = this.initialized.ceilingEntry(mergedStart);
        while (next != null && next.getKey() <= mergedEnd) {
            mergedEnd = Math.max(mergedEnd, next.getValue());
            this.initialized.remove(next.getKey());
            next = this.initialized.ceilingEntry(mergedStart);
        }
        this.initialized.put(mergedStart, mergedEnd);
    }

    private void invalidate(final int start, final int end) {
        if (start == end) {
            return;
        }
        Map.Entry<Integer, Integer> range = this.initialized.floorEntry(start);
        if (range == null || range.getValue() <= start) {
            range = this.initialized.ceilingEntry(start);
        }
        while (range != null && range.getKey() < end) {
            int rangeStart = range.getKey();
            int rangeEnd = range.getValue();
            this.initialized.remove(rangeStart);
            if (rangeStart < start) {
                this.initialized.put(rangeStart, start);
            }
            if (rangeEnd > end) {
                this.initialized.put(end, rangeEnd);
                break;
            }
            range = this.initialized.ceilingEntry(start);
        }
    }
}
