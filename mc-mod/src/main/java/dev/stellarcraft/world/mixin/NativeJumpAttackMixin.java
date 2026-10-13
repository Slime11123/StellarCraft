package dev.stellarcraft.world.mixin;

import dev.stellarcraft.world.NativeJumpAttack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class NativeJumpAttackMixin {
    @Unique private NativeJumpAttack.Attack stellarcraft$jumpAttack;
    @Inject(method="method_7324",remap=false,at=@At("HEAD"))
    private void stellarcraft$captureJumpAttack(Entity target,CallbackInfo ci) {
        stellarcraft$jumpAttack=NativeJumpAttack.capture((Player)(Object)this,target);
    }
    @Redirect(method="method_7324",remap=false,at=@At(value="INVOKE",remap=false,
        target="Lnet/minecraft/class_1297;method_5643(Lnet/minecraft/class_1282;F)Z"),require=1)
    private boolean stellarcraft$primaryHit(Entity target,DamageSource source,float amount) {
        var attack=stellarcraft$jumpAttack;stellarcraft$jumpAttack=null;
        return NativeJumpAttack.hurt(attack,target,source,amount);
    }
    @Inject(method="method_7324",remap=false,at=@At("RETURN"))
    private void stellarcraft$clearJumpAttack(Entity target,CallbackInfo ci) {stellarcraft$jumpAttack=null;}
}
