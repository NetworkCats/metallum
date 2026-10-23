package com.metallum.render;

import com.mojang.renderpearl.api.commands.GpuFence;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class MetalFence implements GpuFence {
    private final MetalCommandEncoder encoder;
    private final long submitIndex;
    private boolean closed;

    MetalFence(final MetalCommandEncoder encoder, final long submitIndex) {
        this.encoder = encoder;
        this.submitIndex = submitIndex;
    }

    @Override
    public void close() {
        this.closed = true;
    }

    @Override
    public boolean awaitCompletion(final long timeoutNS) {
        return this.closed || this.encoder.awaitSubmitCompletion(this.submitIndex, timeoutMillis(timeoutNS));
    }

    /** Negative timeouts wait indefinitely (MappableRingBuffer passes -1); zero only polls. */
    static long timeoutMillis(final long timeoutNS) {
        if (timeoutNS < 0L) {
            return Long.MAX_VALUE;
        }
        return timeoutNS == 0L ? 0L : 1L + (timeoutNS - 1L) / 1_000_000L;
    }
}
