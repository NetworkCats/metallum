package com.metallum.render;

import java.util.ArrayList;
import java.util.List;

/** Tracks untracked Metal resource hazards at buffer-range and texture-subresource granularity. */
final class ResourceAccessTracker {
    enum Access {
        READ,
        WRITE
    }

    record Dependency(
            long priorStages,
            long sameEncoderStages,
            long consumerStages,
            boolean deviceVisibility,
            long[] producerEncoders
    ) {
        boolean hasPriorDependency() {
            return this.priorStages != 0L;
        }

        boolean hasSameEncoderDependency() {
            return this.sameEncoderStages != 0L;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private long currentEncoder = -1L;
    private long nextEncoder;

    long beginEncoder() {
        return this.currentEncoder = this.nextEncoder++;
    }

    Dependency accessBuffer(
            final Object buffer,
            final long offset,
            final long length,
            final Access access,
            final long stages
    ) {
        if (offset < 0L || length < 0L) {
            throw new IllegalArgumentException("Negative Metal buffer range");
        }
        return access(new Entry(
                requireResource(buffer), offset, Math.addExact(offset, length),
                0, 0, 0, 0, access, stages, requireEncoder()
        ));
    }

    Dependency accessTexture(
            final Object texture,
            final int baseMip,
            final int mipCount,
            final int baseLayer,
            final int layerCount,
            final Access access,
            final long stages
    ) {
        if (baseMip < 0 || mipCount < 0 || baseLayer < 0 || layerCount < 0) {
            throw new IllegalArgumentException("Negative Metal texture subresource range");
        }
        return access(new Entry(
                requireResource(texture), 0L, 0L,
                baseMip, Math.addExact(baseMip, mipCount),
                baseLayer, Math.addExact(baseLayer, layerCount),
                access, stages, requireEncoder()
        ));
    }

    void reset() {
        this.entries.clear();
        this.currentEncoder = -1L;
        this.nextEncoder = 0L;
    }

    private Dependency access(final Entry current) {
        long priorStages = 0L;
        long sameStages = 0L;
        boolean visibility = false;
        long[] producers = new long[4];
        int producerCount = 0;

        for (int index = this.entries.size() - 1; index >= 0; index--) {
            Entry previous = this.entries.get(index);
            if (!previous.overlaps(current) || !conflicts(previous.access, current.access)) {
                continue;
            }
            visibility |= previous.access == Access.WRITE;
            if (previous.encoder == current.encoder) {
                sameStages |= previous.stages;
            } else {
                priorStages |= previous.stages;
                if (!contains(producers, producerCount, previous.encoder)) {
                    if (producerCount == producers.length) {
                        long[] grown = new long[producers.length * 2];
                        System.arraycopy(producers, 0, grown, 0, producers.length);
                        producers = grown;
                    }
                    producers[producerCount++] = previous.encoder;
                }
            }
            this.entries.remove(index);
        }

        this.entries.add(current);
        long[] exactProducers = new long[producerCount];
        System.arraycopy(producers, 0, exactProducers, 0, producerCount);
        return new Dependency(priorStages, sameStages, current.stages, visibility, exactProducers);
    }

    private long requireEncoder() {
        if (this.currentEncoder < 0L) {
            throw new IllegalStateException("No active Metal encoder in resource tracker");
        }
        return this.currentEncoder;
    }

    private static Object requireResource(final Object resource) {
        if (resource == null) {
            throw new IllegalArgumentException("Metal resource is null");
        }
        return resource;
    }

    private static boolean conflicts(final Access previous, final Access current) {
        return previous == Access.WRITE || current == Access.WRITE;
    }

    private static boolean contains(final long[] values, final int size, final long value) {
        for (int i = 0; i < size; i++) {
            if (values[i] == value) {
                return true;
            }
        }
        return false;
    }

    private record Entry(
            Object resource,
            long bufferStart,
            long bufferEnd,
            int mipStart,
            int mipEnd,
            int layerStart,
            int layerEnd,
            Access access,
            long stages,
            long encoder
    ) {
        boolean overlaps(final Entry other) {
            if (this.resource != other.resource) {
                return false;
            }
            boolean buffers = this.bufferEnd != 0L || other.bufferEnd != 0L;
            if (buffers) {
                return this.bufferStart < other.bufferEnd && other.bufferStart < this.bufferEnd;
            }
            return this.mipStart < other.mipEnd && other.mipStart < this.mipEnd
                    && this.layerStart < other.layerEnd && other.layerStart < this.layerEnd;
        }
    }
}
