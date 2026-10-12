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

    private static final java.util.LinkedHashSet<java.util.UUID> completed=new java.util.LinkedHashSet<>();
    public static void blast(java.util.UUID id,net.minecraft.world.level.Level level,net.minecraft.world.phys.Vec3 center) {
        if(!completed.add(id)) return;
        while(completed.size()>256) completed.remove(completed.iterator().next());
        for(var actor:level.getEntitiesOfClass(ActorProxy.class,new net.minecraft.world.phys.AABB(center,center).inflate(40))) {
            if(!NativeActors.current(actor)) continue;
            double distance=actor.position().distanceTo(center)/8;
            if(distance>=1) continue;
            // Only registered MC block shapes may attenuate a native explosion.
            int visible=0;
            var box=actor.getBoundingBox();
            for(int ix=0;ix<2;ix++) for(int iy=0;iy<3;iy++) for(int iz=0;iz<2;iz++) {
                var end=new net.minecraft.world.phys.Vec3(ix==0?box.minX:box.maxX,box.minY+(box.maxY-box.minY)*iy/2,iz==0?box.minZ:box.maxZ);
                boolean blocked=false;
                for(long packed:PlacedBlocks.positions()) {
                    var state=PlacedBlocks.state(packed);if(state==null) continue;
                    var pos=net.minecraft.core.BlockPos.of(packed);double offset=PlacedBlocks.offset(packed);
                    if(state.getCollisionShape(level,pos).clip(center.add(0,-offset,0),end.add(0,-offset,0),pos)!=null) {blocked=true;break;}
                }
                if(!blocked) visible++;
            }
            double exposure=(1-distance)*visible/12.0;
            if(exposure>0) {
                float damage=(float)((exposure*exposure+exposure)*28+1);
                net.minecraft.client.Minecraft.getInstance().execute(()->forwardHit(actor.sbUid(),damage,center.x,center.y,center.z,(float)exposure));
            }
        }
    }

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
        ev.putFloat((float) cy);       // c = centre y
        ev.putFloat((float) cz);       // d = centre z (native exposure check)
        ev.putInt(DamageBridge.nextEventId()); // unique damage publication
        ev.putInt(0);                  // weapon
        ev.flip();

        long claimed = ring.claim(Proto.MC_EVENT_SIZE);
        if (claimed < 0) return;
        ring.writeBytes(claimed, ev);
        ring.publish(claimed, Proto.MC_EVENT_SIZE);
    }
}
