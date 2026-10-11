package dev.stellarcraft;

import dev.stellarcraft.link.SharedMemory;

import java.nio.ByteBuffer;

/**
 * JNI bridge to the native shared-memory implementation.
 *
 * <p>The actual mapping is created/opened in {@code src/main/cpp/bridge.cpp} and exposed
 * here. This wrapper keeps the Java side free of {@code sun.misc.Unsafe} or JNA and gives
 * us a plain {@link ByteBuffer} over the file-mapping view.
 */
public final class NativeBridge {
    private static boolean loaded = false;

    static {
        try {
            System.loadLibrary("stellarcraft_bridge");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            // Native DLL not present yet (e.g. running without the Stellar Blade side).
            // The mod stays active but the bridge is inert until the DLL is dropped in.
            System.err.println("[StellarCraft] Native bridge not loaded: " + e.getMessage());
            loaded = false;
        }
    }

    private NativeBridge() {}

    public static boolean isLoaded() { return loaded; }

    /**
     * Opens (or joins) the named shared-memory mapping.
     *
     * @param name the Windows section name (e.g. {@code "Local\\StellarCraft_v1"})
     * @param size the mapping size in bytes
     * @return a direct {@link ByteBuffer} over the mapping, or {@code null} if it can't be opened
     */
    public static native SharedMemory open(String name, long size);

    /** Native helper: creates the mapping (called by the SB side only). */
    public static native ByteBuffer create(String name, long size);
}
