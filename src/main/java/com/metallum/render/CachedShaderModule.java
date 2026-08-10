package com.metallum.render;

import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.SpvSampler;
import com.mojang.blaze3d.vulkan.glsl.SpvUniformBuffer;
import com.mojang.blaze3d.vulkan.glsl.SpvVariable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;

/** Immutable cached shader data that produces private, rebindable SPIR-V copies. */
final class CachedShaderModule implements AutoCloseable {
    static final CachedShaderModule INVALID = new CachedShaderModule(
            "invalid", null, List.of(), List.of(), List.of(), List.of());

    private final String name;
    private ByteBuffer spirv;
    private final List<SpvUniformBuffer> uniformBuffers;
    private final List<SpvSampler> samplers;
    private final List<SpvVariable> outputs;
    private final List<SpvVariable> inputs;

    private CachedShaderModule(
            final String name,
            final ByteBuffer spirv,
            final List<SpvUniformBuffer> uniformBuffers,
            final List<SpvSampler> samplers,
            final List<SpvVariable> outputs,
            final List<SpvVariable> inputs
    ) {
        this.name = name;
        this.spirv = spirv;
        this.uniformBuffers = List.copyOf(uniformBuffers);
        this.samplers = List.copyOf(samplers);
        this.outputs = List.copyOf(outputs);
        this.inputs = List.copyOf(inputs);
    }

    static CachedShaderModule takeOwnership(final IntermediaryShaderModule module) {
        if (module == IntermediaryShaderModule.INVALID || module.spirv() == null) {
            return INVALID;
        }
        return new CachedShaderModule(
                module.name(),
                module.spirv(),
                module.uniformBuffers(),
                module.samplers(),
                module.outputs(),
                module.inputs()
        );
    }

    boolean isValid() {
        return this.spirv != null;
    }

    IntermediaryShaderModule instantiate() {
        ByteBuffer source = this.spirv;
        if (source == null) {
            throw new IllegalStateException("Attempt to instantiate an invalid cached shader");
        }
        ByteBuffer sourceView = source.duplicate();
        ByteBuffer copy = MemoryUtil.memAlloc(sourceView.remaining());
        copy.put(sourceView).flip();
        return new IntermediaryShaderModule(
                this.name,
                copy,
                this.uniformBuffers,
                this.samplers,
                this.outputs,
                this.inputs
        );
    }

    @Override
    public void close() {
        if (this != INVALID && this.spirv != null) {
            MemoryUtil.memFree(this.spirv);
            this.spirv = null;
        }
    }
}
