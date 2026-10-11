package dev.stellarcraft.world.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.stellarcraft.client.ControlStateMachine;
import dev.stellarcraft.world.PlacedBlocks;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.opengl.GL11;

/**
 * Builds the "Minecraft layer" that the overlay composites over Stellar Blade: Steve's own body
 * in third person, and (drawn later by vanilla) the first-person arms and the HUD — but
 * <b>not</b> the world terrain, and <b>not</b> the placed blocks.
 *
 * <p>Placed blocks used to be drawn here and deliberately no longer are: a block has to be a
 * real collidable object in Stellar Blade's world, so it is mirrored as an
 * {@code AStaticMeshActor} instead (see {@link dev.stellarcraft.world.BlockBridge} and
 * {@link #kDrawBlocksInOverlay}).
 *
 * <p>This class merges what used to be two separate mixins ({@code ClearWorldMixin} and
 * {@code SteveBodyMixin}). They must share ONE injection point, for two reasons explained
 * below: the ordering between them is load-bearing, and the old wipe location was actively
 * destroying the frame.
 *
 * <h2>Why everything happens at {@code LevelRenderer.renderLevel} RETURN</h2>
 *
 * <p>{@code GameRenderer.renderLevel} calls, in this order (bytecode offsets 585 and 625):
 * <pre>
 *   LevelRenderer.renderLevel(...)   // the world + this injection's RETURN
 *   this.renderItemInHand(...)       // the first-person arms, drawn by vanilla
 * </pre>
 * and only afterwards does {@code GameRenderer.render} set up the GUI projection and call
 * {@code Gui.render} for the HUD. Wiping and re-drawing here therefore leaves the arms and the
 * HUD to be drawn by <b>vanilla</b>, with exactly the render state vanilla expects.
 *
 * <p>The wipe used to sit at {@code Gui.render} HEAD instead. That is late enough to remove
 * the world, but it also lands <i>after</i> the arms — so the arms had to be re-drawn by hand.
 * Re-invoking {@code renderItemInHand} is not safe, and it silently destroyed the frame:
 * <ul>
 *   <li>it calls {@code resetProjectionMatrix(getProjectionMatrix(getFov(camera, f, false)))} and
 *       <b>never restores it</b>. {@code GameRenderer.render} had already installed the GUI's
 *       orthographic projection (~offset 358, {@code setOrtho(0, guiW, guiH, 0, 1000, 21000)}
 *       with {@code ORTHOGRAPHIC_Z}) — so every HUD draw queued afterwards was projected
 *       through a 3D perspective matrix and landed nowhere;</li>
 *   <li>it relies on {@code RenderSystem.getModelViewMatrix()} still holding the camera
 *       transform, but at that point it holds the GUI's {@code translate(0, 0, -11000)} — so
 *       the re-drawn arms were pushed past the far clip plane and clipped away.</li>
 * </ul>
 * The exported frame came out <b>exactly</b> zero everywhere — not merely dark. That is the
 * signature of a transform problem, not of a state/predicate problem, and it is what pointed
 * at the hand re-draw.
 *
 * <h2>Draw order inside one callback</h2>
 *
 * <p>Order is a hard constraint, not a preference: a second injector at the same point would
 * have an unspecified relative order against this one, and anything drawn <i>before</i> the wipe
 * is erased by it (that was the "人物模型是隐形的" report). So: wipe, then blocks, then Steve —
 * all in this method, in that order.
 *
 * <h2>Position convention</h2>
 *
 * <p>The pose stack at this point carries the camera <b>rotation</b> only; world geometry is
 * emitted camera-relative. That is the convention vanilla's own {@code renderShape} uses
 * ({@code pose.pose().transformPosition(x - camX, ...)}) and what {@code EntityRenderDispatcher}
 * expects ({@code render(entity, x - camX, ...)}).
 */
@Mixin(LevelRenderer.class)
public abstract class WorldLayerMixin {

    /**
     * Draw placed blocks into the exported frame?
     *
     * <p><b>No — and this is deliberate.</b> It was the first implementation, and it is the
     * wrong shape for what a block has to be. Anything drawn here is a picture: it has no
     * depth against Stellar Blade's scenery, and — the part that actually matters — nothing
     * in Stellar Blade can collide with it. A Naytiba has to be <i>stopped</i> by a placed
     * block, and a pixel cannot stop anything.
     *
     * <p>Blocks are therefore mirrored into Stellar Blade as real {@code AStaticMeshActor}s
     * with {@code BlockAll} collision ({@link dev.stellarcraft.world.BlockBridge} →
     * {@code main.lua}), rendered by Stellar Blade's own renderer with correct depth,
     * lighting and occlusion.
     *
     * <p>Kept as a switch rather than deleted: if the native path ever has to be diagnosed in
     * isolation, flipping this to {@code true} gives a known-good visual reference for where
     * the blocks are supposed to be. It must never be left on together with the native path,
     * or every block appears twice (the overlay copy drawn flat on top).
     */
    private static final boolean kDrawBlocksInOverlay = false;

    /** Throttle for the verification line: render calls, not frames (≈5 s at 60 fps). */
    private static int stellarcraft$layerLogTick;

    @Inject(
        method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;" +
                 "Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;" +
                 "Lorg/joml/Matrix4f;)V",
        at = @At("HEAD"), cancellable = true)
    private void stellarcraft$buildMcLayer(PoseStack poseStack, float partialTick, long nanoTime,
                                           boolean renderBlockOutline, Camera camera,
                                           GameRenderer gameRenderer, LightTexture lightTexture,
                                           Matrix4f projection, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (dev.stellarcraft.client.NativeDisplay.suppressWorld()) {
            dev.stellarcraft.render.BodyMeshBridge.capture(mc,partialTick);
            dev.stellarcraft.render.EntityMeshBridge.capture(mc,partialTick);
            RenderSystem.depthMask(true);
            RenderSystem.colorMask(true,true,true,true);
            GlStateManager._clearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, false);
            ci.cancel();
            return;
        }
        if (!ControlStateMachine.isSteve()) return;

        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        // ---- 1. Drop the world layer -----------------------------------------------------
        //
        // `hudWillDraw` mirrors GameRenderer's own gate for even calling Gui.render
        // (`if (hideGui && screen == null) skip`). With F1 held down nothing is drawn after the
        // world, so wiping would leave a black screen; leaving the world visible matches what
        // this code did before the wipe moved here.
        //
        // COLOUR **and DEPTH** are both cleared. Depth matters: without it the (now invisible)
        // world's depth values survive, and everything drawn below — the arms, Steve, the placed
        // blocks — would be depth-tested against terrain nobody can see. A block placed in front
        // of a hidden hill would silently vanish.
        boolean hudWillDraw = !mc.options.hideGui || mc.screen != null;
        if (hudWillDraw) {
            RenderSystem.depthMask(true);
            RenderSystem.colorMask(true,true,true,true);
            GlStateManager._clearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, false);
        }

        // The world's projection and model-view are still active here, which is what puts
        // anything we draw at the correct place and size. (Drawing during the GUI pass would use
        // the GUI's orthographic projection and stamp it at the wrong place/size.)
        Vec3 cam = camera.getPosition();

        // Light/texture state: the world pass has already torn its own down, and both entity and
        // block render types sample the LIGHTMAP texture through `Sampler1`. Without the light
        // layer bound, blocks come out black and Steve comes out dark.
        lightTexture.turnOnLightLayer();
        try {
            // ---- 2. The blocks the player placed ----------------------------------------
            //
            // DISABLED — blocks are no longer drawn here. See kDrawBlocksInOverlay.
            if (kDrawBlocksInOverlay) {
                stellarcraft$drawPlacedBlocks(mc, poseStack, cam);
            }

            // ---- 3. Steve's own body ----------------------------------------------------
            //
            // Third person only: in first person the camera sits inside his head and vanilla
            // hides the body the same way. The first-person ARMS are not our business either —
            // vanilla draws them a few offsets later, after this injection.
            if (mc.getCameraEntity() == player
                    && !mc.options.getCameraType().isFirstPerson()) {
                double dx = player.getX() - cam.x;
                double dy = player.getY() - cam.y;
                double dz = player.getZ() - cam.z;
                // Interpolated yaw so he does not jitter between ticks.
                float yaw = player.getViewYRot(partialTick);

                MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
                poseStack.pushPose();
                try {
                    mc.getEntityRenderDispatcher().render(player, dx, dy, dz, yaw, partialTick,
                        poseStack, buffers, LightTexture.FULL_BRIGHT);
                } finally {
                    poseStack.popPose();
                }
                buffers.endBatch();
            }
        } catch (Throwable t) {
            // Never let a render hiccup kill the frame.
        } finally {
            lightTexture.turnOffLightLayer();
        }
    }

    /**
     * Draws every tracked block that is still present, camera-relative.
     *
     * <p>{@code FULL_BRIGHT} is deliberate: the light layer has no useful data for a block that
     * exists only in a world whose chunks were never lit, and a dark block over Stellar Blade's
     * scenery would read as "placement did nothing".
     */
    private static void stellarcraft$drawPlacedBlocks(Minecraft mc, PoseStack poseStack, Vec3 cam) {
        long[] placed = PlacedBlocks.positions();

        if (++stellarcraft$layerLogTick % 300 == 0) {
            System.out.println("[StellarCraft-Layer] placed=" + placed.length
                + " tracked=" + PlacedBlocks.count()
                + " steve=" + (ControlStateMachine.isSteve() ? 1 : 0));
        }
        if (placed.length == 0) return;

        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        final double cullSq = 96.0 * 96.0;
        int drawn = 0;

        for (long packed : placed) {
            BlockPos pos = BlockPos.of(packed);
            // Cheap distance reject before touching the level.
            double bx = pos.getX() + 0.5 - cam.x;
            double by = pos.getY() + 0.5 - cam.y;
            double bz = pos.getZ() + 0.5 - cam.z;
            if (bx * bx + by * by + bz * bz > cullSq) continue;

            BlockState state = mc.level.getBlockState(pos);
            if (state.isAir()) continue;

            poseStack.pushPose();
            try {
                poseStack.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
                dispatcher.renderSingleBlock(state, poseStack, buffers,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                drawn++;
            } finally {
                poseStack.popPose();
            }
        }

        // Block render types are NOT part of BufferSource's fixed set, and endBatch() only
        // flushes the last-used one — so each layer has to be ended explicitly or a stone block
        // followed by a glass one would leave the stone unflushed (invisible).
        buffers.endBatch(RenderType.solid());
        buffers.endBatch(RenderType.cutoutMipped());
        buffers.endBatch(RenderType.cutout());
        buffers.endBatch(RenderType.translucent());

        if (drawn != placed.length && stellarcraft$layerLogTick % 300 == 0) {
            System.out.println("[StellarCraft-Layer] drew " + drawn + " of " + placed.length
                + " (rest culled by distance or already air)");
        }
    }
}
