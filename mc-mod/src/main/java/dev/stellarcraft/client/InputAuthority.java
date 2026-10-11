package dev.stellarcraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;

/**
 * The single place that answers "does Minecraft currently own keyboard/mouse input?".
 *
 * <p>This is a <em>plain</em> class (NOT in the mixin package, which is protected by the Mixin
 * framework), because the mixins need to reference it.
 *
 * <p>Input ownership is deliberately a different question from character ownership
 * ({@link ControlStateMachine#isSteve()}):
 *
 * <ul>
 *   <li><b>Character ownership</b> — who the avatar is. F4 latch, valid any time.</li>
 *   <li><b>Input ownership</b> — who receives keystrokes. Needs Stellar Blade to have granted
 *       authority ({@code SbFlags.EDIT}) AND MC to be at a point where replaying input makes
 *       sense.</li>
 * </ul>
 *
 * <p>While MC is stuck on a loading screen we suppress our own takeover so vanilla loading
 * behaviour is not disturbed.
 */
public final class InputAuthority {

    private InputAuthority() {}

    /** True while forwarded input should be replayed AND MC's own GLFW handlers suppressed. */
    public static boolean mcOwnsInput() {
        if (!ControlStateMachine.isSteve()) return false;
        if (!ControlStateMachine.hasInputAuthority()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return false;
        // Do not hijack the loading screen; vanilla wants to drive it.
        if (mc.screen instanceof LevelLoadingScreen) return false;
        return true;
    }
}
