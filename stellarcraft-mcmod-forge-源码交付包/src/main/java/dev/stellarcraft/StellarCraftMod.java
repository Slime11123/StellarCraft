package dev.stellarcraft;

import dev.stellarcraft.client.ClientBridge;
import dev.stellarcraft.link.RingBuffer;
import dev.stellarcraft.link.SharedMemory;
import dev.stellarcraft.world.DamageBridge;
import dev.stellarcraft.world.EntityRegistry;
import dev.stellarcraft.world.EntityCollisionMixin;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * StellarCraft Forge mod entrypoint.
 *
 * <p>Boots the client-side bridge: opens the shared-memory mapping created by the
 * Stellar Blade UE4SS mod, wires up the collision field, damage bridge, and actor proxies,
 * then registers the per-frame tick + publish callbacks.
 */
@Mod(StellarCraftMod.MOD_ID)
public final class StellarCraftMod {
    public static final String MOD_ID = "stellarcraft";

    private static SharedMemory memory;
    private static RingBuffer inputRing;
    private static RingBuffer eventRing;
    private static RingBuffer collisionRing;

    public StellarCraftMod() {
        // Subscribe the DeferredRegister<EntityType> to the mod bus.
        EntityRegistry.register(FMLJavaModLoadingContext.get().getModEventBus());
        // Register the client-setup handler on the mod bus (runs during client init).
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);
        // Tick handler on the Forge (game) bus.
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        // Defer actual bridge startup to the first tick, once the level exists.
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        // Only act on the end-of-tick phase.
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return;

        ensureConnected();
        if (ClientBridge.get() != null) ClientBridge.get().tick();
    }

    private static void ensureConnected() {
        if (!NativeBridge.isLoaded()) return;
        if (memory != null && memory.isSbReady()) return;
        // Retry opening until SB is up.
        SharedMemory m = NativeBridge.open(SharedMemory.Proto.K_MAPPING_NAME, SharedMemory.Proto.MAPPING_BYTES);
        if (m != null && m.isSbReady()) {
            memory = m;
            initRings();
            ClientBridge.start(memory);
            EntityCollisionMixin.CollisionFieldHolder.FIELD = ClientBridge.get().collisionField();
        }
    }

    private static void initRings() {
        inputRing = new RingBuffer(
            memory.inputRing(),
            SharedMemory.Proto.INPUT_HEAD_OFF,
            SharedMemory.Proto.INPUT_TAIL_OFF,
            SharedMemory.Proto.INPUT_DATA_OFF,
            (int) SharedMemory.Proto.INPUT_BYTES);

        eventRing = new RingBuffer(
            memory.eventRing(),
            SharedMemory.Proto.EVENT_HEAD_OFF,
            SharedMemory.Proto.EVENT_TAIL_OFF,
            SharedMemory.Proto.EVENT_DATA_OFF,
            (int) SharedMemory.Proto.EVENT_BYTES);

        collisionRing = new RingBuffer(
            memory.collisionRing(),
            SharedMemory.Proto.COLLISION_HEAD_OFF,
            SharedMemory.Proto.COLLISION_TAIL_OFF,
            SharedMemory.Proto.COLLISION_DATA_OFF,
            (int) SharedMemory.Proto.COLLISION_DATA_BYTES);

        DamageBridge.init(eventRing);
    }
}
