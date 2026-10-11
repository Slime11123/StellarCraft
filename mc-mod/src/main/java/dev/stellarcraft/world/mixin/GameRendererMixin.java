package dev.stellarcraft.world.mixin;

import dev.stellarcraft.render.FrameExporter;
import dev.stellarcraft.render.HandMeshBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads back the main render target at the very end of {@code GameRenderer.render}, i.e.
 * AFTER the world AND the HUD have been drawn into it.
 *
 * <p>Guarded on {@code renderLevel}: when MC draws only the GUI (e.g. a screen without the
 * world) we still want the HUD, but when the world itself was skipped the main target holds
 * a cleared frame — exporting that would publish a blank overlay. We export regardless but
 * FrameExporter itself decides whether the frame is usable (see its diagnostics).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method="render",at=@At("HEAD"))
    private void stellarcraft$publishPose(float partialTicks,long nanoTime,boolean renderLevel,CallbackInfo ci) {
        var bridge=dev.stellarcraft.client.ClientBridge.get();
        if (bridge!=null) {
            dev.stellarcraft.client.InputBridge.tick(Minecraft.getInstance());
            bridge.publishMcState(partialTicks);
        }
    }

    // Intermediary selectors also work in the standalone remapped patch build.
    @Redirect(method = "method_3172", remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Lnet/minecraft/class_759;method_22976(FLnet/minecraft/class_4587;Lnet/minecraft/class_4597$class_4598;Lnet/minecraft/class_746;I)V"))
    private void stellarcraft$nativeHands(ItemInHandRenderer renderer, float partialTick, PoseStack pose,
                                         MultiBufferSource.BufferSource buffers, LocalPlayer player, int light) {
        HandMeshBridge.capture(renderer, partialTick, pose, buffers, player, light);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void stellarcraft$exportAfterRender(float partialTicks, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        HandMeshBridge.finishFrame();
        dev.stellarcraft.render.BodyMeshBridge.finishFrame();
        // partialTicks is passed through so FrameExporter can ask for the same effective FOV
        // this frame was rendered with (GameRenderer.getFov takes the partial tick).
        FrameExporter.export(Minecraft.getInstance(), partialTicks);
    }
}
