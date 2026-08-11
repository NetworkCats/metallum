package com.metallum.render;

import com.metallum.Metallum;
import com.metallum.mtl.CAMetalLayer;
import com.metallum.mtl.MTLDevice;
import com.metallum.objc.Cocoa;
import com.metallum.objc.ObjC;
import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeCocoa;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public class MetalBackend implements GpuBackend {
    @Override
    public @NonNull String getName() {
        return "Metal";
    }

    @Override
    public void setWindowHints() {
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
    }

    @Override
    public void handleWindowCreationErrors(final GLFWErrorCapture.Error error) throws BackendCreationException {
        throw new BackendCreationException(error.toString(), BackendCreationException.Reason.GLFW_ERROR);
    }

    @Override
    public @NonNull GpuDevice createDevice(
            final long window, final @NonNull ShaderSource defaultShaderSource, final @NonNull GpuDebugOptions debugOptions, final @NonNull Runnable criticalShaderLoader
    ) throws BackendCreationException {
        MTLDevice metalDevice = MTLDevice.createSystemDefault();
        if (metalDevice == null) {
            throw new BackendCreationException("MTLCreateSystemDefaultDevice returned null", BackendCreationException.Reason.OTHER);
        }

        Cocoa cocoa;
        String deviceName;
        try {
            deviceName = metalDevice.name();
            if (deviceName.isBlank()) deviceName = "<unknown Metal device>";
            cocoa = new Cocoa(
                    MemorySegment.ofAddress(GLFWNativeCocoa.glfwGetCocoaWindow(window)),
                    MemorySegment.ofAddress(GLFWNativeCocoa.glfwGetCocoaView(window))
            );
        } catch (Throwable throwable) {
            ObjC.release(metalDevice.handle());
            throw new BackendCreationException(errorMessage(throwable), BackendCreationException.Reason.GLFW_ERROR);
        }

        CAMetalLayer metalLayer;
        try {
            metalLayer = new CAMetalLayer(metalDevice, cocoa.backingScaleFactor());
        } catch (Throwable throwable) {
            ObjC.release(metalDevice.handle());
            throw new BackendCreationException(errorMessage(throwable), BackendCreationException.Reason.OTHER);
        }

        MetalDevice backend = null;
        boolean layerAttached = false;
        try {
            layerAttached = true;
            cocoa.setViewLayer(metalLayer.handle());
            Metallum.LOGGER.info("Metal device: {}", deviceName);

            backend = new MetalDevice(defaultShaderSource, debugOptions, metalDevice.handle(), metalLayer, deviceName, cocoa);
            Metallum.LOGGER.info(
                    "Metal command path: {}; Metal 4 available: {}; Metal 4 compiler: {}; explicit residency: {}; concurrent compilation: {}",
                    backend.usesMetal4CommandPath() ? "Metal 4" : "Metal 3",
                    backend.metal4Available(),
                    backend.usesMetal4Compiler(),
                    backend.usesExplicitResidency(),
                    backend.concurrentCompilationEnabled()
            );
            return new GpuDevice(backend, criticalShaderLoader);
        } catch (Throwable throwable) {
            if (backend != null) {
                try {
                    backend.close();
                } catch (Throwable closeFailure) {
                    throwable.addSuppressed(closeFailure);
                }
            } else {
                if (layerAttached) {
                    try {
                        cocoa.clearViewLayer();
                    } catch (Throwable cleanupFailure) {
                        throwable.addSuppressed(cleanupFailure);
                    }
                }
                try {
                    metalLayer.close();
                } catch (Throwable cleanupFailure) {
                    throwable.addSuppressed(cleanupFailure);
                }
                ObjC.release(metalDevice.handle());
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
