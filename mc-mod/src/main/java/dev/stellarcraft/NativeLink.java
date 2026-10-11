package dev.stellarcraft;

import dev.stellarcraft.link.Proto;
import dev.stellarcraft.link.SharedMemory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Opens the StellarCraft shared-memory mapping using the pure-Java Foreign Function &amp;
 * Memory (FFM) API — no JNI, no compiled native DLL.
 *
 * <p>This mirrors SkyCraft's {@code SkyLink} approach: we bind the Windows {@code kernel32}
 * functions {@code OpenFileMappingW} and {@code MapViewOfFile} via the {@link Linker}, then
 * expose the mapping as a {@link ByteBuffer} that {@link SharedMemory} slices into typed
 * regions. Stellar Blade (the UE4SS C++ mod) creates the mapping; Minecraft opens it.
 *
 * <p>Requires Java 21 (the {@code java.lang.foreign} API is stable since 21).
 */
public final class NativeLink {
    private NativeLink() {}

    private static final int FILE_MAP_ALL_ACCESS = 0x000F001F;

    private static final MethodHandle OPEN_FILE_MAPPING;
    private static final MethodHandle MAP_VIEW_OF_FILE;
    private static final MethodHandle GET_CURRENT_PROCESS_ID;
    private static final MethodHandle GET_TICK_COUNT64;

    static {
        Linker linker = Linker.nativeLinker();
        SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

        OPEN_FILE_MAPPING = linker.downcallHandle(
            k32.find("OpenFileMappingW").orElseThrow(),
            FunctionDescriptor.of(java.lang.foreign.ValueLayout.ADDRESS,
                java.lang.foreign.ValueLayout.JAVA_INT,          // dwDesiredAccess
                java.lang.foreign.ValueLayout.JAVA_INT,          // bInheritHandle
                java.lang.foreign.ValueLayout.ADDRESS));         // lpName (UTF-16)

        MAP_VIEW_OF_FILE = linker.downcallHandle(
            k32.find("MapViewOfFile").orElseThrow(),
            FunctionDescriptor.of(java.lang.foreign.ValueLayout.ADDRESS,
                java.lang.foreign.ValueLayout.ADDRESS,           // hFileMappingObject
                java.lang.foreign.ValueLayout.JAVA_INT,          // dwDesiredAccess
                java.lang.foreign.ValueLayout.JAVA_INT,          // dwFileOffsetHigh
                java.lang.foreign.ValueLayout.JAVA_INT,          // dwFileOffsetLow
                java.lang.foreign.ValueLayout.JAVA_LONG));       // dwNumberOfBytesToMap

        GET_CURRENT_PROCESS_ID = linker.downcallHandle(
            k32.find("GetCurrentProcessId").orElseThrow(),
            FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT));

        GET_TICK_COUNT64 = linker.downcallHandle(
            k32.find("GetTickCount64").orElseThrow(),
            FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_LONG));
    }

    /** True if the FFM bindings initialized (always true on Java 21 + Windows). */
    public static boolean isLoaded() {
        return true;
    }

    /**
     * Opens the named mapping created by Stellar Blade.
     *
     * @param name the Windows section name, e.g. {@code "Local\\StellarCraft_v1"}
     * @param size the mapping size in bytes
     * @return a {@link SharedMemory} wrapping the mapping, or {@code null} if it can't be
     *         opened yet (Stellar Blade hasn't created it, or it's still booting)
     */
    public static SharedMemory open(String name, long size) {
        try (Arena arena = Arena.ofConfined()) {
            // Windows section names are UTF-16LE + a trailing NUL. Allocate manually
            // because SegmentAllocator.allocateFrom(String, Charset) isn't stable in Java 21.
            byte[] utf16 = name.getBytes(StandardCharsets.UTF_16LE);
            MemorySegment nameSeg = arena.allocate(utf16.length + 2);
            MemorySegment.copy(MemorySegment.ofArray(utf16), 0, nameSeg, 0, utf16.length);

            MemorySegment handle = (MemorySegment) OPEN_FILE_MAPPING.invokeExact(
                FILE_MAP_ALL_ACCESS, 0, nameSeg);
            if (handle.address() == 0L) {
                // Mapping not present yet (normal while Stellar Blade loads). Quiet no-op.
                return null;
            }

            MemorySegment view = (MemorySegment) MAP_VIEW_OF_FILE.invokeExact(
                handle, FILE_MAP_ALL_ACCESS, 0, 0, size);
            if (view.address() == 0L) {
                return null;
            }

            MemorySegment seg = view.reinterpret(size);
            ByteBuffer buf = seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            return new SharedMemory(buf);
        } catch (Throwable t) {
            System.err.println("[StellarCraft] Failed to open shared memory: " + t.getMessage());
            return null;
        }
    }

    /** Current process id (for the header handshake). */
    public static int pid() {
        try {
            return (int) GET_CURRENT_PROCESS_ID.invokeExact();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Monotonic milliseconds since boot (matches the SB side's GetTickCount64). */
    public static long tickCount() {
        try {
            return (long) GET_TICK_COUNT64.invokeExact();
        } catch (Throwable t) {
            return System.currentTimeMillis();
        }
    }
}
