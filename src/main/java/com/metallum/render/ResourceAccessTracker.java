package com.metallum.render;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Tracks untracked Metal resource hazards at buffer-range and texture-subresource granularity. */
final class ResourceAccessTracker {
    enum Access {
        READ,
        WRITE
    }

    /**
     * Scratch dependency result. The tracker reuses this object for every access,
     * so callers must consume it before declaring another access.
     */
    static final class Dependency {
        private long priorStages;
        private long sameEncoderStages;
        private long consumerStages;
        private boolean deviceVisibility;
        private long[] producerEncoders = new long[4];
        private int producerCount;

        long priorStages() {
            return this.priorStages;
        }

        long sameEncoderStages() {
            return this.sameEncoderStages;
        }

        long consumerStages() {
            return this.consumerStages;
        }

        boolean deviceVisibility() {
            return this.deviceVisibility;
        }

        boolean hasPriorDependency() {
            return this.priorStages != 0L;
        }

        boolean hasSameEncoderDependency() {
            return this.sameEncoderStages != 0L;
        }

        int producerCount() {
            return this.producerCount;
        }

        long producerEncoder(final int index) {
            if (index < 0 || index >= this.producerCount) {
                throw new IndexOutOfBoundsException(index);
            }
            return this.producerEncoders[index];
        }

        long[] producerEncoders() {
            return Arrays.copyOf(this.producerEncoders, this.producerCount);
        }

        private void reset(final long consumerStages) {
            this.priorStages = 0L;
            this.sameEncoderStages = 0L;
            this.consumerStages = consumerStages;
            this.deviceVisibility = false;
            this.producerCount = 0;
        }

        private void addProducer(final long encoder) {
            for (int i = 0; i < this.producerCount; i++) {
                if (this.producerEncoders[i] == encoder) {
                    return;
                }
            }
            if (this.producerCount == this.producerEncoders.length) {
                this.producerEncoders = Arrays.copyOf(
                        this.producerEncoders, this.producerEncoders.length * 2);
            }
            this.producerEncoders[this.producerCount++] = encoder;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final ArrayDeque<Entry> freeEntries = new ArrayDeque<>();
    private final ArrayDeque<Entry> residualEntries = new ArrayDeque<>();
    private final Dependency dependency = new Dependency();
    private long[] encoderStages = new long[16];
    private long currentEncoder = -1L;
    private long nextEncoder;

    long beginEncoder() {
        long encoder = this.nextEncoder++;
        if (encoder >= this.encoderStages.length) {
            this.encoderStages = Arrays.copyOf(
                    this.encoderStages, Math.max(this.encoderStages.length * 2, (int) encoder + 1));
        }
        this.encoderStages[(int) encoder] = 0L;
        return this.currentEncoder = encoder;
    }

    long stagesForEncoder(final long encoder) {
        return encoder < 0L || encoder >= this.nextEncoder ? 0L : this.encoderStages[(int) encoder];
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
        Entry entry = acquireEntry();
        entry.setBuffer(
                requireResource(buffer), offset, Math.addExact(offset, length),
                access, stages, requireEncoder());
        return access(entry);
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
        Entry entry = acquireEntry();
        entry.setTexture(
                requireResource(texture), baseMip, Math.addExact(baseMip, mipCount),
                baseLayer, Math.addExact(baseLayer, layerCount),
                access, stages, requireEncoder());
        return access(entry);
    }

    void reset() {
        this.freeEntries.addAll(this.entries);
        this.entries.clear();
        Arrays.fill(this.encoderStages, 0, Math.toIntExact(this.nextEncoder), 0L);
        this.currentEncoder = -1L;
        this.nextEncoder = 0L;
    }

    private Dependency access(final Entry current) {
        this.dependency.reset(current.stages);
        this.encoderStages[(int) current.encoder] |= current.stages;
        this.residualEntries.clear();

        for (int index = this.entries.size() - 1; index >= 0; index--) {
            Entry previous = this.entries.get(index);
            if (!previous.overlaps(current)) {
                continue;
            }
            if (!conflicts(previous.access, current.access)) {
                continue;
            }
            this.dependency.deviceVisibility |= previous.access == Access.WRITE;
            if (previous.encoder == current.encoder) {
                this.dependency.sameEncoderStages |= previous.stages;
            } else {
                this.dependency.priorStages |= previous.stages;
                this.dependency.addProducer(previous.encoder);
            }
            subtract(previous, current);
            this.freeEntries.addLast(this.entries.remove(index));
        }

        for (int index = this.entries.size() - 1; index >= 0; index--) {
            Entry previous = this.entries.get(index);
            if (current.tryMerge(previous)) {
                this.freeEntries.addLast(this.entries.remove(index));
            }
        }
        this.entries.addAll(this.residualEntries);
        this.entries.add(current);
        return this.dependency;
    }

    private void subtract(final Entry previous, final Entry current) {
        if (!previous.texture) {
            long overlapStart = Math.max(previous.bufferStart, current.bufferStart);
            long overlapEnd = Math.min(previous.bufferEnd, current.bufferEnd);
            addBufferResidual(previous, previous.bufferStart, overlapStart);
            addBufferResidual(previous, overlapEnd, previous.bufferEnd);
            return;
        }

        int mipStart = Math.max(previous.mipStart, current.mipStart);
        int mipEnd = Math.min(previous.mipEnd, current.mipEnd);
        int layerStart = Math.max(previous.layerStart, current.layerStart);
        int layerEnd = Math.min(previous.layerEnd, current.layerEnd);
        addTextureResidual(
                previous, previous.mipStart, mipStart,
                previous.layerStart, previous.layerEnd);
        addTextureResidual(
                previous, mipEnd, previous.mipEnd,
                previous.layerStart, previous.layerEnd);
        addTextureResidual(
                previous, mipStart, mipEnd,
                previous.layerStart, layerStart);
        addTextureResidual(
                previous, mipStart, mipEnd,
                layerEnd, previous.layerEnd);
    }

    private void addBufferResidual(final Entry source, final long start, final long end) {
        if (start >= end) {
            return;
        }
        Entry residual = acquireEntry();
        residual.setBuffer(
                source.resource, start, end,
                source.access, source.stages, source.encoder);
        this.residualEntries.addLast(residual);
    }

    private void addTextureResidual(
            final Entry source,
            final int mipStart,
            final int mipEnd,
            final int layerStart,
            final int layerEnd
    ) {
        if (mipStart >= mipEnd || layerStart >= layerEnd) {
            return;
        }
        Entry residual = acquireEntry();
        residual.setTexture(
                source.resource, mipStart, mipEnd, layerStart, layerEnd,
                source.access, source.stages, source.encoder);
        this.residualEntries.addLast(residual);
    }

    private Entry acquireEntry() {
        Entry entry = this.freeEntries.pollLast();
        return entry == null ? new Entry() : entry;
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

    private static final class Entry {
        private Object resource;
        private boolean texture;
        private long bufferStart;
        private long bufferEnd;
        private int mipStart;
        private int mipEnd;
        private int layerStart;
        private int layerEnd;
        private Access access;
        private long stages;
        private long encoder;

        void setBuffer(
                final Object resource,
                final long start,
                final long end,
                final Access access,
                final long stages,
                final long encoder
        ) {
            this.resource = resource;
            this.texture = false;
            this.bufferStart = start;
            this.bufferEnd = end;
            this.mipStart = 0;
            this.mipEnd = 0;
            this.layerStart = 0;
            this.layerEnd = 0;
            this.access = access;
            this.stages = stages;
            this.encoder = encoder;
        }

        void setTexture(
                final Object resource,
                final int mipStart,
                final int mipEnd,
                final int layerStart,
                final int layerEnd,
                final Access access,
                final long stages,
                final long encoder
        ) {
            this.resource = resource;
            this.texture = true;
            this.bufferStart = 0L;
            this.bufferEnd = 0L;
            this.mipStart = mipStart;
            this.mipEnd = mipEnd;
            this.layerStart = layerStart;
            this.layerEnd = layerEnd;
            this.access = access;
            this.stages = stages;
            this.encoder = encoder;
        }

        boolean overlaps(final Entry other) {
            if (this.resource != other.resource) {
                return false;
            }
            if (this.texture != other.texture) {
                return false;
            }
            if (!this.texture) {
                return this.bufferStart < other.bufferEnd && other.bufferStart < this.bufferEnd;
            }
            return this.mipStart < other.mipEnd && other.mipStart < this.mipEnd
                    && this.layerStart < other.layerEnd && other.layerStart < this.layerEnd;
        }

        boolean tryMerge(final Entry other) {
            if (this.resource != other.resource
                    || this.texture != other.texture
                    || this.access != other.access
                    || this.encoder != other.encoder
                    || this.stages != other.stages) {
                return false;
            }
            if (!this.texture) {
                if (this.bufferEnd < other.bufferStart || other.bufferEnd < this.bufferStart) {
                    return false;
                }
                this.bufferStart = Math.min(this.bufferStart, other.bufferStart);
                this.bufferEnd = Math.max(this.bufferEnd, other.bufferEnd);
                return true;
            }
            if (this.mipStart == other.mipStart && this.mipEnd == other.mipEnd
                    && this.layerEnd >= other.layerStart && other.layerEnd >= this.layerStart) {
                this.layerStart = Math.min(this.layerStart, other.layerStart);
                this.layerEnd = Math.max(this.layerEnd, other.layerEnd);
                return true;
            }
            if (this.layerStart == other.layerStart && this.layerEnd == other.layerEnd
                    && this.mipEnd >= other.mipStart && other.mipEnd >= this.mipStart) {
                this.mipStart = Math.min(this.mipStart, other.mipStart);
                this.mipEnd = Math.max(this.mipEnd, other.mipEnd);
                return true;
            }
            return false;
        }
    }
}
