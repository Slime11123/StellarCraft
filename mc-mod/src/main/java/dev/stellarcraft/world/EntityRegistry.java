package dev.stellarcraft.world;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Entity registration for StellarCraft (Fabric 1.20.1).
 *
 * <p>The {@link ActorProxy} entity type is registered directly into the vanilla
 * {@link BuiltInRegistries#ENTITY_TYPE} registry via {@link Registry#register}.
 */
public final class EntityRegistry {
    private EntityRegistry() {}

    public static final String MOD_ID = "stellarcraft";

    public static final EntityType<ActorProxy> ACTOR_PROXY = Registry.register(
        BuiltInRegistries.ENTITY_TYPE,
        new ResourceLocation(MOD_ID, "actor_proxy"),
        EntityType.Builder.<ActorProxy>of(ActorProxy::new, MobCategory.MISC)
            .sized(0.6f, 1.8f)
            .clientTrackingRange(8)
            .updateInterval(3)
            .build("actor_proxy"));

    /**
     * Default attribute table for the proxy.
     *
     * <p><b>Why this is mandatory, not cosmetic.</b> {@code LivingEntity}'s constructor does
     * {@code new AttributeMap(DefaultAttributes.getSupplier(type))}, and
     * {@code DefaultAttributes.getSupplier} is a bare {@code SUPPLIERS.get(type)} that
     * returns <b>null</b> for any entity type nobody registered attributes for. The very next
     * statement is {@code this.setHealth(this.getMaxHealth())} →
     * {@code getAttributeValue(Attributes.MAX_HEALTH)} → {@code AttributeMap.getValue} →
     * NPE on {@code this.supplier}. So the first proxy spawn kills the game:
     *
     * <pre>
     *   NullPointerException: Cannot invoke "AttributeSupplier.getValue(Attribute)"
     *     because "this.supplier" is null
     *       at AttributeMap.getValue
     *       at LivingEntity.getAttributeValue   &lt;- getMaxHealth()
     *       at LivingEntity.getMaxHealth
     *       at LivingEntity.&lt;init&gt;
     *       at ActorProxy.&lt;init&gt;
     *       at ActorProxy.spawn
     *       at ClientBridge.reconcileActors
     * </pre>
     *
     * <p>Vanilla mobs get their table from their own {@code createAttributes()}; a custom
     * {@code LivingEntity} has to register one itself through
     * {@link FabricDefaultAttributeRegistry}.
     *
     * <p>Values: MAX_HEALTH 20 makes the mirrored health fraction meaningful (the proxy is
     * kept alive by {@code hurt} returning false anyway). MOVEMENT_SPEED is non-zero so any
     * vanilla knockback/push maths stays well-defined; the proxy's actual position is driven
     * externally by {@link ActorProxy#syncFrom}. Knockback resistance and armour are 0 — MC
     * side combat must not attenuate damage before it is forwarded to Stellar Blade.
     */
    private static final AttributeSupplier ACTOR_PROXY_ATTRIBUTES = AttributeSupplier.builder()
        .add(Attributes.MAX_HEALTH, 20.0)
        .add(Attributes.MOVEMENT_SPEED, 0.1)
        .add(Attributes.KNOCKBACK_RESISTANCE, 0.0)
        .add(Attributes.ARMOR, 0.0)
        .add(Attributes.ARMOR_TOUGHNESS, 0.0)
        .build();

    /**
     * Binds the attribute table above to {@link #ACTOR_PROXY}. Called from
     * {@code StellarCraftMod.onInitializeClient}, after the entity type exists.
     */
    public static void register() {
        FabricDefaultAttributeRegistry.register(ACTOR_PROXY, ACTOR_PROXY_ATTRIBUTES);
    }
}
