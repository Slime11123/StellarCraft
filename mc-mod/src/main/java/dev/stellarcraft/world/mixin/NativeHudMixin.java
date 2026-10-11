package dev.stellarcraft.world.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** SB owns scene shading; vanilla vignette also overwrites the entire HUD's alpha. */
@Mixin(Gui.class)
public abstract class NativeHudMixin {
    @Inject(method="method_1735",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$nativeSceneVignette(GuiGraphics graphics,Entity camera,CallbackInfo ci) {
        if(dev.stellarcraft.client.ControlStateMachine.isSteve()) ci.cancel();
    }
}
