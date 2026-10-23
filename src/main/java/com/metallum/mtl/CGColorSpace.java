package com.metallum.mtl;

import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;

/** Minimal CoreGraphics color space bindings for configuring the presentation layer. */
@Environment(EnvType.CLIENT)
public final class CGColorSpace {
    private static final SymbolLookup CORE_GRAPHICS = SymbolLookup.libraryLookup(
            "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", Arena.global());
    private static final MethodHandle CREATE_WITH_NAME = ObjC.LINKER.downcallHandle(
            CORE_GRAPHICS.findOrThrow("CGColorSpaceCreateWithName"), FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle RELEASE = ObjC.LINKER.downcallHandle(
            CORE_GRAPHICS.findOrThrow("CGColorSpaceRelease"), FunctionDescriptor.ofVoid(ADDRESS));

    public static final String SRGB = "kCGColorSpaceSRGB";
    public static final String DISPLAY_P3 = "kCGColorSpaceDisplayP3";

    private CGColorSpace() {
    }

    /** Returns a +1 retained color space for one of the {@code kCGColorSpace*} name constants. */
    public static MemorySegment createWithName(final String constantName) {
        MemorySegment name = CORE_GRAPHICS.findOrThrow(constantName)
                .reinterpret(ADDRESS.byteSize())
                .get(ADDRESS, 0L);
        try {
            MemorySegment colorSpace = (MemorySegment) CREATE_WITH_NAME.invokeExact(name);
            if (ObjC.isNil(colorSpace)) {
                throw new IllegalStateException("CGColorSpaceCreateWithName(" + constantName + ") returned NULL");
            }
            return colorSpace;
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IllegalStateException("CGColorSpaceCreateWithName failed", throwable);
        }
    }

    public static void release(final MemorySegment colorSpace) {
        if (ObjC.isNil(colorSpace)) {
            return;
        }
        try {
            RELEASE.invokeExact(colorSpace);
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IllegalStateException("CGColorSpaceRelease failed", throwable);
        }
    }
}
