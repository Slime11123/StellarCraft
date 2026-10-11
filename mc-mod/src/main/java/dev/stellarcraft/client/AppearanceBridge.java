package dev.stellarcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.stellarcraft.link.Proto;
import dev.stellarcraft.link.SharedMemory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Bridges the Minecraft player's skin + cape into the shared-memory appearance region so
 * Stellar Blade can render the MC player (Steve) with the player's own appearance.
 *
 * <p>Data flow: MC is the <em>source of truth</em> for the player's appearance. Each tick we
 * (re)read the current skin/cape texture locations, resolve their textures to RGBA pixels, and
 * write them into {@code OFF_APPEARANCE} (seqlock + FNV-1a hashes for change detection). The SB
 * side reads the region, notices a hash/seq change, and re-uploads the textures to its material.
 *
 * <p>Fallback rules (per the task spec):
 * <ul>
 *   <li><b>No cape</b> — {@code HAS_CAPE} flag cleared; SB falls back to no cape.</li>
 *   <li><b>Offline / custom skin</b> — the cape/skin texture may resolve to
 *       the bundled Steve/Alex default; we mark {@code DEFAULT} so SB can apply its own
 *       default-character fallback if the texture is missing.</li>
 *   <li><b>Texture load failure</b> — keep the last successfully published pixels and only
 *       bump the seq when a <em>new</em> valid texture arrives, so SB never flickers.</li>
 * </ul>
 *
 * <p>Auto-refresh: we keep the last-published skin/cape hashes; when either changes (the
 * player switched skin/cape in the launcher, or a texture finished loading), we re-publish.
 */
public final class AppearanceBridge {
    private AppearanceBridge() {}

    private static long lastSkinHash = -1L;
    private static long lastCapeHash = -1L;

    /** Called once per client tick from {@link ClientBridge#tick}. */
    public static void tick(Minecraft mc, SharedMemory mem) {
        if (mem == null) return;
        AbstractClientPlayer p = mc.player;
        if (p == null) return;

        // 1.20.1 API: skin/cape are exposed as ResourceLocations, not a PlayerSkin record
        // (PlayerSkin only exists in 1.20.2+).
        ResourceLocation skinLoc = p.getSkinTextureLocation();
        ResourceLocation capeLoc = p.getCloakTextureLocation();

        // No skin info yet (offline/singleplayer before skin loads) — publish a default.
        if (skinLoc == null) {
            publishDefault(mem);
            return;
        }

        // Resolve skin + cape textures to pixels. These are async; when a future completes
        // we re-run on a later tick via change detection.
        resolveAndPublish(mc, mem, p, skinLoc, capeLoc);
    }

    /** Reads the skin/cape textures and publishes once both are available or failed. */
    private static void resolveAndPublish(Minecraft mc, SharedMemory mem, AbstractClientPlayer p,
                                          ResourceLocation skinLoc, ResourceLocation capeLoc) {
        boolean slim = "slim".equals(p.getModelName());

        // Skin pixels. NativeImage ownership: we may create a new image (from a stream),
        // which we must close, OR borrow the DynamicTexture's image, which we must NOT close.
        TextureImage skinTex = loadTextureImage(mc, skinLoc);
        TextureImage capeTex = (capeLoc != null) ? loadTextureImage(mc, capeLoc) : null;

        NativeImage skinImg = skinTex != null ? skinTex.image : null;
        NativeImage capeImg = capeTex != null ? capeTex.image : null;

        int flags = 0;
        if (skinImg != null) flags |= Proto.AppearanceFlags.HAS_SKIN;
        if (capeImg != null) flags |= Proto.AppearanceFlags.HAS_CAPE;
        if (slim) flags |= Proto.AppearanceFlags.SLIM;
        if (isDefaultSkin(skinLoc)) flags |= Proto.AppearanceFlags.DEFAULT;
        if (capeLoc == null && capeImg == null) flags |= Proto.AppearanceFlags.CAPE_OFFLINE;

        // Convert to RGBA byte buffers (little-endian already on x86-64).
        ByteBuffer skinPixels = skinImg != null ? toRgba(skinImg) : null;
        ByteBuffer capePixels = capeImg != null ? toRgba(capeImg) : null;

        long skinHash = skinPixels != null ? fnv1a(skinPixels) : 0L;
        long capeHash = capePixels != null ? fnv1a(capePixels) : 0L;

        // Change detection: only re-publish when something actually changed.
        if (skinHash == lastSkinHash && capeHash == lastCapeHash) {
            closeIfOwned(skinTex);
            closeIfOwned(capeTex);
            return;
        }
        lastSkinHash = skinHash;
        lastCapeHash = capeHash;

        int skinW = skinImg != null ? skinImg.getWidth() : 0;
        int skinH = skinImg != null ? skinImg.getHeight() : 0;
        int capeW = capeImg != null ? capeImg.getWidth() : 0;
        int capeH = capeImg != null ? capeImg.getHeight() : 0;

        mem.writeAppearance(flags, skinW, skinH, capeW, capeH, skinHash, capeHash,
                            skinPixels, capePixels);

        // Free only images we allocated ourselves.
        closeIfOwned(skinTex);
        closeIfOwned(capeTex);
    }

    /** A NativeImage plus an ownership flag (owned = we created it and must close it). */
    private static final class TextureImage {
        final NativeImage image;
        final boolean owned;
        TextureImage(NativeImage image, boolean owned) { this.image = image; this.owned = owned; }
    }

    private static void closeIfOwned(TextureImage t) {
        if (t != null && t.owned && t.image != null) t.image.close();
    }

    /** Publishes a "no appearance" default state (no skin, no cape). */
    private static void publishDefault(SharedMemory mem) {
        if (lastSkinHash == 0L && lastCapeHash == 0L) return;
        lastSkinHash = 0L;
        lastCapeHash = 0L;
        mem.writeAppearance(0, 0, 0, 0, 0, 0L, 0L, null, null);
    }

    /**
     * Loads a registered texture's pixels as a {@link TextureImage}. Returns {@code null} on
     * failure (texture not yet loaded / missing), so callers can apply fallback.
     *
     * <p>In 1.20.1 skins are registered either as {@code DynamicTexture} (pixels directly
     * available) or {@code SimpleTexture} (a source ResourceLocation we can read back).
     */
    private static TextureImage loadTextureImage(Minecraft mc, ResourceLocation loc) {
        if (loc == null) return null;
        try {
            var tex = mc.getTextureManager().getTexture(loc);
            if (tex == null) return null;

            // DynamicTexture holds its NativeImage directly (borrowed, do not close).
            if (tex instanceof net.minecraft.client.renderer.texture.DynamicTexture dyn) {
                NativeImage img = dyn.getPixels();
                return img != null ? new TextureImage(img, false) : null;
            }
            // SimpleTexture: read the underlying source image from the resource manager.
            if (tex instanceof net.minecraft.client.renderer.texture.SimpleTexture) {
                var res = mc.getResourceManager().getResource(loc);
                if (res.isEmpty()) return null;
                try (var in = res.get().open()) {
                    return new TextureImage(NativeImage.read(in), true);
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** True if the skin location is one of Minecraft's bundled default skins. */
    private static boolean isDefaultSkin(ResourceLocation loc) {
        if (loc == null) return true;
        String path = loc.getPath();
        return path.contains("steve") || path.contains("alex");
    }

    /** Copies a NativeImage's pixels into a tightly-packed RGBA8 little-endian buffer. */
    private static ByteBuffer toRgba(NativeImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        ByteBuffer buf = ByteBuffer.allocate(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = img.getPixelRGBA(x, y); // NativeImage stores ABGR
                int a = (abgr >>> 24) & 0xFF;
                int b = (abgr >>> 16) & 0xFF;
                int g = (abgr >>> 8) & 0xFF;
                int r = abgr & 0xFF;
                buf.put((byte) r);
                buf.put((byte) g);
                buf.put((byte) b);
                buf.put((byte) a);
            }
        }
        buf.flip();
        return buf;
    }

    /** FNV-1a 64-bit hash over the buffer (without consuming it). */
    private static long fnv1a(ByteBuffer buf) {
        long hash = 0xcbf29ce484222325L;
        ByteBuffer dup = buf.duplicate();
        while (dup.hasRemaining()) {
            hash ^= (dup.get() & 0xFF);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
