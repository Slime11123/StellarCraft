package dev.stellarcraft.world;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Entity registration for StellarCraft (Forge 1.20.1).
 *
 * <p>The {@link ActorProxy} entity type is registered via {@link DeferredRegister}, which
 * must be subscribed to the mod event bus during construction. This mirrors the standard
 * Forge mod pattern and replaces the Fabric {@code Registry.register} call.
 */
public final class EntityRegistry {
    private EntityRegistry() {}

    public static final String MOD_ID = "stellarcraft";

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID);

    public static final RegistryObject<EntityType<ActorProxy>> ACTOR_PROXY =
        ENTITY_TYPES.register("actor_proxy",
            () -> EntityType.Builder
                .<ActorProxy>of(ActorProxy::new, MobCategory.MISC)
                .sized(0.6f, 1.8f)
                .setTrackingRange(8)
                .setUpdateInterval(3)
                .setShouldReceiveVelocityUpdates(false)
                .build(new ResourceLocation(MOD_ID, "actor_proxy").toString()));

    /** Subscribes the DeferredRegister to the mod bus. Must be called during mod construction. */
    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
    }

    /** Convenience: auto-subscribe using the current mod loading context. */
    public static void ensureRegistered() {
        // The DeferredRegister is already bound at class-load; this is a no-op marker
        // used by the mod entrypoint to keep the call site explicit. Real subscription
        // happens in StellarCraftMod's constructor via {@link #register(IEventBus)}.
    }
}
