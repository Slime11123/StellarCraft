package dev.stellarcraft.world.mixin;

import dev.stellarcraft.world.CollisionField;
import dev.stellarcraft.world.CollisionFieldHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Appends Stellar Blade's collision shapes to Minecraft's movement collision query.
 *
 * <p>This is the exact trick SkyCraft uses: instead of re-implementing Minecraft physics,
 * we feed foreign geometry into the same {@code collide} path MC already uses. Gravity,
 * step-up, sprint-jumping, sneaking-at-edges all keep working — they just see extra
 * solid boxes.
 *
 * <p>Target method is {@code Entity.collide(Vec3)}, which is the single funnel all
 * client movement goes through. The merged shape replaces the return value.
 *
 * <p>NOTE: Mixin target names must match the Minecraft version you build against.
 * This file targets the Mojang-mapped method name (unobfuscated 26.x). Adjust the
 * {@code @Mixin} / {@code @Inject(method=...)} string if you pin a different version.
 */
@Mixin(Entity.class)
public abstract class EntityCollisionMixin {
    @Inject(method="method_5697",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$ignoreHiddenEntityPush(Entity other,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if(dev.stellarcraft.client.NativeMovement.owns((Entity)(Object)this)
            || dev.stellarcraft.client.NativeMovement.owns(other)
            || dev.stellarcraft.world.NativeEntities.owns((Entity)(Object)this)
            || dev.stellarcraft.world.NativeEntities.owns(other)) ci.cancel();
    }

    @Inject(method={"method_5799","method_5869","method_5771","method_5757","method_5675"},
        remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$ignoreHiddenEnvironment(CallbackInfoReturnable<Boolean> cir) {
        if(dev.stellarcraft.client.NativeMovement.owns((Entity)(Object)this) || dev.stellarcraft.world.NativeEntities.owns((Entity)(Object)this)) cir.setReturnValue(false);
    }

    @Inject(method="method_20233",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$hostPose(net.minecraft.world.entity.Pose pose,CallbackInfoReturnable<Boolean> cir) {
        if(dev.stellarcraft.client.NativeMovement.owns((Entity)(Object)this) || dev.stellarcraft.world.NativeEntities.owns((Entity)(Object)this)) cir.setReturnValue(true);
    }

    @Inject(method="method_5777",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$hostEye(net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> fluid,
        CallbackInfoReturnable<Boolean> cir) {
        if(dev.stellarcraft.client.NativeMovement.owns((Entity)(Object)this) || dev.stellarcraft.world.NativeEntities.owns((Entity)(Object)this)) cir.setReturnValue(false);
    }

    @Inject(method={"method_5852","method_5630"},remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$ignoreHiddenBlocks( org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if(dev.stellarcraft.client.NativeMovement.owns((Entity)(Object)this) || dev.stellarcraft.world.NativeEntities.owns((Entity)(Object)this)) ci.cancel();
    }

    @Inject(method="method_5876",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$ignoreHiddenFlow(CallbackInfoReturnable<Boolean> cir) {
        if(dev.stellarcraft.client.NativeMovement.owns((Entity)(Object)this) || dev.stellarcraft.world.NativeEntities.owns((Entity)(Object)this)) cir.setReturnValue(false);
    }

    @Inject(method = "method_5784", remap = false, at = @At("HEAD"), cancellable = true)
    private void stellarcraft$hostPhysics(net.minecraft.world.entity.MoverType type,
            net.minecraft.world.phys.Vec3 movement, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        if(dev.stellarcraft.world.NativeEntities.owns(self)) {
            self.noPhysics=true;self.fallDistance=0;
            if(!dev.stellarcraft.world.NativeEntities.simulates(self)) {self.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);ci.cancel();}
            return;
        }
        dev.stellarcraft.client.NativeMovement.apply(self);
        if (dev.stellarcraft.client.NativeMovement.owns(self)) {
            // The integrated server must accept the native Y carried by movement
            // packets. Dropping server-side Y made packet validation see a mismatch
            // and send the player back. noPhysics already skips MC server collision.
            if(!self.level().isClientSide) return;
            boolean gliding=dev.stellarcraft.client.ElytraBridge.gliding((net.minecraft.world.entity.player.Player)self);
            if(gliding) dev.stellarcraft.client.NativeMovement.recordIntent(movement);
            self.setPos(self.getX() + movement.x, self.getY(), self.getZ() + movement.z);
            // The cancelled vanilla move also advances the walking phase consumed by
            // GameRenderer.bobView. Keep that animation without querying hidden blocks.
            self.walkDist += (float)movement.horizontalDistance() * 0.6f;
            if(!gliding) self.setDeltaMovement(self.getDeltaMovement().multiply(1, 0, 1));
            ci.cancel();
        }
    }

    /** Invoker for the private {@code Entity.collideWithShapes(Vec3, AABB, List)}. */
    @Invoker("collideWithShapes")
    private static net.minecraft.world.phys.Vec3 invokeCollideWithShapes(
            net.minecraft.world.phys.Vec3 movement, AABB aabb, List<VoxelShape> shapes) {
        throw new AssertionError("Mixin invoker not transformed");
    }

    /**
     * Returns the shared CollisionField. The real mod injects a singleton via a static
     * holder set during client init; here we lazily resolve through a holder to keep the
     * Mixin decoupled from the lifecycle.
     */
    private static CollisionField collisionField() {
        return CollisionFieldHolder.FIELD;
    }

    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void stellarcraft$injectCollision(net.minecraft.world.phys.Vec3 movement,
                                              CallbackInfoReturnable<net.minecraft.world.phys.Vec3> cir) {
        CollisionField field = collisionField();
        if (field == null || field.isEmpty()) return;

        Entity self = (Entity) (Object) this;
        if (dev.stellarcraft.client.NativeMovement.owns(self)||dev.stellarcraft.world.NativeEntities.owns(self)) {cir.setReturnValue(movement);return;}
        Level level = self.level();
        if (level == null) return;

        // Build the swept AABB along the movement path (inflated slightly to catch edges).
        AABB start = self.getBoundingBox();
        AABB swept = start.minmax(start.move(movement)).inflate(0.001);

        List<AABB> foreign = new ArrayList<>();
        field.forEachOverlapping(swept, foreign);
        if (foreign.isEmpty()) return;

        // Convert each foreign box to a VoxelShape.
        List<VoxelShape> shapes = new ArrayList<>(foreign.size());
        for (AABB box : foreign) {
            shapes.add(Shapes.create(box));
        }

        // Resolve the movement against the foreign geometry via the private vanilla helper.
        net.minecraft.world.phys.Vec3 adjusted = invokeCollideWithShapes(movement, start, shapes);
        cir.setReturnValue(adjusted);
    }
}
