package dev.stellarcraft.world.mixin;

import dev.stellarcraft.world.ActorProxy;
import dev.stellarcraft.world.ExplosionDamageSink;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Captures Minecraft TNT explosions and forwards the damage to Stellar Blade.
 *
 * <p>Vanilla MC already computes, for every explosion: the centre, the blast radius, and
 * which entities are in range and how much damage each takes (with distance falloff).
 * We <em>reuse</em> that computation and forward the per-actor results to SB, rather than
 * re-implementing blast physics.
 *
 * <p>Flow:
 * <ol>
 *   <li>TNT ignites and explodes (vanilla).</li>
 *   <li>This Mixin captures the centre + radius + affected entities.</li>
 *   <li>For each affected {@link ActorProxy} (an invisible stand-in for a real SB enemy),
 *       {@link ExplosionDamageSink#forwardHit} writes an {@code EV_EXPLOSION_HIT} record.</li>
 *   <li>The SB {@code DamageApplier} applies that damage to the real Naytiba.</li>
 * </ol>
 *
 * <p><b>Keep this class minimal.</b> {@code dev.stellarcraft.world.mixin.*} is a mixin
 * package, so nothing in it may be referenced directly from ordinary code - see
 * {@link ExplosionDamageSink} for the crash that causes. Only the {@code @Shadow} fields
 * and the {@code @Inject} handler belong here; all shared state and helpers live in
 * {@link ExplosionDamageSink}.
 */
@Mixin(net.minecraft.world.level.Explosion.class)
public abstract class ExplosionMixin {

    @Shadow private Level level;
    @Shadow private double x;
    @Shadow private double y;
    @Shadow private double z;
    @Shadow private float radius;

    @Inject(method = "explode", at = @At("TAIL"))
    private void stellarcraft$captureExplosion(CallbackInfo ci) {
        // Registered TNT uses NativeEntities.explode only. Hidden/vanilla MC
        // explosions are deliberately not forwarded into the native scene.
    }
}
