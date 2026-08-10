package com.metallum.mtl;

import com.metallum.Metallum;
import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

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
    private static final Msg ADD_ALLOCATIONS = Msg.ofVoid("addAllocations:count:", ADDRESS, JAVA_LONG);
    private static final Msg REMOVE_ALLOCATION = Msg.ofVoid("removeAllocation:", ADDRESS);
    private static final Msg REMOVE_ALLOCATIONS = Msg.ofVoid("removeAllocations:count:", ADDRESS, JAVA_LONG);
    private static final Msg REMOVE_ALL_ALLOCATIONS = Msg.ofVoid("removeAllAllocations");
    private static final Msg COMMIT = Msg.ofVoid("commit");

    private MemorySegment handle;
    private boolean dirty;
    private final boolean supportsBatchOperations;
    private final List<MemorySegment> pendingAdds = new ArrayList<>();
    private final List<MemorySegment> pendingRemoves = new ArrayList<>();
    private final List<MemorySegment> releasesAfterCommit = new ArrayList<>();

    private MTLResidencySet(final MemorySegment handle) {
        this.handle = handle;
        this.supportsBatchOperations = ObjC.respondsToSelector(handle, "addAllocations:count:")
                && ObjC.respondsToSelector(handle, "removeAllocations:count:");
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
        this.pendingAdds.add(allocation);
        this.dirty = true;
    }

    public synchronized void removeAllocation(final MemorySegment allocation) {
        if (ObjC.isNil(allocation) || ObjC.isNil(this.handle)) {
            return;
        }
        this.pendingRemoves.add(allocation);
        this.dirty = true;
    }

    public synchronized void removeAllocationAndRelease(final MemorySegment allocation) {
        if (ObjC.isNil(allocation) || ObjC.isNil(this.handle)) {
            return;
        }
        this.pendingRemoves.add(allocation);
        this.releasesAfterCommit.add(allocation);
        this.dirty = true;
    }

    public synchronized void commitIfNeeded() {
        if (!this.dirty || ObjC.isNil(this.handle)) {
            return;
        }
        flushPending(this.pendingAdds, ADD_ALLOCATIONS, ADD_ALLOCATION);
        flushPending(this.pendingRemoves, REMOVE_ALLOCATIONS, REMOVE_ALLOCATION);
        COMMIT.send(this.handle);
        releaseCommittedAllocations();
        this.dirty = false;
    }

    @Override
    public synchronized void close() {
        if (ObjC.isNil(this.handle)) {
            return;
        }
        REMOVE_ALL_ALLOCATIONS.send(this.handle);
        COMMIT.send(this.handle);
        releaseCommittedAllocations();
        this.pendingAdds.clear();
        this.pendingRemoves.clear();
        ObjC.release(this.handle);
        this.handle = MemorySegment.NULL;
        this.dirty = false;
    }

    private void releaseCommittedAllocations() {
        for (MemorySegment allocation : this.releasesAfterCommit) {
            ObjC.release(allocation);
        }
        this.releasesAfterCommit.clear();
    }

    private void flushPending(
            final List<MemorySegment> pending,
            final Msg batchMessage,
            final Msg singleMessage
    ) {
        if (pending.isEmpty()) {
            return;
        }
        if (this.supportsBatchOperations) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                int byteSize = Math.multiplyExact(pending.size(), Long.BYTES);
                MemorySegment allocations = MemorySegment.ofAddress(
                        stack.nmalloc(Long.BYTES, byteSize)
                ).reinterpret(byteSize);
                for (int i = 0; i < pending.size(); i++) {
                    allocations.setAtIndex(ADDRESS, i, pending.get(i));
                }
                batchMessage.send(this.handle, allocations, (long) pending.size());
            }
        } else {
            for (MemorySegment allocation : pending) {
                singleMessage.send(this.handle, allocation);
            }
        }
        pending.clear();
    }
}
