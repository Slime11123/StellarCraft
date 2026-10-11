package dev.stellarcraft.world.mixin;

import dev.stellarcraft.world.NativeEntities;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An attached native rocket retains vanilla fuel/boost; hidden blocks cannot detonate it. */
@Mixin(FireworkRocketEntity.class)
public abstract class NativeFireworkMixin {
    @Inject(method="method_7454",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$ignoreHiddenEntity(net.minecraft.world.phys.EntityHitResult hit,CallbackInfo ci) {
        if(NativeEntities.owns((FireworkRocketEntity)(Object)this)) ci.cancel();
    }
    @Inject(method="method_24920",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$ignoreHiddenBlock(net.minecraft.world.phys.BlockHitResult hit,CallbackInfo ci) {
        if(NativeEntities.owns((FireworkRocketEntity)(Object)this)) ci.cancel();
    }
}
