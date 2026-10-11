package dev.stellarcraft.world.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class NativeBlockItemMixin {
    @Inject(method="method_7709",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$hostPlacementCollision(BlockPlaceContext context,BlockState state,
            CallbackInfoReturnable<Boolean> cir) {
        if(dev.stellarcraft.world.BlockBridge.isNativeUse()) {
            // Ignore hidden mobs/terrain, but do not build inside the controlled player.
            var player=context.getPlayer();var pos=context.getClickedPos();
            var shape=state.getCollisionShape(context.getLevel(),pos).move(pos.getX(),pos.getY(),pos.getZ());
            cir.setReturnValue(player==null || !net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(shape,
                net.minecraft.world.phys.shapes.Shapes.create(player.getBoundingBox()),
                net.minecraft.world.phys.shapes.BooleanOp.AND));
        }
    }
}
