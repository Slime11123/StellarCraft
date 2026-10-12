package dev.stellarcraft.world;

import dev.stellarcraft.link.Proto;
import dev.stellarcraft.link.RingBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Shared state + helpers for forwarding Minecraft explosions to Stellar Blade.
 *
 * <p><b>Why this class lives here and NOT inside {@code ExplosionMixin}.</b>
 * {@code stellarcraft.mixins.json} declares {@code dev.stellarcraft.world.mixin.*} as a
 * mixin package, which means every class in it may only be loaded <em>through</em> the
 * Mixin transformer. Ordinary code that references such a class directly (here: the mod
 * entrypoint assigning the ring during init) fails at class-load time with:
 *
 * <pre>
 *   IllegalClassLoadError: ...ExplosionDamageHolder is in a defined mixin package
 *   dev.stellarcraft.world.mixin.* owned by stellarcraft.mixins.json
 *   and cannot be referenced directly
 * </pre>
 *
 * That is a hard crash on startup, so the rule is: <b>a mixin class may contain nothing
 * but the {@code @Mixin} class itself</b> - no nested holders, no shared statics that
 * outside code needs to touch. Everything shared goes in a plain class like this one.
 *
 * <p>Minimising static members in the mixin has a second benefit: private static methods
 * in a mixin get merged into the target class ({@code net.minecraft.world.level.Explosion})
 * unless marked {@code @Unique}. Keeping them out of the mixin sidesteps that entirely.
 */
public final class ExplosionDamageSink {

    private ExplosionDamageSink() {}

    /**
     * The MC -&gt; SB event ring. Shared with {@link DamageBridge}: both write {@code kEv*}
     * records into the one MC-&gt;SB event channel. Left null until the mod entrypoint wires
     * {@link dev.stellarcraft.StellarCraftMod#initRings()} - while it is null every
     * explosion hit is silently dropped.
     */
    public static volatile RingBuffer eventRing = null;

    /**
     * Extra multiplier applied to explosion damage before it leaves Minecraft.
     *
     * <p>Kept at 1.0: MC -&gt; SB now sends the <b>raw</b> vanilla amount and the Stellar Blade
     * side converts it to native HP through a per-tier budget table (see
     * {@code sb-mod/lua/main.lua}). TNT already resolves to ~56 raw damage at the centre,
     * which is lethal to minions/warriors and a big chunk of an elite's bar — so no extra
     * fudge factor is wanted. Raise this only to give explosions extra punch relative to
     * melee, and expect it to scale every tier by the same factor.
     */
    public static volatile float scale = 1.0f;

    /**
     * Writes one {@code EV_EXPLOSION_HIT} record for a single affected SB actor.
     *
     * @param uid        Stellar Blade actor uid carried by the {@code ActorProxy}
     * @param damage     raw MC explosion damage for this actor (the SB side converts it)
     * @param cx         blast centre x (context only)
     * @param cz         blast centre z (context only)
     * @param knockback  falloff factor, 1.0 at the centre and 0.0 at the edge
     */
    public static void forwardHit(int uid, float damage, double cx, double cy, double cz,
                                  float knockback) {
        RingBuffer ring = eventRing;
        if (ring == null) return;

        ByteBuffer ev = ByteBuffer.allocate(Proto.MC_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        ev.putInt(Proto.EV_EXPLOSION_HIT);
        ev.putInt(uid);
        ev.putFloat(damage);           // a = scaled damage
        ev.putFloat((float) cx);       // b = centre x
        ev.putFloat((float) cz);       // c = centre z
        ev.putFloat(knockback);        // d = knockback strength (falloff)
        ev.putInt(0);                  // flags
        ev.putInt(0);                  // weapon
        ev.flip();

        long claimed = ring.claim(Proto.MC_EVENT_SIZE);
        if (claimed < 0) return;
        ring.writeBytes(claimed, ev);
        ring.publish(claimed, Proto.MC_EVENT_SIZE);
    }
}
