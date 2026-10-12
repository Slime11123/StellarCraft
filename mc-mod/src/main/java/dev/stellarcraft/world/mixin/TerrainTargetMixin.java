package dev.stellarcraft.world.mixin;

import dev.stellarcraft.client.ControlStateMachine;
import dev.stellarcraft.world.VirtualTerrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Supplies the missing "ground" to Minecraft's pick raycast while Steve is standing in
 * Stellar Blade's world.
 *
 * <p>Minecraft's {@code GameRenderer.pick} casts the view ray against MC's own blocks. In
 * Stellar Blade there are none, so the result is always a MISS and right-click never places
 * a block. After vanilla {@code pick} has run we, only when it missed, cast the same ray
 * against {@link VirtualTerrain} (the SB-side sampled surface). A hit there is written into
 * {@code Minecraft.hitResult}, after which the normal {@code useItemOn} path places the
 * block itself — no reimplementation of placement, inventory consumption or reach.
 *
 * <p>We keep vanilla block/entity reach and ordering: if vanilla already found something
 * closer, its result is left untouched (we only fill in a MISS).
 */
@Mixin(GameRenderer.class)
public abstract class TerrainTargetMixin {

    @Inject(method = "pick", at = @At("RETURN"))
    private void stellarcraft$fillGroundPick(float partialTick, CallbackInfo ci) {
        if (!ControlStateMachine.isSteve()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Entity camera = mc.getCameraEntity();
        if (camera == null) camera = mc.player;

        Vec3 start = camera.getEyePosition(partialTick);
        Vec3 look = camera.getViewVector(partialTick);
        // Vanilla block reach: the interaction manager knows the effective value (creative
        // reaches further). Fall back to the survival default if it is unavailable.
        double reach = 4.5;
        if (mc.gameMode != null) {
            double r = mc.gameMode.getPickRange();
            if (r > 0) reach = r;
        }
        Vec3 end = start.add(look.scale(reach));
        double entityReach=mc.gameMode!=null&&mc.gameMode.hasFarPickRange()?6.0:3.0;
        Vec3 entityEnd=start.add(look.scale(entityReach));

        BlockHitResult hit = VirtualTerrain.raycast(start, end);
        // Invisible MC terrain must not intercept the native placement ray. Search only
        // blocks actually mirrored into SB, plus native actors and sampled native ground.
        double nearest = hit == null ? reach * reach : start.distanceToSqr(hit.getLocation());
        for (long packed : dev.stellarcraft.world.PlacedBlocks.positions()) {
            var pos = net.minecraft.core.BlockPos.of(packed);
            if (pos.getCenter().distanceToSqr(start) > (reach + 2) * (reach + 2)) continue;
            var state = dev.stellarcraft.world.PlacedBlocks.state(packed);
            if (state == null) continue;
            var shape = state.getShape(mc.level, pos);
            double offset=dev.stellarcraft.world.PlacedBlocks.offset(packed);
            BlockHitResult block = shape.clip(start.add(0,-offset,0), end.add(0,-offset,0), pos);
            if(block!=null) block=new BlockHitResult(block.getLocation().add(0,offset,0),block.getDirection(),pos,block.isInside());
            if (block != null && start.distanceToSqr(block.getLocation()) < nearest) {
                nearest = start.distanceToSqr(block.getLocation());
                hit = block;
            }
        }
        HitResult current = mc.hitResult;
        if (current instanceof net.minecraft.world.phys.EntityHitResult entity
            && (entity.getEntity() instanceof dev.stellarcraft.world.ActorProxy || dev.stellarcraft.world.NativeEntities.owns(entity.getEntity()))
            && start.distanceToSqr(current.getLocation())<=entityReach*entityReach
            && start.distanceToSqr(current.getLocation()) < nearest) return;
        for(var proxy:mc.level.getEntitiesOfClass(dev.stellarcraft.world.ActorProxy.class,camera.getBoundingBox().inflate(reach+32))) {
            if(!proxy.isNativeLive()) continue;
            var intersection=proxy.getBoundingBox().inflate(.1).clip(start,entityEnd);
            if(intersection.isPresent()&&start.distanceToSqr(intersection.get())<nearest) {
                nearest=start.distanceToSqr(intersection.get());current=new net.minecraft.world.phys.EntityHitResult(proxy,intersection.get());
            }
        }
        // Re-pick native entities explicitly: hidden MC blocks may have shortened vanilla's ray.
        for(var nativeEntity:dev.stellarcraft.world.NativeEntities.snapshots()) {
            Entity entity=mc.level.getEntity(nativeEntity.id());if(entity==null) continue;
            var intersection=entity.getBoundingBox().inflate(.1).clip(start,entityEnd);
            if(intersection.isPresent()&&start.distanceToSqr(intersection.get())<nearest) {
                nearest=start.distanceToSqr(intersection.get());
                current=new net.minecraft.world.phys.EntityHitResult(entity,intersection.get());
            }
        }
        if(current instanceof net.minecraft.world.phys.EntityHitResult entity
            &&(entity.getEntity() instanceof dev.stellarcraft.world.ActorProxy||dev.stellarcraft.world.NativeEntities.owns(entity.getEntity()))
            &&start.distanceToSqr(current.getLocation())<=entityReach*entityReach&&start.distanceToSqr(current.getLocation())<=nearest) {
            mc.hitResult=current;mc.crosshairPickEntity=entity.getEntity();return;
        }
        mc.hitResult = hit != null ? hit : BlockHitResult.miss(end,
            net.minecraft.core.Direction.getNearest(look.x, look.y, look.z),
            net.minecraft.core.BlockPos.containing(end));
        mc.crosshairPickEntity = null;
    }
}
