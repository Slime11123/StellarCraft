package dev.stellarcraft.client;

/**
 * Holds the "window focus takeover" flag, decoupled from the WindowMixin class.
 *
 * <p>This is a <em>plain</em> class (NOT in the mixin package), because the mixin package
 * ({@code dev.stellarcraft.world.mixin}) is protected by the Mixin framework — any class
 * placed there that isn't a real mixin triggers {@code IllegalClassLoadError} when another
 * class references it. The WindowMixin reads this flag from inside its injected method.
 */
public final class WindowFocusBridge {
    private static volatile boolean bridgeActive = false;

    private WindowFocusBridge() {}

    /** Called by the mod when the Stellar Blade bridge comes up / goes down. */
    public static void setBridgeActive(boolean active) {
        bridgeActive = active;
    }

    public static boolean isBridgeActive() {
        return bridgeActive;
    }
}
