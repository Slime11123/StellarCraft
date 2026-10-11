package dev.stellarcraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * The dual-character control state machine for milestone M1.
 *
 * <p>Two states:
 * <ul>
 *   <li><b>EVELYN</b> — Stellar Blade's protagonist controls the game; the Minecraft
 *       client is frozen (its simulation is paused, input is dropped).</li>
 *   <li><b>STEVE</b> — the Minecraft player takes over; the SB protagonist enters a
 *       wait/follow AI state, and MC's full physics/inventory/block gameplay is live.</li>
 * </ul>
 *
 * <p>The F4 hotkey toggles between them. On the wire this is expressed by a control bit
 * in {@code SbState.flags} / {@code McState.flags}, plus explicit switch events so both
 * sides agree on the transition timing.
 */
public final class ControlStateMachine {
    public enum Control { EVELYN, STEVE }

    private static Control current = Control.EVELYN;
    private static boolean f4WasDown = false;

    private ControlStateMachine() {}

    public static Control current() { return current; }
    public static boolean isSteve() { return current == Control.STEVE; }

    /**
     * Called every client tick with the current SB state flags. Detects the F4 hotkey and
     * performs the control handoff. Returns the new control mode.
     */
    public static Control tick(Minecraft mc, int sbFlags, boolean f4Down) {
        boolean pressed = f4Down && !f4WasDown;
        f4WasDown = f4Down;

        if (pressed) {
            toggle(mc);
        }
        return current;
    }

    private static void toggle(Minecraft mc) {
        if (current == Control.EVELYN) {
            switchToSteve(mc);
        } else {
            switchToEvelyn(mc);
        }
    }

    private static void switchToSteve(Minecraft mc) {
        current = Control.STEVE;
        LocalPlayer p = mc.player;
        if (p != null) {
            // Teleport Steve to Eve's current position so the switch is seamless.
            // The actual SB player position comes from SbState; ClientBridge caches it.
            double[] anchor = ClientBridge.lastSbAnchor();
            if (anchor != null) {
                p.teleportTo(anchor[0], anchor[1], anchor[2]);
            }
        }
        // Grab mouse (MC window told it's focused so it doesn't pause).
        if (mc.mouseHandler != null) {
            mc.mouseHandler.grabMouse();
        }
        // TODO: signal SB to freeze Eve + hide SB HUD (write control bit / switch event).
    }

    private static void switchToEvelyn(Minecraft mc) {
        current = Control.EVELYN;
        if (mc.mouseHandler != null) {
            mc.mouseHandler.releaseMouse();
        }
        // TODO: signal SB to unfreeze Eve + restore SB HUD.
    }
}
