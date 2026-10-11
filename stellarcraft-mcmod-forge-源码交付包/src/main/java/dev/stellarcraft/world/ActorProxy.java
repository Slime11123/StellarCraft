package dev.stellarcraft.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * An invisible, hittable stand-in for a Stellar Blade character.
 *
 * <p>Stellar Blade draws the <em>real</em> character; Minecraft only needs a collision +
 * damage target. For each SB actor within range, the mod spawns one of these proxies whose
 * position, hitbox and rotation track the real actor via the shared-memory actor table.
 *
 * <p>Because it's a real {@link LivingEntity}, all vanilla Minecraft combat works on it:
 * sword swings, attack cooldown, crits, sweeping edge, Sharpness/Fire Aspect, knockback,
 * arrows, tridents, splash potions. When {@link #hurt} is called, the mod intercepts the
 * final damage and forwards a {@code kEvHitActor} event to SB, then keeps the proxy alive
 * (it isn't the real character).
 */
public class ActorProxy extends LivingEntity {
    public static final EntityDataAccessor<Integer> DATA_SB_UID =
        SynchedEntityData.defineId(ActorProxy.class, EntityDataSerializers.INT);

    /** The SB-side actor id this proxy mirrors. */
    private int sbUid = -1;
    private float mirroredHealth = 1.0f;
    private boolean suppressNextDamageForward = false;

    public ActorProxy(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
    }

    public static ActorProxy spawn(Level level, double x, double y, double z, int uid,
                                   float width, float height, float healthFrac) {
        // The mod registers its own entity type; this factory keeps call sites clean.
        ActorProxy p = new ActorProxy(EntityRegistry.ACTOR_PROXY, level);
        p.setPos(x, y, z);
        p.sbUid = uid;
        p.mirroredHealth = healthFrac;
        p.noCulling = true;
        level.addFreshEntity(p);
        return p;
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_SB_UID, -1);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.sbUid = tag.getInt("sbUid");
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("sbUid", this.sbUid);
    }

    public int sbUid() { return sbUid; }
    public void setSbUid(int uid) { this.sbUid = uid; this.entityData.set(DATA_SB_UID, uid); }
    public void setMirroredHealth(float f) { this.mirroredHealth = f; }
    public float mirroredHealth() { return mirroredHealth; }

    /** Update tracked position/hitbox from the SB actor table. */
    public void syncFrom(float x, float y, float z, float yaw, float width, float height, float healthFrac) {
        this.setPos(x, y, z);
        this.setYRot(yaw);
        // 1.20.1: hitbox dimensions are fixed at construction; width/height are reserved
        // for a later per-actor resize (see refreshDimensions). Kept as no-op for M1.
        this.mirroredHealth = healthFrac;
    }

    /**
     * When Minecraft deals damage to this proxy, the damage is forwarded to SB as a
     * {@code kEvHitActor} event (see {@code DamageBridge}), and the proxy is kept alive so
     * it can keep receiving hits. Return {@code false} to suppress Minecraft's own death
     * handling.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (suppressNextDamageForward) {
            // This hurt call is a mirror (SB already resolved it); don't echo it back.
            suppressNextDamageForward = false;
            return super.hurt(source, amount);
        }
        // Forward to SB; the bridge decides final damage and returns whether the proxy
        // should visibly react. We don't actually kill the proxy here.
        DamageBridge.forward(this, source, amount);
        return false;
    }

    public void applyMirroredHurt(float amount) {
        this.suppressNextDamageForward = true;
        super.hurt(this.damageSources().generic(), amount);
    }

    /** These proxies are invisible — SB draws the real actor. */
    @Override
    public boolean shouldRenderAtSqrDistance(double distance) { return false; }

    /** Proxies never take fall damage or despawn; they're pure stand-ins. */
    @Override
    public boolean isInvulnerable() { return true; }
}
