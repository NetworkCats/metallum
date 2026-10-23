package com.metallum.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.renderpearl.api.pipeline.UniformType;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.spvc.Spvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalCrossShaderCompilerTest {
    @Test
    void recognizesSignedAndUnsignedShaderIntegerInputs() {
        assertTrue(MetalCrossShaderCompiler.isIntegerBaseType(Spvc.SPVC_BASETYPE_INT16));
        assertTrue(MetalCrossShaderCompiler.isIntegerBaseType(Spvc.SPVC_BASETYPE_UINT16));
        assertTrue(MetalCrossShaderCompiler.isIntegerBaseType(Spvc.SPVC_BASETYPE_INT32));
        assertTrue(MetalCrossShaderCompiler.isIntegerBaseType(Spvc.SPVC_BASETYPE_UINT32));
        assertFalse(MetalCrossShaderCompiler.isIntegerBaseType(Spvc.SPVC_BASETYPE_FP32));
    }

    @Test
    void mapsUniformsOntoSeparateMetalNamespaces() {
        MetalCrossShaderCompiler.MetalIndices indices = MetalCrossShaderCompiler.MetalIndices.of(List.of(
                new UniformDescription("Projection", UniformType.UNIFORM_BUFFER, null),
                new UniformDescription("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER, null),
                new UniformDescription("Lights", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT),
                new UniformDescription("Fog", UniformType.UNIFORM_BUFFER, null),
                new UniformDescription("Sampler1", UniformType.COMBINED_IMAGE_SAMPLER, null)
        ));

        // Buffers: Projection=0, Fog=1. Textures: Sampler0=0, Lights=1, Sampler1=2.
        assertArrayEquals(new int[]{0, 0, 1, 1, 2}, indices.byBinding());
        // Only sampled images consume sampler slots.
        assertArrayEquals(new int[]{-1, 0, -1, -1, 1}, indices.samplerByBinding());
        // Push constants follow the last uniform buffer.
        assertEquals(2, indices.pushConstants());
    }
}
