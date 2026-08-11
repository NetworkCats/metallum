package com.metallum.mtl;

import com.mojang.blaze3d.GpuFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MTLTextureUsageTest {
    @Test
    void matchesMetalSdkBitValues() {
        assertEquals(0x0001L, MTLTextureUsage.ShaderRead.value);
        assertEquals(0x0002L, MTLTextureUsage.ShaderWrite.value);
        assertEquals(0x0004L, MTLTextureUsage.RenderTarget.value);
        assertEquals(0x0010L, MTLTextureUsage.PixelFormatView.value);
        assertEquals(0x0020L, MTLTextureUsage.ShaderAtomic.value);
    }

    @Test
    void mapsPackedUintTextureFormat() {
        assertEquals(MTLPixelFormat.RGB10A2Uint, MTLPixelFormat.from(GpuFormat.RGB10A2_UINT));
    }

    @Test
    void mapsSmallAndPackedVertexFormats() {
        assertEquals(MTLVertexFormat.UChar2, MTLVertexFormat.from(GpuFormat.RG8_UINT));
        assertEquals(MTLVertexFormat.Char2Normalized, MTLVertexFormat.from(GpuFormat.RG8_SNORM));
        assertEquals(MTLVertexFormat.FloatRG11B10, MTLVertexFormat.from(GpuFormat.RG11B10_FLOAT));
        assertEquals(MTLVertexFormat.UInt1010102Normalized, MTLVertexFormat.from(GpuFormat.RGB10A2_UNORM));
    }
}
