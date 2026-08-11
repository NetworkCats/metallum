package com.metallum.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLTextureUsage {
    Unknown(0L),
    ShaderRead(1L),
    ShaderWrite(2L),
    RenderTarget(4L),
    PixelFormatView(0x10L),
    ShaderAtomic(0x20L);

    public final long value;

    MTLTextureUsage(final long value) {
        this.value = value;
    }
}
