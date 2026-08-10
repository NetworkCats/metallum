package com.metallum.objc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.foreign.ValueLayout.*;

public final class ObjCBlock {
    private static final int BLOCK_IS_GLOBAL = 1 << 28;

    private static final MemorySegment NS_CONCRETE_GLOBAL_BLOCK =
            Linker.nativeLinker().defaultLookup().findOrThrow("_NSConcreteGlobalBlock");
    private static final Arena BLOCK_ARENA = Arena.global();
    private static final MemorySegment BLOCK_DESCRIPTOR;
    private static final MethodHandle INVOKE_RUNNABLE;
    private static final MethodHandle INVOKE_CONSUMER;
    private static final MethodHandle INVOKE_BI_CONSUMER_ONCE;
    private static final int COMPLETION_SLOT_COUNT = 16;
    private static final BlockingQueue<CompletionSlot> COMPLETION_SLOTS =
            new ArrayBlockingQueue<>(COMPLETION_SLOT_COUNT);

    static {
        BLOCK_DESCRIPTOR = BLOCK_ARENA.allocate(16, 8);
        BLOCK_DESCRIPTOR.set(JAVA_LONG, 0, 0L);
        BLOCK_DESCRIPTOR.set(JAVA_LONG, 8, 32L);
        try {
            INVOKE_RUNNABLE = MethodHandles.lookup().findStatic(
                    ObjCBlock.class,
                    "invokeRunnable",
                    MethodType.methodType(void.class, Runnable.class, MemorySegment.class, MemorySegment.class)
            );
            INVOKE_CONSUMER = MethodHandles.lookup().findStatic(
                    ObjCBlock.class,
                    "invokeConsumer",
                    MethodType.methodType(void.class, Consumer.class, MemorySegment.class, MemorySegment.class)
            );
            INVOKE_BI_CONSUMER_ONCE = MethodHandles.lookup().findStatic(
                    ObjCBlock.class,
                    "invokeBiConsumerOnce",
                    MethodType.methodType(
                            void.class,
                            AtomicReference.class,
                            MemorySegment.class,
                            MemorySegment.class,
                            MemorySegment.class)
            );
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to resolve block invoke handle", e);
        }
        for (int i = 0; i < COMPLETION_SLOT_COUNT; i++) {
            COMPLETION_SLOTS.add(new CompletionSlot());
        }
    }

    private ObjCBlock() {
    }

    public static MemorySegment withRunnable(final Runnable action) {
        MethodHandle target = MethodHandles.insertArguments(INVOKE_RUNNABLE, 0, action);
        return createBlock(target);
    }

    public static MemorySegment withConsumer(final Consumer<MemorySegment> action) {
        MethodHandle target = MethodHandles.insertArguments(INVOKE_CONSUMER, 0, action);
        return createBlock(target);
    }

    /**
     * Borrows one of a bounded set of global completion blocks. The lease must
     * remain open until the native callback has run.
     */
    public static CompletionLease leaseBiConsumer(
            final BiConsumer<MemorySegment, MemorySegment> action
    ) {
        final CompletionSlot slot;
        try {
            slot = COMPLETION_SLOTS.take();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for an Objective-C completion block", exception);
        }
        slot.action.set(action);
        return new CompletionLease(slot);
    }

    private static MemorySegment createBlock(final MethodHandle target) {
        return createBlock(target, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    }

    private static MemorySegment createBlock(
            final MethodHandle target,
            final FunctionDescriptor descriptor
    ) {
        MemorySegment invoke = ObjC.LINKER.upcallStub(
                target,
                descriptor,
                BLOCK_ARENA
        );

        MemorySegment block = BLOCK_ARENA.allocate(32, 8);
        block.set(ADDRESS, 0, NS_CONCRETE_GLOBAL_BLOCK);
        block.set(JAVA_INT, 8, BLOCK_IS_GLOBAL);
        block.set(JAVA_INT, 12, 0);
        block.set(ADDRESS, 16, invoke);
        block.set(ADDRESS, 24, BLOCK_DESCRIPTOR);
        return block;
    }

    private static void invokeRunnable(final Runnable action, final MemorySegment block, final MemorySegment argument) {
        action.run();
    }

    private static void invokeConsumer(
            final Consumer<MemorySegment> action,
            final MemorySegment block,
            final MemorySegment argument
    ) {
        action.accept(argument);
    }

    private static void invokeBiConsumerOnce(
            final AtomicReference<BiConsumer<MemorySegment, MemorySegment>> reference,
            final MemorySegment block,
            final MemorySegment first,
            final MemorySegment second
    ) {
        BiConsumer<MemorySegment, MemorySegment> action = reference.getAndSet(null);
        if (action != null) {
            action.accept(first, second);
        }
    }

    private static final class CompletionSlot {
        private final AtomicReference<BiConsumer<MemorySegment, MemorySegment>> action =
                new AtomicReference<>();
        private final MemorySegment block;

        private CompletionSlot() {
            MethodHandle target = MethodHandles.insertArguments(
                    INVOKE_BI_CONSUMER_ONCE, 0, this.action);
            this.block = createBlock(
                    target,
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
        }
    }

    public static final class CompletionLease implements AutoCloseable {
        private CompletionSlot slot;

        private CompletionLease(final CompletionSlot slot) {
            this.slot = slot;
        }

        public MemorySegment block() {
            CompletionSlot current = this.slot;
            if (current == null) {
                throw new IllegalStateException("Objective-C completion block lease is closed");
            }
            return current.block;
        }

        @Override
        public void close() {
            CompletionSlot current = this.slot;
            if (current == null) {
                return;
            }
            this.slot = null;
            current.action.set(null);
            if (!COMPLETION_SLOTS.offer(current)) {
                throw new IllegalStateException("Objective-C completion block pool overflow");
            }
        }
    }
}
