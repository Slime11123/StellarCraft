package dev.stellarcraft.world.mixin;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin({net.minecraft.world.entity.item.PrimedTnt.class,net.minecraft.world.entity.projectile.ThrowableProjectile.class})
public abstract class NativeProjectileMixin {
    @Inject(method="method_5773",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$nativeProjectile(CallbackInfo ci) {
        if(dev.stellarcraft.world.NativeEntities.tickProjectile((Entity)(Object)this)) ci.cancel();
    }
}
