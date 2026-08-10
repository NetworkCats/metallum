package com.metallum.render;

import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

final class CachedShaderModuleTest {
    @Test
    void eachPipelineGetsAnIndependentRebindableSpirvCopy() {
        ByteBuffer ownedSpirv = MemoryUtil.memAlloc(8);
        ownedSpirv.putInt(0x07230203).putInt(17).flip();
        IntermediaryShaderModule source = new IntermediaryShaderModule(
                "test", ownedSpirv, List.of(), List.of(), List.of(), List.of());

        try (CachedShaderModule cached = CachedShaderModule.takeOwnership(source);
             IntermediaryShaderModule first = cached.instantiate();
             IntermediaryShaderModule second = cached.instantiate()) {
            assertNotSame(first.spirv(), second.spirv());
            first.spirv().putInt(4, 99);
            assertEquals(17, second.spirv().getInt(4));
            try (IntermediaryShaderModule third = cached.instantiate()) {
                assertEquals(17, third.spirv().getInt(4));
            }
        }
    }
}
