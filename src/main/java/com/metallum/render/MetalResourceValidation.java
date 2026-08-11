package com.metallum.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuTexture;

/** Backend-specific limits which the Blaze3D resource base classes do not validate. */
final class MetalResourceValidation {
    private static final int MAX_TEXTURE_DIMENSION_2D = 16_384;

    private MetalResourceValidation() {
    }

    static long bufferAllocationSize(@GpuBuffer.Usage final int usage, final long size, final long maxBufferLength) {
        if (size < 0L || size > maxBufferLength) {
            throw new IllegalArgumentException(
                    "Metal buffer size " + size + " is outside the supported range 0.." + maxBufferLength);
        }

        final long allocationSize;
        try {
            allocationSize = size == 0L ? 16L : Math.addExact(size, 15L) & ~15L;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Metal buffer size overflows while applying alignment: " + size, exception);
        }
        if (allocationSize > maxBufferLength) {
            throw new IllegalArgumentException(
                    "Aligned Metal buffer size " + allocationSize + " exceeds " + maxBufferLength);
        }
        if (isCpuAccessible(usage) && allocationSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "CPU-accessible Metal buffers cannot exceed " + Integer.MAX_VALUE + " bytes");
        }
        return allocationSize;
    }

    static void texture(
            @GpuTexture.Usage final int usage,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        if (width <= 0 || height <= 0 || depthOrLayers <= 0 || mipLevels <= 0) {
            throw new IllegalArgumentException(
                    "Metal texture dimensions, layers, and mip levels must be positive: "
                            + width + "x" + height + "x" + depthOrLayers + ", mips=" + mipLevels);
        }
        if (width > MAX_TEXTURE_DIMENSION_2D || height > MAX_TEXTURE_DIMENSION_2D) {
            throw new IllegalArgumentException(
                    "Metal texture dimensions exceed " + MAX_TEXTURE_DIMENSION_2D + ": " + width + "x" + height);
        }
        int maximumMipLevels = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
        if (mipLevels > maximumMipLevels) {
            throw new IllegalArgumentException(
                    "Texture requests " + mipLevels + " mip levels, but " + width + "x" + height
                            + " supports at most " + maximumMipLevels);
        }
        if ((usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0) {
            if (width != height) {
                throw new IllegalArgumentException("Metal cube textures must be square: " + width + "x" + height);
            }
            if (depthOrLayers % 6 != 0) {
                throw new IllegalArgumentException(
                        "Metal cube texture layer count must be a positive multiple of 6: " + depthOrLayers);
            }
        }
    }

    static void textureView(final int textureMipLevels, final int baseMipLevel, final int mipLevels) {
        BufferCopySupport.requireRange(baseMipLevel, mipLevels, textureMipLevels, "Texture view mip levels");
        if (mipLevels == 0) {
            throw new IllegalArgumentException("Texture views must contain at least one mip level");
        }
    }

    private static boolean isCpuAccessible(@GpuBuffer.Usage final int usage) {
        return (usage & (GpuBuffer.USAGE_MAP_READ
                | GpuBuffer.USAGE_MAP_WRITE
                | GpuBuffer.USAGE_HINT_CLIENT_STORAGE)) != 0
                || (usage & GpuBuffer.USAGE_UNIFORM) != 0 && (usage & GpuBuffer.USAGE_COPY_DST) != 0;
    }
}
