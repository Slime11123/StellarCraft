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

    private int lastTeleportSeq = -1;
    private long tickCounter = 0;

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

    /** Called once per client tick. */
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        SharedMemory.SbStateSnapshot sb = mem.readSbState();
        if (sb == null) return; // mid-write; retry next tick

        // Cache SB player anchor for control-switch teleport.
        lastSbAnchor = new double[] { sb.posX, sb.posY, sb.posZ };

        // 1. If SB teleported us (new level / respawn), move the player there.
        if (sb.teleportSeq != lastTeleportSeq) {
            LocalPlayer p = mc.player;
            if (p != null) {
                p.teleportTo(sb.posX, sb.posY, sb.posZ);
                p.setYRot(sb.yaw);
                p.setXRot(sb.pitch);
            }
            lastTeleportSeq = sb.teleportSeq;
            collision.clear(); // level changed -> drop foreign geometry
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
                proxy = ActorProxy.spawn(mc.level, a.x, a.y, a.z, a.uid, a.width, a.height, a.healthFrac);
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

    /** Writes the current MC player state back to SB (called each render frame). */
    public void publishMcState(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;

        SharedMemory.McStateSnapshot s = new SharedMemory.McStateSnapshot();
        s.flags = 0;
        if (mc.level != null) s.flags |= Proto.McFlags.IN_WORLD;
        if (mc.screen != null) s.flags |= Proto.McFlags.SCREEN_OPEN;
        if (p.onGround()) s.flags |= Proto.McFlags.ON_GROUND;
        if (p.isShiftKeyDown()) s.flags |= Proto.McFlags.SNEAKING;
        if (p.isSprinting()) s.flags |= Proto.McFlags.SPRINTING;
        if (p.isDeadOrDying()) s.flags |= Proto.McFlags.DEAD;
        if (p.isSwimming()) s.flags |= Proto.McFlags.SWIMMING;
        if (p.getAbilities().flying) s.flags |= Proto.McFlags.FLYING;

        // Interpolated render position (partial-tick), matching MC's own renderer.
        s.x = lerp(p.xo, p.getX(), partialTick);
        s.y = lerp(p.yo, p.getY(), partialTick);
        s.z = lerp(p.zo, p.getZ(), partialTick);
        s.yaw = p.getYRot();
        s.pitch = p.getXRot();
        s.eyeHeight = p.getEyeHeight();
        s.teleportAck = lastTeleportSeq;
        s.fovDeg = (float) mc.options.fov().get();
        // 1.20.1 has no Minecraft#getFrameTimeNs; use a monotonic tick counter instead.
        s.frameCounter = tickCounter++;

        mem.writeMcState(s);
    }

    private static double lerp(double a, double b, float t) {
        return a + (b - a) * t;
    }
}
