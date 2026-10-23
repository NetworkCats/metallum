package com.metallum.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Lets Distant Horizons pick its renderpearl (Vulkan-style) path when Metal is active. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper", remap = false)
public class DistantHorizonsRenderWrapperMixin {
    @Redirect(
            method = "getMcRenderingApi",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/String;equalsIgnoreCase(Ljava/lang/String;)Z"
            ),
            remap = false
    )
    private boolean metallum$treatMetalLikeVulkan(final String backendName, final String expected) {
        return backendName.equalsIgnoreCase(expected) || "Metal".equalsIgnoreCase(backendName);
    }
}
