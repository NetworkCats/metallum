package com.metallum.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;

public final class MetalDrawContext extends DrawContext {
    private MetalRenderPass metalPass;

    @Override
    public void setContext(RenderPass pass, RenderPipeline pipeline) {
        this.pass = pass;
        this.metalPass = (MetalRenderPass) ((net.caffeinemc.mods.sodium.mixin.core.RenderPassAccessor) pass).getBackend();
    }

    @Override
    public void updateData(RenderRegion region, CameraTransform camera) {
        float x = getCameraTranslation(region.getOriginX(), camera.intX, camera.fracX);
        float y = getCameraTranslation(region.getOriginY(), camera.intY, camera.fracY);
        float z = getCameraTranslation(region.getOriginZ(), camera.intZ, camera.fracZ);

        this.metalPass.setSodiumPushConstants(
                x,
                y,
                z,
                Math.toIntExact(System.currentTimeMillis() - region.getCreationTime()),
                region.getId()
        );
    }

    void draw(final MetalIndirectDrawBatch batch) {
        if (this.metalPass == null) {
            throw new IllegalStateException("Sodium draw context has no active render pass");
        }
        this.metalPass.drawBatch(batch);
    }

    @Override
    public void rotate() {
        // ICB batches own their command storage and need no per-frame upload ring.
    }

    @Override
    public void delete() {
        this.metalPass = null;
        this.pass = null;
    }

    @Override
    public void endDraw() {
    }
}
