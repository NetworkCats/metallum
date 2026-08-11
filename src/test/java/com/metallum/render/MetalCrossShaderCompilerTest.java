package com.metallum.render;

import org.junit.jupiter.api.Test;
import org.lwjgl.util.spvc.Spvc;

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
}
