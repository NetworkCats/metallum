package com.metallum.mtl;

import com.metallum.Metallum;
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
public final class MTLCommandQueue {
    private static final int MAX_SUBMITS_IN_FLIGHT = 3;

    private static final Msg COMMAND_BUFFER = Msg.of("commandBuffer", ADDRESS);
    private static final Msg COMMAND_BUFFER_WITH_UNRETAINED_REFERENCES =
            Msg.of("commandBufferWithUnretainedReferences", ADDRESS);
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
    private final boolean metal4;
    @Nullable
    private final Metal4SubmissionSlot[] submissionSlots;
    @Nullable
    private MTLResidencySet residencySet;
    private MemorySegment handle;
    private boolean useUnretainedCommandBuffers;
    private int nextSubmissionSlot;

    private MTLCommandQueue(
            final MTLDevice device,
            final MemorySegment handle,
            final boolean metal4,
            @Nullable final Metal4SubmissionSlot[] submissionSlots
    ) {
        this.device = device;
        this.handle = handle;
        this.metal4 = metal4;
        this.submissionSlots = submissionSlots;
    }

    static MTLCommandQueue legacy(final MTLDevice device, final MemorySegment handle) {
        return new MTLCommandQueue(device, handle, false, null);
    }

    @Nullable
    static MTLCommandQueue tryCreateMetal4(final MTLDevice device) {
        if (ObjC.isNil(COMMIT_OPTIONS_CLASS)
                || !ObjC.respondsToSelector(device.handle(), "newCommandAllocator")
                || !ObjC.respondsToSelector(device.handle(), "newCommandBuffer")) {
            return null;
        }

        MemorySegment queue = MemorySegment.NULL;
        Metal4SubmissionSlot[] slots = null;
        try {
            queue = NEW_MTL4_COMMAND_QUEUE.sendPtr(device.handle());
            if (ObjC.isNil(queue)) {
                return null;
            }
            slots = new Metal4SubmissionSlot[MAX_SUBMITS_IN_FLIGHT];
            for (int i = 0; i < slots.length; i++) {
                slots[i] = Metal4SubmissionSlot.create(device, i);
            }
            return new MTLCommandQueue(device, queue, true, slots);
        } catch (RuntimeException exception) {
            Metallum.LOGGER.warn("[metallum] Failed to initialize the Metal 4 command path; falling back to Metal 3", exception);
            if (slots != null) {
                for (Metal4SubmissionSlot slot : slots) {
                    if (slot != null) {
                        slot.close();
                    }
                }
            }
            if (!ObjC.isNil(queue)) {
                ObjC.release(queue);
            }
            return null;
        }
    }

    public static void setDebugLabelsEnabled(final boolean enabled) {
        debugLabelsEnabled = enabled;
    }

    public static boolean debugLabelsEnabled() {
        return debugLabelsEnabled;
    }

    public boolean isMetal4() {
        return this.metal4;
    }

    public MTLCommandBuffer makeCommandBuffer(@Nullable final String label) {
        if (this.metal4) {
            return makeMetal4CommandBuffer(label);
        }
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MemorySegment commandBuffer = this.useUnretainedCommandBuffers
                    ? COMMAND_BUFFER_WITH_UNRETAINED_REFERENCES.sendPtr(this.handle)
                    : COMMAND_BUFFER.sendPtr(this.handle);
            if (ObjC.isNil(commandBuffer)) {
                throw new IllegalStateException("Failed to create MTLCommandBuffer");
            }
            commandBuffer = ObjC.retain(commandBuffer);
            setLabel(commandBuffer, label);
            return MTLCommandBuffer.metal3(commandBuffer);
        }
    }

    private MTLCommandBuffer makeMetal4CommandBuffer(@Nullable final String label) {
        Metal4SubmissionSlot[] slots = this.submissionSlots;
        if (slots == null) {
            throw new IllegalStateException("Metal 4 command queue is incomplete");
        }
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
            return MTLCommandBuffer.metal4(
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
                COMMIT_MTL4.send(this.handle, commandBuffers, 1L, slot.commitOptions);
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
        if (this.metal4) {
            WAIT_FOR_DRAWABLE.send(this.handle, drawable.handle());
        }
    }

    public void useUnretainedCommandBuffers(final boolean enabled) {
        if (!this.metal4) {
            this.useUnretainedCommandBuffers = enabled;
        }
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
        if (this.submissionSlots != null) {
            for (Metal4SubmissionSlot slot : this.submissionSlots) {
                slot.close();
            }
        }
        ObjC.release(this.handle);
        this.handle = MemorySegment.NULL;
    }

    static final class Metal4SubmissionSlot implements AutoCloseable {
        private final Semaphore completion = new Semaphore(0);
        private final MemorySegment allocator;
        private final MemorySegment commitOptions;
        private final MTL4ArgumentTable argumentTable;
        @Nullable
        private volatile MTLCommandBuffer activeCommandBuffer;
        private volatile boolean submitted;

        private Metal4SubmissionSlot(
                final MemorySegment allocator,
                final MemorySegment commitOptions,
                final MTL4ArgumentTable argumentTable
        ) {
            this.allocator = allocator;
            this.commitOptions = commitOptions;
            this.argumentTable = argumentTable;
        }

        static Metal4SubmissionSlot create(final MTLDevice device, final int index) {
            MemorySegment allocator = NEW_MTL4_COMMAND_ALLOCATOR.sendPtr(device.handle());
            if (ObjC.isNil(allocator)) {
                throw new IllegalStateException("Failed to create MTL4CommandAllocator " + index);
            }
            MemorySegment options = NEW.sendPtr(COMMIT_OPTIONS_CLASS);
            if (ObjC.isNil(options)) {
                ObjC.release(allocator);
                throw new IllegalStateException("Failed to create MTL4CommitOptions " + index);
            }
            MTL4ArgumentTable table;
            try {
                table = MTL4ArgumentTable.create(
                        device,
                        debugLabelsEnabled ? "Metallum argument table " + index : null
                );
            } catch (RuntimeException exception) {
                ObjC.release(options);
                ObjC.release(allocator);
                throw exception;
            }
            Metal4SubmissionSlot slot = new Metal4SubmissionSlot(allocator, options, table);
            MemorySegment handler = ObjCBlock.withConsumer(slot::complete);
            ADD_FEEDBACK_HANDLER.send(options, handler);
            return slot;
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
            ObjC.release(this.commitOptions);
            ObjC.release(this.allocator);
            this.argumentTable.close();
        }
    }
}
