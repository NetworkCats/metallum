package com.metallum.mtl;

import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Runtime-only wrapper for the Metal 4 resource-binding table. */
@Environment(EnvType.CLIENT)
public final class MTL4ArgumentTable implements AutoCloseable {
    public static final int MAX_BUFFERS = 31;
    public static final int MAX_TEXTURES = 128;
    public static final int MAX_SAMPLERS = 16;

    private static final MemorySegment DESCRIPTOR_CLASS = ObjC.findClass("MTL4ArgumentTableDescriptor");
    private static final Msg NEW = Msg.of("new", ADDRESS);
    private static final Msg SET_MAX_BUFFERS = Msg.ofVoid("setMaxBufferBindCount:", JAVA_LONG);
    private static final Msg SET_MAX_TEXTURES = Msg.ofVoid("setMaxTextureBindCount:", JAVA_LONG);
    private static final Msg SET_MAX_SAMPLERS = Msg.ofVoid("setMaxSamplerStateBindCount:", JAVA_LONG);
    private static final Msg SET_INITIALIZE_BINDINGS = Msg.ofVoid("setInitializeBindings:", JAVA_BOOLEAN);
    private static final Msg SET_LABEL = Msg.ofVoid("setLabel:", ADDRESS);
    private static final Msg CREATE = Msg.of(
            "newArgumentTableWithDescriptor:error:", ADDRESS, ADDRESS, ADDRESS);
    private static final Msg SET_ADDRESS = Msg.ofVoid("setAddress:atIndex:", JAVA_LONG, JAVA_LONG);
    private static final Msg SET_TEXTURE = Msg.ofVoid("setTexture:atIndex:", JAVA_LONG, JAVA_LONG);
    private static final Msg SET_SAMPLER = Msg.ofVoid("setSamplerState:atIndex:", JAVA_LONG, JAVA_LONG);
    private static final Msg GPU_RESOURCE_ID = Msg.of("gpuResourceID", JAVA_LONG);

    private MemorySegment handle;

    private MTL4ArgumentTable(final MemorySegment handle) {
        this.handle = handle;
    }

    static MTL4ArgumentTable create(final MTLDevice device, @Nullable final String label) {
        if (ObjC.isNil(DESCRIPTOR_CLASS)) {
            throw new IllegalStateException("MTL4ArgumentTableDescriptor is unavailable");
        }
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MemorySegment descriptor = NEW.sendPtr(DESCRIPTOR_CLASS);
            if (ObjC.isNil(descriptor)) {
                throw new IllegalStateException("Failed to create MTL4ArgumentTableDescriptor");
            }
            try {
                SET_MAX_BUFFERS.send(descriptor, (long) MAX_BUFFERS);
                SET_MAX_TEXTURES.send(descriptor, (long) MAX_TEXTURES);
                SET_MAX_SAMPLERS.send(descriptor, (long) MAX_SAMPLERS);
                SET_INITIALIZE_BINDINGS.send(descriptor, true);
                if (label != null && !label.isEmpty()) {
                    MemorySegment nsLabel = ObjC.nsString(label);
                    try {
                        SET_LABEL.send(descriptor, nsLabel);
                    } finally {
                        ObjC.release(nsLabel);
                    }
                }
                MemorySegment table = CREATE.sendPtr(
                        device.handle(), descriptor, MemorySegment.NULL);
                if (ObjC.isNil(table)) {
                    throw new IllegalStateException("Failed to create MTL4ArgumentTable");
                }
                return new MTL4ArgumentTable(table);
            } finally {
                ObjC.release(descriptor);
            }
        }
    }

    MemorySegment handle() {
        if (ObjC.isNil(this.handle)) {
            throw new IllegalStateException("MTL4ArgumentTable is closed");
        }
        return this.handle;
    }

    void setBufferAddress(final long address, final long index) {
        requireIndex(index, MAX_BUFFERS, "buffer");
        SET_ADDRESS.send(handle(), address, index);
    }

    void setTexture(final MemorySegment texture, final long index) {
        requireIndex(index, MAX_TEXTURES, "texture");
        SET_TEXTURE.send(handle(), resourceId(texture), index);
    }

    void setSampler(final MemorySegment sampler, final long index) {
        requireIndex(index, MAX_SAMPLERS, "sampler");
        SET_SAMPLER.send(handle(), resourceId(sampler), index);
    }

    private static long resourceId(final MemorySegment resource) {
        return ObjC.isNil(resource) ? 0L : GPU_RESOURCE_ID.sendLong(resource);
    }

    private static void requireIndex(final long index, final int limit, final String kind) {
        if (index < 0L || index >= limit) {
            throw new IllegalArgumentException("Metal 4 " + kind + " binding index " + index
                    + " exceeds the limit " + limit);
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
