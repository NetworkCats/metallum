package com.metallum.render;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MetalResourceValidationTest {
    @Test
    void bufferAlignmentIsCheckedAndZeroBuffersGetSafeBacking() {
        assertEquals(16L, MetalResourceValidation.bufferAllocationSize(0, 0L, 1024L));
        assertEquals(32L, MetalResourceValidation.bufferAllocationSize(0, 17L, 1024L));
        assertThrows(IllegalArgumentException.class,
                () -> MetalResourceValidation.bufferAllocationSize(0, Long.MAX_VALUE, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> MetalResourceValidation.bufferAllocationSize(GpuBuffer.USAGE_MAP_WRITE,
                        Integer.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void cubemapsRequireSquareFacesAndCompleteSixFaceGroups() {
        MetalResourceValidation.texture(GpuTexture.USAGE_CUBEMAP_COMPATIBLE, 256, 256, 6, 9);
        assertThrows(IllegalArgumentException.class,
                () -> MetalResourceValidation.texture(GpuTexture.USAGE_CUBEMAP_COMPATIBLE, 256, 128, 6, 8));
        assertThrows(IllegalArgumentException.class,
                () -> MetalResourceValidation.texture(GpuTexture.USAGE_CUBEMAP_COMPATIBLE, 256, 256, 7, 8));
    }

    @Test
    void mipCountsAndViewsStayWithinTheTexture() {
        MetalResourceValidation.texture(0, 16, 8, 1, 5);
        assertThrows(IllegalArgumentException.class,
                () -> MetalResourceValidation.texture(0, 16, 8, 1, 6));
        MetalResourceValidation.textureView(5, 1, 4);
        assertThrows(IndexOutOfBoundsException.class,
                () -> MetalResourceValidation.textureView(5, 4, 2));
        assertThrows(IllegalArgumentException.class,
                () -> MetalResourceValidation.textureView(5, 0, 0));
    }
}
