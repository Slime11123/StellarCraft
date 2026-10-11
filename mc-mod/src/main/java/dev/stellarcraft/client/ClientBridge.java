package dev.stellarcraft.client;

import dev.stellarcraft.link.Proto;
import dev.stellarcraft.link.SharedMemory;
import dev.stellarcraft.world.ActorProxy;
import dev.stellarcraft.world.CollisionField;
import dev.stellarcraft.world.DamageBridge;
import dev.stellarcraft.world.EntityRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.HashMap;
import java.util.Map;

/**
 * The per-frame bridge: pulls Stellar Blade's state into Minecraft and pushes Minecraft's
 * state back out.
 *
 * <p>Minecraft is <em>authoritative</em> for the player's position, physics, and health.
 * Stellar Blade is authoritative for the world (geometry, NPCs, quests, save). This class
 * is the seam where the two meet every frame:
 *
 * <ol>
 *   <li>Read the seqlock-protected SB state (flags, anchor position, game hour).</li>
 *   <li>Drain the collision ring into the {@link CollisionField}.</li>
 *   <li>Drain the input ring and inject SB's keyboard/mouse into MC.</li>
 *   <li>Reconcile the {@link ActorProxy} set against the SB actor table.</li>
 *   <li>Write back the MC player's interpolated position/look/pose.</li>
 * </ol>
 */
public final class ClientBridge {
    private static ClientBridge instance;

    private final SharedMemory mem;
    private final CollisionField collision = new CollisionField();
    private final Map<Integer, ActorProxy> proxies = new HashMap<>();

    /** Consecutive proxy-spawn failures before actor mirroring is abandoned for the session. */
    private static final int MAX_SPAWN_FAILURES = 5;
    private int spawnFailures = 0;

    private int lastTeleportSeq = -1;
    private long tickCounter = 0;
    private net.minecraft.client.multiplayer.ClientLevel preparedLevel;
    private int nativePid;
    private long lastCorrectionLog;
    private int damageGuid;
    private double damageTotal;
    private boolean damagePrimed;

    private void refreshHostIdentity() {
        int pid=mem.sbPid();
        if(pid!=nativePid) {
            nativePid=pid;lastTeleportSeq=-1;preparedLevel=null;lastSbAnchor=null;
            damagePrimed=false;
        }
    }

    // Cached last-known SB player position (MC coords), used to place Steve at Eve's
    // location when the player switches control to Steve.
    private static volatile double[] lastSbAnchor;

    private ClientBridge(SharedMemory mem) {
        this.mem = mem;
    }

    /** Last-known SB player position in MC coords, or null if unknown. */
    public static double[] lastSbAnchor() {
        return lastSbAnchor;
    }

    public static ClientBridge get() { return instance; }

    public static void start(SharedMemory mem) {
        instance = new ClientBridge(mem);
    }

    public CollisionField collisionField() { return collision; }
    public int hostPid() {return mem.sbPid();}

    /** Called once per client tick. */
    public void tick() {
        refreshHostIdentity();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        SharedMemory.SbStateSnapshot sb = mem.readSbState();
        if (sb == null) return; // mid-write; retry next tick
        long age = dev.stellarcraft.NativeLink.tickCount() - mem.sbHeartbeat();
        boolean nativeScene = age >= 0 && age < 5000 && (sb.flags & Proto.SbFlags.IN_GAME) != 0;
        NativeMovement.tick(mc, nativeScene);
        var hurt=mem.readPlayerHurt();
        if(hurt!=null&&Double.isFinite(hurt.total())&&hurt.total()>=0) {
            double delta=damagePrimed&&damageGuid==hurt.guid()?hurt.total()-damageTotal:0;
            damageGuid=hurt.guid();damageTotal=hurt.total();damagePrimed=true;
            if(nativeScene&&ControlStateMachine.isSteve()&&delta>0&&delta<=1000&&mc.player!=null)
                DamageBridge.applyNativeHealthDamage(mc.player,(float)delta,hurt.attacker());
        }

        // Cache SB player anchor for control-switch teleport.
        if(nativeScene && sb.teleportSeq>0) lastSbAnchor = new double[] { sb.posX, sb.posY, sb.posZ };

        // 1. If SB teleported us (new level / respawn), move the player there.
        if (lastTeleportSeq == -1) lastTeleportSeq = sb.teleportSeq;
        if (nativeScene && sb.teleportSeq>0 && sb.teleportSeq != lastTeleportSeq) {
            LocalPlayer p = mc.player;
            if (p != null) {
                long now = System.nanoTime();
                if (now - lastCorrectionLog > 2_000_000_000L) {
                    lastCorrectionLog = now;
                    System.out.printf(java.util.Locale.ROOT,
                        "[StellarCraft-Move] correction=%d local=(%.3f,%.3f,%.3f) native=(%.3f,%.3f,%.3f) velocity=%s input=(%.2f,%.2f) owns=%s%n",
                        sb.teleportSeq,p.getX(),p.getY(),p.getZ(),sb.posX,sb.posY,sb.posZ,
                        p.getDeltaMovement(),p.input.leftImpulse,p.input.forwardImpulse,NativeMovement.owns(p));
                }
                // Entity.teleportTo is server-only in MC 1.20.1: on a LocalPlayer it
                // returns without moving. Commit the native correction before its ack.
                p.setPos(sb.posX, sb.posY, sb.posZ);
                p.xo = p.xOld = sb.posX;
                p.yo = p.yOld = sb.posY;
                p.zo = p.zOld = sb.posZ;
                p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                p.fallDistance = 0;
                if (!ControlStateMachine.isSteve()) {
                    p.setYRot(sb.yaw);
                    p.setXRot(sb.pitch);
                }
                NativeMovement.reconcileServer(mc, sb.posX, sb.posY, sb.posZ);
            }
            lastTeleportSeq = sb.teleportSeq;
        }
        if (nativeScene && sb.teleportSeq > 0 && NativeMovement.active() && mc.player != null) {
            // Native capsule gravity/stairs own height. Keep MC's eye and placement ray
            // at those feet without issuing a teleport packet every time height changes.
            NativeMovement.feedback(mc,sb,mem.sbPid());
        }

        // 2. Reconcile actor proxies against the SB actor table.
        reconcileActors(mc, sb);

        // 3. Publish the player's skin/cape so SB renders Steve with the player's look.
        AppearanceBridge.tick(mc, mem);

        // 4. Drive MC daylight from SB's game hour (keeps block shading in sync).
        if ((sb.flags & Proto.SbFlags.IN_GAME) != 0) {
            // TODO: set level dayTime = gameHour * 1000
        }
    }

    private void reconcileActors(Minecraft mc, SharedMemory.SbStateSnapshot sb) {
        int count = mem.actorCount();
        java.util.Set<Integer> seen = new java.util.HashSet<>();

        for (int i = 0; i < count && i < Proto.MAX_ACTORS; i++) {
            SharedMemory.ActorSnapshot a = mem.readActor(i);
            if (a.uid == 0) continue;
            seen.add(a.uid);

            ActorProxy proxy = proxies.get(a.uid);
            if (proxy == null || proxy.isRemoved()) {
                if (spawnFailures >= MAX_SPAWN_FAILURES) {
                    // Give up entirely rather than retrying (and logging) every frame.
                    continue;
                }
                // Guarded on purpose. Spawning a custom LivingEntity touches vanilla
                // machinery that a mod can get wrong (see EntityRegistry: an unregistered
                // attribute table NPEs inside LivingEntity's constructor). A bug there must
                // cost us the proxies, not the player's game.
                try {
                    proxy = ActorProxy.spawn(mc.level, a.x, a.y, a.z, a.uid, a.width, a.height, a.healthFrac);
                } catch (Throwable t) {
                    spawnFailures++;
                    System.out.println("[StellarCraft] ActorProxy.spawn failed for uid " + a.uid
                        + " (" + t + ")" + (spawnFailures >= MAX_SPAWN_FAILURES
                            ? " - disabling actor mirroring for this session" : ""));
                    continue;
                }
                proxies.put(a.uid, proxy);
            }
            proxy.syncFrom(a.x, a.y, a.z, a.yaw, a.width, a.height, a.healthFrac);
        }

        // Remove proxies for actors that left range.
        proxies.entrySet().removeIf(e -> {
            if (!seen.contains(e.getKey())) {
                e.getValue().discard();
                return true;
            }
            return false;
        });
    }

    /**
     * Writes the current MC player state back to SB (called each render frame).
     *
     * <p>This runs even when no world is loaded (the title screen), because the
     * {@code CONTROL_STEVE} bit is what Stellar Blade watches to decide who owns the
     * character. Without publishing from the menu, pressing F4 while MC sits on the title
     * screen would flip SB's latch but MC would never confirm it, and the handoff would only
     * "take" once a world was entered. Player-dependent fields (position, look, on-ground)
     * are skipped when there is no local player; the flags that matter for the handoff are
     * always written.
     */
    public void publishMcState(float partialTick) {
        refreshHostIdentity();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;

        // Publish a safe first origin before SB calibrates its world frame. Old bridge
        // saves can contain positions inside MC terrain; height syncing cannot use SB's
        // uncalibrated coordinates until its first teleport serial acknowledges this origin.
        if (p != null && mc.level != null && ControlStateMachine.isSteve() && preparedLevel != mc.level) {
            var sb = mem.readSbState();
            if (sb != null && sb.teleportSeq == 0 && (sb.flags & Proto.SbFlags.IN_GAME) != 0) {
                double y = Math.max(p.getY(), Math.max(192,
                    mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        p.getBlockX(), p.getBlockZ()) + 8));
                y = Math.min(y, mc.level.getMaxBuildHeight() - 24);
                p.setPos(p.getX(), y, p.getZ());
                p.yo = p.yOld = y;
                p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                NativeMovement.tick(mc, true);
                NativeMovement.reconcileServer(mc, p.getX(), y, p.getZ());
                preparedLevel = mc.level;
            } else if(sb!=null && sb.teleportSeq>0) {
                preparedLevel = mc.level;
            }
        }

        SharedMemory.McStateSnapshot s = new SharedMemory.McStateSnapshot();
        s.flags = 0;
        if (mc.level != null) s.flags |= Proto.McFlags.IN_WORLD;
        if (mc.screen != null) s.flags |= Proto.McFlags.SCREEN_OPEN;

        if (p != null) {
            if (mc.screen == null && ControlStateMachine.hasInputAuthority() && mc.options.keyJump.isDown())
                s.flags |= Proto.McFlags.JUMP_HELD;
            if (p.onGround()) s.flags |= Proto.McFlags.ON_GROUND;
            // Also carries the flight descend key, independent of grounded sneak pose.
            if (mc.screen == null && ControlStateMachine.hasInputAuthority() && mc.options.keyShift.isDown())
                s.flags |= Proto.McFlags.SNEAKING;
            if (p.isSprinting()) s.flags |= Proto.McFlags.SPRINTING;
            if (p.isDeadOrDying()) s.flags |= Proto.McFlags.DEAD;
            if (p.isSwimming()) s.flags |= Proto.McFlags.SWIMMING;
            if (p.getAbilities().flying) s.flags |= Proto.McFlags.FLYING;
        }
        // Tell SB whether the MC player is ready to receive forwarded input.
        //
        // This is the mirror of SekiroCraft's `mcStatus`: MC is the only party that knows
        // whether it can actually consume keystrokes right now, so it is MC that asserts
        // this bit -- and the overlay uses it (together with "F4 latched" and "game window
        // focused") to decide whether input authority may flow.
        //
        // Crucially it does NOT require a loaded world. MC's world-select / title screens are
        // legitimate consumers of forwarded input (they are what the player sees after
        // pressing F4 from the menu), and MC is perfectly able to run key handling without a
        // world. Only the "ready" condition -- MC is linked and has a window to drive -- is
        // required.
        if (ControlStateMachine.isSteve()) s.flags |= Proto.McFlags.CONTROL_STEVE;

        if (p != null) {
            // Interpolated render position (partial-tick), matching MC's own renderer.
            s.x = lerp(p.xo,p.getX(),partialTick);
            s.y = p.getY();
            s.z = lerp(p.zo,p.getZ(),partialTick);
            s.yaw = p.getYRot();
            s.pitch = p.getXRot();
            s.eyeHeight = p.getEyeHeight();
        }
        s.teleportAck = lastTeleportSeq;
        NativeMovement.publishIntent(s,mem.sbPid());
        if(p!=null) {
            s.prevX=p.xo;s.prevY=p.yo;s.prevZ=p.zo;
            s.curX=p.getX();s.curY=p.getY();s.curZ=p.getZ();s.tickMs=50;
        }
        s.fovDeg = (float) mc.options.fov().get();
        var camera=mc.options.getCameraType();
        s.cameraMode=camera.isFirstPerson()?0:(camera.isMirrored()?2:1);
        s.cameraDistance=4.0f;
        // 1.20.1 has no Minecraft#getFrameTimeNs; use a monotonic tick counter instead.
        s.frameCounter = tickCounter++;

        mem.writeMcState(s);
    }

    private static double lerp(double a, double b, float t) {
        return a + (b - a) * t;
    }
}
