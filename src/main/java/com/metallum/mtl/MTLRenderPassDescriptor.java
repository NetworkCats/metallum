package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.*;

@Environment(EnvType.CLIENT)
public final class MTLRenderPassDescriptor implements AutoCloseable {
    public static final long LOAD_ACTION_DONT_CARE = 0L;
    public static final long LOAD_ACTION_LOAD = 1L;
    public static final long LOAD_ACTION_CLEAR = 2L;
    public static final long STORE_ACTION_DONT_CARE = 0L;
    public static final long STORE_ACTION_STORE = 1L;

    private static final MemorySegment MTL4_CLS = ObjC.findClass("MTL4RenderPassDescriptor");
    private static final Msg NEW = Msg.of("new", ADDRESS);
    private static final Msg COLOR_ATTACHMENTS = Msg.of("colorAttachments", ADDRESS);
    private static final Msg OBJECT_AT_INDEXED_SUBSCRIPT = Msg.of("objectAtIndexedSubscript:", ADDRESS, JAVA_LONG);
    private static final Msg DEPTH_ATTACHMENT = Msg.of("depthAttachment", ADDRESS);
    private static final Msg STENCIL_ATTACHMENT = Msg.of("stencilAttachment", ADDRESS);
    private static final Msg SET_TEXTURE = Msg.ofVoid("setTexture:", ADDRESS);
    private static final Msg SET_LEVEL = Msg.ofVoid("setLevel:", JAVA_LONG);
    private static final Msg SET_SLICE = Msg.ofVoid("setSlice:", JAVA_LONG);
    private static final Msg SET_LOAD_ACTION = Msg.ofVoid("setLoadAction:", JAVA_LONG);
    private static final Msg SET_STORE_ACTION = Msg.ofVoid("setStoreAction:", JAVA_LONG);
    private static final Msg SET_CLEAR_COLOR = Msg.ofVoid("setClearColor:", JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);
    private static final Msg SET_CLEAR_DEPTH = Msg.ofVoid("setClearDepth:", JAVA_DOUBLE);

    private final MemorySegment handle;
    private boolean closed;

    public MTLRenderPassDescriptor() {
        if (ObjC.isNil(MTL4_CLS)) {
            throw new IllegalStateException("MTL4RenderPassDescriptor is unavailable");
        }
        this.handle = NEW.sendPtr(MTL4_CLS);
        if (ObjC.isNil(this.handle)) {
            throw new IllegalStateException("Failed to create render pass descriptor");
        }
    }

    public MemorySegment handle() {
        return this.handle;
    }

    public void colorAttachment(
            final long index,
            final MemorySegment texture,
            final long loadAction,
            final long storeAction,
            @Nullable final Vector4fc clearColor
    ) {
        colorAttachment(index, texture, 0L, loadAction, storeAction, clearColor);
    }

    public void colorAttachment(
            final long index,
            final MemorySegment texture,
            final long level,
            final long loadAction,
            final long storeAction,
            @Nullable final Vector4fc clearColor
    ) {
        colorAttachment(index, texture, level, 0L, loadAction, storeAction, clearColor);
    }

    public void colorAttachment(
            final long index,
            final MemorySegment texture,
            final long level,
            final long slice,
            final long loadAction,
            final long storeAction,
            @Nullable final Vector4fc clearColor
    ) {
        MemorySegment attachment = OBJECT_AT_INDEXED_SUBSCRIPT.sendPtr(COLOR_ATTACHMENTS.sendPtr(this.handle), index);
        SET_TEXTURE.send(attachment, ObjC.orNil(texture));
        SET_LEVEL.send(attachment, level);
        SET_SLICE.send(attachment, slice);
        SET_LOAD_ACTION.send(attachment, loadAction);
        SET_STORE_ACTION.send(attachment, storeAction);
        if (loadAction == LOAD_ACTION_CLEAR && clearColor != null) {
            SET_CLEAR_COLOR.send(attachment, clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w());
        }
    }

    public void depthAttachment(final MemorySegment texture, final long loadAction, final long storeAction, @Nullable final Double clearDepth) {
        depthAttachment(texture, 0L, loadAction, storeAction, clearDepth);
    }

    public void depthAttachment(final MemorySegment texture, final long level, final long loadAction, final long storeAction, @Nullable final Double clearDepth) {
        depthAttachment(texture, level, 0L, loadAction, storeAction, clearDepth);
    }

    public void depthAttachment(final MemorySegment texture, final long level, final long slice, final long loadAction, final long storeAction, @Nullable final Double clearDepth) {
        MemorySegment attachment = DEPTH_ATTACHMENT.sendPtr(this.handle);
        SET_TEXTURE.send(attachment, ObjC.orNil(texture));
        SET_LEVEL.send(attachment, level);
        SET_SLICE.send(attachment, slice);
        SET_LOAD_ACTION.send(attachment, loadAction);
        SET_STORE_ACTION.send(attachment, storeAction);
        if (loadAction == LOAD_ACTION_CLEAR && clearDepth != null) {
            SET_CLEAR_DEPTH.send(attachment, clearDepth.doubleValue());
        }
    }

    public void stencilAttachment(final MemorySegment texture, final long loadAction, final long storeAction) {
        stencilAttachment(texture, 0L, loadAction, storeAction);
    }

    public void stencilAttachment(final MemorySegment texture, final long level, final long loadAction, final long storeAction) {
        stencilAttachment(texture, level, 0L, loadAction, storeAction);
    }

    public void stencilAttachment(final MemorySegment texture, final long level, final long slice, final long loadAction, final long storeAction) {
        MemorySegment attachment = STENCIL_ATTACHMENT.sendPtr(this.handle);
        SET_TEXTURE.send(attachment, ObjC.orNil(texture));
        SET_LEVEL.send(attachment, level);
        SET_SLICE.send(attachment, slice);
        SET_LOAD_ACTION.send(attachment, loadAction);
        SET_STORE_ACTION.send(attachment, storeAction);
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            ObjC.release(this.handle);
        }
    }
}
