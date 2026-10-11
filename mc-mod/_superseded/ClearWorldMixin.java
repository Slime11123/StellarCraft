package dev.stellarcraft.world.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.stellarcraft.client.ControlStateMachine;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.opengl.GL11;

/**
 * Clears the WORLD rendering right before the HUD is drawn, so only Minecraft's UI (hotbar,
 * hearts, hunger, crosshair, open screens) and the first-person hands survive into the
 * exported frame — the world itself is not shown in Stellar Blade.
 *
 * <p>{@code Gui.render} is the first HUD draw call, invoked right after the world has finished
 * rendering into the main render target. Clearing the colour buffer here wipes the world and
 * leaves a transparent background for the HUD, exactly the "overlay-only" layer SekiroCraft's
 * {@code overlay()} pass produces.
 *
 * <p><b>Only while a world is actually loaded.</b> On the title screen there is no world in
 * the colour buffer — there is the panorama/dirt background that the menu sits on, which the
 * player very much needs to see after pressing F4 from the main menu. Clearing there produced
 * a fully transparent frame, i.e. nothing at all.
 *
 * <p><b>Hands are re-drawn after the wipe.</b> Vanilla's render order is
 * {@code renderLevel -> renderItemInHand -> Gui.render}, so the first-person arms are painted
 * BEFORE this wipe and would be erased with the terrain. Since {@code renderItemInHand} resets
 * its own projection and matrix state and internally no-ops unless the camera is in first
 * person, re-running it here restores the arms for first person while leaving second/third
 * person untouched (which is exactly the requested behaviour: hands in first person only).
 *
 * <p>Steve's own body (when he owns control) is drawn separately by {@link SteveBodyMixin},
 * which runs at the END of {@code LevelRenderer.renderLevel} — while the world projection
 * matrix is still active. Drawing him in the GUI pass would use the GUI's orthographic
 * projection and stamp him at the wrong place/size, which showed up as an invisible avatar.
 */
@Mixin(Gui.class)
public abstract class ClearWorldMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void stellarcraft$clearWorldBeforeHud(
            net.minecraft.client.gui.GuiGraphics guiGraphics, float partialTick, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        // Nothing to clear without a world, and clearing would destroy the menu background.
        if (mc.level == null) return;
        if (!ControlStateMachine.isSteve()) return;

        // Clear the world so only the HUD remains. We clear to fully transparent black;
        // FrameExporter then derives alpha from COLOUR (non-black = HUD/hands -> opaque,
        // black = world -> transparent).
        GlStateManager._clearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, false);

        // Restore the first-person hands that the wipe just erased. Both guards live inside
        // renderItemInHand/renderHandsWithItems, so this is a no-op in second/third person and
        // while the player is sleeping (where vanilla hides the arms too).
        if (mc.gameRenderer != null) {
            Camera camera = mc.gameRenderer.getMainCamera();
            PoseStack poseStack = new PoseStack();
            if (mc.gameRenderer instanceof GameRendererAccessor accessor) {
                accessor.stellarcraft$renderItemInHand(poseStack, camera, partialTick);
            }
        }
    }
}
