package dev.stellarcraft.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;

/**
 * Renders Minecraft's own content (placed blocks, held item, hotbar GUI) offscreen so
 * Stellar Blade can composite it into its frame.
 *
 * <p>SkyCraft-style layered rendering: Minecraft renders <em>only its own stuff</em> at
 * Stellar Blade's resolution, into a shared GPU texture (or a CPU readback in the MVP),
 * in three layers:
 * <ul>
 *   <li><b>World</b> — placed blocks, block entities, dropped items, particles</li>
 *   <li><b>Hand</b> — first-person arm + held item + swing/equip/eat animations</li>
 *   <li><b>GUI</b> — hotbar, hearts, hunger, crosshair, open screens</li>
 * </ul>
 *
 * <p>The MVP uses a CPU path: render to a framebuffer, read pixels back via PBO, and write
 * them into the shared-memory overlay slot (triple-buffered). The SB mod then composites
 * them into its own frame. The production path swaps in {@code WGL_NV_DX_interop2} /
 * {@code VK_KHR_external_memory_win32} for zero-copy GPU sharing.
 */
public final class OverlayCompositor {
    private static int framebuffer = -1;
    private static int colorTexture = -1;
    private static int depthTexture = -1;

    private OverlayCompositor() {}

    public static void ensureFramebuffer(int width, int height) {
        if (framebuffer != -1) return;
        framebuffer = GlStateManager.glGenFramebuffers();
        colorTexture = GlStateManager._genTexture();
        depthTexture = GlStateManager._genTexture();

        // Color
        GlStateManager._bindTexture(colorTexture);
        GlStateManager._texImage2D(
            com.mojang.blaze3d.platform.GlConst.GL_TEXTURE_2D, 0,
            com.mojang.blaze3d.platform.GlConst.GL_RGBA8, width, height, 0,
            com.mojang.blaze3d.platform.GlConst.GL_RGBA,
            com.mojang.blaze3d.platform.GlConst.GL_UNSIGNED_BYTE, null);

        // Depth
        GlStateManager._bindTexture(depthTexture);
        GlStateManager._texImage2D(
            com.mojang.blaze3d.platform.GlConst.GL_TEXTURE_2D, 0,
            com.mojang.blaze3d.platform.GlConst.GL_DEPTH_COMPONENT, width, height, 0,
            com.mojang.blaze3d.platform.GlConst.GL_DEPTH_COMPONENT,
            com.mojang.blaze3d.platform.GlConst.GL_FLOAT, null);

        GlStateManager._glBindFramebuffer(com.mojang.blaze3d.platform.GlConst.GL_FRAMEBUFFER, framebuffer);
        GlStateManager._glFramebufferTexture2D(
            com.mojang.blaze3d.platform.GlConst.GL_FRAMEBUFFER,
            com.mojang.blaze3d.platform.GlConst.GL_COLOR_ATTACHMENT0,
            com.mojang.blaze3d.platform.GlConst.GL_TEXTURE_2D, colorTexture, 0);
        GlStateManager._glFramebufferTexture2D(
            com.mojang.blaze3d.platform.GlConst.GL_FRAMEBUFFER,
            com.mojang.blaze3d.platform.GlConst.GL_DEPTH_ATTACHMENT,
            com.mojang.blaze3d.platform.GlConst.GL_TEXTURE_2D, depthTexture, 0);

        if (GlStateManager.glCheckFramebufferStatus(
                com.mojang.blaze3d.platform.GlConst.GL_FRAMEBUFFER)
                != com.mojang.blaze3d.platform.GlConst.GL_FRAMEBUFFER_COMPLETE) {
            // Log + fall back; in the skeleton we just proceed.
        }
    }

    /** Binds our offscreen framebuffer so the next MC render goes to the overlay. */
    public static void beginOverlay() {
        RenderSystem.assertOnRenderThread();
        GlStateManager._glBindFramebuffer(
            com.mojang.blaze3d.platform.GlConst.GL_FRAMEBUFFER, framebuffer);
    }

    /** Unbinds back to the default framebuffer. */
    public static void endOverlay() {
        GlStateManager._glBindFramebuffer(
            com.mojang.blaze3d.platform.GlConst.GL_FRAMEBUFFER, 0);
    }

    /**
     * CPU readback path (MVP): glReadPixels into a heap buffer, then the caller writes it
     * into the shared-memory overlay slot. Costs ~1 frame latency; the GPU-interop path
     * replaces this later.
     */
    public static void readbackPixels(int width, int height, java.nio.ByteBuffer dst) {
        GlStateManager._bindTexture(colorTexture);
        // 1.20.1: _getTexImage(target, level, format, type, pixels) takes a native
        // pointer (long). The buffer must be direct so we can pass its address.
        long addr = org.lwjgl.system.MemoryUtil.memAddress(dst);
        GlStateManager._getTexImage(
            com.mojang.blaze3d.platform.GlConst.GL_TEXTURE_2D, 0,
            com.mojang.blaze3d.platform.GlConst.GL_RGBA,
            com.mojang.blaze3d.platform.GlConst.GL_UNSIGNED_BYTE, addr);
    }
}
