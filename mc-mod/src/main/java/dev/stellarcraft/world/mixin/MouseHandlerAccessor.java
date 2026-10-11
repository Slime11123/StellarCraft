package dev.stellarcraft.world.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link MouseHandler}'s private input entry points so forwarded mouse events can be
 * injected exactly where the real GLFW callbacks land.
 *
 * <p>{@code onMove} / {@code onPress} / {@code onScroll} are the same methods GLFW's
 * callbacks invoke. Replaying captured events through them means Minecraft's own GUI cursor
 * logic, screen click handling and world click handling all behave as if the real mouse had
 * moved — no reimplementation of any of it.
 *
 * <p>Official (Mojang) mappings are in use, so these are already named {@code onMove} /
 * {@code onPress} / {@code onScroll} (the same as Yarn here, unlike KeyboardHandler).
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {

    @Invoker("onMove")
    void stellarcraft$onMove(long window, double x, double y);

    @Invoker("onPress")
    void stellarcraft$onPress(long window, int button, int action, int mods);

    @Invoker("onScroll")
    void stellarcraft$onScroll(long window, double xOffset, double yOffset);
}
