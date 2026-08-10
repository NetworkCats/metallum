package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public abstract class MTLCommandEncoder {
    private static final Msg END_ENCODING = Msg.ofVoid("endEncoding");
    private static final Msg MTL4_CONSUMER_BARRIER = Msg.ofVoid(
            "barrierAfterQueueStages:beforeStages:visibilityOptions:",
            java.lang.foreign.ValueLayout.JAVA_LONG,
            java.lang.foreign.ValueLayout.JAVA_LONG,
            java.lang.foreign.ValueLayout.JAVA_LONG
    );
    private static final Msg MTL4_ENCODER_BARRIER = Msg.ofVoid(
            "barrierAfterEncoderStages:beforeEncoderStages:visibilityOptions:",
            java.lang.foreign.ValueLayout.JAVA_LONG,
            java.lang.foreign.ValueLayout.JAVA_LONG,
            java.lang.foreign.ValueLayout.JAVA_LONG
    );

    MemorySegment handle;
    final boolean metal4;

    MTLCommandEncoder(final MemorySegment handle) {
        this(handle, false);
    }

    MTLCommandEncoder(final MemorySegment handle, final boolean metal4) {
        this.handle = handle;
        this.metal4 = metal4;
    }

    public MemorySegment handle() {
        if (ObjC.isNil(this.handle)) {
            throw new IllegalStateException(getClass().getSimpleName() + " is closed");
        }
        return this.handle;
    }

    public final boolean isMetal4() {
        return this.metal4;
    }

    public void endEncoding() {
        if (ObjC.isNil(this.handle)) {
            return;
        }
        END_ENCODING.send(this.handle);
        ObjC.release(this.handle);
        this.handle = MemorySegment.NULL;
    }

    public final void barrierAfterQueueStages(
            final long producerStages,
            final long consumerStages,
            final boolean deviceVisibility
    ) {
        if (!this.metal4) {
            throw new IllegalStateException("Queue barriers require Metal 4");
        }
        MTL4_CONSUMER_BARRIER.send(
                handle(), producerStages, consumerStages, deviceVisibility ? 1L : 0L);
    }

    public final void barrierAfterEncoderStages(
            final long producerStages,
            final long consumerStages,
            final boolean deviceVisibility
    ) {
        if (!this.metal4) {
            throw new IllegalStateException("Encoder barriers require Metal 4");
        }
        MTL4_ENCODER_BARRIER.send(
                handle(), producerStages, consumerStages, deviceVisibility ? 1L : 0L);
    }
}
