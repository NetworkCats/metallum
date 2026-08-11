package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.*;

@Environment(EnvType.CLIENT)
public final class CAMetalLayer implements AutoCloseable {
    private static final MemorySegment CLS = ObjC.clazz("CAMetalLayer");
    private static final Msg NEW = Msg.of("new", ADDRESS);
    private static final Msg SET_DEVICE = Msg.ofVoid("setDevice:", ADDRESS);
    private static final Msg SET_FRAMEBUFFER_ONLY = Msg.ofVoid("setFramebufferOnly:", JAVA_BOOLEAN);
    private static final Msg SET_OPAQUE = Msg.ofVoid("setOpaque:", JAVA_BOOLEAN);
    private static final Msg SET_CONTENTS_SCALE = Msg.ofVoid("setContentsScale:", JAVA_DOUBLE);
    private static final Msg SET_PIXEL_FORMAT = Msg.ofVoid("setPixelFormat:", JAVA_LONG);
    private static final Msg SET_DRAWABLE_SIZE = Msg.ofVoid("setDrawableSize:", JAVA_DOUBLE, JAVA_DOUBLE);
    private static final Msg SET_ALLOWS_NEXT_DRAWABLE_TIMEOUT = Msg.ofVoid("setAllowsNextDrawableTimeout:", JAVA_BOOLEAN);
    private static final Msg SET_PRESENTS_WITH_TRANSACTION = Msg.ofVoid("setPresentsWithTransaction:", JAVA_BOOLEAN);
    private static final Msg SET_DISPLAY_SYNC_ENABLED = Msg.ofVoid("setDisplaySyncEnabled:", JAVA_BOOLEAN);
    private static final Msg NEXT_DRAWABLE = Msg.of("nextDrawable", true, ADDRESS);

    private MemorySegment handle;

    public CAMetalLayer(final MTLDevice device, final double contentsScale) {
        MemorySegment created = NEW.sendPtr(CLS);
        if (ObjC.isNil(created)) {
            throw new IllegalStateException("Failed to create CAMetalLayer");
        }
        try {
            SET_DEVICE.send(created, device.handle());
            SET_FRAMEBUFFER_ONLY.send(created, true);
            SET_OPAQUE.send(created, true);
            SET_CONTENTS_SCALE.send(created, contentsScale);
        } catch (Throwable throwable) {
            ObjC.release(created);
            throw throwable;
        }
        this.handle = created;
    }

    public MemorySegment handle() {
        if (ObjC.isNil(this.handle)) {
            throw new IllegalStateException("CAMetalLayer is closed");
        }
        return this.handle;
    }

    public void configure(final double width, final double height, final boolean immediatePresentMode) {
        MemorySegment layer = handle();
        SET_PIXEL_FORMAT.send(layer, MTLPixelFormat.BGRA8Unorm.value);
        SET_DRAWABLE_SIZE.send(layer, width, height);
        // nextDrawable can otherwise block forever while the window is hidden,
        // minimized, or moving between displays. Callers already handle nil.
        SET_ALLOWS_NEXT_DRAWABLE_TIMEOUT.send(layer, true);
        SET_PRESENTS_WITH_TRANSACTION.send(layer, false);
        SET_DISPLAY_SYNC_ENABLED.send(layer, !immediatePresentMode);
    }

    @Nullable
    CAMetalDrawable nextDrawable() {
        MemorySegment drawable = NEXT_DRAWABLE.sendPtr(handle());
        return ObjC.isNil(drawable) ? null : new CAMetalDrawable(drawable);
    }

    @Override
    public void close() {
        if (ObjC.isNil(this.handle)) {
            return;
        }
        MemorySegment released = this.handle;
        this.handle = MemorySegment.NULL;
        ObjC.release(released);
    }
}
