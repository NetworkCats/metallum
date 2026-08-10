package com.metallum.mtl;

import com.metallum.Metallum;
import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;

/** Synchronous Metal 4 compiler facade used on macOS 26 and newer. */
@Environment(EnvType.CLIENT)
public final class MTL4Compiler implements AutoCloseable {
    private static final MemorySegment COMPILER_DESCRIPTOR_CLASS = ObjC.findClass("MTL4CompilerDescriptor");
    private static final MemorySegment LIBRARY_DESCRIPTOR_CLASS = ObjC.findClass("MTL4LibraryDescriptor");
    private static final Msg NEW = Msg.of("new", ADDRESS);
    private static final Msg SET_LABEL = Msg.ofVoid("setLabel:", ADDRESS);
    private static final Msg SET_SOURCE = Msg.ofVoid("setSource:", ADDRESS);
    private static final Msg NEW_COMPILER = Msg.of(
            "newCompilerWithDescriptor:error:", true, ADDRESS, ADDRESS, ADDRESS);
    private static final Msg NEW_LIBRARY = Msg.of(
            "newLibraryWithDescriptor:error:", true, ADDRESS, ADDRESS, ADDRESS);
    private static final Msg NEW_RENDER_PIPELINE = Msg.of(
            "newRenderPipelineStateWithDescriptor:compilerTaskOptions:error:",
            true,
            ADDRESS,
            ADDRESS,
            ADDRESS,
            ADDRESS
    );
    private static final Msg LOCALIZED_DESCRIPTION = Msg.of("localizedDescription", ADDRESS);

    private MemorySegment handle;

    private MTL4Compiler(final MemorySegment handle) {
        this.handle = handle;
    }

    @Nullable
    public static MTL4Compiler create(final MTLDevice device, @Nullable final String label) {
        if (ObjC.isNil(COMPILER_DESCRIPTOR_CLASS)
                || ObjC.isNil(LIBRARY_DESCRIPTOR_CLASS)
                || !ObjC.respondsToSelector(device.handle(), "newCompilerWithDescriptor:error:")) {
            return null;
        }
        try (AutoreleasePool _ = AutoreleasePool.push(); Arena arena = Arena.ofConfined()) {
            MemorySegment descriptor = NEW.sendPtr(COMPILER_DESCRIPTOR_CLASS);
            if (ObjC.isNil(descriptor)) {
                return null;
            }
            try {
                setOptionalLabel(descriptor, label);
                MemorySegment errorOut = arena.allocate(ADDRESS);
                MemorySegment compiler = NEW_COMPILER.sendPtr(device.handle(), descriptor, errorOut);
                if (ObjC.isNil(compiler)) {
                    Metallum.LOGGER.error("[metallum] Failed to create MTL4Compiler: {}", errorDescription(errorOut));
                    return null;
                }
                return new MTL4Compiler(compiler);
            } finally {
                ObjC.release(descriptor);
            }
        }
    }

    public MemorySegment newLibrary(final String source) {
        try (AutoreleasePool _ = AutoreleasePool.push(); Arena arena = Arena.ofConfined()) {
            MemorySegment descriptor = NEW.sendPtr(LIBRARY_DESCRIPTOR_CLASS);
            if (ObjC.isNil(descriptor)) {
                return MemorySegment.NULL;
            }
            try {
                MemorySegment nsSource = ObjC.nsString(source);
                try {
                    SET_SOURCE.send(descriptor, nsSource);
                } finally {
                    ObjC.release(nsSource);
                }
                MemorySegment errorOut = arena.allocate(ADDRESS);
                MemorySegment library = NEW_LIBRARY.sendPtr(handle(), descriptor, errorOut);
                if (ObjC.isNil(library)) {
                    Metallum.LOGGER.error("[metallum] Metal 4 library compilation failed: {}", errorDescription(errorOut));
                    return MemorySegment.NULL;
                }
                return library;
            } finally {
                ObjC.release(descriptor);
            }
        }
    }

    public MemorySegment newRenderPipelineState(final MTL4RenderPipelineDescriptor descriptor) {
        try (AutoreleasePool _ = AutoreleasePool.push(); Arena arena = Arena.ofConfined()) {
            MemorySegment errorOut = arena.allocate(ADDRESS);
            MemorySegment pipeline = NEW_RENDER_PIPELINE.sendPtr(
                    handle(),
                    descriptor.handle(),
                    MemorySegment.NULL,
                    errorOut
            );
            if (ObjC.isNil(pipeline)) {
                Metallum.LOGGER.error("[metallum] Metal 4 render pipeline compilation failed: {}", errorDescription(errorOut));
                return MemorySegment.NULL;
            }
            return pipeline;
        }
    }

    private MemorySegment handle() {
        if (ObjC.isNil(this.handle)) {
            throw new IllegalStateException("MTL4Compiler is closed");
        }
        return this.handle;
    }

    private static void setOptionalLabel(final MemorySegment object, @Nullable final String label) {
        if (label == null || label.isEmpty()) {
            return;
        }
        MemorySegment nsLabel = ObjC.nsString(label);
        try {
            SET_LABEL.send(object, nsLabel);
        } finally {
            ObjC.release(nsLabel);
        }
    }

    private static String errorDescription(final MemorySegment errorOut) {
        MemorySegment error = errorOut.get(ADDRESS, 0L);
        if (ObjC.isNil(error)) {
            return "unknown error";
        }
        MemorySegment description = LOCALIZED_DESCRIPTION.sendPtr(error);
        return ObjC.isNil(description) ? "unknown error" : ObjC.javaString(description);
    }

    @Override
    public void close() {
        if (!ObjC.isNil(this.handle)) {
            ObjC.release(this.handle);
            this.handle = MemorySegment.NULL;
        }
    }
}
