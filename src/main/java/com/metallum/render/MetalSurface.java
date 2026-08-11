package com.metallum.render;

import com.metallum.mtl.CAMetalLayer;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

@Environment(EnvType.CLIENT)
final class MetalSurface implements GpuSurfaceBackend {
    private static final Set<GpuSurface.PresentMode> SUPPORTED_PRESENT_MODES = Collections.unmodifiableSet(
            EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE));
    private final MetalDevice device;
    private final CAMetalLayer metalLayer;
    private GpuSurface.Configuration configuration;
    private MetalCommandEncoder pendingPresentEncoder;
    private boolean closed;

    MetalSurface(final MetalDevice device, final CAMetalLayer metalLayer) {
        this.device = device;
        this.metalLayer = metalLayer;
    }

    @Override
    public void configure(final GpuSurface.Configuration config) throws SurfaceException {
        if (this.closed) {
            throw new SurfaceException("Metal surface is closed");
        }
        if (config.width() <= 0 || config.height() <= 0) {
            throw new SurfaceException("Metal surface configuration must be positive, got " + config.width() + "x" + config.height());
        }
        if (!SUPPORTED_PRESENT_MODES.contains(config.presentMode())) {
            throw new SurfaceException("Unsupported Metal present mode: " + config.presentMode());
        }

        this.metalLayer.configure(
                config.width(),
                config.height(),
                config.presentMode() == GpuSurface.PresentMode.IMMEDIATE
        );

        this.configuration = config;
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() {
    }

    @Override
    public void blitFromTexture(final @NonNull CommandEncoderBackend commandEncoder, final @NonNull GpuTextureView textureView) {
        if (this.closed) {
            throw new IllegalStateException("Metal surface is closed");
        }
        if (this.configuration == null) {
            throw new IllegalStateException("Metal surface is not configured");
        }
        if (this.pendingPresentEncoder != null) {
            throw new IllegalStateException("Metal surface already has a pending presentation");
        }
        if (!(commandEncoder instanceof MetalCommandEncoder metalEncoder)) {
            throw new IllegalArgumentException("Metal surface requires MetalCommandEncoder");
        }
        if (!metalEncoder.belongsTo(this.device)) {
            throw new IllegalArgumentException("Metal surface command encoder belongs to another device");
        }

        metalEncoder.presentTextureToDrawable(metalLayer, textureView);
        this.pendingPresentEncoder = metalEncoder;
    }

    @Override
    public void present() {
        if (this.pendingPresentEncoder == null) {
            throw new IllegalStateException("Metal surface has no pending presentation");
        }
        MetalCommandEncoder encoder = this.pendingPresentEncoder;
        this.pendingPresentEncoder = null;
        encoder.submit();
        Stats.endFrame();
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.pendingPresentEncoder = null;
        this.configuration = null;
    }

    @Override
    public @NonNull Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return SUPPORTED_PRESENT_MODES;
    }
}
