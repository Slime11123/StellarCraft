package dev.stellarcraft.client;

import dev.stellarcraft.link.Proto;
import dev.stellarcraft.link.SharedMemory;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Runs only on the render thread. The host output size is independent of F4/UI visibility. */
public final class NativeDisplay {
    private static long checked, resized;
    private static int originalLimit = -1;
    private static boolean originalVsync, hostScene;
    private NativeDisplay() {}

    public static boolean suppressWorld() {
        Minecraft mc = Minecraft.getInstance();
        return hostScene && (ControlStateMachine.isSteve()
            || GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_FOCUSED) == 0);
    }

    public static void tick(Minecraft mc, SharedMemory memory) {
        long now = System.nanoTime();
        if (now - checked < 250_000_000L) return;
        checked = now;
        var sb = memory.readSbState();
        long age = dev.stellarcraft.NativeLink.tickCount() - memory.sbHeartbeat();
        boolean alive = age >= 0 && age < 5000;
        hostScene = alive && sb != null && (sb.flags & Proto.SbFlags.IN_GAME) != 0;
        if (!alive) {
            if (originalLimit >= 0) {
                mc.options.framerateLimit().set(originalLimit);
                mc.options.enableVsync().set(originalVsync);
                mc.getWindow().updateVsync(originalVsync);
                originalLimit = -1;
            }
            return;
        }
        if (originalLimit < 0) {
            originalLimit = mc.options.framerateLimit().get();
            originalVsync = mc.options.enableVsync().get();
        }
        // Keep movement and native limb poses at 60 Hz; full 4K HUD transfers stay separate.
        int limit = ControlStateMachine.isSteve() ? 60
            : (GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_FOCUSED) != 0 ? 60 : 15);
        mc.options.framerateLimit().set(limit);
        mc.getWindow().setFramerateLimit(limit);
        if (mc.options.enableVsync().get()) {
            mc.options.enableVsync().set(false);
            mc.getWindow().updateVsync(false);
        }
        if (!hostScene || sb.viewW < 320 || sb.viewH < 200) return;
        // A decorated 4K window is clamped to the work area (2160 minus its title bar).
        // Borderless remains windowed and gives the framebuffer the exact host dimensions.
        long window = mc.getWindow().getWindow();
        if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_DECORATED) != 0) {
            GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
            GLFW.glfwSetWindowPos(window, 0, 0);
        }
        int w = Math.min(Proto.MAX_OVERLAY_W, sb.viewW);
        int h = Math.min(Proto.MAX_OVERLAY_H, sb.viewH);
        if (now - resized < 1_000_000_000L) return;
        if (mc.getWindow().isFullscreen()) {
            mc.options.fullscreen().set(false);
            mc.getWindow().toggleFullScreen();
        }
        if (mc.getWindow().getWidth() != w || mc.getWindow().getHeight() != h) {
            mc.getWindow().setWindowed(w, h);
            GLFW.glfwSetWindowPos(window, 0, 0);
            resized = now;
            System.out.println("[StellarCraft-Display] host=" + sb.viewW + "x" + sb.viewH
                + " MC windowed=" + w + "x" + h + " limit=" + limit);
        }
    }
}
