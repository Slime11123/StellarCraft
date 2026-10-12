package dev.stellarcraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Only the controlled player bypasses MC terrain; the host capsule owns collision/gravity. */
public final class NativeMovement {
    private record Saved(boolean noPhysics, boolean noGravity) {}
    private static final ConcurrentHashMap<Entity,Saved> saved = new ConcurrentHashMap<>();
    private static volatile UUID playerId;
    private static volatile boolean active;
    private static volatile boolean grounded = true;
    private static net.minecraft.world.phys.Vec3 intent = net.minecraft.world.phys.Vec3.ZERO;
    private static long intentTime;
    public static void recordIntent(net.minecraft.world.phys.Vec3 movement) {
        intent=movement;intentTime=dev.stellarcraft.NativeLink.tickCount();
    }
    public static void publishIntent(dev.stellarcraft.link.SharedMemory.McStateSnapshot state,int epoch) {
        state.motionEpoch=epoch;state.motionTimeMs=intentTime;
        var player=Minecraft.getInstance().player;
        state.motionMode=player!=null&&player.getAbilities().flying?2
            :player!=null&&ElytraBridge.gliding(player)?3:grounded?0:1;
        state.velocityX=intent.x;state.velocityY=intent.y;state.velocityZ=intent.z;
        if(Minecraft.getInstance().screen!=null||!ControlStateMachine.isSteve()) {
            state.motionTimeMs=dev.stellarcraft.NativeLink.tickCount();
            state.velocityX=state.velocityY=state.velocityZ=0;
        }
    }
    public static void feedback(Minecraft mc,dev.stellarcraft.link.SharedMemory.SbStateSnapshot state,int epoch) {
        var p=mc.player;
        long age=dev.stellarcraft.NativeLink.tickCount()-state.motionTimeMs;
        if(p==null||!owns(p)||state.motionEpoch!=epoch||age<0||age>250
            ||!Double.isFinite(state.posX)||!Double.isFinite(state.posY)||!Double.isFinite(state.posZ)) return;
        grounded=(state.contactFlags&1)!=0;
        if(!ControlStateMachine.isSteve()) return;
        double dx=state.posX-p.getX(),dy=state.posY-p.getY(),dz=state.posZ-p.getZ();
        p.setPos(state.posX,state.posY,state.posZ);
        ElytraBridge.feedback(mc,p,state);
        // Translate both animation endpoints. A routine collision result is not a teleport.
        p.xo+=dx;p.xOld+=dx;p.yo+=dy;p.yOld+=dy;p.zo+=dz;p.zOld+=dz;
        var velocity=p.getDeltaMovement();
        p.setDeltaMovement((state.contactFlags&2)!=0?0:velocity.x,
            (state.contactFlags&4)!=0?0:ElytraBridge.gliding(p)?velocity.y:state.velocityY,
            (state.contactFlags&8)!=0?0:velocity.z);
        apply(p);
    }
    private NativeMovement() {}
    public static boolean active() { return active; }
    public static boolean owns(Entity entity) {
        return active && entity instanceof Player && entity.getUUID().equals(playerId);
    }

    public static void tick(Minecraft mc, boolean hostAlive) {
        playerId = mc.player == null ? null : mc.player.getUUID();
        // The hidden world stays isolated during F4 handoff and Eve's gameplay too.
        active = hostAlive && mc.hasSingleplayerServer();
        if(!active) {intent=net.minecraft.world.phys.Vec3.ZERO;intentTime=0;grounded=true;ElytraBridge.reset();}
        if (mc.player != null) apply(mc.player);
        var server = mc.getSingleplayerServer();
        UUID id = playerId;
        if (server != null && id != null) server.execute(() -> {
            var p = server.getPlayerList().getPlayer(id);
            if (p != null) apply(p);
        });
    }

    private static final ThreadLocal<Boolean> hostDamage = ThreadLocal.withInitial(() -> false);
    public static boolean acceptsDamage(Entity entity,net.minecraft.world.damagesource.DamageSource source) {
        if(!owns(entity)) return true;
        if(!ControlStateMachine.isSteve()||((Player)entity).isCreative()||((Player)entity).isSpectator()) return false;
        Entity attacker=source.getEntity();
        Entity direct=source.getDirectEntity();
        return hostDamage.get() || direct instanceof net.minecraft.world.entity.projectile.FireworkRocketEntity
            &&dev.stellarcraft.world.NativeEntities.owns(direct)&&dev.stellarcraft.world.NativeEntities.simulates(direct)
            || attacker instanceof net.minecraft.world.entity.monster.Monster
            &&dev.stellarcraft.world.NativeEntities.owns(attacker)&&dev.stellarcraft.world.NativeEntities.simulates(attacker);
    }
    public static void withHostDamage(Runnable hit) {
        boolean previous=hostDamage.get();hostDamage.set(true);
        try {hit.run();} finally {hostDamage.set(previous);}
    }

    /** Forward only a registered monster's successful attack impulse into MC movement intent. */
    public static void registeredKnockback(Entity attacker,Player player,net.minecraft.world.phys.Vec3 impulse) {
        if(!owns(player)||!ControlStateMachine.isSteve()||player.isCreative()||player.isSpectator()
            ||!dev.stellarcraft.world.NativeEntities.owns(attacker)
            ||!dev.stellarcraft.world.NativeEntities.simulates(attacker)
            ||!Double.isFinite(impulse.x)||!Double.isFinite(impulse.z)) return;
        // The server does not simulate host travel. Its next movement packet clears
        // velocity, so vanilla's deferred velocity packet would otherwise carry zero.
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        player.hurtMarked=false;
        UUID id=player.getUUID();
        Minecraft.getInstance().execute(() -> {
            var local=Minecraft.getInstance().player;
            if(local==null||!local.getUUID().equals(id)||!owns(local)||!ControlStateMachine.isSteve()
                ||local.isCreative()||local.isSpectator()||!local.isAlive()) return;
            local.setDeltaMovement(local.getDeltaMovement().add(impulse.x,0,impulse.z));
            System.out.println("[StellarCraft-Knockback] registered="+attacker.getUUID()+" impulse="+impulse);
        });
    }

    public static void apply(Entity entity) {
        if (owns(entity)) {
            saved.computeIfAbsent(entity, e -> new Saved(e.noPhysics, e.isNoGravity()));
            entity.noPhysics = true;
            entity.setNoGravity(true);
            // LocalPlayer.aiStep cancels creative flight on ground after travel.
            // Hidden MC terrain must not ground a player flying in the host world.
            entity.setOnGround(grounded && !((Player) entity).getAbilities().flying);
            ElytraBridge.validate((Player)entity);
            entity.fallDistance = 0;
        } else {
            Saved old = saved.remove(entity);
            if (old != null) {
                entity.noPhysics = old.noPhysics;
                entity.setNoGravity(old.noGravity);
                ElytraBridge.validate((Player)entity);
            }
        }
    }

    public static void reconcileServer(Minecraft mc, double x, double y, double z) {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        UUID id = mc.player.getUUID();
        server.execute(() -> {
            var p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                apply(p);
                p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                if(owns(p)) {
                    // MC has already applied this native correction locally. Do not send
                    // a second teleport that can arrive after newer movement/look input.
                    p.setPos(x,y,z);
                    p.connection.resetPosition();
                    p.serverLevel().getChunkSource().move(p);
                } else p.connection.teleport(x, y, z, 0, 0,
                    java.util.EnumSet.of(net.minecraft.world.entity.RelativeMovement.Y_ROT,
                                         net.minecraft.world.entity.RelativeMovement.X_ROT));
            }
        });
    }
    public static void alignHeight(Minecraft mc, double y) {
        var p = mc.player;
        if (p == null || !owns(p)) return;
        p.setPos(p.getX(), y, p.getZ());
        p.yo = p.yOld = y;
        p.setDeltaMovement(p.getDeltaMovement().multiply(1, 0, 1));
        p.fallDistance = 0;
        var server = mc.getSingleplayerServer();
        UUID id = playerId;
        if (server != null && id != null) server.execute(() -> {
            var peer = server.getPlayerList().getPlayer(id);
            if (peer != null && owns(peer)) {
                apply(peer);
                peer.setPos(peer.getX(), y, peer.getZ());
                peer.setDeltaMovement(peer.getDeltaMovement().multiply(1, 0, 1));
            }
        });
    }
}
