package dev.stellarcraft.world.mixin;

import dev.stellarcraft.client.InputAuthority;
import dev.stellarcraft.client.InputBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops Minecraft from capturing the real cursor / double-applying mouse events while Stellar
 * Blade owns the foreground. Mirrors SekiroCraft's {@code MouseMixin}.
 *
 * <p>Official (Mojang) mappings are in use here, so:
 * <ul>
 *   <li>{@code grabMouse} is Yarn's {@code lockCursor} — cancelled so MC never clips the OS
 *       cursor to its own background window. The player needs the real cursor for Stellar
 *       Blade's menus, and the overlay paints its own cursor over MC's GUIs.</li>
 *   <li>{@code releaseMouse} is Yarn's {@code unlockCursor}.</li>
 *   <li>{@code isMouseGrabbed} is Yarn's {@code isCursorLocked} — forced to
 *       "grabbed iff no GUI is open", which is what makes first-person mouse look work while
 *       a screen correctly frees the cursor. MC's own {@code grabMouse} would return early
 *       anyway because the window is unfocused, so we answer for it instead.</li>
 *   <li>The event handlers are cancelled so the forwarded ring is the only input source.</li>
 * </ul>
 */
@Mixin(MouseHandler.class)
public abstract class MouseMixin {

    // Raw deltas are already applied by InputBridge. Never consume GLFW cursor warps again.
    @Inject(method = "method_1606", remap = false, at = @At("HEAD"), cancellable = true)
    private void stellarcraft$singleLookSource(CallbackInfo ci) {
        // Focus is permission to replay, not permission for GLFW to rotate the host view.
        // Cursor recentering accumulated while EDIT was briefly false used to snap look.
        if (dev.stellarcraft.client.ControlStateMachine.isSteve()) ci.cancel();
    }

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void stellarcraft$noCapture(CallbackInfo ci) {
        if (dev.stellarcraft.client.ControlStateMachine.isSteve()) {
            ci.cancel();
        }
    }

    @Inject(method = "isMouseGrabbed", at = @At("HEAD"), cancellable = true)
    private void stellarcraft$logicalCapture(
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (dev.stellarcraft.client.ControlStateMachine.isSteve()) {
            cir.setReturnValue(Minecraft.getInstance().screen == null);
        }
    }

    @Inject(method = {"onPress", "onMove", "onScroll"}, at = @At("HEAD"), cancellable = true)
    private void stellarcraft$bridgeOnly(CallbackInfo ci) {
        if (dev.stellarcraft.client.ControlStateMachine.isSteve() && !InputBridge.replaying) {
            ci.cancel();
        }
    }
}
