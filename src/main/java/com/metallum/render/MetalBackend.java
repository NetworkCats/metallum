package com.metallum.render;

import com.metallum.Metallum;
import com.metallum.mtl.MTLDevice;
import com.metallum.objc.ObjC;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;

@Environment(EnvType.CLIENT)
public class MetalBackend implements GpuBackend {
    @Override
    public @NonNull String getName() {
        return "Metal";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
            throw new BackendCreationException("Metal is only available on macOS", BackendCreationException.Reason.PLATFORM_ERROR);
        }
    }

    @Override
    public void unloadLibrary() {
    }

    @Override
    public long createWindow(@Nullable final String title, final int width, final int height, final long flags) {
        return SDLVideo.SDL_CreateWindow(title, width, height, SDLVideo.SDL_WINDOW_METAL | flags);
    }

    @Override
    public @NonNull GpuDevice createDevice(final @NonNull GpuDebugOptions debugOptions) throws BackendCreationException {
        MTLDevice metalDevice = MTLDevice.createSystemDefault();
        if (metalDevice == null) {
            throw new BackendCreationException("MTLCreateSystemDefaultDevice returned null", BackendCreationException.Reason.PLATFORM_ERROR);
        }

        String deviceName;
        try {
            deviceName = metalDevice.name();
            if (deviceName.isBlank()) deviceName = "<unknown Metal device>";
            if (!metalDevice.supportsMetal4()) {
                ObjC.release(metalDevice.handle());
                throw new BackendCreationException(
                        "Metallum requires Metal 4 (macOS 26 or newer on Apple silicon); " + deviceName + " does not support it",
                        BackendCreationException.Reason.PLATFORM_ERROR);
            }
        } catch (BackendCreationException exception) {
            throw exception;
        } catch (Throwable throwable) {
            ObjC.release(metalDevice.handle());
            throw new BackendCreationException(errorMessage(throwable), BackendCreationException.Reason.PLATFORM_ERROR);
        }

        Metallum.LOGGER.info("Metal device: {}", deviceName);
        MetalDevice backend;
        try {
            backend = new MetalDevice(debugOptions, metalDevice.handle(), deviceName);
        } catch (Throwable throwable) {
            ObjC.release(metalDevice.handle());
            throw new BackendCreationException(
                    "Metal device initialization failed: " + errorMessage(throwable),
                    BackendCreationException.Reason.OTHER);
        }

        try {
            Metallum.LOGGER.info(
                    "Metal 4 command path; concurrent compilation: {}",
                    backend.concurrentCompilationEnabled()
            );
            return new FrontendGpuDevice(backend);
        } catch (Throwable throwable) {
            try {
                backend.close();
            } catch (Throwable closeFailure) {
                throwable.addSuppressed(closeFailure);
            }
            throw new BackendCreationException(
                    "Metal device initialization failed: " + errorMessage(throwable),
                    BackendCreationException.Reason.OTHER);
        }
    }

    private static String errorMessage(final Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
