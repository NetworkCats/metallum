package com.metallum.mtl;

import com.metallum.objc.Msg;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;

@Environment(EnvType.CLIENT)
public record CAMetalDrawable(MemorySegment handle) {
    private static final Msg TEXTURE = Msg.of("texture", ADDRESS);
    private static final Msg PRESENT = Msg.ofVoid("present");

    public MemorySegment texture() {
        return TEXTURE.sendPtr(handle);
    }

    public void present() {
        PRESENT.send(handle);
    }
}
