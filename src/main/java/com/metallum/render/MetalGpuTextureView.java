package com.metallum.render;

import com.metallum.mtl.MTLTexture;
import com.metallum.objc.ObjC;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
final class MetalGpuTextureView extends BaseGpuTextureView {
    private boolean closed;
    @Nullable
    private MemorySegment nativeHandle;

    MetalGpuTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
        ((MetalGpuTexture) texture).addView();
    }

    /**
     * Returns the native texture for this view. The handle is retained by the view and released
     * through the deferred destruction queue, so a view closed mid-frame stays usable by commands
     * already recorded or still being recorded for that frame.
     */
    MemorySegment nativeHandle() {
        if (this.nativeHandle == null) {
            if (this.closed) {
                throw new IllegalStateException("Texture view is closed");
            }
            MetalGpuTexture texture = (MetalGpuTexture) this.texture();
            if (this.baseMipLevel() == 0 && this.mipLevels() >= texture.getMipLevels()) {
                this.nativeHandle = ObjC.retain(texture.nativeHandle());
            } else {
                MemorySegment viewHandle = MTLTexture.newTextureView(
                        texture.nativeHandle(),
                        this.baseMipLevel(),
                        this.mipLevels()
                );
                if (ObjC.isNil(viewHandle)) {
                    throw new IllegalStateException(
                            "Failed to create Metal texture view for mip range " + this.baseMipLevel() + "+" + this.mipLevels()
                    );
                }
                this.nativeHandle = viewHandle;
            }
        }
        return this.nativeHandle;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        MetalGpuTexture texture = (MetalGpuTexture) this.texture();
        if (this.nativeHandle != null) {
            texture.queueNativeRelease(this.nativeHandle);
        }
        this.closed = true;
        texture.removeView();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }
}
