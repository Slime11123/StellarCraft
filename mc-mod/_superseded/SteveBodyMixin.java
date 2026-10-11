package dev.stellarcraft.world.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.stellarcraft.client.ControlStateMachine;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws Steve's own body in the WORLD pass, which is what makes the avatar actually visible
 * in Stellar Blade.
 *
 * <p><b>Why this exists.</b> Stellar Blade hides Eve's mesh while Steve owns the character
 * (see {@code apply_eve_visibility} in main.lua) so the player is not staring at the inside of
 * her head. Steve himself is drawn by Minecraft and composited by the overlay. But the overlay
 * only exports MC's HUD/screen layer — {@code ClearWorldMixin} wipes the colour buffer right
 * before the HUD is drawn, precisely so the world is NOT exported. So if Steve were drawn as
 * part of the normal world pass, the clear would erase him too (that is what produced the
 * "人物模型是隐形的" report).
 *
 * <p>The fix is to draw him LATE — after the world has been drawn and after the HUD clear —
 * but still with the world's projection matrix active, so he appears at the correct place and
 * size. We hook the very end of {@code LevelRenderer.renderLevel}: at that point the world
 * transform is still set up, and {@code GameRenderer} has not yet cleared for the HUD.
 *
 * <p>Only the local player is drawn, and only while Steve owns the character. In first person
 * the camera sits inside him, so the body is naturally hidden by back-face culling plus the
 * near plane — exactly like vanilla. In third person (F5) the full model is visible.
 */
@Mixin(LevelRenderer.class)
public abstract class SteveBodyMixin {

    @Inject(
        method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;" +
                 "Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;" +
                 "Lorg/joml/Matrix4f;)V",
        at = @At("RETURN"))
    private void stellarcraft$drawSteve(PoseStack poseStack, float partialTick, long nanoTime,
                                        boolean renderBlockOutline, Camera camera,
                                        GameRenderer gameRenderer, LightTexture lightTexture,
                                        Matrix4f projection, CallbackInfo ci) {
        if (!ControlStateMachine.isSteve()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        // Do not double-draw him if something else already did.
        if (mc.getCameraEntity() != player) return;

        // Draw relative to the camera, which is the convention renderLevel uses: the world
        // transform already has the camera translation applied.
        Vec3 cam = camera.getPosition();
        double dx = player.getX() - cam.x;
        double dy = player.getY() - cam.y;
        double dz = player.getZ() - cam.z;

        // Skip the first-person case entirely: the camera is inside the head and drawing the
        // body only wastes a pass (and can show stray geometry through the near plane).
        boolean firstPerson = mc.options != null
            && mc.options.getCameraType().isFirstPerson();
        if (firstPerson) return;

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        var dispatcher = mc.getEntityRenderDispatcher();

        // Interpolated yaw so he does not jitter between ticks.
        float yaw = player.getViewYRot((float) partialTick);
        poseStack.pushPose();
        try {
            dispatcher.render(player, dx, dy, dz, yaw, partialTick, poseStack,
                buffers, LightTexture.FULL_BRIGHT);
        } catch (Throwable t) {
            // Never let a render hiccup kill the frame.
        } finally {
            poseStack.popPose();
        }
        buffers.endBatch();
    }
}
