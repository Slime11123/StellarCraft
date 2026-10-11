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
 * <p>Damage scaling is a config decision (see design doc §Damage scaling): SB characters
 * have far more HP than MC's weapons deal, so MC→SB damage is multiplied by a level-scaled
 * factor, and SB→MC damage is divided.
 */
public final class DamageBridge {
    private DamageBridge() {}

    /** MC → SB damage multiplier: {@code 5 + 0.25 * level} (config-tunable). */
    public static float MC_TO_SB_SCALE = 5.0f;
    public static float MC_TO_SB_SCALE_PER_LEVEL = 0.25f;

    /** SB → MC damage divisor (a 20-damage SB hit becomes 4 MC damage). */
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

        // Level-scaled damage: diamond sword (7) vs a level-10 SB enemy -> ~75.
        float scaled = amount * (MC_TO_SB_SCALE + MC_TO_SB_SCALE_PER_LEVEL * 10); // TODO: use real level

        ByteBuffer ev = ByteBuffer.allocate(Proto.MC_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        ev.putInt(Proto.EV_HIT_ACTOR);
        ev.putInt(proxy.sbUid());
        ev.putFloat(scaled);
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
        DamageSource src = player.damageSources().generic(); // TODO: map kind -> custom source
        player.hurt(src, scaled);
    }
}
