package dev.stellarcraft.world.mixin;

import dev.stellarcraft.client.WindowFocusBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Minecraft running while its window is unfocused.
 *
 * <p>This is the exact trick SkyCraft uses, adapted for 1.20.1: when the bridge to
 * Stellar Blade is up, we pretend the MC window is always active so Minecraft does not
 * pause on focus loss ({@code pauseOnLostFocus}). Without this, switching to the Stellar
 * Blade window would freeze the MC simulation, so Steve could never move, open his
 * inventory, or interact with blocks while the player is looking at Stellar Blade.
 *
 * <p>In 1.20.1 the focus check is {@code Minecraft.isWindowActive()} (SkyCraft's newer
 * {@code isFocused()} does not exist in 1.20.1 — that is why targeting {@code isFocused}
 * failed with "could not find any targets"). The takeover flag lives in
 * {@link WindowFocusBridge} (a plain class in {@code dev.stellarcraft.client}, NOT in the
 * mixin package, so it can be referenced freely).
 */
@Mixin(net.minecraft.client.Minecraft.class)
public abstract class WindowMixin {

    @Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
    private void stellarcraft$forceActive(CallbackInfoReturnable<Boolean> cir) {
        // While linked to Stellar Blade, report active so MC keeps ticking.
        if (WindowFocusBridge.isBridgeActive()) {
            cir.setReturnValue(true);
        }
    }
}
