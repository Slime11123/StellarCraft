package dev.stellarcraft.world.mixin;

import dev.stellarcraft.client.NativeMovement;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep vanilla input/animation, but use dry MC walking independently of the hidden world. */
@Mixin(Player.class)
public abstract class NativePlayerMixin {
    @Inject(method="method_5773",remap=false,at=@At(value="FIELD",
        target="Lnet/minecraft/class_1657;field_5960:Z",remap=false,opcode=181,shift=At.Shift.AFTER))
    private void stellarcraft$restoreHostPhysics(CallbackInfo ci) {
        NativeMovement.apply((Player)(Object)this);
    }

    @Inject(method="method_6091",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$dryTravel(net.minecraft.world.phys.Vec3 input,CallbackInfo ci) {
        Player player=(Player)(Object)this;
        if(!NativeMovement.owns(player)) return;
        NativeMovement.apply(player);
        if(!dev.stellarcraft.client.ControlStateMachine.isSteve()) {
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);ci.cancel();return;
        }
        // Reuse LivingEntity's exact 1.20.1 glide branch, including lift and drag.
        // Entity.move below records its 3D intent without consulting hidden terrain.
        if(player.level().isClientSide&&dev.stellarcraft.client.ElytraBridge.gliding(player)) return;
        if(player.level().isClientSide) {
            // Vanilla dry ground: input acceleration from the movement attribute,
            // followed by 0.6 ground friction * 0.91 drag. Sprint/sneak still use MC input.
            boolean flying=player.getAbilities().flying;
            float acceleration=flying ? player.getAbilities().getFlyingSpeed()*(player.isSprinting()?2:1)
                : player.getSpeed();
            double drag=flying ? .91 : .546;
            player.moveRelative(acceleration,new net.minecraft.world.phys.Vec3(input.x,0,input.z));
            var velocity=player.getDeltaMovement();
            NativeMovement.recordIntent(new net.minecraft.world.phys.Vec3(velocity.x,0,velocity.z));
            player.move(net.minecraft.world.entity.MoverType.SELF,
                new net.minecraft.world.phys.Vec3(velocity.x,0,velocity.z));
            player.setDeltaMovement(velocity.x*drag,0,velocity.z*drag);
            player.calculateEntityAnimation(false);
        } else player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        ci.cancel();
    }

    @Inject(method="method_5643",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$hostDamageOnly(net.minecraft.world.damagesource.DamageSource source,float amount,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if(!NativeMovement.acceptsDamage((Player)(Object)this,source)) cir.setReturnValue(false);
    }

    @Inject(method="method_7318",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$nativePose(CallbackInfo ci) {
        Player player=(Player)(Object)this;
        if(!NativeMovement.owns(player)) return;
        player.setPose(dev.stellarcraft.client.ElytraBridge.gliding(player)
            ? net.minecraft.world.entity.Pose.FALL_FLYING : player.isShiftKeyDown() && !player.getAbilities().flying
            ? net.minecraft.world.entity.Pose.CROUCHING : net.minecraft.world.entity.Pose.STANDING);
        ci.cancel();
    }
}
