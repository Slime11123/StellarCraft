package dev.stellarcraft.world.mixin;

import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractArrow.class)
public abstract class NativeArrowMixin implements dev.stellarcraft.world.NativeArrowAccess {
    @Accessor(value="field_7588",remap=false)
    public abstract void stellarcraft$embedded(boolean value);
    @Invoker(value="method_7454",remap=false)
    public abstract void stellarcraft$hitEntity(EntityHitResult hit);

    @Inject(method="method_5773",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$nativeArrow(CallbackInfo ci) {
        if(dev.stellarcraft.world.NativeEntities.tickArrow((AbstractArrow)(Object)this)) ci.cancel();
    }
}
