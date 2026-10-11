package dev.stellarcraft.world.mixin;

import dev.stellarcraft.client.InputAuthority;
import dev.stellarcraft.client.InputBridge;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops Minecraft's own GLFW keyboard handlers while Stellar Blade is forwarding input.
 *
 * <p>Mirrors SekiroCraft's {@code KeyboardMixin}: while the bridge owns input, vanilla
 * {@code keyPress} / {@code charTyped} are cancelled, so the ONLY source of keyboard input is
 * the forwarded ring. Our own replay is let through by {@link InputBridge#replaying}.
 *
 * <p>Official (Mojang) mappings are in use here, so the methods are {@code keyPress} and
 * {@code charTyped} — NOT Yarn's {@code onKey} / {@code onChar}.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardMixin {

    @Inject(method = {"keyPress", "charTyped"}, at = @At("HEAD"), cancellable = true)
    private void stellarcraft$bridgeOnly(CallbackInfo ci) {
        if (InputAuthority.mcOwnsInput() && !InputBridge.replaying) {
            ci.cancel();
        }
    }
}
