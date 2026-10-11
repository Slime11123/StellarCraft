package dev.stellarcraft.world.mixin;

import dev.stellarcraft.world.BlockBridge;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class NativeBlockUseMixin {
    @Inject(method="method_2896",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$useNativeGround(LocalPlayer player,InteractionHand hand,BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result=BlockBridge.useNativeBlock(player,hand,hit);
        if(result==null) result=dev.stellarcraft.world.NativeEntities.useOn(player,hand,hit);
        if(result!=null) cir.setReturnValue(result);
    }

    @Inject(method="method_2919",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$nativeItem(net.minecraft.world.entity.player.Player player,InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result=dev.stellarcraft.world.NativeEntities.use(player,hand);
        if(result!=null) cir.setReturnValue(result);
    }
}
