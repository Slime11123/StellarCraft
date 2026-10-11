package dev.stellarcraft.world;

import dev.stellarcraft.link.SharedMemory;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import org.spongepowered.asm.mixin.Mixin;
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
        Level level = self.level();
        if (level == null) return;

        // Build the swept AABB along the movement path (inflated slightly to catch edges).
        AABB start = self.getBoundingBox();
        AABB swept = start.minmax(start.move(movement)).inflate(0.001);

        List<AABB> foreign = new ArrayList<>();
        field.forEachOverlapping(swept, foreign);
        if (foreign.isEmpty()) return;

        // Convert each foreign box to a VoxelShape and union them.
        VoxelShape merged = Shapes.empty();
        for (AABB box : foreign) {
            merged = Shapes.or(merged, Shapes.create(box));
        }

        // Let Minecraft's own collide logic resolve against the merged shape.
        // 1.20.1: collision is the static Shapes.collide, not an instance method.
        VoxelShape selfShape = Shapes.create(start);
        net.minecraft.world.phys.Vec3 adjusted = Shapes.collide(selfShape, merged, movement);
        cir.setReturnValue(adjusted);
    }

    /** Static holder so the Mixin doesn't hard-depend on init order. */
    public static final class CollisionFieldHolder {
        public static CollisionField FIELD = null;
    }
}
