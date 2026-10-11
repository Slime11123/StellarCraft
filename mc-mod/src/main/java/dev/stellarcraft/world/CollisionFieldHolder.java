package dev.stellarcraft.world;

/**
 * Static holder for the shared {@link CollisionField}.
 *
 * <p>This lives in the plain {@code dev.stellarcraft.world} package (NOT the mixin
 * sub-package) so that {@code StellarCraftMod} and the mixin can both reference it
 * without tripping Mixin's "protected mixin package" check. The mixin reads it here;
 * {@code StellarCraftMod.ensureConnected()} writes it during the handshake.
 */
public final class CollisionFieldHolder {
    public static CollisionField FIELD = null;

    private CollisionFieldHolder() {}
}
