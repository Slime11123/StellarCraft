package dev.stellarcraft.world.mixin;

import dev.stellarcraft.client.NativeMovement;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class NativeLocalPlayerMixin {
    @org.spongepowered.asm.mixin.Shadow(remap=false) private boolean field_23093;
    @Inject(method="method_6007",remap=false,at=@At("TAIL"))
    private void stellarcraft$nativeCrouch(CallbackInfo ci) {
        LocalPlayer player=(LocalPlayer)(Object)this;
        if(!NativeMovement.owns(player)) return;
        boolean shift=player.input.shiftKeyDown;
        boolean gliding=dev.stellarcraft.client.ElytraBridge.gliding(player);
        field_23093=shift && !player.getAbilities().flying && !gliding;
        player.setShiftKeyDown(shift);
        player.setPose(gliding ? net.minecraft.world.entity.Pose.FALL_FLYING
            : field_23093 ? net.minecraft.world.entity.Pose.CROUCHING : net.minecraft.world.entity.Pose.STANDING);
    }
    @Inject(method="method_30673",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$noHiddenBlockPush(double x,double z,CallbackInfo ci) {
        if(NativeMovement.owns((LocalPlayer)(Object)this)) ci.cancel();
    }
    @Inject(method="method_3148",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$noHiddenAutoJump(float x,float z,CallbackInfo ci) {
        if(NativeMovement.owns((LocalPlayer)(Object)this)) ci.cancel();
    }
}
