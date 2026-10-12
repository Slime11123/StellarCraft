package dev.stellarcraft;

import dev.stellarcraft.client.ClientBridge;
import dev.stellarcraft.client.ControlStateMachine;
import dev.stellarcraft.client.InputBridge;
import dev.stellarcraft.client.WindowFocusBridge;
import dev.stellarcraft.link.Proto;
import dev.stellarcraft.link.RingBuffer;
import dev.stellarcraft.link.SharedMemory;
import dev.stellarcraft.render.FrameExporter;
import dev.stellarcraft.world.DamageBridge;
import dev.stellarcraft.world.EntityRegistry;
import dev.stellarcraft.world.CollisionFieldHolder;
import dev.stellarcraft.world.TerrainRing;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * StellarCraft Fabric mod entrypoint.
 *
 * <p>Boots the client-side bridge: opens the shared-memory mapping created by the
 * Stellar Blade UE4SS mod, wires up the collision field, damage bridge, and actor proxies,
 * then registers the per-frame tick + publish callbacks.
 */
public final class StellarCraftMod implements ClientModInitializer {
    public static final String MOD_ID = "stellarcraft";

    private static SharedMemory memory;
    private static SharedMemory candidate;
    private static boolean duplicateWarning;
    private static RingBuffer inputRing;
    private static RingBuffer eventRing;
    private static RingBuffer collisionRing;

    @Override
    public void onInitializeClient() {
        // Packaged Windows launchers can virtualize TEMP into an AF_UNIX-incompatible
        // path. Netty's selector needs an actual local socket directory before world login.
        if (System.getProperty("jdk.net.unixdomain.tmpdir") == null) {
            try {
                var sockets = Minecraft.getInstance().gameDirectory.toPath().resolve("stellarcraft-sockets");
                java.nio.file.Files.createDirectories(sockets);
                System.setProperty("jdk.net.unixdomain.tmpdir", sockets.toAbsolutePath().toString());
            } catch (java.io.IOException e) {
                System.err.println("[StellarCraft] Cannot prepare Java socket directory: " + e);
            }
        }
        EntityRegistry.register();
        dev.stellarcraft.world.NativeEntities.init();
        dev.stellarcraft.world.NativeActors.init();

        // Auto-launch Stellar Blade in the background so the bridge comes up on its own.
        SBAutoLauncher.maybeLaunch();

        // Tick handler on the client side.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            // Handshake runs even on the title screen (before entering a world), so the
            // bridge comes up as soon as Stellar Blade has created the mapping.
            ensureConnected();

            if (memory == null) return;

            // Write MC heartbeat + pid so SB sees us as alive. Done before the world check:
            // the overlay's process-alive poll must see MC even while it sits on the menu.
            memory.setMcHeartbeat(NativeLink.tickCount());

            // F4 handoff works from the title screen too. The player often presses F4 while
            // MC is still on the main menu, so the control switch + the McState publish that
            // confirms it to Stellar Blade must not be gated behind "a world is loaded".
            tickControlHandoff(mc);

            // Apply input forwarded from Stellar Blade (keys/mouse captured by the overlay
            // and dropped into the input ring).
            //
            // This runs OUTSIDE the world check on purpose. The world check used to wrap it,
            // which meant the title screen / world-select screen could never be driven by the
            // forwarded keyboard -- pressing F4 from the menu took effect on SB's side but MC
            // sat there unresponsive, which read as "F4 does nothing until I load a save".
            // InputBridge itself decides what is applicable via the SB-published authority
            // bits (see ControlStateMachine.hasInputAuthority / Proto.mayEdit).
            InputBridge.tick(mc);

            // Everything below needs a live world and a local player.
            dev.stellarcraft.world.BlockBridge.tick(mc);
            dev.stellarcraft.world.NativeEntities.clientTick();
            dev.stellarcraft.world.NativeQa.tick(mc);
            if (mc.level == null) return;

            if (ClientBridge.get() != null) {
                // Advance world-dependent bridge work (teleport, actor reconciliation, skin).
                ClientBridge.get().tick();
            }

            // Drain SB's sampled ground so the pick raycast can land on it (this is
            // what lets the player place blocks while standing in Stellar Blade).
            TerrainRing.tick(mc);

            // If F4 was pressed on the title screen we did not grab the mouse there (the
            // menu needs the cursor). Re-establish the grab now that a world is loaded.
            ControlStateMachine.maintainGrab(mc);

            // The camera type is deliberately NOT forced here: while Steve owns control
            // the player may press F5 to cycle first / third-back / third-front exactly
            // like vanilla Minecraft. ControlStateMachine.switchToSteve only sets the
            // initial *first-person* view; after that the player decides.
        });
    }

    /** Reads the SB-published authority flags and drives the character/input state machine. */
    private static void tickControlHandoff(Minecraft mc) {
        // F4 is detected on the Stellar Blade side (where the player presses it) and published
        // as SbFlags.CONTROL_STEVE. Stellar Blade also publishes the INPUT-authority bits
        // (EDIT / NATIVE_UI) that it computes from the real foreground state. We adopt both:
        // character ownership decides who the avatar is, input authority decides whether
        // forwarded events are applied at all. Keeping them separate is what lets F4 work
        // instantly from the title screen without making Stellar Blade's menus unclickable.
        int sbFlags = 0;
        if (memory != null) {
            SharedMemory.SbStateSnapshot sb = memory.readSbState();
            if (sb != null) sbFlags = sb.flags;
        }
        ControlStateMachine.tick(mc, sbFlags);
    }

    private static void ensureConnected() {
        if (!NativeLink.isLoaded()) return;
        if (memory != null && memory.isSbReady()) return;
        // Retry opening until SB is up.
        if(candidate==null || !candidate.isSbReady()) candidate=NativeLink.open(Proto.K_MAPPING_NAME, Proto.MAPPING_BYTES);
        SharedMemory m = candidate;
        if (m != null && m.isSbReady()) {
            if(!m.claimMcPid(NativeLink.pid())) {
                if(!duplicateWarning) {
                    duplicateWarning=true;
                    System.out.println("[StellarCraft] Bridge already owned by another running Minecraft instance; waiting.");
                }
                return;
            }
            duplicateWarning=false;
            memory = m;
            // Complete the handshake: write our PID + heartbeat so SB sees MC as alive.
            memory.setMcHeartbeat(NativeLink.tickCount());
            initRings();
            ClientBridge.start(memory);
            CollisionFieldHolder.FIELD = ClientBridge.get().collisionField();

            // FrameExporter now runs from GameRendererMixin (render RETURN, on the render
            // thread, after the HUD is drawn). Give it the mapping here.
            FrameExporter.setMemory(memory);

            // Keep Minecraft running while its window is unfocused, so Steve can move,
            // open his inventory, and interact with blocks while the player is looking at
            // Stellar Blade. This mirrors SkyCraft's WindowMixin + pauseOnLostFocus=false.
            WindowFocusBridge.setBridgeActive(true);
            Minecraft.getInstance().options.pauseOnLostFocus = false;

            System.out.println("[StellarCraft] Linked to Stellar Blade! (sbPid=" + memory.sbPid()
                + ", version=" + memory.version() + ")");
        }
    }

    private static void initRings() {
        inputRing = new RingBuffer(
            memory.inputRing(),
            Proto.INPUT_HEAD_OFF,
            Proto.INPUT_TAIL_OFF,
            Proto.INPUT_DATA_OFF,
            (int) Proto.INPUT_BYTES);

        eventRing = new RingBuffer(
            memory.eventRing(),
            Proto.EVENT_HEAD_OFF,
            Proto.EVENT_TAIL_OFF,
            Proto.EVENT_DATA_OFF,
            (int) Proto.EVENT_BYTES);

        collisionRing = new RingBuffer(
            memory.collisionRing(),
            Proto.COLLISION_HEAD_OFF,
            Proto.COLLISION_TAIL_OFF,
            Proto.COLLISION_DATA_OFF,
            (int) Proto.COLLISION_DATA_BYTES);

        // Input forwarded from Stellar Blade (overlay keyboard hook) -> MC key bindings.
        InputBridge.init(inputRing);

        // SB ground samples (collision ring) -> VirtualTerrain, for the pick raycast.
        TerrainRing.init(collisionRing);

        DamageBridge.init(eventRing);

        // TNT / explosion damage forwarding. ExplosionMixin reuses the same event ring as
        // DamageBridge (both write kEv* records into the one MC->SB event channel).
        // Without this assignment the sink stays null and every explosion hit was silently
        // dropped -- TNT looked like it had no effect on Stellar Blade enemies.
        //
        // NOTE: the sink deliberately lives in dev.stellarcraft.world, NOT in
        // dev.stellarcraft.world.mixin. Anything in the mixin package can only be loaded
        // through the Mixin transformer, so referencing it from here would abort startup
        // with IllegalClassLoadError ("... cannot be referenced directly").
        dev.stellarcraft.world.ExplosionDamageSink.eventRing = eventRing;

        // Block mirroring: MC -> SB "a block changed" records, so Stellar Blade can spawn a
        // real collidable actor for each placed block instead of us drawing it as an overlay
        // pixel. Same channel, same reason for living outside the mixin package.
        dev.stellarcraft.world.BlockBridge.eventRing = eventRing;
    }
}
