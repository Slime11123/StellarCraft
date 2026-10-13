package dev.stellarcraft.world;

import dev.stellarcraft.link.Proto;
import dev.stellarcraft.link.RingBuffer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Forwards Minecraft combat results to Stellar Blade (and vice-versa).
 *
 * <p>Two directions:
 * <ol>
 *   <li><b>MC hits SB actor</b> — vanilla MC computes damage on an {@link ActorProxy};
 *       {@link ActorProxy#hurt} calls {@link #forward}, which writes a
 *       {@code kEvHitActor} event to the event ring.</li>
 *   <li><b>SB hits MC player</b> — the SB mod writes a {@code kInHurt} input event; the
 *       client tick reads it and applies {@code player.hurt()} with a custom damage source,
 *       so MC armor/shields/i-frames all work.</li>
 * </ol>
 *
 * <p>Damage scaling: MC → SB is sent as the <b>raw</b> vanilla amount and converted to native
 * HP on the Stellar Blade side via a per-tier budget table (see {@link #forward}). SB → MC is
 * divided by {@link #SB_TO_MC_DIVISOR} so a native hit lands in MC's 0–20 health range.
 */
public final class DamageBridge {
    private DamageBridge() {}

    /**
     * SB → MC damage divisor (a 20-damage SB hit becomes 4 MC damage).
     *
     * <p>Note the MC → SB direction is deliberately NOT scaled here any more — see
     * {@link #forward}.
     */
    public static float SB_TO_MC_DIVISOR = 5.0f;

    private static RingBuffer eventRing;
    private static int eventSerial;
    private static long incomingSerial;
    private static int incomingPid,incomingWorld,incomingPlayer;

    public static int nextEventId() {return ++eventSerial;}

    public static void init(RingBuffer events) {
        eventRing = events;
    }

    /** Called from {@link ActorProxy#hurt}: forward MC damage to SB. */
    public static void forward(ActorProxy proxy, DamageSource source, float amount) {
        int weapon=Proto.WEAPON_UNARMED;
        net.minecraft.world.phys.Vec3 origin=null,direction=null;
        if(source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.AbstractArrow arrow) {
            weapon=Proto.WEAPON_ARROW;
            var segment=NativeEntities.hitSegment(arrow);
            if(segment!=null) {origin=segment.start();direction=segment.end().subtract(segment.start()).normalize();}
        } else if(source.getDirectEntity() instanceof Player player) {
            origin=player.position();
            var item=player.getMainHandItem().getItem();
            if(item instanceof net.minecraft.world.item.SwordItem) weapon=Proto.WEAPON_BLADE;
            else if(item instanceof net.minecraft.world.item.AxeItem) weapon=Proto.WEAPON_AXE;
            else if(item instanceof net.minecraft.world.item.DiggerItem) weapon=Proto.WEAPON_BLUNT;
        }
        boolean jumpHeavy=weapon!=Proto.WEAPON_ARROW&&NativeJumpAttack.heavy(proxy,source);
        if(jumpHeavy) weapon|=Proto.HIT_STYLE_JUMP_HEAVY;
        NativeJumpAttack.log(proxy,jumpHeavy);
        forward(proxy,source,amount,weapon,origin,direction,dev.stellarcraft.NativeLink.tickCount());
    }
    private static void forward(ActorProxy proxy,DamageSource source,float amount,int weapon,
        net.minecraft.world.phys.Vec3 origin,net.minecraft.world.phys.Vec3 direction,long time) {
        var mc=net.minecraft.client.Minecraft.getInstance();
        if(!mc.isSameThread()) {mc.execute(()->forward(proxy,source,amount,weapon,origin,direction,time));return;}
        if (eventRing == null||!NativeActors.current(proxy)||!Float.isFinite(amount)||amount<=0) return;

        // Determine weapon class for SB's impact effects/sounds.
        int flags = nextEventId();

        // Send the RAW vanilla damage amount. Converting it into native HP is the Stellar
        // Blade side's job, because only SB knows the target's tier and MaxHP:
        //
        //     native_HP_lost = MaxHP * (mcDamage / TIER_BUDGET[tier])
        //
        // This replaces a single global multiplier (previously `5 + 0.25 * level`). A global
        // rate cannot work here: Stellar Blade's enemy HP spans ~950x (Creeper 384 ..
        // Elder Naytiba 366,000), so one rate makes either the trash trivial or the final
        // boss worth 246 sword hits. The replacement is SekiroCraft-Passthrough's per-tier
        // "budget" model -- see docs/HP_CONVERSION.html and sb-mod/lua/main.lua.
        float raw = amount;

        ByteBuffer ev = ByteBuffer.allocate(Proto.MC_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        ev.putInt(Proto.EV_HIT_ACTOR);
        ev.putInt(proxy.sbUid());
        ev.putFloat(raw);
        ev.putFloat(0f);   // knockback x
        ev.putFloat(0f);   // knockback z
        ev.putFloat(0.3f); // knockback strength
        ev.putInt(flags);
        ev.putInt(weapon);
        ev.flip();

        publishHit(eventRing,ev,proxy.sbUid(),flags,weapon,origin,direction,time);
    }

    /** Publish context and HP event together; insufficient context space keeps legacy HP behavior. */
    public static void publishHit(RingBuffer ring,ByteBuffer hit,int uid,int serial,int weapon,
        net.minecraft.world.phys.Vec3 origin,net.minecraft.world.phys.Vec3 direction,long time) {
        int records=finite(origin)?(finite(direction)&&direction.lengthSqr()>.5?3:2):1;
        int bytes=records*Proto.MC_EVENT_SIZE;
        long claimed=ring.claim(bytes);
        if(claimed<0) {records=1;bytes=Proto.MC_EVENT_SIZE;claimed=ring.claim(bytes);}
        if(claimed<0) return;
        ByteBuffer batch=ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(records>1) context(batch,Proto.EV_HIT_ORIGIN,uid,serial,weapon,origin,time);
        if(records==3) context(batch,Proto.EV_HIT_DIRECTION,uid,serial,weapon,direction,time);
        batch.put(hit);batch.flip();ring.writeBytes(claimed,batch);ring.publish(claimed,bytes);
    }
    private static boolean finite(net.minecraft.world.phys.Vec3 v) {
        return v!=null&&Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z)
            &&Math.abs(v.x)<3e7&&Math.abs(v.y)<2e7&&Math.abs(v.z)<3e7;
    }
    private static void context(ByteBuffer out,int kind,int uid,int serial,int weapon,
        net.minecraft.world.phys.Vec3 v,long time) {
        out.putInt(kind).putInt(uid).putFloat((float)v.x).putFloat((float)v.y).putFloat((float)v.z)
            .putInt((int)time).putInt(serial).putInt(weapon);
    }

    /** Applies an SB-sourced hit to the MC player (called from the client tick). */
    public static void applyIncomingHurt(Player player, int kind, float damage, int attackerUid, int hurtFlags) {
        float scaled = damage / SB_TO_MC_DIVISOR;
        applyNativeHealthDamage(player,scaled,attackerUid);
    }

    /** Native HP proportion, already converted to MC's 20 health units. */
    public static void applyNativeHealthDamage(Player player,float scaled,int attackerUid) {
        if(!Float.isFinite(scaled)||scaled<=0||!dev.stellarcraft.client.ControlStateMachine.isSteve()) return;
        var server=net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        if(server!=null) server.execute(() -> {
            var peer=server.getPlayerList().getPlayer(player.getUUID());
            if(peer!=null&&peer.isAlive()&&!peer.isCreative()&&!peer.isSpectator()
                    &&dev.stellarcraft.client.NativeMovement.owns(peer)&&dev.stellarcraft.client.ControlStateMachine.isSteve()) {
                float before=peer.getHealth();
                dev.stellarcraft.client.NativeMovement.withHostDamage(() -> peer.hurt(peer.damageSources().mobAttack(null),scaled));
                System.out.println("[StellarCraft-PlayerDamage] native attacker="+attackerUid+" amount="+scaled+" HP="+before+" -> "+peer.getHealth());
            }
        });
    }

    /** Per-hit native event, evaluated on the authoritative MC server at current shield state. */
    public static void applyNativeHit(Player player,dev.stellarcraft.link.SharedMemory.CombatHit hit) {
        var mc=net.minecraft.client.Minecraft.getInstance();var server=mc.getSingleplayerServer();
        if(server==null||!dev.stellarcraft.client.ControlStateMachine.isSteve()) return;
        int pid=dev.stellarcraft.client.ClientBridge.get().hostPid();
        server.execute(()->{
            if(pid!=dev.stellarcraft.client.ClientBridge.get().hostPid()
                ||hit.world()!=PlacedBlocks.worldId()||dev.stellarcraft.NativeLink.tickCount()-hit.time()>500) return;
            if(incomingPid!=pid||incomingWorld!=hit.world()||incomingPlayer!=hit.player()) {
                incomingSerial=0;incomingPid=pid;incomingWorld=hit.world();incomingPlayer=hit.player();
            }
            if(hit.id()<=incomingSerial) return;incomingSerial=hit.id();
            var peer=server.getPlayerList().getPlayer(player.getUUID());
            if(peer==null||!peer.isAlive()||peer.isCreative()||peer.isSpectator()
                ||!dev.stellarcraft.client.NativeMovement.owns(peer)||!dev.stellarcraft.client.ControlStateMachine.isSteve()) return;
            var type=peer.level().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                .getHolderOrThrow(net.minecraft.world.damagesource.DamageTypes.MOB_ATTACK);
            var position=hit.directional()?new net.minecraft.world.phys.Vec3(hit.x(),hit.y(),hit.z()):null;
            var source=new DamageSource(type,position);
            float before=peer.getHealth();
            dev.stellarcraft.client.NativeMovement.withHostDamage(()->peer.hurt(source,hit.damage()));
            System.out.println("[StellarCraft-PlayerDamage] event="+hit.id()+" rawSource="+hit.rawSource()
                +" verifiedSource="+hit.sourceGuid()+" direction="+hit.directional()+" shield="+peer.isBlocking()
                +" amount="+hit.damage()+" HP="+before+" -> "+peer.getHealth());
        });
    }
}
