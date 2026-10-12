package dev.stellarcraft.world;

import net.minecraft.world.phys.EntityHitResult;

/** Implemented on AbstractArrow by its mixin; never cast an entity to a mixin class. */
public interface NativeArrowAccess {
    void stellarcraft$hitEntity(EntityHitResult hit);
    void stellarcraft$embedded(boolean value);
}
