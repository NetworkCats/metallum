package com.metallum.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class PipelineCacheIdentityTest {
    @Test
    void hashIsStableAndLengthDelimited() {
        String first = PipelineCacheIdentity.hash("device", "metal4", "shader");
        assertEquals(first, PipelineCacheIdentity.hash("device", "metal4", "shader"));
        assertEquals(32, first.length());

        assertNotEquals(
                PipelineCacheIdentity.hash("ab", "c"),
                PipelineCacheIdentity.hash("a", "bc"));
        assertNotEquals(first, PipelineCacheIdentity.hash("device", "metal3", "shader"));
    }
}
