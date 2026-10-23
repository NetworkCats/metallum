package com.metallum.mtl;

import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import com.metallum.objc.ObjCBlock;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public final class MTLCommandQueue implements AutoCloseable {
    private static final int MAX_SUBMITS_IN_FLIGHT = 3;

    private static final Msg SET_LABEL = Msg.ofVoid("setLabel:", ADDRESS);
    private static final Msg ADD_RESIDENCY_SET = Msg.ofVoid("addResidencySet:", ADDRESS);
    private static final Msg REMOVE_RESIDENCY_SET = Msg.ofVoid("removeResidencySet:", ADDRESS);

    private static final Msg NEW_MTL4_COMMAND_QUEUE = Msg.of("newMTL4CommandQueue", ADDRESS);
    private static final Msg NEW_MTL4_COMMAND_ALLOCATOR = Msg.of("newCommandAllocator", ADDRESS);
    private static final Msg NEW_MTL4_COMMAND_BUFFER = Msg.of("newCommandBuffer", ADDRESS);
    private static final Msg BEGIN_MTL4_COMMAND_BUFFER =
            Msg.ofVoid("beginCommandBufferWithAllocator:", ADDRESS);
    private static final Msg COMMIT_MTL4 =
            Msg.ofVoid("commit:count:options:", ADDRESS, JAVA_LONG, ADDRESS);
    private static final Msg SIGNAL_DRAWABLE = Msg.ofVoid("signalDrawable:", ADDRESS);
    private static final Msg WAIT_FOR_DRAWABLE = Msg.ofVoid("waitForDrawable:", ADDRESS);
    private static final Msg RESET_ALLOCATOR = Msg.ofVoid("reset");

    private static final MemorySegment COMMIT_OPTIONS_CLASS = ObjC.findClass("MTL4CommitOptions");
    private static final Msg NEW = Msg.of("new", ADDRESS);
    private static final Msg ADD_FEEDBACK_HANDLER = Msg.ofVoid("addFeedbackHandler:", ADDRESS);
    private static final Msg FEEDBACK_ERROR = Msg.of("error", ADDRESS);
    private static final Msg LOCALIZED_DESCRIPTION = Msg.of("localizedDescription", ADDRESS);

    private static volatile boolean debugLabelsEnabled;

    private final MTLDevice device;
    private final Metal4SubmissionSlot[] submissionSlots;
    @Nullable
    private MTLResidencySet residencySet;
    private MemorySegment handle;
    private int nextSubmissionSlot;

    private MTLCommandQueue(
            final MTLDevice device,
            final MemorySegment handle,
            final Metal4SubmissionSlot[] submissionSlots
    ) {
        this.device = device;
        this.handle = handle;
        this.submissionSlots = submissionSlots;
    }

    static MTLCommandQueue create(final MTLDevice device) {
        if (ObjC.isNil(COMMIT_OPTIONS_CLASS)
                || !ObjC.respondsToSelector(device.handle(), "newMTL4CommandQueue")
                || !ObjC.respondsToSelector(device.handle(), "newCommandAllocator")
                || !ObjC.respondsToSelector(device.handle(), "newCommandBuffer")) {
            throw new IllegalStateException("Metal 4 command queues are unavailable on this system");
        }

        MemorySegment queue = MemorySegment.NULL;
        Metal4SubmissionSlot[] slots = new Metal4SubmissionSlot[MAX_SUBMITS_IN_FLIGHT];
        try {
            queue = NEW_MTL4_COMMAND_QUEUE.sendPtr(device.handle());
            if (ObjC.isNil(queue)) {
                throw new IllegalStateException("Failed to create MTL4CommandQueue");
            }
            for (int i = 0; i < slots.length; i++) {
                slots[i] = Metal4SubmissionSlot.create(device, i);
            }
            return new MTLCommandQueue(device, queue, slots);
        } catch (RuntimeException exception) {
            for (Metal4SubmissionSlot slot : slots) {
                if (slot != null) {
                    slot.close();
                }
            }
            if (!ObjC.isNil(queue)) {
                ObjC.release(queue);
            }
            throw exception;
        }
    }

    public static void setDebugLabelsEnabled(final boolean enabled) {
        debugLabelsEnabled = enabled;
    }

    public static boolean debugLabelsEnabled() {
        return debugLabelsEnabled;
    }

    public MTLCommandBuffer makeCommandBuffer(@Nullable final String label) {
        Metal4SubmissionSlot[] slots = this.submissionSlots;
        Metal4SubmissionSlot slot = slots[this.nextSubmissionSlot];
        this.nextSubmissionSlot = (this.nextSubmissionSlot + 1) % slots.length;
        slot.prepare();

        MemorySegment commandBuffer = NEW_MTL4_COMMAND_BUFFER.sendPtr(this.device.handle());
        if (ObjC.isNil(commandBuffer)) {
            throw new IllegalStateException("Failed to create MTL4CommandBuffer");
        }
        try {
            BEGIN_MTL4_COMMAND_BUFFER.send(commandBuffer, slot.allocator);
            setLabel(commandBuffer, label);
            return new MTLCommandBuffer(
                    commandBuffer,
                    this,
                    slot,
                    slot.argumentTable,
                    this.device,
                    this.residencySet
            );
        } catch (RuntimeException exception) {
            ObjC.release(commandBuffer);
            throw exception;
        }
    }

    private static void setLabel(final MemorySegment object, @Nullable final String label) {
        if (!debugLabelsEnabled || label == null || label.isEmpty()) {
            return;
        }
        MemorySegment nsLabel = ObjC.nsString(label);
        try {
            SET_LABEL.send(object, nsLabel);
        } finally {
            ObjC.release(nsLabel);
        }
    }

    void commitMetal4(final MTLCommandBuffer commandBuffer, @Nullable final CAMetalDrawable drawable) {
        Metal4SubmissionSlot slot = commandBuffer.metal4SubmissionSlot();
        slot.markSubmitting(commandBuffer);
        try {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment commandBuffers = arena.allocate(ADDRESS);
                commandBuffers.set(ADDRESS, 0L, commandBuffer.handle());
                MemorySegment options = NEW.sendPtr(COMMIT_OPTIONS_CLASS);
                if (ObjC.isNil(options)) {
                    throw new IllegalStateException("Failed to create MTL4CommitOptions");
                }
                try {
                    ADD_FEEDBACK_HANDLER.send(options, slot.feedbackHandler);
                    COMMIT_MTL4.send(this.handle, commandBuffers, 1L, options);
                } finally {
                    ObjC.release(options);
                }
            }
        } catch (RuntimeException exception) {
            slot.failSubmission("Metal 4 queue commit failed: " + exception.getMessage());
            throw exception;
        }
        if (drawable != null) {
            SIGNAL_DRAWABLE.send(this.handle, drawable.handle());
            drawable.present();
        }
    }

    void waitForDrawable(final CAMetalDrawable drawable) {
        WAIT_FOR_DRAWABLE.send(this.handle, drawable.handle());
    }

    public void addResidencySet(final MTLResidencySet residencySet) {
        ADD_RESIDENCY_SET.send(this.handle, residencySet.handle());
        this.residencySet = residencySet;
    }

    public void removeResidencySet(final MTLResidencySet residencySet) {
        if (!ObjC.isNil(this.handle)) {
            REMOVE_RESIDENCY_SET.send(this.handle, residencySet.handle());
        }
        if (this.residencySet == residencySet) {
            this.residencySet = null;
        }
    }

    public void close() {
        if (ObjC.isNil(this.handle)) {
            return;
        }
        for (Metal4SubmissionSlot slot : this.submissionSlots) {
            slot.close();
        }
        ObjC.release(this.handle);
        this.handle = MemorySegment.NULL;
    }

    static final class Metal4SubmissionSlot implements AutoCloseable {
        private final Semaphore completion = new Semaphore(0);
        private final MemorySegment allocator;
        private final MTL4ArgumentTable argumentTable;
        // One block per slot, registered on fresh commit options for every commit: on macOS 27
        // a feedback handler attached to reused MTL4CommitOptions only fires for its first commit.
        private MemorySegment feedbackHandler = MemorySegment.NULL;
        @Nullable
        private volatile MTLCommandBuffer activeCommandBuffer;
        private volatile boolean submitted;

        private Metal4SubmissionSlot(
                final MemorySegment allocator,
                final MTL4ArgumentTable argumentTable
        ) {
            this.allocator = allocator;
            this.argumentTable = argumentTable;
        }

        static Metal4SubmissionSlot create(final MTLDevice device, final int index) {
            MemorySegment allocator = NEW_MTL4_COMMAND_ALLOCATOR.sendPtr(device.handle());
            if (ObjC.isNil(allocator)) {
                throw new IllegalStateException("Failed to create MTL4CommandAllocator " + index);
            }
            MTL4ArgumentTable table;
            try {
                table = MTL4ArgumentTable.create(
                        device,
                        debugLabelsEnabled ? "Metallum argument table " + index : null
                );
            } catch (RuntimeException exception) {
                ObjC.release(allocator);
                throw exception;
            }
            Metal4SubmissionSlot slot = new Metal4SubmissionSlot(allocator, table);
            try {
                slot.feedbackHandler = ObjCBlock.withConsumer(slot::complete);
                return slot;
            } catch (RuntimeException exception) {
                slot.close();
                throw exception;
            }
        }

        void prepare() {
            if (this.submitted && !isCompleted()) {
                throw new IllegalStateException("Attempted to reuse an in-flight MTL4CommandAllocator");
            }
            this.completion.drainPermits();
            this.activeCommandBuffer = null;
            this.submitted = false;
            RESET_ALLOCATOR.send(this.allocator);
        }

        void markSubmitting(final MTLCommandBuffer commandBuffer) {
            this.activeCommandBuffer = commandBuffer;
            this.submitted = true;
        }

        void failSubmission(final String description) {
            MTLCommandBuffer commandBuffer = this.activeCommandBuffer;
            if (commandBuffer != null) {
                commandBuffer.completeMetal4(description);
            }
            this.completion.release();
        }

        private void complete(final MemorySegment feedback) {
            String description = null;
            try (AutoreleasePool _ = AutoreleasePool.push()) {
                MemorySegment error = ObjC.isNil(feedback)
                        ? MemorySegment.NULL
                        : FEEDBACK_ERROR.sendPtr(feedback);
                if (!ObjC.isNil(error)) {
                    MemorySegment localizedDescription = LOCALIZED_DESCRIPTION.sendPtr(error);
                    description = ObjC.isNil(localizedDescription)
                            ? "Metal 4 submission failed without a localized description"
                            : ObjC.javaString(localizedDescription);
                }
            } catch (RuntimeException exception) {
                description = "Unable to read Metal 4 submission feedback: " + exception.getMessage();
            } finally {
                MTLCommandBuffer commandBuffer = this.activeCommandBuffer;
                if (commandBuffer != null) {
                    commandBuffer.completeMetal4(description);
                }
                this.completion.release();
            }
        }

        boolean isCompleted() {
            return this.completion.availablePermits() != 0;
        }

        boolean await(final long timeoutMs) {
            try {
                if (!this.completion.tryAcquire(Math.max(timeoutMs, 0L), TimeUnit.MILLISECONDS)) {
                    return false;
                }
                this.completion.release();
                return true;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Metal 4 submission", exception);
            }
        }

        @Override
        public void close() {
            ObjC.release(this.allocator);
            this.argumentTable.close();
        }
    }
}
