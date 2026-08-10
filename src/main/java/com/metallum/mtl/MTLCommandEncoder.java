package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public abstract class MTLCommandEncoder {
    private static final Msg END_ENCODING = Msg.ofVoid("endEncoding");

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

    public void endEncoding() {
        if (ObjC.isNil(this.handle)) {
            return;
        }
        END_ENCODING.send(this.handle);
        ObjC.release(this.handle);
        this.handle = MemorySegment.NULL;
    }
}
