package com.metallum.mtl;

import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** CPU-encoded, reusable indexed draw commands. */
@Environment(EnvType.CLIENT)
public final class MTLIndirectCommandBuffer implements AutoCloseable {
    private static final MemorySegment DESCRIPTOR_CLASS =
            ObjC.findClass("MTLIndirectCommandBufferDescriptor");
    private static final Msg NEW = Msg.of("new", ADDRESS);
    private static final Msg SET_COMMAND_TYPES = Msg.ofVoid("setCommandTypes:", JAVA_LONG);
    private static final Msg SET_INHERIT_PIPELINE = Msg.ofVoid("setInheritPipelineState:", JAVA_BOOLEAN);
    private static final Msg SET_INHERIT_BUFFERS = Msg.ofVoid("setInheritBuffers:", JAVA_BOOLEAN);
    private static final Msg SET_MAX_VERTEX_BUFFERS =
            Msg.ofVoid("setMaxVertexBufferBindCount:", JAVA_LONG);
    private static final Msg SET_MAX_FRAGMENT_BUFFERS =
            Msg.ofVoid("setMaxFragmentBufferBindCount:", JAVA_LONG);
    private static final Msg NEW_ICB = Msg.of(
            "newIndirectCommandBufferWithDescriptor:maxCommandCount:options:",
            ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG);
    private static final Msg COMMAND_AT_INDEX =
            Msg.of("indirectRenderCommandAtIndex:", ADDRESS, JAVA_LONG);
    private static final Msg DRAW_INDEXED = Msg.ofVoid(
            "drawIndexedPrimitives:indexCount:indexType:indexBuffer:indexBufferOffset:instanceCount:baseVertex:baseInstance:",
            JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);

    private MemorySegment handle;
    private final int size;

    private MTLIndirectCommandBuffer(final MemorySegment handle, final int size) {
        this.handle = handle;
        this.size = size;
    }

    public static MTLIndirectCommandBuffer create(final MTLDevice device, final int commandCount) {
        if (commandCount <= 0) {
            throw new IllegalArgumentException("ICB command count must be positive");
        }
        if (ObjC.isNil(DESCRIPTOR_CLASS)
                || !ObjC.respondsToSelector(device.handle(),
                "newIndirectCommandBufferWithDescriptor:maxCommandCount:options:")) {
            throw new UnsupportedOperationException("Metal indirect command buffers are unavailable");
        }
        try (AutoreleasePool ignored = AutoreleasePool.push()) {
            MemorySegment descriptor = NEW.sendPtr(DESCRIPTOR_CLASS);
            if (ObjC.isNil(descriptor)) {
                throw new IllegalStateException("Failed to create MTLIndirectCommandBufferDescriptor");
            }
            try {
                SET_COMMAND_TYPES.send(descriptor, 1L << 1); // MTLIndirectCommandTypeDrawIndexed
                SET_INHERIT_PIPELINE.send(descriptor, true);
                SET_INHERIT_BUFFERS.send(descriptor, true);
                SET_MAX_VERTEX_BUFFERS.send(descriptor, 0L);
                SET_MAX_FRAGMENT_BUFFERS.send(descriptor, 0L);
                MemorySegment icb = NEW_ICB.sendPtr(
                        device.handle(), descriptor, commandCount,
                        MTLResourceOptions.of(MTLStorageMode.Private, MTLHazardTrackingMode.Untracked));
                if (ObjC.isNil(icb)) {
                    throw new IllegalStateException("Failed to create MTLIndirectCommandBuffer");
                }
                return new MTLIndirectCommandBuffer(icb, commandCount);
            } finally {
                ObjC.release(descriptor);
            }
        }
    }

    public void encodeIndexed(
            final int commandIndex,
            final MTLPrimitiveType primitiveType,
            final int indexCount,
            final MTLIndexType indexType,
            final MTLBuffer indexBuffer,
            final long indexBufferOffset,
            final int baseVertex
    ) {
        if (commandIndex < 0 || commandIndex >= this.size) {
            throw new IndexOutOfBoundsException(commandIndex);
        }
        MemorySegment command = COMMAND_AT_INDEX.sendPtr(handle(), commandIndex);
        if (ObjC.isNil(command)) {
            throw new IllegalStateException("Metal returned no indirect command at " + commandIndex);
        }
        DRAW_INDEXED.send(
                command,
                primitiveType.value,
                indexCount,
                indexType.value,
                indexBuffer.handle(),
                indexBufferOffset,
                1L,
                baseVertex,
                0L
        );
    }

    public MemorySegment handle() {
        if (ObjC.isNil(this.handle)) {
            throw new IllegalStateException("MTLIndirectCommandBuffer is closed");
        }
        return this.handle;
    }

    /** Transfers ownership of the native handle to a deferred destruction queue. */
    public MemorySegment detach() {
        MemorySegment detached = handle();
        this.handle = MemorySegment.NULL;
        return detached;
    }

    @Override
    public void close() {
        if (!ObjC.isNil(this.handle)) {
            ObjC.release(this.handle);
            this.handle = MemorySegment.NULL;
        }
    }
}
