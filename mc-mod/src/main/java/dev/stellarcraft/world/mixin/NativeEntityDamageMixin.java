package dev.stellarcraft.world.mixin;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(LivingEntity.class)
public abstract class NativeEntityDamageMixin {
    @Inject(method="method_5643",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$nativeDamage(DamageSource source,float damage,CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self=(LivingEntity)(Object)this;
        if(source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.FireworkRocketEntity rocket
            &&dev.stellarcraft.world.NativeEntities.owns(rocket)
            &&(!dev.stellarcraft.world.NativeEntities.simulates(rocket)
                ||!dev.stellarcraft.world.NativeEntities.owns(self)&&!dev.stellarcraft.client.NativeMovement.owns(self))) {
            cir.setReturnValue(false);return;
        }
        if(!dev.stellarcraft.world.NativeEntities.owns(self)) return;
        var cause=source.getDirectEntity();var attacker=source.getEntity();
        if(!dev.stellarcraft.world.NativeEntities.owns(cause)&&!dev.stellarcraft.world.NativeEntities.owns(attacker)
            &&!dev.stellarcraft.client.NativeMovement.owns(attacker)) cir.setReturnValue(false);
    }
}
