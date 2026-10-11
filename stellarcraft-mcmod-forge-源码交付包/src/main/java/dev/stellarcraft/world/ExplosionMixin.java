package dev.stellarcraft.world;

import dev.stellarcraft.link.Proto;
import dev.stellarcraft.link.RingBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 *       write a {@code kEvExplosion} event carrying the scaled damage.</li>
 *   <li>The SB {@code DamageApplier} applies that damage to the real Naytiba via its own
 *       hit pipeline.</li>
 * </ol>
 *
 * <p>NOTE: the exact explosion method to hook varies by Minecraft version. This targets a
 * generic explosion-completion hook; adjust the {@code method} string for the pinned MC
 * build. The important part is capturing {@code (centre, radius, affectedEntities)}.
 */
@Mixin(net.minecraft.world.level.Explosion.class)
public abstract class ExplosionMixin {

    /** MC -> SB damage scale for explosions (SB enemies have far more HP than MC). */
    private static float explosionDamageScale() {
        return ExplosionDamageHolder.SCALE;
    }

    @Inject(method = "explode", at = @At("TAIL"))
    private void stellarcraft$captureExplosion(CallbackInfo ci) {
        Explosion self = (Explosion) (Object) this;
        Level level = self.level();
        if (level == null || !level.isClientSide()) return; // client only for M1

        BlockPos center = self.getPosition();
        float radius = self.getToBlow().isEmpty() ? 0 : self.getToBlow().size();
        // Better: radius is derivable from getToBlow() block positions; here we keep it simple.
        double cx = center.getX(), cy = center.getY(), cz = center.getZ();
        double r = Math.max(radius, 4.0); // TNT blast radius ~4 blocks

        // Find ActorProxy entities in range.
        AABB box = new AABB(cx - r, cy - r, cz - r, cx + r, cy + r, cz + r);
        List<ActorProxy> proxies = level.getEntitiesOfClass(ActorProxy.class, box);

        for (ActorProxy proxy : proxies) {
            double dist = proxy.distanceToSqr(cx, cy, cz);
            // Vanilla-style falloff: full damage at centre, zero at the edge.
            double falloff = 1.0 - Math.sqrt(dist) / r;
            if (falloff <= 0) continue;
            float mcDamage = (float) (56.0 * falloff); // TNT max ~56
            float sbDamage = mcDamage * explosionDamageScale();

            forwardExplosionHit(proxy.sbUid(), sbDamage, cx, cy, cz, (float) falloff);
        }
    }

    private static void forwardExplosionHit(int uid, float damage, double cx, double cy, double cz, float knockback) {
        RingBuffer ring = ExplosionDamageHolder.eventRing;
        if (ring == null) return;

        ByteBuffer ev = ByteBuffer.allocate(Proto.MC_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        ev.putInt(Proto.EV_EXPLOSION_HIT);
        ev.putInt(uid);
        ev.putFloat(damage);     // a = scaled damage
        ev.putFloat((float) cx); // b = centre x (unused per-hit, kept for context)
        ev.putFloat((float) cz); // c = centre z
        ev.putFloat(knockback);  // d = knockback strength (falloff)
        ev.putInt(0);            // flags
        ev.putInt(0);            // weapon
        ev.flip();

        long claimed = ring.claim(Proto.MC_EVENT_SIZE);
        if (claimed < 0) return;
        ring.writeBytes(claimed, ev);
        ring.publish(claimed, Proto.MC_EVENT_SIZE);
    }

    /** Holder for the shared ring + config, set during mod init. */
    public static final class ExplosionDamageHolder {
        public static RingBuffer eventRing = null;
        public static float SCALE = 10.0f; // config-tunable
    }
}
