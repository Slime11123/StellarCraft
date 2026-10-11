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

    public static void init(RingBuffer events) {
        eventRing = events;
    }

    /** Called from {@link ActorProxy#hurt}: forward MC damage to SB. */
    public static void forward(ActorProxy proxy, DamageSource source, float amount) {
        if (eventRing == null) return;

        // Determine weapon class for SB's impact effects/sounds.
        int weapon = Proto.WEAPON_UNARMED;
        int flags = 0;
        if (source.getDirectEntity() instanceof Player p) {
            var item = p.getMainHandItem();
            // TODO: map item type -> weapon class (sword/axe/blunt/pierce/bow).
        }

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

        long claimed = eventRing.claim(Proto.MC_EVENT_SIZE);
        if (claimed < 0) return; // ring full; drop
        eventRing.writeBytes(claimed, ev);
        eventRing.publish(claimed, Proto.MC_EVENT_SIZE);
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
}
