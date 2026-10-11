package dev.stellarcraft.world.mixin;

import dev.stellarcraft.client.WindowFocusBridge;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses a screen's full-window dim background while the StellarCraft bridge is active.
 *
 * <p>Vanilla {@code Screen.renderBackground} fills the ENTIRE window with a dark gradient
 * (or the dirt texture on the title screen) before the panel itself is drawn. That is what
 * turned the area around Minecraft's inventory into a black box in Stellar Blade: the
 * gradient is opaque, so {@code FrameExporter} exported it as UI and the overlay drew it over
 * the game.
 *
 * <p>By cancelling this call we let the surrounding area stay transparent (the world was
 * already cleared by {@link WorldLayerMixin}), so only the inventory panel itself is
 * exported and Stellar Blade stays visible around it.
 */
@Mixin(Screen.class)
public abstract class ScreenBackgroundMixin {

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void stellarcraft$skipBackgroundWhenBridged(GuiGraphics guiGraphics, CallbackInfo ci) {
        if (WindowFocusBridge.isBridgeActive()) {
            ci.cancel();
        }
    }
}
