package dev.stellarcraft.world.mixin;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.LevelReader;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class NativeLightSupportMixin {
    @Inject(method="method_26184",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$nativeTorchSupport(LevelReader level,BlockPos pos,CallbackInfoReturnable<Boolean> cir) {
        BlockState state=(BlockState)(Object)this;
        if(state.getBlock() instanceof net.minecraft.world.level.block.TorchBlock
            &&(dev.stellarcraft.world.BlockBridge.isNativeUse()||dev.stellarcraft.world.PlacedBlocks.contains(pos.asLong())))
            cir.setReturnValue(true);
    }
}
