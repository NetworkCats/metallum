package com.metallum.mtl;

import com.metallum.Metallum;
import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;

/** Runtime-optional Metal 3 binary archive with corruption-safe persistence. */
public final class MTLBinaryArchive implements AutoCloseable {
    private static final MemorySegment DESCRIPTOR_CLASS = ObjC.findClass("MTLBinaryArchiveDescriptor");
    private static final MemorySegment URL_CLASS = ObjC.findClass("NSURL");
    private static final Msg NEW = Msg.of("new", ADDRESS);
    private static final Msg FILE_URL = Msg.of("fileURLWithPath:", ADDRESS, ADDRESS);
    private static final Msg SET_URL = Msg.ofVoid("setURL:", ADDRESS);
    private static final Msg CREATE = Msg.of(
            "newBinaryArchiveWithDescriptor:error:", true, ADDRESS, ADDRESS, ADDRESS);
    private static final Msg ADD_RENDER_PIPELINE = Msg.of(
            "addRenderPipelineFunctionsWithDescriptor:error:", true, JAVA_BOOLEAN, ADDRESS, ADDRESS);
    private static final Msg SERIALIZE = Msg.of(
            "serializeToURL:error:", true, JAVA_BOOLEAN, ADDRESS, ADDRESS);

    private final Path path;
    private MemorySegment handle;
    private boolean dirty;

    private MTLBinaryArchive(final MemorySegment handle, final Path path) {
        this.handle = handle;
        this.path = path;
    }

    @Nullable
    public static MTLBinaryArchive create(final MTLDevice device, final Path path) {
        if (ObjC.isNil(DESCRIPTOR_CLASS)
                || ObjC.isNil(URL_CLASS)
                || !ObjC.respondsToSelector(device.handle(), "newBinaryArchiveWithDescriptor:error:")) {
            return null;
        }
        MemorySegment loaded = Files.isRegularFile(path)
                ? createArchive(device, path)
                : MemorySegment.NULL;
        if (ObjC.isNil(loaded)) {
            loaded = createArchive(device, null);
        }
        return ObjC.isNil(loaded) ? null : new MTLBinaryArchive(loaded, path);
    }

    private static MemorySegment createArchive(final MTLDevice device, @Nullable final Path path) {
        try (AutoreleasePool ignored = AutoreleasePool.push(); Arena arena = Arena.ofConfined()) {
            MemorySegment descriptor = NEW.sendPtr(DESCRIPTOR_CLASS);
            if (ObjC.isNil(descriptor)) {
                return MemorySegment.NULL;
            }
            try {
                if (path != null) {
                    SET_URL.send(descriptor, fileUrl(path));
                }
                MemorySegment error = arena.allocate(ADDRESS);
                MemorySegment archive = CREATE.sendPtr(device.handle(), descriptor, error);
                if (ObjC.isNil(archive) && path != null) {
                    Metallum.LOGGER.warn("[metallum] Ignoring incompatible Metal binary archive {}", path);
                }
                return archive;
            } finally {
                ObjC.release(descriptor);
            }
        } catch (RuntimeException exception) {
            Metallum.LOGGER.warn("[metallum] Metal binary archive initialization failed; compiling normally", exception);
            return MemorySegment.NULL;
        }
    }

    public MemorySegment handle() {
        return this.handle;
    }

    public synchronized void addRenderPipeline(final MTLRenderPipelineDescriptor descriptor) {
        if (ObjC.isNil(this.handle)) {
            return;
        }
        try (AutoreleasePool ignored = AutoreleasePool.push(); Arena arena = Arena.ofConfined()) {
            if (ADD_RENDER_PIPELINE.sendBoolean(
                    this.handle, descriptor.handle(), arena.allocate(ADDRESS))) {
                this.dirty = true;
            }
        } catch (RuntimeException exception) {
            Metallum.LOGGER.debug("[metallum] Pipeline could not be added to the Metal binary archive", exception);
        }
    }

    public synchronized void flush() {
        if (!this.dirty || ObjC.isNil(this.handle)) {
            return;
        }
        Path temporary = this.path.resolveSibling(this.path.getFileName() + ".tmp");
        try {
            Files.createDirectories(this.path.getParent());
            Files.deleteIfExists(temporary);
            try (AutoreleasePool ignored = AutoreleasePool.push(); Arena arena = Arena.ofConfined()) {
                if (!SERIALIZE.sendBoolean(
                        this.handle, fileUrl(temporary), arena.allocate(ADDRESS))) {
                    return;
                }
            }
            atomicReplace(temporary, this.path);
            this.dirty = false;
        } catch (IOException | RuntimeException exception) {
            Metallum.LOGGER.warn("[metallum] Failed to persist Metal binary archive {}; continuing without it",
                    this.path, exception);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }

    private static MemorySegment fileUrl(final Path path) {
        MemorySegment string = ObjC.nsString(path.toAbsolutePath().toString());
        try {
            return FILE_URL.sendPtr(URL_CLASS, string);
        } finally {
            ObjC.release(string);
        }
    }

    private static void atomicReplace(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public synchronized void close() {
        flush();
        if (!ObjC.isNil(this.handle)) {
            ObjC.release(this.handle);
            this.handle = MemorySegment.NULL;
        }
    }
}
