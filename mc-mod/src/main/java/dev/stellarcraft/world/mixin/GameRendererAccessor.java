package dev.stellarcraft.world.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link GameRenderer}'s private FOV computation, which the frame export needs in order
 * to record the pose the frame was rendered with.
 *
 * <p>{@code getFov} is private and takes {@code (Camera, partialTick, useFovSetting)}.
 * {@code GameRenderer.render} calls it with {@code useFovSetting = true} (verified by
 * disassembling 1.20.1), so that is the value the rendered frame actually used — the options
 * slider alone would miss the sprint / bow-zoom / underwater modifiers.
 *
 * <p><b>There is deliberately no {@code renderItemInHand} invoker here any more.</b> One used
 * to exist so the world-wipe could re-draw the first-person arms after erasing them. That is
 * NOT safe and it silently destroyed the whole frame:
 * <ul>
 *   <li>{@code renderItemInHand} calls
 *       {@code resetProjectionMatrix(getProjectionMatrix(getFov(camera, f, false)))} and never
 *       restores it, so the GUI's orthographic projection installed earlier by
 *       {@code GameRenderer.render} was replaced with a 3D perspective one and every HUD draw
 *       afterwards landed nowhere;</li>
 *   <li>it depends on {@code RenderSystem.getModelViewMatrix()} still holding the camera
 *       transform, which is not true inside the GUI pass.</li>
 * </ul>
 * The symptoms were an exported frame of <b>exactly</b> zero — no arms, no HUD, no world — and
 * a black Minecraft window. The wipe now runs before vanilla draws the arms, so nothing needs
 * re-drawing; see {@code WorldLayerMixin} for the full write-up.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

    @Invoker("getFov")
    double stellarcraft$getFov(Camera camera, float partialTick, boolean useFovSetting);
}
