package dev.stellarcraft.world.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link KeyboardHandler}'s private character-typing entry point so forwarded text
 * input can be injected exactly where GLFW's char callback lands.
 *
 * <p>Official (Mojang) mappings are in use, so the method is {@code charTyped} — NOT Yarn's
 * {@code onChar}. {@code keyPress} is public and needs no invoker (see
 * {@code InputBridge.apply}).
 */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerAccessor {

    @Invoker("charTyped")
    void stellarcraft$charTyped(long window, int codepoint, int modifiers);
}
