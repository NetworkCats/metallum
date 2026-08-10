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
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static java.lang.foreign.ValueLayout.ADDRESS;

/** Synchronous Metal 4 compiler facade used on macOS 26 and newer. */
@Environment(EnvType.CLIENT)
public final class MTL4Compiler implements AutoCloseable {
    private static final MemorySegment COMPILER_DESCRIPTOR_CLASS = ObjC.findClass("MTL4CompilerDescriptor");
    private static final MemorySegment LIBRARY_DESCRIPTOR_CLASS = ObjC.findClass("MTL4LibraryDescriptor");
    private static final MemorySegment SERIALIZER_DESCRIPTOR_CLASS =
            ObjC.findClass("MTL4PipelineDataSetSerializerDescriptor");
    private static final MemorySegment TASK_OPTIONS_CLASS = ObjC.findClass("MTL4CompilerTaskOptions");
    private static final MemorySegment URL_CLASS = ObjC.findClass("NSURL");
    private static final MemorySegment ARRAY_CLASS = ObjC.findClass("NSArray");
    private static final Msg NEW = Msg.of("new", ADDRESS);
    private static final Msg SET_LABEL = Msg.ofVoid("setLabel:", ADDRESS);
    private static final Msg SET_SOURCE = Msg.ofVoid("setSource:", ADDRESS);
    private static final Msg SET_SERIALIZER_CONFIGURATION = Msg.ofVoid("setConfiguration:", java.lang.foreign.ValueLayout.JAVA_LONG);
    private static final Msg NEW_SERIALIZER = Msg.of(
            "newPipelineDataSetSerializerWithDescriptor:", ADDRESS, ADDRESS);
    private static final Msg SET_PIPELINE_SERIALIZER = Msg.ofVoid("setPipelineDataSetSerializer:", ADDRESS);
    private static final Msg NEW_ARCHIVE = Msg.of(
            "newArchiveWithURL:error:", true, ADDRESS, ADDRESS, ADDRESS);
    private static final Msg ARRAY_WITH_OBJECT = Msg.of("arrayWithObject:", ADDRESS, ADDRESS);
    private static final Msg SET_LOOKUP_ARCHIVES = Msg.ofVoid("setLookupArchives:", ADDRESS);
    private static final Msg FILE_URL = Msg.of("fileURLWithPath:", ADDRESS, ADDRESS);
    private static final Msg SERIALIZE_ARCHIVE = Msg.of(
            "serializeAsArchiveAndFlushToURL:error:", true,
            java.lang.foreign.ValueLayout.JAVA_BOOLEAN, ADDRESS, ADDRESS);
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
    private static final Msg NEW_RENDER_PIPELINE_ASYNC = Msg.of(
            "newRenderPipelineStateWithDescriptor:compilerTaskOptions:completionHandler:",
            true,
            ADDRESS,
            ADDRESS,
            ADDRESS,
            ADDRESS
    );
    private static final Msg WAIT_UNTIL_COMPLETED = Msg.ofVoid("waitUntilCompleted", true);
    private static final Msg LOCALIZED_DESCRIPTION = Msg.of("localizedDescription", ADDRESS);

    private MemorySegment handle;
    private final MemorySegment serializer;
    private final MemorySegment lookupArchive;
    private final MemorySegment taskOptions;
    private final Path archivePath;
    private boolean closed;

    private MTL4Compiler(
            final MemorySegment handle,
            final MemorySegment serializer,
            final MemorySegment lookupArchive,
            final MemorySegment taskOptions,
            final Path archivePath
    ) {
        this.handle = handle;
        this.serializer = serializer;
        this.lookupArchive = lookupArchive;
        this.taskOptions = taskOptions;
        this.archivePath = archivePath;
    }

    @Nullable
    public static MTL4Compiler create(
            final MTLDevice device,
            @Nullable final String label,
            final Path archivePath
    ) {
        if (ObjC.isNil(COMPILER_DESCRIPTOR_CLASS)
                || ObjC.isNil(LIBRARY_DESCRIPTOR_CLASS)
                || !ObjC.respondsToSelector(device.handle(), "newCompilerWithDescriptor:error:")) {
            return null;
        }
        MemorySegment serializer = MemorySegment.NULL;
        MemorySegment compiler = MemorySegment.NULL;
        MemorySegment archive = MemorySegment.NULL;
        MemorySegment options = MemorySegment.NULL;
        try (AutoreleasePool _ = AutoreleasePool.push(); Arena arena = Arena.ofConfined()) {
            MemorySegment descriptor = NEW.sendPtr(COMPILER_DESCRIPTOR_CLASS);
            if (ObjC.isNil(descriptor)) {
                return null;
            }
            try {
                setOptionalLabel(descriptor, label);
                serializer = createSerializer(device);
                if (!ObjC.isNil(serializer)) {
                    SET_PIPELINE_SERIALIZER.send(descriptor, serializer);
                }
                MemorySegment errorOut = arena.allocate(ADDRESS);
                compiler = NEW_COMPILER.sendPtr(device.handle(), descriptor, errorOut);
                if (ObjC.isNil(compiler)) {
                    releaseIfPresent(serializer);
                    serializer = MemorySegment.NULL;
                    Metallum.LOGGER.error("[metallum] Failed to create MTL4Compiler: {}", errorDescription(errorOut));
                    return null;
                }
                archive = loadArchive(device, archivePath);
                options = createTaskOptions(archive);
                return new MTL4Compiler(
                        compiler, serializer, archive, options, archivePath);
            } finally {
                ObjC.release(descriptor);
            }
        } catch (RuntimeException exception) {
            releaseIfPresent(options);
            releaseIfPresent(archive);
            releaseIfPresent(compiler);
            releaseIfPresent(serializer);
            Metallum.LOGGER.warn(
                    "[metallum] Metal 4 compiler initialization failed; falling back to Metal 3",
                    exception);
            return null;
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
        if (ObjC.respondsToSelector(
                handle(),
                "newRenderPipelineStateWithDescriptor:compilerTaskOptions:completionHandler:")) {
            return newRenderPipelineStateAsync(descriptor);
        }
        try (AutoreleasePool _ = AutoreleasePool.push(); Arena arena = Arena.ofConfined()) {
            MemorySegment errorOut = arena.allocate(ADDRESS);
            MemorySegment pipeline = NEW_RENDER_PIPELINE.sendPtr(
                    handle(),
                    descriptor.handle(),
                    ObjC.orNil(this.taskOptions),
                    errorOut
            );
            if (ObjC.isNil(pipeline)) {
                Metallum.LOGGER.error("[metallum] Metal 4 render pipeline compilation failed: {}", errorDescription(errorOut));
                return MemorySegment.NULL;
            }
            return pipeline;
        }
    }

    private MemorySegment newRenderPipelineStateAsync(final MTL4RenderPipelineDescriptor descriptor) {
        CompletableFuture<MemorySegment> completion = new CompletableFuture<>();
        try (ObjCBlock.CompletionLease lease = ObjCBlock.leaseBiConsumer((pipeline, error) -> {
            try (AutoreleasePool ignored = AutoreleasePool.push()) {
                if (!ObjC.isNil(pipeline)) {
                    completion.complete(ObjC.retain(pipeline));
                } else {
                    String message = ObjC.isNil(error)
                            ? "unknown error"
                            : ObjC.javaString(LOCALIZED_DESCRIPTION.sendPtr(error));
                    completion.completeExceptionally(new IllegalStateException(message));
                }
            } catch (Throwable throwable) {
                completion.completeExceptionally(throwable);
            }
        })) {
            MemorySegment task = NEW_RENDER_PIPELINE_ASYNC.sendPtr(
                    handle(), descriptor.handle(), ObjC.orNil(this.taskOptions), lease.block());
            if (ObjC.isNil(task)) {
                return MemorySegment.NULL;
            }
            try {
                WAIT_UNTIL_COMPLETED.send(task);
                return completion.join();
            } catch (CompletionException exception) {
                Metallum.LOGGER.error("[metallum] Metal 4 render pipeline compilation failed: {}",
                        exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage());
                return MemorySegment.NULL;
            } finally {
                ObjC.release(task);
            }
        }
    }

    public synchronized void flushArchive() {
        if (this.closed || ObjC.isNil(this.serializer)) {
            return;
        }
        Path temporary = this.archivePath.resolveSibling(this.archivePath.getFileName() + ".tmp");
        try {
            Files.createDirectories(this.archivePath.getParent());
            Files.deleteIfExists(temporary);
            try (AutoreleasePool ignored = AutoreleasePool.push(); Arena arena = Arena.ofConfined()) {
                if (!SERIALIZE_ARCHIVE.sendBoolean(
                        this.serializer, fileUrl(temporary), arena.allocate(ADDRESS))) {
                    return;
                }
            }
            atomicReplace(temporary, this.archivePath);
        } catch (IOException | RuntimeException exception) {
            Metallum.LOGGER.warn("[metallum] Failed to persist Metal 4 pipeline archive {}; continuing normally",
                    this.archivePath, exception);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }

    private static MemorySegment createSerializer(final MTLDevice device) {
        if (ObjC.isNil(SERIALIZER_DESCRIPTOR_CLASS)
                || !ObjC.respondsToSelector(device.handle(), "newPipelineDataSetSerializerWithDescriptor:")) {
            return MemorySegment.NULL;
        }
        MemorySegment descriptor = NEW.sendPtr(SERIALIZER_DESCRIPTOR_CLASS);
        if (ObjC.isNil(descriptor)) {
            return MemorySegment.NULL;
        }
        try {
            SET_SERIALIZER_CONFIGURATION.send(descriptor, 1L << 1);
            return NEW_SERIALIZER.sendPtr(device.handle(), descriptor);
        } finally {
            ObjC.release(descriptor);
        }
    }

    private static MemorySegment loadArchive(final MTLDevice device, final Path path) {
        if (!Files.isRegularFile(path)
                || ObjC.isNil(URL_CLASS)
                || !ObjC.respondsToSelector(device.handle(), "newArchiveWithURL:error:")) {
            return MemorySegment.NULL;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment archive = NEW_ARCHIVE.sendPtr(
                    device.handle(), fileUrl(path), arena.allocate(ADDRESS));
            if (ObjC.isNil(archive)) {
                Metallum.LOGGER.warn("[metallum] Ignoring incompatible Metal 4 archive {}", path);
            }
            return archive;
        }
    }

    private static MemorySegment createTaskOptions(final MemorySegment archive) {
        if (ObjC.isNil(archive) || ObjC.isNil(TASK_OPTIONS_CLASS) || ObjC.isNil(ARRAY_CLASS)) {
            return MemorySegment.NULL;
        }
        MemorySegment options = NEW.sendPtr(TASK_OPTIONS_CLASS);
        if (ObjC.isNil(options)) {
            return MemorySegment.NULL;
        }
        MemorySegment archives = ARRAY_WITH_OBJECT.sendPtr(ARRAY_CLASS, archive);
        SET_LOOKUP_ARCHIVES.send(options, archives);
        return options;
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

    private static void releaseIfPresent(final MemorySegment object) {
        if (!ObjC.isNil(object)) {
            ObjC.release(object);
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
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        flushArchive();
        this.closed = true;
        if (!ObjC.isNil(this.handle)) {
            ObjC.release(this.handle);
            this.handle = MemorySegment.NULL;
        }
        releaseIfPresent(this.taskOptions);
        releaseIfPresent(this.lookupArchive);
        releaseIfPresent(this.serializer);
    }
}
