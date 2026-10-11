package dev.stellarcraft.world.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve coverage alpha in the native HUD instead of discarding black pixels. */
@Mixin(RenderSystem.class)
public abstract class GuiAlphaMixin {
    @Inject(method="defaultBlendFunc",remap=false,at=@At("HEAD"),cancellable=true)
    private static void stellarcraft$coverageAlpha(CallbackInfo ci) {
        if(!dev.stellarcraft.client.ControlStateMachine.isSteve()) return;
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        ci.cancel();
    }
}
