package dev.stellarcraft.world.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.stellarcraft.client.InputBridge;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla Screen modifier queries normally poll the unfocused GLFW window. */
@Mixin(InputConstants.class)
public abstract class NativeGuiKeysMixin {
    @Inject(method="method_15987",remap=false,at=@At("HEAD"),cancellable=true)
    private static void stellarcraft$forwardedModifier(long window,int key,CallbackInfoReturnable<Boolean> cir) {
        if(key>=340&&key<=346&&key!=343&&InputBridge.forwardsModifiers()
                &&window==Minecraft.getInstance().getWindow().getWindow())
            cir.setReturnValue(InputBridge.isHeld(key));
    }
}
