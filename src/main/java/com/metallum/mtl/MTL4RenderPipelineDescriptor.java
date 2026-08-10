package com.metallum.mtl;

import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public final class MTL4RenderPipelineDescriptor implements AutoCloseable {
    private static final MemorySegment DESCRIPTOR_CLASS = ObjC.findClass("MTL4RenderPipelineDescriptor");
    private static final MemorySegment FUNCTION_DESCRIPTOR_CLASS =
            ObjC.findClass("MTL4LibraryFunctionDescriptor");
    private static final Msg NEW = Msg.of("new", ADDRESS);
    private static final Msg SET_VERTEX_FUNCTION_DESCRIPTOR =
            Msg.ofVoid("setVertexFunctionDescriptor:", ADDRESS);
    private static final Msg SET_FRAGMENT_FUNCTION_DESCRIPTOR =
            Msg.ofVoid("setFragmentFunctionDescriptor:", ADDRESS);
    private static final Msg SET_LIBRARY = Msg.ofVoid("setLibrary:", ADDRESS);
    private static final Msg SET_NAME = Msg.ofVoid("setName:", ADDRESS);
    private static final Msg SET_VERTEX_DESCRIPTOR = Msg.ofVoid("setVertexDescriptor:", ADDRESS);
    private static final Msg COLOR_ATTACHMENTS = Msg.of("colorAttachments", ADDRESS);
    private static final Msg OBJECT_AT_INDEXED_SUBSCRIPT =
            Msg.of("objectAtIndexedSubscript:", ADDRESS, JAVA_LONG);
    private static final Msg SET_PIXEL_FORMAT = Msg.ofVoid("setPixelFormat:", JAVA_LONG);
    private static final Msg SET_WRITE_MASK = Msg.ofVoid("setWriteMask:", JAVA_LONG);
    private static final Msg SET_BLENDING_STATE = Msg.ofVoid("setBlendingState:", JAVA_LONG);
    private static final Msg SET_SOURCE_RGB_BLEND_FACTOR = Msg.ofVoid("setSourceRGBBlendFactor:", JAVA_LONG);
    private static final Msg SET_DESTINATION_RGB_BLEND_FACTOR = Msg.ofVoid("setDestinationRGBBlendFactor:", JAVA_LONG);
    private static final Msg SET_RGB_BLEND_OPERATION = Msg.ofVoid("setRgbBlendOperation:", JAVA_LONG);
    private static final Msg SET_SOURCE_ALPHA_BLEND_FACTOR = Msg.ofVoid("setSourceAlphaBlendFactor:", JAVA_LONG);
    private static final Msg SET_DESTINATION_ALPHA_BLEND_FACTOR = Msg.ofVoid("setDestinationAlphaBlendFactor:", JAVA_LONG);
    private static final Msg SET_ALPHA_BLEND_OPERATION = Msg.ofVoid("setAlphaBlendOperation:", JAVA_LONG);
    private static final Msg SET_INPUT_PRIMITIVE_TOPOLOGY =
            Msg.ofVoid("setInputPrimitiveTopology:", JAVA_LONG);
    private static final Msg SET_SUPPORT_ICB =
            Msg.ofVoid("setSupportIndirectCommandBuffers:", JAVA_LONG);

    private MemorySegment handle;

    public MTL4RenderPipelineDescriptor() {
        if (ObjC.isNil(DESCRIPTOR_CLASS) || ObjC.isNil(FUNCTION_DESCRIPTOR_CLASS)) {
            throw new IllegalStateException("Metal 4 render pipeline descriptors are unavailable");
        }
        this.handle = NEW.sendPtr(DESCRIPTOR_CLASS);
        if (ObjC.isNil(this.handle)) {
            throw new IllegalStateException("Failed to create MTL4RenderPipelineDescriptor");
        }
    }

    public MemorySegment handle() {
        if (ObjC.isNil(this.handle)) {
            throw new IllegalStateException("MTL4RenderPipelineDescriptor is closed");
        }
        return this.handle;
    }

    public void setFunctions(
            final MemorySegment vertexLibrary,
            final String vertexEntry,
            final MemorySegment fragmentLibrary,
            final String fragmentEntry
    ) {
        MemorySegment vertex = functionDescriptor(vertexLibrary, vertexEntry);
        MemorySegment fragment = functionDescriptor(fragmentLibrary, fragmentEntry);
        try {
            SET_VERTEX_FUNCTION_DESCRIPTOR.send(handle(), vertex);
            SET_FRAGMENT_FUNCTION_DESCRIPTOR.send(handle(), fragment);
        } finally {
            ObjC.release(vertex);
            ObjC.release(fragment);
        }
    }

    public void setVertexDescriptor(final MTLVertexDescriptor vertexDescriptor) {
        SET_VERTEX_DESCRIPTOR.send(handle(), vertexDescriptor.handle());
    }

    public void setColorAttachmentFormat(final long index, final long pixelFormat) {
        SET_PIXEL_FORMAT.send(colorAttachment(index), pixelFormat);
    }

    public void setInputPrimitiveTopology(final long topologyClass) {
        SET_INPUT_PRIMITIVE_TOPOLOGY.send(handle(), topologyClass);
    }

    public void setSupportIndirectCommandBuffers(final boolean enabled) {
        SET_SUPPORT_ICB.send(handle(), enabled ? 1L : 0L);
    }

    public void setBlendState(
            final long index,
            final MTLBlendFactor sourceColorBlendFactor,
            final MTLBlendFactor destinationColorBlendFactor,
            final MTLBlendOperation colorBlendOperation,
            final MTLBlendFactor sourceAlphaBlendFactor,
            final MTLBlendFactor destinationAlphaBlendFactor,
            final MTLBlendOperation alphaBlendOperation,
            final long writeMask
    ) {
        MemorySegment attachment = colorAttachment(index);
        SET_WRITE_MASK.send(attachment, writeMask);
        SET_BLENDING_STATE.send(attachment, 1L);
        SET_SOURCE_RGB_BLEND_FACTOR.send(attachment, sourceColorBlendFactor.value);
        SET_DESTINATION_RGB_BLEND_FACTOR.send(attachment, destinationColorBlendFactor.value);
        SET_RGB_BLEND_OPERATION.send(attachment, colorBlendOperation.value);
        SET_SOURCE_ALPHA_BLEND_FACTOR.send(attachment, sourceAlphaBlendFactor.value);
        SET_DESTINATION_ALPHA_BLEND_FACTOR.send(attachment, destinationAlphaBlendFactor.value);
        SET_ALPHA_BLEND_OPERATION.send(attachment, alphaBlendOperation.value);
    }

    public void disableBlending(final long index, final long writeMask) {
        MemorySegment attachment = colorAttachment(index);
        SET_WRITE_MASK.send(attachment, writeMask);
        SET_BLENDING_STATE.send(attachment, 0L);
    }

    private MemorySegment colorAttachment(final long index) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            return OBJECT_AT_INDEXED_SUBSCRIPT.sendPtr(
                    COLOR_ATTACHMENTS.sendPtr(handle()), index);
        }
    }

    private static MemorySegment functionDescriptor(final MemorySegment library, final String entry) {
        MemorySegment descriptor = NEW.sendPtr(FUNCTION_DESCRIPTOR_CLASS);
        if (ObjC.isNil(descriptor)) {
            throw new IllegalStateException("Failed to create MTL4LibraryFunctionDescriptor");
        }
        MemorySegment nsName = ObjC.nsString(entry);
        try {
            SET_LIBRARY.send(descriptor, library);
            SET_NAME.send(descriptor, nsName);
            return descriptor;
        } catch (RuntimeException exception) {
            ObjC.release(descriptor);
            throw exception;
        } finally {
            ObjC.release(nsName);
        }
    }

    @Override
    public void close() {
        if (!ObjC.isNil(this.handle)) {
            ObjC.release(this.handle);
            this.handle = MemorySegment.NULL;
        }
    }
}
