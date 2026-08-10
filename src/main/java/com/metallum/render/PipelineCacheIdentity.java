package com.metallum.render;

import com.metallum.Metallum;
import com.metallum.mtl.MTLDevice;
import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentSkipListSet;

import static java.lang.foreign.ValueLayout.ADDRESS;

/** Stable device/runtime identity and per-pipeline fingerprints for native archives. */
final class PipelineCacheIdentity {
    private static final MemorySegment PROCESS_INFO_CLASS = ObjC.findClass("NSProcessInfo");
    private static final Msg PROCESS_INFO = Msg.of("processInfo", ADDRESS);
    private static final Msg OS_VERSION_STRING = Msg.of("operatingSystemVersionString", ADDRESS);

    private final String globalKey;
    private final Path archivePath;
    private final Path manifestPath;
    private final ConcurrentSkipListSet<String> pipelineKeys = new ConcurrentSkipListSet<>();

    private PipelineCacheIdentity(
            final String globalKey,
            final Path archivePath,
            final Path manifestPath
    ) {
        this.globalKey = globalKey;
        this.archivePath = archivePath;
        this.manifestPath = manifestPath;
    }

    static PipelineCacheIdentity create(final MTLDevice device, final boolean metal4) {
        String metalPath = metal4 ? "metal4" : "metal3";
        String mslVersion = metal4 ? "4.0" : "3.0";
        String key = hash(
                "registry=" + Long.toUnsignedString(device.registryId()),
                "os=" + osBuild(),
                "arch=" + System.getProperty("os.arch", "unknown"),
                "minecraft=" + modVersion("minecraft"),
                "sodium=" + modVersion("sodium"),
                "metallum=" + modVersion("metallum"),
                "path=" + metalPath,
                "msl=" + mslVersion
        );
        Path directory = FabricLoader.getInstance().getConfigDir()
                .resolve("metallum")
                .resolve("pipeline-cache");
        Path archive = directory.resolve(metalPath + "-" + key
                + (metal4 ? ".mtl4archive" : ".metallibarchive"));
        return new PipelineCacheIdentity(
                key,
                archive,
                directory.resolve(metalPath + "-" + key + ".manifest")
        );
    }

    Path archivePath() {
        return this.archivePath;
    }

    String recordPipeline(
            final RenderPipeline pipeline,
            final String vertexMsl,
            final String fragmentMsl
    ) {
        String fingerprint = hash(
                this.globalKey,
                pipeline.getLocation().toString(),
                pipeline.getVertexShader().toString(),
                pipeline.getFragmentShader().toString(),
                pipeline.getShaderDefines().toString(),
                pipeline.getBindGroupLayouts().toString(),
                String.valueOf(pipeline.getDepthStencilState()),
                String.valueOf(pipeline.getColorTargetState()),
                String.valueOf(pipeline.getPolygonMode()),
                Boolean.toString(pipeline.isCull()),
                String.valueOf(pipeline.getPrimitiveTopology()),
                Arrays.toString(pipeline.getVertexFormatBindings()),
                vertexMsl,
                fragmentMsl
        );
        this.pipelineKeys.add(fingerprint);
        return fingerprint;
    }

    void flushManifest() {
        Path temporary = this.manifestPath.resolveSibling(this.manifestPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(this.manifestPath.getParent());
            StringBuilder contents = new StringBuilder("global=").append(this.globalKey).append('\n');
            for (String key : this.pipelineKeys) {
                contents.append("pipeline=").append(key).append('\n');
            }
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            atomicReplace(temporary, this.manifestPath);
        } catch (IOException exception) {
            Metallum.LOGGER.warn("[metallum] Failed to persist pipeline cache manifest {}",
                    this.manifestPath, exception);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }

    static String hash(final String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest(), 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }

    private static String modVersion(final String id) {
        return FabricLoader.getInstance().getModContainer(id)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("missing");
    }

    private static String osBuild() {
        if (ObjC.isNil(PROCESS_INFO_CLASS)) {
            return System.getProperty("os.version", "unknown");
        }
        try (AutoreleasePool ignored = AutoreleasePool.push()) {
            MemorySegment processInfo = PROCESS_INFO.sendPtr(PROCESS_INFO_CLASS);
            MemorySegment version = OS_VERSION_STRING.sendPtr(processInfo);
            String value = ObjC.isNil(version) ? "" : ObjC.javaString(version);
            return value.isBlank() ? System.getProperty("os.version", "unknown") : value;
        } catch (RuntimeException exception) {
            return System.getProperty("os.version", "unknown");
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
}
