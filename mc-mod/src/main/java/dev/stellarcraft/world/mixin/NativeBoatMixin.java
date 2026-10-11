package dev.stellarcraft.world.mixin;
import net.minecraft.world.entity.vehicle.Boat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Boat.class)
public abstract class NativeBoatMixin {
    @Inject(method="method_7552",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$nativeSurface(CallbackInfoReturnable<Boat.Status> cir) {
        if(dev.stellarcraft.world.NativeEntities.owns((Boat)(Object)this)) cir.setReturnValue(Boat.Status.ON_LAND);
    }
    @Inject(method="method_7548",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$nativeFriction(CallbackInfoReturnable<Float> cir) {
        if(dev.stellarcraft.world.NativeEntities.owns((Boat)(Object)this)) cir.setReturnValue(.6f);
    }
}
