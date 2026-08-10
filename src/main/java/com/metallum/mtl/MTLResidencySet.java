package com.metallum.mtl;

import com.metallum.Metallum;
import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A runtime-optional wrapper around {@code MTLResidencySet}. The API is only
 * referenced through Objective-C selectors so loading Metallum on an older
 * Metal 3 runtime remains safe.
 */
@Environment(EnvType.CLIENT)
public final class MTLResidencySet implements AutoCloseable {
    private static final String CREATE_SELECTOR = "newResidencySetWithDescriptor:error:";
    private static final MemorySegment DESCRIPTOR_CLASS = ObjC.findClass("MTLResidencySetDescriptor");

    private static final Msg NEW = Msg.of("new", ADDRESS);
    private static final Msg SET_LABEL = Msg.ofVoid("setLabel:", ADDRESS);
    private static final Msg SET_INITIAL_CAPACITY = Msg.ofVoid("setInitialCapacity:", JAVA_LONG);
    private static final Msg CREATE = Msg.of(CREATE_SELECTOR, ADDRESS, ADDRESS, ADDRESS);
    private static final Msg ADD_ALLOCATION = Msg.ofVoid("addAllocation:", ADDRESS);
    private static final Msg REMOVE_ALLOCATION = Msg.ofVoid("removeAllocation:", ADDRESS);
    private static final Msg REMOVE_ALL_ALLOCATIONS = Msg.ofVoid("removeAllAllocations");
    private static final Msg COMMIT = Msg.ofVoid("commit");

    private MemorySegment handle;
    private boolean dirty;

    private MTLResidencySet(final MemorySegment handle) {
        this.handle = handle;
    }

    @Nullable
    public static MTLResidencySet create(
            final MTLDevice device,
            final long initialCapacity,
            @Nullable final String label
    ) {
        if (ObjC.isNil(DESCRIPTOR_CLASS)
                || !ObjC.respondsToSelector(device.handle(), CREATE_SELECTOR)) {
            return null;
        }

        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MemorySegment descriptor = NEW.sendPtr(DESCRIPTOR_CLASS);
            if (ObjC.isNil(descriptor)) {
                return null;
            }
            try {
                SET_INITIAL_CAPACITY.send(descriptor, Math.max(0L, initialCapacity));
                if (label != null && !label.isEmpty()) {
                    MemorySegment nsLabel = ObjC.nsString(label);
                    try {
                        SET_LABEL.send(descriptor, nsLabel);
                    } finally {
                        ObjC.release(nsLabel);
                    }
                }
                MemorySegment residencySet = CREATE.sendPtr(
                        device.handle(),
                        descriptor,
                        MemorySegment.NULL
                );
                return ObjC.isNil(residencySet) ? null : new MTLResidencySet(residencySet);
            } finally {
                ObjC.release(descriptor);
            }
        } catch (RuntimeException exception) {
            Metallum.LOGGER.warn("[metallum] Failed to enable Metal residency sets; using implicit residency", exception);
            return null;
        }
    }

    public MemorySegment handle() {
        if (ObjC.isNil(this.handle)) {
            throw new IllegalStateException("MTLResidencySet is closed");
        }
        return this.handle;
    }

    public synchronized void addAllocation(final MemorySegment allocation) {
        if (ObjC.isNil(allocation) || ObjC.isNil(this.handle)) {
            return;
        }
        ADD_ALLOCATION.send(this.handle, allocation);
        this.dirty = true;
    }

    public synchronized void removeAllocation(final MemorySegment allocation) {
        if (ObjC.isNil(allocation) || ObjC.isNil(this.handle)) {
            return;
        }
        REMOVE_ALLOCATION.send(this.handle, allocation);
        this.dirty = true;
    }

    public synchronized void commitIfNeeded() {
        if (!this.dirty || ObjC.isNil(this.handle)) {
            return;
        }
        COMMIT.send(this.handle);
        this.dirty = false;
    }

    @Override
    public synchronized void close() {
        if (ObjC.isNil(this.handle)) {
            return;
        }
        REMOVE_ALL_ALLOCATIONS.send(this.handle);
        COMMIT.send(this.handle);
        ObjC.release(this.handle);
        this.handle = MemorySegment.NULL;
        this.dirty = false;
    }
}
