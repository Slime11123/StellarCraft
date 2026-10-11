package dev.stellarcraft.client;

import net.minecraft.client.Minecraft;

/**
 * Character-ownership state machine.
 *
 * <p>Two states:
 * <ul>
 *   <li><b>EVELYN</b> — Stellar Blade's protagonist is the avatar.</li>
 *   <li><b>STEVE</b> — the Minecraft player is the avatar; MC renders it and SB hides Eve.</li>
 * </ul>
 *
 * <p><b>Character ownership is NOT input ownership.</b> This machine tracks only the former —
 * the F4 latch published by Stellar Blade as {@code SbFlags.CONTROL_STEVE}. It is valid at
 * any moment, including while MC sits on its title screen: pressing F4 must take effect
 * immediately, without waiting for a world to be loaded.
 *
 * <p>Whether forwarded keyboard/mouse events are <em>applied</em> is a separate question,
 * answered by {@link Proto#mayEdit(int)} from the SB-published {@code EDIT} bit and the two
 * gates ({@code MENU_OPEN}, {@code NATIVE_UI}). See {@link InputBridge}. Conflating the two
 * was the source of every "fix one thing, break another" regression in this handoff.
 *
 * <p>The F4 level is read directly with no edge detection, so "press once = Steve, press
 * again = Eve" is race-free and each press maps to exactly one switch.
 */
public final class ControlStateMachine {
    public enum Control { EVELYN, STEVE }

    private static volatile Control current = Control.EVELYN;

    /** Set true while SB grants MC input authority (SbFlags.EDIT and neither gate set). */
    private static volatile boolean inputAuthority;

    private ControlStateMachine() {}

    public static Control current() { return current; }
    public static boolean isSteve() { return current == Control.STEVE; }

    /** True while MC may actually replay forwarded input. Drives the mixin cancellation. */
    public static boolean hasInputAuthority() { return inputAuthority; }

    /**
     * Called every client tick with the SB-published flags. Adopts whatever character level
     * Stellar Blade reports; the level is persistent state, so a per-frame write can never
     * clobber a transition.
     */
    public static Control tick(Minecraft mc, int sbFlags) {
        boolean sbControlSteve = (sbFlags & dev.stellarcraft.link.Proto.SbFlags.CONTROL_STEVE) != 0;
        inputAuthority = dev.stellarcraft.link.Proto.mayEdit(sbFlags);

        if (sbControlSteve && current != Control.STEVE) {
            switchToSteve(mc);
        } else if (!sbControlSteve && current != Control.EVELYN) {
            switchToEvelyn(mc);
        }
        return current;
    }

    private static void switchToSteve(Minecraft mc) {
        current = Control.STEVE;
        System.out.println("[StellarCraft] Character ownership -> STEVE (MC mode)");

        // Re-publish the block set. Stellar Blade mirrors every placed block as a real actor in
        // its own world (so its characters actually collide with it), and it may have been
        // started fresh since those blocks were placed -- a re-sync is the only way it learns
        // about blocks that already existed. Idempotent on the receiving side.
        dev.stellarcraft.world.BlockBridge.syncAll();
        // NOTE: no teleport to Eve's anchor and no forced first-person camera here.
        //
        // Both used to be done on the switch, and both were wrong:
        //   * teleporting hijacked the MC player's position on every F4 press, including
        //     while MC was sitting in a menu with no world;
        //   * forcing the camera type fought the F5 perspective cycle the player expects.
        // Climbing into Stellar Blade's world is a separate, deliberate action (F4 pressed
        // while a world is loaded, in-game). Here we only take ownership of the character.
        //
        // The camera type is seeded to first person so the switch *looks* right, but only
        // when a world actually exists (on the title screen there is no camera to set).
        if (mc.level != null && mc.options != null) {
            mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
        }
    }

    private static void switchToEvelyn(Minecraft mc) {
        current = Control.EVELYN;
        System.out.println("[StellarCraft] Character ownership -> EVELYN (SB mode)");
        if (mc.mouseHandler != null) {
            mc.mouseHandler.releaseMouse();
        }
    }

    /**
     * Re-asserts the mouse grab for Steve once a world is actually available. F4 can be
     * pressed on the title screen (where there is nothing to grab), so the grab has to be
     * re-established after the player enters a world.
     *
     * <p>Only while MC holds input authority: while Stellar Blade's own UI is up the player
     * needs the real cursor to click it.
     */
    public static void maintainGrab(Minecraft mc) {
        if (current != Control.STEVE) return;
        if (!inputAuthority) return;
        if (mc.level == null || mc.screen != null) return;
        if (mc.mouseHandler != null && !mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.grabMouse();
        }
    }
}
