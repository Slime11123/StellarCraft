package dev.stellarcraft.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
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
    private int nativePid,nativeWorld;
    private static final EntityDataAccessor<Float> WIDTH=SynchedEntityData.defineId(ActorProxy.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HEIGHT=SynchedEntityData.defineId(ActorProxy.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> FLAGS=SynchedEntityData.defineId(ActorProxy.class,EntityDataSerializers.INT);
    public int nativePid() {return nativePid;}
    public int nativeWorld() {return nativeWorld;}
    public boolean isNativeLive() {return !isRemoved()&&mirroredHealth>0&&(entityData.get(FLAGS)&18)==0;}
    public void syncNative(int pid,dev.stellarcraft.link.SharedMemory.ActorSnapshot a) {
        nativePid=pid;nativeWorld=a.world;setSbUid(a.uid);
        entityData.set(WIDTH,a.width);entityData.set(HEIGHT,a.height);entityData.set(FLAGS,a.flags);
        syncFrom(a.x,a.y,a.z,a.yaw,a.width,a.height,a.healthFrac);
    }
    @Override public net.minecraft.world.entity.EntityDimensions getDimensions(net.minecraft.world.entity.Pose pose) {
        return net.minecraft.world.entity.EntityDimensions.scalable(entityData.get(WIDTH),entityData.get(HEIGHT));
    }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);if(key==WIDTH||key==HEIGHT) refreshDimensions();
    }
    @Override public void tick() {super.tick();setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);}
    @Override public boolean shouldBeSaved() {return false;}
    @Override protected void actuallyHurt(DamageSource source,float amount) {
        DamageBridge.forward(this,source,amount);
    }
    private boolean suppressNextDamageForward = false;

    public ActorProxy(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
    }

    public static ActorProxy spawn(Level level, double x, double y, double z, int uid,
                                   float width, float height, float healthFrac) {
        // The mod registers its own entity type; this factory keeps call sites clean.
        ActorProxy p = new ActorProxy(EntityRegistry.ACTOR_PROXY, level);
        p.setPos(x, y, z);
        p.setSbUid(uid);p.noPhysics=true;p.setNoGravity(true);
        p.entityData.set(WIDTH,width);p.entityData.set(HEIGHT,height);
        p.mirroredHealth = healthFrac;
        p.noCulling = true;
        level.addFreshEntity(p);
        return p;
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_SB_UID, -1);
        this.entityData.define(WIDTH,.6f);this.entityData.define(HEIGHT,1.8f);this.entityData.define(FLAGS,0);
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

    public int sbUid() { return entityData.get(DATA_SB_UID); }
    public void setSbUid(int uid) { this.sbUid = uid; this.entityData.set(DATA_SB_UID, uid); }
    public void setMirroredHealth(float f) { this.mirroredHealth = f; }
    public float mirroredHealth() { return mirroredHealth; }

    /** Update tracked position/hitbox from the SB actor table. */
    public void syncFrom(float x, float y, float z, float yaw, float width, float height, float healthFrac) {
        this.setPos(x, y, z);
        this.setYRot(yaw);
        refreshDimensions();
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
        if(level().isClientSide||!NativeActors.current(this)||!Float.isFinite(amount)||amount<=0
            ||!dev.stellarcraft.client.NativeMovement.owns(source.getEntity())) return false;
        var direct=source.getDirectEntity();
        if(!(direct instanceof net.minecraft.world.entity.player.Player)
            &&!(direct instanceof net.minecraft.world.entity.projectile.AbstractArrow&&NativeEntities.owns(direct)&&NativeEntities.simulates(direct))) return false;
        // Vanilla hurt handles cooldown/i-frames and attack feedback; actuallyHurt
        // forwards its final amount without maintaining a second native HP pool.
        return super.hurt(source,amount);
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
    public boolean isInvulnerable() { return false; }

    /** LivingEntity requires a main-arm; proxies default to right (cosmetic only). */
    @Override
    public HumanoidArm getMainArm() { return HumanoidArm.RIGHT; }

    /** Proxies carry no equipment; these are no-op impls of the abstract slots API. */
    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) { }

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) { return ItemStack.EMPTY; }

    /** Proxies wear no armor. */
    @Override
    public Iterable<ItemStack> getArmorSlots() { return java.util.Collections.emptyList(); }
}
