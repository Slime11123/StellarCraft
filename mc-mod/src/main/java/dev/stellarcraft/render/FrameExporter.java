package dev.stellarcraft.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.stellarcraft.client.ControlStateMachine;
import dev.stellarcraft.link.Proto;
import dev.stellarcraft.link.SharedMemory;
import dev.stellarcraft.world.mixin.GameRendererAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

import java.nio.ByteBuffer;

/**
 * Copies Minecraft's main render target (HUD + open screens) back from the GPU and
 * publishes it into the shared-memory overlay slot, so Stellar Blade can composite it.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li><b>GPU downscale.</b> The main target is blitted into a small offscreen target capped
 *       at {@value #kMaxExportW}x{@value #kMaxExportH} (aspect preserved). The overlay only
 *       ever draws a window-sized image, so exporting a 4K frame is pure waste.</li>
 *   <li><b>Async readback.</b> {@code glReadPixels} of the SMALL texture into a PBO; with a
 *       pack buffer bound the call returns immediately and the DMA completes later. A fence
 *       polls completion with a zero timeout, so the render thread never stalls.</li>
 *   <li><b>Publish.</b> Next frame, map the PBO and copy it into shared memory in one bulk
 *       copy. No per-pixel work happens in Java at all.</li>
 * </ol>
 *
 * <h2>Why the size cap and the bulk copy matter (measured, not theory)</h2>
 *
 * <p>Minecraft's window was 3840x2054. The first version of this class exported that
 * unmodified, and the cost was paid three times over 7.9M pixels:
 * <ul>
 *   <li>a full-size GPU→CPU readback plus shared-memory write of 31.5 MB per frame;</li>
 *   <li>a per-pixel loop here to derive the alpha mask (4 gets + 4 puts per pixel);</li>
 *   <li>a second byte-by-byte copy inside {@code SharedMemory.writeOverlayFrame};</li>
 *   <li>and, in the overlay process, another pass over all 7.9M pixels converting to a
 *       full-size DIB before {@code StretchBlt} could scale it.</li>
 * </ul>
 * Measured result: <b>~12 fps</b> (frame counters 60→120 took 5.0 s, 120→180 took 7.0 s) —
 * smooth in EVE mode, visibly stuttering in STEVE mode, i.e. the cost was ours. Capping to
 * 1280x720 cuts the pixel count by ~8.6x and removes every one of those passes.
 *
 * <p>The alpha mask is no longer derived here at all: the overlay derives it while scaling,
 * which is the pass that already has to touch every pixel in order to resample.
 *
 * <p>Rows are bottom-up (GL texture origin is bottom-left), flagged in the slot header.
 * RGBA8 only — the depth layer is RESERVED in the protocol but not produced yet.
 *
 * <p>Each published frame also carries the camera pose it was RENDERED WITH (position,
 * orientation, effective vertical FOV, aspect, clip planes). Nothing consumes it yet: it is
 * recorded so a re-projection pass can be added without another protocol change.
 */
public final class FrameExporter {
    private static final int SLOTS = 3;

    /**
     * Hard cap on the exported frame, aspect preserved.
     *
     * <p>Sized so that a host window of roughly this size can be served with almost no
     * resampling, while a 4K Minecraft window is cut down by nearly an order of magnitude.
     */
    private static final int kMaxExportW = Proto.MAX_OVERLAY_W;
    private static final int kMaxExportH = Proto.MAX_OVERLAY_H;

    /**
     * PNG dumping was a debugging aid that cost a full-frame BufferedImage plus a PNG encode
     * every 120 frames — hundreds of milliseconds of stall, which showed up as a freeze every
     * couple of seconds. Off unless someone is actually chasing a pixel problem.
     */
    private static final boolean kDumpPng = false;

    private static final class Slot {
        int pbo;
        long fence;
        long capture;
        /** EXPORT size — may be smaller than the render target (see the size cap). */
        int width;
        int height;
        int inFlight; // 0 = free, 1 = readback issued
        /** Pose captured when this frame was rendered; consumed when the readback lands. */
        SharedMemory.FramePose pose;
    }

    private static final Slot[] slots = { new Slot(), new Slot(), new Slot() };

    /** Render-target size (what MC actually rendered at). */
    private static int srcW, srcH;
    /** Exported size — what gets published, and the space cursor coordinates arrive in. */
    private static int width, height;

    /** Offscreen target the frame is downscaled into before readback. */
    private static int exportFbo, exportTex;

    private static long frames;
    private static long lastReadback;
    private static long captures, lastPublishedCapture;

    private static SharedMemory memory;

    private FrameExporter() {}

    /** Called once the shared-memory mapping is up (from StellarCraftMod). */
    public static void setMemory(SharedMemory m) {
        memory = m;
    }

    /**
     * Ratio {@code renderTarget : exportedFrame}, for converting coordinates that arrive in
     * export space (the overlay's cursor forwarding) back into render-target pixels.
     * 1.0 when no downscale is in effect.
     */
    public static float exportScale() {
        return width > 0 ? (float) srcW / (float) width : 1.0f;
    }

    /** Convenience entry used by GameRendererMixin (render RETURN, on the render thread). */
    public static void export(Minecraft mc, float partialTick) {
        if (memory != null) export(mc, memory, partialTick);
    }

    /**
     * Called once per render frame while linked: downscale + read back the main render target,
     * and publish any readback that has landed.
     *
     * <p>Runs at {@code GameRenderer.render} RETURN, i.e. immediately after the frame was
     * drawn — which is exactly why the camera pose is sampled here: it is the pose that
     * produced the pixels we are reading back. (Publishing happens in a LATER frame's poll, so
     * sampling the pose at publish time would record the wrong camera.)
     */
    public static void export(Minecraft mc, SharedMemory mem, float partialTick) {
        dev.stellarcraft.client.NativeDisplay.tick(mc, mem);
        // Only while MC owns the character. MC's own menus are legitimate content here: after
        // F4 the player is looking at Minecraft, and the world-select / options screens are
        // what they need to interact with. Only the world layer is suppressed
        // (WorldLayerMixin wipes it before the hand and HUD passes).
        if (!ControlStateMachine.isSteve()) return;

        RenderTarget target = mc.getMainRenderTarget();
        if (target == null || target.width <= 0 || target.height <= 0) return;

        // Poll finished readbacks first (cheap, zero-timeout).
        poll(mem);

        // Full resolution UI at 30 Hz; camera and native geometry update independently.
        long now = System.nanoTime();
        if (now - lastReadback < (mc.screen == null ? 100_000_000L : 33_000_000L)) return;
        lastReadback = now;

        int w = target.width;
        int h = target.height;
        resizeIfNeeded(w, h);

        // Find a free slot.
        Slot slot = null;
        for (Slot s : slots) {
            if (s.inFlight == 0) { slot = s; break; }
        }
        if (slot == null) return; // all slots busy; skip this frame (drop)

        slot.width = width;
        slot.height = height;
        slot.pose = capturePose(mc, w, h, partialTick);
        slot.capture = ++captures;

        // Downscale on the GPU, then read the SMALL texture back. glGetTexImage (which the
        // first version used, mirroring Screenshot.takeScreenshot) cannot scale, which is why
        // this goes through an offscreen target instead.
        blitToExport(target);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.pbo);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);

        // Record a fence so poll() can detect completion without stalling.
        slot.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL11.glFlush();
        slot.inFlight = 1;
    }

    /** Poll the fence of each in-flight slot; publish any that have finished. */
    private static void poll(SharedMemory mem) {
        Slot newest = null;
        for (Slot s : slots) {
            if (s.inFlight != 1 || s.fence == 0 || s.capture <= lastPublishedCapture) continue;
            int r = GL32.glClientWaitSync(s.fence, 0, 0);
            if ((r == GL32.GL_ALREADY_SIGNALED || r == GL32.GL_CONDITION_SATISFIED)
                && (newest == null || s.capture > newest.capture)) newest = s;
        }
        for (Slot s : slots) {
            if (s.inFlight != 1 || s.fence == 0) continue;
            int r = GL32.glClientWaitSync(s.fence, 0, 0); // zero timeout — never blocks
            if (r == GL32.GL_ALREADY_SIGNALED || r == GL32.GL_CONDITION_SATISFIED) {
                if (s == newest) { publish(mem, s); lastPublishedCapture = s.capture; }
                GL32.glDeleteSync(s.fence);
                s.fence = 0;
                s.inFlight = 0;
            }
            // else: still in flight, leave for next frame.
        }
    }

    /**
     * Publishes a finished readback.
     *
     * <p>Deliberately trivial: NO per-pixel work happens here any more. The alpha mask
     * (non-black = UI) is derived by the overlay while it scales, and the data is handed to
     * shared memory for a single bulk copy.
     */
    private static void publish(SharedMemory mem, Slot s) {
        int bytes = s.width * s.height * 4;
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, s.pbo);
        ByteBuffer mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, bytes, GL30.GL_MAP_READ_BIT);
        if (mapped != null) {
            try {
                mem.writeOverlayFrame(s.width, s.height, true, mapped, s.pose);
                frames++;

                if (frames <= 3 || frames % 120 == 0) {
                    logFrame(s, mapped);
                    if (kDumpPng && frames % 120 == 0) dumpPng(s, mapped);
                }
            } finally {
                GL30.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            }
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
    }

    /**
     * Verification line: counts non-black pixels on a sparse grid and reports the first one.
     *
     * <p>Sampled on purpose. The version this replaces walked every pixel of every frame to
     * build a colour histogram, which was itself part of the frame-time problem.
     *
     * <p>Reading it: with a world loaded, a value near zero means the frame is empty (a
     * transform bug — see WorldLayerMixin); a healthy HUD frame is tens of percent. EVE-mode
     * frames are not exported at all, so there is no control line to compare against.
     */
    private static void logFrame(Slot s, ByteBuffer m) {
        final int step = 1024;
        int total = s.width * s.height;
        int samples = 0, opaque = 0, alphaPixels=0, blackPixels=0;
        int firstR = -1, firstG = -1, firstB = -1;
        for (int px = 0; px < total; px += step) {
            int i = px * 4;
            int r = m.get(i) & 0xFF, g = m.get(i + 1) & 0xFF, b = m.get(i + 2) & 0xFF;
            samples++;
            if((m.get(i+3)&255)>0) {alphaPixels++;if((r|g|b)==0) blackPixels++;}
            if ((r | g | b) != 0) {
                opaque++;
                if (firstR < 0) { firstR = r; firstG = g; firstB = b; }
            }
        }
        System.out.println("[StellarCraft-FX] frame " + frames
            + " export=" + s.width + "x" + s.height
            + " (rt=" + srcW + "x" + srcH + ")"
            + " sampledOpaque=" + opaque + "/" + samples
            + " alphaPixels="+alphaPixels+" blackCovered="+blackPixels
            + " first=(" + firstR + "," + firstG + "," + firstB + ")");
    }

    // ---- Offscreen export target ----------------------------------------------------------

    /**
     * Creates (or recreates) the small target the frame is downscaled into.
     *
     * <p>Every GL call here is raw, and every binding it touches is saved first and restored
     * afterwards. {@code GlStateManager} keeps its own cached copy of the bound texture and
     * framebuffer, so going through the cache would work too — but leaving GL exactly as we
     * found it keeps that cache valid without having to reason about its per-target rules.
     *
     * <p>The texture and framebuffer are allocated once and kept for the lifetime of the game,
     * so the driver can never hand their names to anything else.
     */
    private static void createExportTarget() {
        if (exportTex == 0) exportTex = GL11.glGenTextures();
        if (exportFbo == 0) exportFbo = GlStateManager.glGenFramebuffers();

        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, exportTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                          GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, exportFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                                    GL11.GL_TEXTURE_2D, exportTex, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevDraw);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);

        System.out.println("[StellarCraft-FX] export target " + width + "x" + height
            + " fbo=" + exportFbo + " tex=" + exportTex
            + " status=" + (status == GL30.GL_FRAMEBUFFER_COMPLETE
                            ? "complete" : "0x" + Integer.toHexString(status)));
    }

    /**
     * Blits the main render target into the small export target, scaling on the GPU.
     *
     * <p>Restores through {@code GL_FRAMEBUFFER} so BOTH the read and draw bindings end up back
     * where they started — everything in Minecraft binds through {@code GL_FRAMEBUFFER}, so a
     * single {@code GL_FRAMEBUFFER} restore leaves the renderer's cached binding valid.
     * (Binding read and draw separately is unavoidable: {@code glBlitFramebuffer} needs two
     * different framebuffers bound at once.)
     */
    private static void blitToExport(RenderTarget target) {
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, exportFbo);
        GL30.glBlitFramebuffer(0, 0, srcW, srcH, 0, 0, width, height,
                               GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevDraw);
    }

    // ---- Pose ------------------------------------------------------------------------------

    /**
     * Samples the camera pose that produced the frame currently being read back.
     *
     * <p>Deliberately defensive: a missing camera or an unexpected FOV result must not break
     * frame export. This is diagnostic cargo until something consumes it, so falling back to
     * a sane default is better than dropping the frame.
     */
    private static SharedMemory.FramePose capturePose(Minecraft mc, int w, int h, float partialTick) {
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return null;

        Vec3 pos = camera.getPosition();
        float vFov = 70.0f;
        try {
            // The FOV the frame was ACTUALLY rendered with (sprint / bow-zoom / underwater
            // modifiers already applied), not the options slider value.
            vFov = (float) ((GameRendererAccessor) mc.gameRenderer)
                .stellarcraft$getFov(camera, partialTick, true);
        } catch (Throwable ignored) {
            // keep the 70° default
        }

        float aspect = h > 0 ? (float) w / (float) h : 16.0f / 9.0f;
        return new SharedMemory.FramePose(
            pos.x, pos.y, pos.z,
            camera.getYRot(), camera.getXRot(),
            0.0f,                        // 1.20.1 Camera has no roll field
            vFov, aspect,
            Proto.MC_NEAR_PLANE, mc.gameRenderer.getDepthFar(),
            cameraMode(mc));
    }

    /** MC's F5 camera mode, in the encoding documented on McState.cameraMode (0/1/2). */
    private static int cameraMode(Minecraft mc) {
        CameraType type = mc.options.getCameraType();
        if (type == CameraType.THIRD_PERSON_BACK) return 1;
        if (type == CameraType.THIRD_PERSON_FRONT) return 2;
        return 0; // FIRST_PERSON
    }

    // ---- Size management -------------------------------------------------------------------

    private static void resizeIfNeeded(int w, int h) {
        if (w == srcW && h == srcH && slots[0].pbo != 0) return;
        discard();
        srcW = w;
        srcH = h;

        float s = Math.min(1.0f, Math.min(kMaxExportW / (float) w, kMaxExportH / (float) h));
        width = Math.max(1, Math.round(w * s));
        height = Math.max(1, Math.round(h * s));

        createExportTarget();

        for (Slot slot : slots) {
            if (slot.pbo == 0) slot.pbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.pbo);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) width * height * 4, GL15.GL_STREAM_READ);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);

        System.out.println("[StellarCraft-FX] export size " + srcW + "x" + srcH
            + " -> " + width + "x" + height);
    }

    private static void discard() {
        for (Slot s : slots) {
            if (s.fence != 0) {
                GL32.glDeleteSync(s.fence);
                s.fence = 0;
            }
            s.inFlight = 0;
        }
    }

    /**
     * Writes the raw RGBA frame (GL order: bottom-up) to a PNG for inspection.
     * Disabled by default — see {@link #kDumpPng}.
     */
    private static void dumpPng(Slot s, ByteBuffer mapped) {
        try {
            java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(s.width, s.height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < s.height; y++) {
                // PNG row 0 = top; GL texture row 0 = bottom.
                int srcRow = (s.height - 1 - y) * s.width * 4;
                for (int x = 0; x < s.width; x++) {
                    int i = srcRow + x * 4;
                    int r = mapped.get(i) & 0xFF;
                    int g = mapped.get(i + 1) & 0xFF;
                    int b = mapped.get(i + 2) & 0xFF;
                    int a = (r | g | b) != 0 ? 0xFF : 0x00;
                    img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            java.io.File out = new java.io.File("stellarcraft_frame.png");
            javax.imageio.ImageIO.write(img, "png", out);
            System.out.println("[StellarCraft-FX] dumped frame " + frames + " -> " + out.getAbsolutePath());
        } catch (Throwable t) {
            System.out.println("[StellarCraft-FX] png dump failed: " + t);
        }
    }
}
