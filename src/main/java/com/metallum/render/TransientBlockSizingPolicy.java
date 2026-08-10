package com.metallum.render;

import java.util.Arrays;

/** Rolling P95 policy for Metallum's CPU/GPU transient arenas. */
final class TransientBlockSizingPolicy {
    static final long MIN_BLOCK_SIZE = 512L * 1024L;
    static final long MAX_BLOCK_SIZE = 4L * 1024L * 1024L;
    private static final int WINDOW_FRAMES = 120;
    private static final int EVALUATION_INTERVAL = 60;
    private static final int HIGH_WINDOWS_TO_GROW = 2;
    private static final int IDLE_FRAMES_TO_SHRINK = 300;

    private final long[] samples = new long[WINDOW_FRAMES];
    private final long[] sortScratch = new long[WINDOW_FRAMES];
    private int sampleCount;
    private int sampleCursor;
    private int framesSinceEvaluation;
    private int consecutiveHighWindows;
    private int idleFrames;
    private long blockSize = MIN_BLOCK_SIZE;

    long recordFrame(final long allocatedBytes) {
        long usage = Math.max(allocatedBytes, 0L);
        this.samples[this.sampleCursor] = usage;
        this.sampleCursor = (this.sampleCursor + 1) % this.samples.length;
        this.sampleCount = Math.min(this.sampleCount + 1, this.samples.length);
        this.framesSinceEvaluation++;

        if (usage <= this.blockSize / 2L) {
            this.idleFrames++;
        } else {
            this.idleFrames = 0;
        }

        if (this.blockSize > MIN_BLOCK_SIZE && this.idleFrames >= IDLE_FRAMES_TO_SHRINK) {
            this.blockSize = Math.max(MIN_BLOCK_SIZE, this.blockSize / 2L);
            this.idleFrames = 0;
            this.consecutiveHighWindows = 0;
            return this.blockSize;
        }

        if (this.sampleCount < WINDOW_FRAMES || this.framesSinceEvaluation < EVALUATION_INTERVAL) {
            return this.blockSize;
        }
        this.framesSinceEvaluation = 0;

        long p95 = percentile95();
        if (p95 > this.blockSize * 2L && this.blockSize < MAX_BLOCK_SIZE) {
            if (++this.consecutiveHighWindows >= HIGH_WINDOWS_TO_GROW) {
                this.blockSize = Math.min(MAX_BLOCK_SIZE, nextPowerOfTwo(p95));
                this.consecutiveHighWindows = 0;
                this.idleFrames = 0;
            }
        } else {
            this.consecutiveHighWindows = 0;
        }
        return this.blockSize;
    }

    long blockSize() {
        return this.blockSize;
    }

    private long percentile95() {
        System.arraycopy(this.samples, 0, this.sortScratch, 0, this.sampleCount);
        Arrays.sort(this.sortScratch, 0, this.sampleCount);
        int index = Math.max(0, (int) Math.ceil(this.sampleCount * 0.95) - 1);
        return this.sortScratch[index];
    }

    private static long nextPowerOfTwo(final long value) {
        if (value <= 1L) {
            return 1L;
        }
        long highest = Long.highestOneBit(value - 1L);
        return highest > Long.MAX_VALUE / 2L ? Long.MAX_VALUE : highest << 1;
    }
}
