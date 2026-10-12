package dev.stellarcraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.stellarcraft.link.Proto;
import dev.stellarcraft.link.RingBuffer;
import dev.stellarcraft.render.FrameExporter;
import dev.stellarcraft.world.mixin.KeyboardHandlerAccessor;
import dev.stellarcraft.world.mixin.MouseHandlerAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Consumes the SB-&gt;MC input ring and replays the events through Minecraft's own entry
 * points.
 *
 * <p>While the player is looking at Stellar Blade, Minecraft's window is in the background so
 * its GLFW event pump never sees the keystrokes. The in-process StellarCraft ReShade add-on
 * captures them with low-level keyboard/mouse hooks and writes them into the {@code input ring}. This class
 * drains that ring every client tick and replays each event at exactly the same methods
 * Minecraft's own GLFW callbacks invoke, so all of MC's normal behaviour — gameplay key binds
 * AND screen/GUI handling, including typing — works unmodified.
 *
 * <p>Event wire format (little-endian, mirror of {@code InputType} in the protocol):
 * <pre>
 *   u16 type
 *   u16 code      // virtual-key code for kInKey, button id for kInMouseButton
 *   i32 a         // 1/0 down for keys &amp; buttons; wheel notches * 120 for kInScroll;
 *                 //   cursor x for kInCursor
 *   i32 b         // cursor y for kInCursor, else reserved
 * </pre>
 * That is 12 bytes per event.
 *
 * <p><b>Authority.</b> Events are applied only while Stellar Blade grants input authority:
 * {@code SbFlags.EDIT} set, and neither {@code MENU_OPEN} nor {@code NATIVE_UI} set. That is
 * the same gate SekiroCraft uses
 * ({@code (flags &amp; EDIT) != 0 &amp;&amp; (flags &amp; (MENU | NATIVE_UI)) == 0}). While the gate is
 * closed we still DRAIN the ring, so a backlog cannot burst in when ownership returns.
 *
 * <p><b>Replay ordering.</b> Key events go through {@code KeyboardHandler.keyPress}, which is
 * the same entry point the GLFW callback uses — verifiably a single funnel for BOTH
 * behaviours: it dispatches to {@code Screen.keyPressed} when a screen is open (so ESC/E
 * close a GUI and typing works) and it drives {@code KeyMapping.set/click} when no screen is
 * open (so WASD/hotbar/attack use work). We therefore do <b>not</b> touch {@code KeyMapping}
 * on a press: doing so used to queue a duplicate click and made the inventory impossible to
 * close (see the IN_KEY case). Mouse events go through {@code MouseHandler} exactly as GLFW
 * would deliver them.
 */
public final class InputBridge {
    private static final int EVENT_BYTES = 12;

    private static RingBuffer ring;
    private static final ByteBuffer scratch = ByteBuffer.allocateDirect(EVENT_BYTES).order(ByteOrder.LITTLE_ENDIAN);

    /** Set while we are replaying, so our own mixins let the replayed events through. */
    public static volatile boolean replaying;

    /** Held virtual-key -> GLFW key, so a stale event cannot release the wrong key. */
    private static final int[] pressed = new int[256];

    /** Mouse buttons believed held from forwarded input, indexed by GLFW button id. */
    private static final boolean[] mouseHeld = new boolean[8];

    private InputBridge() {}

    public static void init(RingBuffer inputRing) {
        ring = inputRing;
    }

    /** Called once per client tick. Drains and (when authorised) applies pending events. */
    public static void tick(Minecraft mc) {
        if (ring == null) return;

        boolean authorised = ControlStateMachine.hasInputAuthority();

        while (!ring.isEmpty()) {
            long cursor = ring.tail();
            ring.readBytes(cursor, EVENT_BYTES, scratch);
            ring.setTail(cursor + EVENT_BYTES);

            int type = scratch.getShort(0) & 0xFFFF;
            int code = scratch.getShort(2) & 0xFFFF;
            int a = scratch.getInt(4);
            int b = scratch.getInt(8);

            if (!authorised) continue; // drain but do not apply

            replaying = true;
            try {
                apply(mc, type, code, a, b);
            } catch (Throwable t) {
                // A GUI hiccup must never kill the tick loop.
                System.out.println("[StellarCraft] input replay failed: " + t);
            } finally {
                replaying = false;
            }
        }

        // Keep MC's key-binding levels in sync with what we believe is held. MC's own
        // KeyMapping ticking reads these levels; without this a key we hold via the screen
        // dispatcher never reaches gameplay (and vice versa).
        if (!authorised) {
            // Ownership left us: make sure nothing stays stuck down.
            releaseAll();
        }
        if (authorised && mouseHeld[1] && mc.screen == null)
            dev.stellarcraft.world.BlockBridge.notePlacement();
    }

    private static void apply(Minecraft mc, int type, int code, int a, int b) {
        switch (type) {
            case Proto.IN_KEY: {
                int glfw = vkToGlfw(code);
                if (glfw <= 0) return;
                boolean down = a != 0;
                int action = down ? InputConstants.PRESS : InputConstants.RELEASE;

                // Screens first: this is what makes ESC close the inventory, E close the
                // inventory screen, typing work in chat, and -- critically -- what lets the
                // world-select screen respond to the keyboard after F4 from the title screen.
                // keyPress is public in the official mappings; charTyped needs the invoker.
                long window = mc.getWindow().getWindow();
                record(code,glfw,down);
                mc.keyboardHandler.keyPress(window, glfw, code & 0xFF, action, modifiers());

                // Deliberately NOT calling KeyMapping.set/click here -- keyPress already did it.
                //
                // Disassembly of KeyboardHandler.keyPress confirms the vanilla contract: when
                // `mc.screen == null` a press runs `KeyMapping.set(key, true)` followed by
                // `KeyMapping.click(key)`, and a release runs `KeyMapping.set(key, false)`.
                // Repeating it here queued a SECOND click for every forwarded key, and that
                // duplicate is what made the inventory impossible to close:
                //
                //   press E   -> click #1 opens InventoryScreen (click #2 makes it open twice)
                //   press E   -> screen.keyPressed() closes it, and because mc.screen is null
                //                again by the time this code ran, the duplicate queued ANOTHER
                //                click for keyInventory
                //   next tick -> Minecraft.handleKeybinds() consumes it -> the inventory
                //                instantly re-opens. "背包关不掉."
                //
                // The queue survives screens because handleKeybinds() only runs while no
                // screen is open. Q dropped two items and F swapped the offhand twice for
                // exactly the same reason.
                record(code, glfw, down);

                // One thing vanilla does NOT cover: its `KeyMapping.set(key, false)` sits
                // inside the same `mc.screen == null` guard, so a release that arrives while a
                // screen is up is dropped and the binding stays stuck down forever (press E to
                // open, release while the inventory is open -> keyInventory held). Clearing the
                // level here is a supplement, never a duplicate: it queues nothing.
                if (!down) {
                    KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(glfw), false);
                }
                break;
            }
            case Proto.IN_MOUSE_BTN: {
                long window = mc.getWindow().getWindow();
                // Button ids arrive in GLFW numbering: LEFT=0, RIGHT=1, MIDDLE=2, X1=3, X2=4
                // (see mouse_hook in reshade-addon/StellarCraft.cpp). Vanilla binds keyAttack -> 0, keyUse -> 1,
                // keyPickItem -> 2, which is exactly the numbering Minecraft's own event
                // pump uses, so it can be replayed verbatim.
                int btn = clamp(code, 0, 7);
                boolean down = a != 0;
                int action = down ? InputConstants.PRESS : InputConstants.RELEASE;
                if (btn == 1 && down && mc.screen == null)
                    dev.stellarcraft.world.BlockBridge.notePlacement();
                if (mc.mouseHandler instanceof MouseHandlerAccessor acc) {
                    acc.stellarcraft$onPress(window, btn, action, modifiers());
                }

                // Vanilla owns the press and its single queued click; track only its release.
                InputConstants.Key mouse = InputConstants.Type.MOUSE.getOrCreate(btn);
                recordMouse(btn, mouse, down);

                // Gameplay key binds are only meaningful with no screen open; with a GUI up
                // the click belongs to the GUI and MouseHandler.onPress has already routed
                // it there (click events), so we leave the levels alone.
                // onPress already sets the mapping and queues exactly one click.
                // A second click here places two blocks for a single physical press.
                if (!down) KeyMapping.set(mouse, false);
                break;
            }
            case Proto.IN_CURSOR: {
                if (mc.screen != null && mc.mouseHandler instanceof MouseHandlerAccessor acc) {
                    // a,b arrive in EXPORTED frame pixels: the add-on undoes the letterbox
                    // scale while keeping GLFW's top-down cursor coordinates (see publish_cursor
                    // in StellarCraft.cpp). MouseHandler works in the render target's own pixel
                    // space though, and the export is now downscaled on the GPU before
                    // readback (a 4K target is capped so the whole path stays cheap), so the
                    // coordinates have to be scaled back up. exportScale() is 1.0 whenever no
                    // downscale is in effect.
                    float k = FrameExporter.exportScale();
                    acc.stellarcraft$onMove(mc.getWindow().getWindow(),
                                            (double) a * (double) k, (double) b * (double) k);
                }
                break;
            }
            case Proto.IN_SCROLL: {
                if (mc.mouseHandler instanceof MouseHandlerAccessor acc) {
                    acc.stellarcraft$onScroll(mc.getWindow().getWindow(), 0.0, a / 120.0);
                }
                break;
            }
            case Proto.IN_MOUSE_DELTA: {
                // MC is unfocused, so vanilla's MouseHandler.turnPlayer never consumes its
                // accumulators. Apply the same sensitivity curve to raw motion explicitly.
                // Player.turn performs vanilla's final 0.15-degree factor and pitch clamp.
                if (mc.player != null && mc.screen == null && mc.options != null) {
                    double sensitivity = mc.options.sensitivity().get() * 0.6 + 0.2;
                    double scale = sensitivity * sensitivity * sensitivity * 8.0;
                    double invert = mc.options.invertYMouse().get() ? -1.0 : 1.0;
                    mc.player.turn(a * scale, b * scale * invert);
                }
                break;
            }
            case Proto.IN_TEXT: {
                if (mc.screen != null && a >= 32 && Character.isValidCodePoint(a)
                    && mc.keyboardHandler instanceof KeyboardHandlerAccessor acc)
                    acc.stellarcraft$charTyped(mc.getWindow().getWindow(),a,modifiers());
                break;
            }
            case Proto.IN_LOOK_SYNC: {
                if (mc.player != null) {
                    mc.player.setYRot(a / 1000.0f);
                    mc.player.setXRot(b / 1000.0f);
                    mc.player.yRotO = mc.player.getYRot();
                    mc.player.xRotO = mc.player.getXRot();
                }
                break;
            }
            case Proto.IN_RELEASE_ALL: {
                releaseAll();
                break;
            }
            case Proto.IN_OPEN_MENU: {
                if (mc.screen == null && mc.level != null) {
                    mc.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
                }
                break;
            }
            default:
                break;
        }
    }

    /** Records a key's GLFW code so a later release closes the same key even if VK differs. */
    private static void record(int vk, int glfw, boolean down) {
        if (vk < 0 || vk >= pressed.length) return;
        if (down) {
            if (pressed[vk] == 0) pressed[vk] = glfw;
        } else {
            pressed[vk] = 0;
        }
    }

    /** Tracks forwarded mouse buttons by id so {@link #releaseAll()} can un-stick them. */
    private static void recordMouse(int btn, InputConstants.Key key, boolean down) {
        if (btn < 0 || btn >= mouseHeld.length) return;
        mouseHeld[btn] = down;
    }

    /** True while the given GLFW key is believed held from forwarded input. */
    public static boolean isHeld(int glfwKey) {
        for (int k : pressed) if (k != 0 && k == glfwKey) return true;
        return false;
    }

    public static boolean forwardsModifiers() {
        return ControlStateMachine.hasInputAuthority();
    }

    /** Clears every currently-held key/button, e.g. when input ownership leaves MC. */
    private static void releaseAll() {
        Minecraft mc = Minecraft.getInstance();
        for (int vk = 8; vk < pressed.length; vk++) {
            if (pressed[vk] == 0) continue;
            InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(pressed[vk]);
            KeyMapping.set(key, false);
            pressed[vk] = 0;
        }
        // Mouse buttons we had held down: a stuck keyUse would keep eating items, and a
        // stuck keyAttack would keep mining, after control has already left Steve.
        for (int btn = 0; btn < mouseHeld.length; btn++) {
            if (!mouseHeld[btn]) continue;
            KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(btn), false);
            mouseHeld[btn] = false;
        }
        if (mc.options == null) return;
        KeyMapping[] all = {
            mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
            mc.options.keyJump, mc.options.keyShift, mc.options.keySprint,
            mc.options.keyAttack, mc.options.keyUse, mc.options.keyPickItem,
        };
        for (KeyMapping km : all) {
            // setDown(false) clears the actual mapping (honouring a rebind), rather than
            // only releasing the default key -- otherwise a custom binding stays stuck.
            if (km != null) {
                km.setDown(false);
                KeyMapping.set(km.getDefaultKey(), false);
                while (km.consumeClick()) { /* Discard input queued before F4 handoff. */ }
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int modifiers() {
        int result=0;
        for (int key:pressed) {
            if (key==340 || key==344) result|=1;
            if (key==341 || key==345) result|=2;
            if (key==342 || key==346) result|=4;
        }
        return result;
    }

    /** Best-effort character for a virtual key, used when a text field has focus. */
    private static char vkToChar(int vk) {
        if (vk >= 0x41 && vk <= 0x5A) return (char) ('a' + (vk - 0x41));
        if (vk >= 0x30 && vk <= 0x39) return (char) vk;
        if (vk == 0x20) return ' ';
        switch (vk) {
            case 0xBA: return ';';
            case 0xBB: return '=';
            case 0xBC: return ',';
            case 0xBD: return '-';
            case 0xBE: return '.';
            case 0xBF: return '/';
            case 0xC0: return '`';
            case 0xDB: return '[';
            case 0xDC: return '\\';
            case 0xDD: return ']';
            case 0xDE: return '\'';
            default: return 0;
        }
    }

    /**
     * Translates a Windows virtual-key code into the GLFW keysym Minecraft uses.
     *
     * <p>For letters (VK 0x41-0x5A), digits (VK 0x30-0x39) and space (VK 0x20) the two
     * numbering schemes already agree, so those pass straight through. Navigation and
     * modifier keys differ and are mapped explicitly.
     */
    private static int vkToGlfw(int vk) {
        switch (vk) {
            case 0xA0: return 340;
            case 0xA1: return 344;
            case 0xA2: return 341;
            case 0xA3: return 345;
            case 0xA4: return 342;
            case 0xA5: return 346;
            case 0xBA: return 59;
            case 0xBB: return 61;
            case 0xBC: return 44;
            case 0xBD: return 45;
            case 0xBE: return 46;
            case 0xBF: return 47;
            case 0xC0: return 96;
            case 0xDB: return 91;
            case 0xDC: return 92;
            case 0xDD: return 93;
            case 0xDE: return 39;
            case 0xE7: return -1;
            case 0x10: return 340; // VK_SHIFT    -> GLFW_KEY_LEFT_SHIFT
            case 0x11: return 341; // VK_CONTROL  -> GLFW_KEY_LEFT_CONTROL
            case 0x12: return 342; // VK_MENU(Alt)-> GLFW_KEY_LEFT_ALT
            case 0x1B: return 256; // VK_ESCAPE
            case 0x0D: return 257; // VK_RETURN
            case 0x09: return 258; // VK_TAB
            case 0x08: return 259; // VK_BACK
            case 0x2D: return 260; // VK_INSERT
            case 0x2E: return 261; // VK_DELETE
            case 0x27: return 262; // VK_RIGHT
            case 0x25: return 263; // VK_LEFT
            case 0x28: return 264; // VK_DOWN
            case 0x26: return 265; // VK_UP
            case 0x21: return 266; // VK_PRIOR (Page Up)
            case 0x22: return 267; // VK_NEXT  (Page Down)
            case 0x24: return 268; // VK_HOME
            case 0x23: return 269; // VK_END
            case 0x14: return 280; // VK_CAPITAL
            default:
                if (vk >= 0x70 && vk <= 0x87) return 290 + (vk - 0x70); // F1..F24
                return vk; // A-Z, 0-9, space, punctuation agree
        }
    }

    /** Debug helper retained for logging. */
    public static String describe(int type, int code) {
        return "type=" + type + " code=" + code;
    }
}
