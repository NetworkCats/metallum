package com.metallum.render;

import com.metallum.MetallumConfig;
import com.metallum.mtl.CAMetalLayer;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLMetal;

import java.lang.foreign.MemorySegment;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

@Environment(EnvType.CLIENT)
final class MetalSurface implements GpuSurfaceBackend {
    private static final Set<GpuSurface.PresentMode> SUPPORTED_PRESENT_MODES = Collections.unmodifiableSet(
            EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.MAILBOX));
    private final MetalDevice device;
    private final long metalView;
    private final CAMetalLayer metalLayer;
    private GpuSurface.Configuration configuration;
    private MetalCommandEncoder pendingPresentEncoder;
    private boolean closed;

    MetalSurface(final MetalDevice device, final long windowHandle) {
        this.device = device;
        this.metalView = SDLMetal.SDL_Metal_CreateView(windowHandle);
        if (this.metalView == 0L) {
            throw new IllegalStateException("Failed to create SDL Metal view: " + SDLError.SDL_GetError());
        }
        try {
            long layerHandle = SDLMetal.SDL_Metal_GetLayer(this.metalView);
            if (layerHandle == 0L) {
                throw new IllegalStateException("Failed to get SDL Metal layer: " + SDLError.SDL_GetError());
            }
            this.metalLayer = new CAMetalLayer(
                    MemorySegment.ofAddress(layerHandle),
                    device.metalDevice(),
                    MetallumConfig.INSTANCE.displayP3
            );
        } catch (Throwable throwable) {
            SDLMetal.SDL_Metal_DestroyView(this.metalView);
            throw throwable;
        }
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
                config.presentMode() != GpuSurface.PresentMode.FIFO
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

        metalEncoder.presentTextureToDrawable(this.metalLayer, textureView);
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
        this.device.waitForSubmittedGpuWork();
        this.metalLayer.close();
        SDLMetal.SDL_Metal_DestroyView(this.metalView);
    }

    @Override
    public @NonNull Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return SUPPORTED_PRESENT_MODES;
    }
}
