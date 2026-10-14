package dev.stellarcraft.link;

/**
 * StellarCraft shared-memory protocol (Java mirror).
 *
 * This class MUST stay byte-for-byte aligned with
 * {@code protocol/stellarcraft_protocol.h}. If you change the C++ side,
 * change this too and bump {@link #K_VERSION}.
 *
 * All multi-byte values are little-endian. Stellar Blade (the UE4SS C++ mod)
 * creates the memory mapping; Minecraft (this Fabric mod) opens it.
 * All coordinates here are in Minecraft space (Y-up, Z-south).
 */
public final class Proto {
    private Proto() {}

    public static final int K_MAGIC = 0x43544153; // "STAC"
    public static final int K_VERSION = 6;        // packed jump-heavy visual style; layouts unchanged
    public static final String K_MAPPING_NAME = "Local\\StellarCraft_v1";

    /** 100 Unreal Units (1 m) per Minecraft block. */
    public static final double K_UNITS_PER_BLOCK = 100.0;

    // ---- Region offsets -------------------------------------------------------------
    public static final long OFF_HEADER        = 0x0;
    public static final long OFF_SB_STATE      = 0x100;
    public static final long OFF_MC_STATE      = 0x200;
    public static final long OFF_INPUT_RING    = 0x1000;
    public static final long OFF_EVENT_RING    = 0x10000;
    public static final long OFF_COLLISION_RING = 0x20000;
    public static final long COLLISION_RING_BYTES = 32L << 20;
    // Overlay control block (OverlayCtl + 3 OverlaySlotHdr) sits between the collision ring
    // and the overlay pixels. NOTE: OFF_OVERLAY_PIXELS must include this 0x1000 control block
    // (the C++ protocol header has kOffOverlayCtl then kOffOverlayPixels = kOffOverlayCtl + 0x1000).
    public static final long OFF_OVERLAY_CTL = OFF_COLLISION_RING + COLLISION_RING_BYTES;
    public static final long OVERLAY_CTL_BYTES = 0x1000;
    public static final long OFF_OVERLAY_PIXELS = OFF_OVERLAY_CTL + OVERLAY_CTL_BYTES;
    public static final int  MAX_OVERLAY_W = 3840;
    public static final int  MAX_OVERLAY_H = 2160;
    public static final long OVERLAY_SLOT_BYTES = (long) MAX_OVERLAY_W * MAX_OVERLAY_H * 4;
    public static final int  OVERLAY_SLOTS = 3;

    // ---- Reserved depth layer (v2) ---------------------------------------------------
    // NOT PRODUCED YET. The region is reserved so the layout is final before anything
    // depends on it: filling it later moves nothing. A Windows section commits pages
    // lazily, so 3 x 33 MB of address space costs nothing until it is actually written.
    public static final long OVERLAY_DEPTH_SLOT_BYTES = (long) MAX_OVERLAY_W * MAX_OVERLAY_H * 4; // f32/px
    public static final long OFF_OVERLAY_DEPTH = OFF_OVERLAY_PIXELS + OVERLAY_SLOT_BYTES * OVERLAY_SLOTS;
    public static final long OVERLAY_DEPTH_BYTES = OVERLAY_DEPTH_SLOT_BYTES * OVERLAY_SLOTS;

    public static final long OFF_RENDER_RING = OFF_OVERLAY_DEPTH + OVERLAY_DEPTH_BYTES;
    public static final long RENDER_RING_BYTES = 64L << 20;
    public static final int NATIVE_POSE_SLOTS=19, NATIVE_POSE_STRIDE=0x18000, NATIVE_POSE_HEADER=64;

    // ---- Appearance region (MC -> SB skin/cape) --------------------------------------
    public static final int  SKIN_W = 64;
    public static final int  SKIN_H = 64;
    public static final int  CAPE_W = 64;
    public static final int  CAPE_H = 32;
    public static final long SKIN_PIXELS_BYTES = (long) SKIN_W * SKIN_H * 4;   // 16 KiB
    public static final long CAPE_PIXELS_BYTES = (long) CAPE_W * CAPE_H * 4;   // 8 KiB
    public static final int  APPEARANCE_HEADER_BYTES = 0x80;
    public static final long APPEARANCE_BYTES = APPEARANCE_HEADER_BYTES + SKIN_PIXELS_BYTES + CAPE_PIXELS_BYTES;

    public static final long OFF_APPEARANCE = OFF_RENDER_RING + RENDER_RING_BYTES;
    public static final long OFF_ACTOR_TABLE = OFF_APPEARANCE + APPEARANCE_BYTES;
    public static final long OFF_COMBAT = OFF_ACTOR_TABLE + 0x4040;
    public static final int COMBAT_SLOTS = 128;
    public static final int COMBAT_HIT_SIZE = 80;
    public static final int COMBAT_BYTES = 0x80 + COMBAT_SLOTS * COMBAT_HIT_SIZE;
    public static final long MAPPING_BYTES = OFF_COMBAT + COMBAT_BYTES;

    // AppearanceHeader field offsets (within the appearance region).
    public static final int APP_SEQ       = 0x00;
    public static final int APP_FLAGS     = 0x04;
    public static final int APP_SKIN_W    = 0x08;
    public static final int APP_SKIN_H    = 0x0C;
    public static final int APP_CAPE_W    = 0x10;
    public static final int APP_CAPE_H    = 0x14;
    public static final int APP_SKIN_HASH = 0x18;
    public static final int APP_CAPE_HASH = 0x20;
    // Pixel buffers follow the header:
    public static final long OFF_SKIN_PIXELS = OFF_APPEARANCE + APPEARANCE_HEADER_BYTES;
    public static final long OFF_CAPE_PIXELS = OFF_SKIN_PIXELS + SKIN_PIXELS_BYTES;

    public static final class AppearanceFlags {
        public static final int HAS_SKIN       = 1 << 0;
        public static final int HAS_CAPE       = 1 << 1;
        public static final int SLIM           = 1 << 2;
        public static final int DEFAULT        = 1 << 3;
        public static final int CAPE_OFFLINE   = 1 << 4;
        private AppearanceFlags() {}
    }


    // ---- Header ----------------------------------------------------------------------
    // struct Header { u32 magic; u32 version; u32 sbPid; u32 mcPid; u64 sbHeartbeatMs; u64 mcHeartbeatMs; }
    public static final int HEADER_SIZE = 0x20;
    public static final int HDR_MAGIC      = 0x00;
    public static final int HDR_VERSION    = 0x04;
    public static final int HDR_SB_PID     = 0x08;
    public static final int HDR_MC_PID     = 0x0C;
    public static final int HDR_SB_HB      = 0x10;
    public static final int HDR_MC_HB      = 0x18;

    // ---- SB -> MC state (seqlock) ----------------------------------------------------
    public static final int SB_STATE_SIZE = 0x70;
    public static final int SB_MOTION_TIME = 0x40, SB_MOTION_FRAME = 0x48;
    public static final int SB_MOTION_EPOCH = 0x50, SB_CONTACTS = 0x54;
    public static final int SB_VX = 0x58, SB_VY = 0x60, SB_VZ = 0x68;
    public static final int SB_SEQ        = 0x00;
    public static final int SB_FLAGS      = 0x04;
    public static final int SB_LEVEL_ID   = 0x08;
    public static final int SB_TELEPORT   = 0x0C;
    public static final int SB_POS_X      = 0x10;
    public static final int SB_POS_Y      = 0x18;
    public static final int SB_POS_Z      = 0x20;
    public static final int SB_YAW        = 0x28;
    public static final int SB_PITCH      = 0x2C;
    public static final int SB_VIEW_W     = 0x30;
    public static final int SB_VIEW_H     = 0x34;
    public static final int SB_GAME_HOUR  = 0x38;
    public static final int SB_MAP_ID     = 0x3C; // formerly reserved, stable across pawn rebinds

    public static final class SbFlags {
        public static final int IN_GAME   = 1 << 0;
        public static final int MENU_OPEN = 1 << 1;
        public static final int LOADING   = 1 << 2;
        public static final int PAUSED    = 1 << 3;
        /** F4 latch: the MC player (Steve) is the active avatar. Character ownership only. */
        public static final int CONTROL_STEVE = 1 << 4;
        /** SB grants MC keyboard/mouse ownership this frame. Input authority only. */
        public static final int EDIT = 1 << 5;
        /** SB's own UI owns input; MC must stay passive (no title screen, no HUD). */
        public static final int NATIVE_UI = 1 << 6;
        private SbFlags() {}
    }

    /** MC may replay forwarded input only while EDIT is set and neither gate is set. */
    public static boolean mayEdit(int sbFlags) {
        int auth = SbFlags.EDIT | SbFlags.MENU_OPEN | SbFlags.NATIVE_UI;
        return (sbFlags & SbFlags.EDIT) != 0 && (sbFlags & auth) == SbFlags.EDIT;
    }

    // ---- MC -> SB state (seqlock) ----------------------------------------------------
    public static final int MC_STATE_SIZE = 0xE0;
    public static final int MC_WORLD_ID = 0xD8;
    public static final int MC_MOTION_TIME = 0xB0, MC_MOTION_EPOCH = 0xB8, MC_MOTION_MODE = 0xBC;
    public static final int MC_VX = 0xC0, MC_VY = 0xC8, MC_VZ = 0xD0;
    public static final int MC_SEQ         = 0x00;
    public static final int MC_FLAGS       = 0x04;
    public static final int MC_X           = 0x08;
    public static final int MC_Y           = 0x10;
    public static final int MC_Z           = 0x18;
    public static final int MC_YAW         = 0x20;
    public static final int MC_PITCH       = 0x24;
    public static final int MC_EYE_HEIGHT  = 0x28;
    public static final int MC_TELEPORT_ACK = 0x2C;
    public static final int MC_GUI_SCALE   = 0x30;
    public static final int MC_FRAME       = 0x38;
    public static final int MC_FOV         = 0x40;
    public static final int MC_BOB_PHASE   = 0x44;
    public static final int MC_BOB_AMOUNT  = 0x48;
    public static final int MC_CAM_MODE    = 0x4C;
    public static final int MC_CAM_DIST    = 0x50;
    public static final int MC_TICK_QPC    = 0x58;
    public static final int MC_PREV_X      = 0x60;
    public static final int MC_PREV_Y      = 0x68;
    public static final int MC_PREV_Z      = 0x70;
    public static final int MC_CUR_X       = 0x78;
    public static final int MC_CUR_Y       = 0x80;
    public static final int MC_CUR_Z       = 0x88;
    public static final int MC_TICK_EYE_O  = 0x90;
    public static final int MC_TICK_EYE    = 0x94;
    public static final int MC_WALKDIST_O  = 0x98;
    public static final int MC_WALKDIST    = 0x9C;
    public static final int MC_BOB_O       = 0xA0;
    public static final int MC_BOB         = 0xA4;
    public static final int MC_TICK_MS     = 0xA8;

    public static final class McFlags {
        public static final int IN_WORLD    = 1 << 0;
        public static final int SCREEN_OPEN = 1 << 1;
        public static final int ON_GROUND   = 1 << 2;
        public static final int SNEAKING    = 1 << 3;
        public static final int SPRINTING   = 1 << 4;
        public static final int DEAD        = 1 << 5;
        public static final int SWIMMING    = 1 << 6;
        public static final int FLYING      = 1 << 7;
        public static final int CONTROL_STEVE = 1 << 8;
        public static final int JUMP_HELD   = 1 << 9;
        private McFlags() {}
    }

    // ---- Actor table -----------------------------------------------------------------
    public static final int MAX_ACTORS = 256;
    public static final int ACTOR_RECORD_SIZE = 64;
    public static final int ACTOR_TABLE_SIZE = 0x40 + 64 * MAX_ACTORS;
    public static final int ACTOR_SEQ   = 0x00;
    public static final int ACTOR_COUNT = 0x04;
    public static final int ACTOR_DATA  = 0x40;  // records start here

    public static final int AR_UID         = 0x00;
    public static final int AR_FLAGS       = 0x04;
    public static final int AR_X           = 0x08;
    public static final int AR_Y           = 0x0C;
    public static final int AR_Z           = 0x10;
    public static final int AR_YAW         = 0x14;
    public static final int AR_WIDTH       = 0x18;
    public static final int AR_HEIGHT      = 0x1C;
    public static final int AR_HEALTH      = 0x20;
    public static final int AR_LEVEL       = 0x24;
    public static final int AR_NAME        = 0x28;  // char[24]

    public static final class ActorFlags {
        public static final int HOSTILE   = 1 << 0;
        public static final int DEAD      = 1 << 1;
        public static final int BOSS      = 1 << 2;
        public static final int IN_COMBAT = 1 << 3;
        public static final int INVULN    = 1 << 4;
        private ActorFlags() {}
    }

    // ---- Input ring (SB -> MC) --------------------------------------------------------
    public static final long INPUT_HEAD_OFF = 0x00;
    public static final long INPUT_TAIL_OFF = 0x40;
    public static final long INPUT_DATA_OFF = 0x80;
    public static final long INPUT_BYTES    = 0x10000 - INPUT_DATA_OFF;

    public static final int IN_KEY         = 1;
    public static final int IN_MOUSE_BTN   = 2;
    public static final int IN_SCROLL      = 3;
    public static final int IN_CURSOR      = 4;
    public static final int IN_TEXT        = 5;
    public static final int IN_RELEASE_ALL = 6;
    public static final int IN_HURT        = 7;
    public static final int IN_OPEN_MENU   = 8;
    public static final int IN_MOUSE_DELTA = 9;
    public static final int IN_LOOK_SYNC   = 10;

    public static final int HURT_MELEE     = 0;
    public static final int HURT_PROJECTILE = 1;
    public static final int HURT_MAGIC     = 2;
    public static final int HURT_OTHER     = 3;

    // ---- Event ring (MC -> SB) ---------------------------------------------------------
    public static final long EVENT_HEAD_OFF = 0x00;
    public static final long EVENT_TAIL_OFF = 0x40;
    public static final long EVENT_DATA_OFF = 0x80;
    public static final long EVENT_BYTES    = 0x10000 - EVENT_DATA_OFF;

    public static final int EV_HIT_ACTOR   = 1;
    public static final int EV_PLAYER_DIED = 2;
    public static final int EV_EXPLOSION   = 3;
    public static final int EV_SKILL_USE   = 4;
    public static final int EV_BLOCK_CHANGE = 5;
    public static final int EV_EXPLOSION_HIT = 6;
    public static final int EV_BLOCK_LIGHT = 7;
    public static final int EV_PEARL_TELEPORT = 8;
    public static final int EV_HIT_ORIGIN = 9;    // a/b/c source; raw d bits = low32 Windows tick count
    public static final int EV_HIT_DIRECTION = 10; // confirmed arrow direction, same UID/serial/time

    /**
     * {@code MC_EVENT.flags} for {@link #EV_BLOCK_CHANGE}. Mirrors {@code BlockAction} in
     * protocol/stellarcraft_protocol.h and the {@code BLOCK_*} names in sb-mod/lua/main.lua.
     */
    public static final int EV_BLOCK_BREAK     = 0;  // the block at a/b/c is gone
    public static final int EV_BLOCK_PLACE     = 1;  // a block now occupies a/b/c
    public static final int EV_BLOCK_CLEAR_ALL = 2;  // drop every mirrored actor (pre re-sync)

    public static final int HIT_CRITICAL   = 1 << 0;
    public static final int HIT_PROJECTILE = 1 << 1;
    public static final int HIT_SWEEP      = 1 << 2;
    public static final int HIT_FIRE       = 1 << 3;

    public static final int WEAPON_UNARMED = 0;
    public static final int WEAPON_BLADE   = 1;
    public static final int WEAPON_AXE     = 2;
    public static final int WEAPON_BLUNT   = 3;
    public static final int WEAPON_PIERCE  = 4;
    public static final int WEAPON_ARROW   = 5;
    public static final int HIT_STYLE_JUMP_HEAVY = 0x100;

    public static final int MC_EVENT_SIZE = 32;

    // ---- Collision ring (SB -> MC) -----------------------------------------------------
    public static final long COLLISION_HEAD_OFF = 0x00;
    public static final long COLLISION_TAIL_OFF = 0x40;
    public static final long COLLISION_DATA_OFF = 0x80;
    public static final long COLLISION_DATA_BYTES = COLLISION_RING_BYTES - COLLISION_DATA_OFF;

    public static final int COL_SECTION = 1;
    public static final int COL_EVICT   = 2;
    public static final int COL_CLEAR   = 3;
    public static final int COL_GROUND  = 4;

    public static final int COLLISION_BOX_SIZE = 24;   // 6 floats
    public static final int COLLISION_SECTION_HEADER_SIZE = 16; // 3 ints + 1 int

    // ---- Ground height-field patch (COL_GROUND) ----------------------------------------
    // u32 op + CollisionGroundHeader(16) + CollisionGroundCell[count](16 each).
    // Cells sit on a fixed 0.5-block lattice: gx = floor(mcX / 0.5 + 0.5), same for gz.
    public static final int GROUND_HEADER_SIZE = 16;
    public static final int GROUND_CELL_SIZE   = 16;
    public static final int GROUND_CELL_STRIDE = 9;    // 9x9 patch per message
    public static final float GROUND_CELL_SIZE_M = 0.5f;

    public static final int GROUND_ORIGIN_GX = 0x00;
    public static final int GROUND_ORIGIN_GZ = 0x04;
    public static final int GROUND_COUNT     = 0x08;

    public static final int GROUND_CELL_GX   = 0x00;
    public static final int GROUND_CELL_GZ   = 0x04;
    public static final int GROUND_CELL_TOPY = 0x08;
    public static final int GROUND_CELL_HIT  = 0x0C;

    // ---- Overlay triple buffer ---------------------------------------------------------
    public static final int OVERLAY_DIRTY = 1 << 2;

    // OverlaySlotHdr (0x80 bytes each). Slot 0 starts at OFF_OVERLAY_CTL + 0x10, because
    // OverlayCtl is state(u32) pad(u32) framesPublished(u64) = 0x10.
    //
    // Layout mirrors stellarcraft_protocol.h exactly; change both together.
    public static final int OV_CTL_STATE   = 0x00;
    public static final int OV_CTL_PAD     = 0x04;
    public static final int OV_CTL_FRAMES  = 0x08;
    public static final int OV_SLOT_HDR    = 0x10;   // first slot header
    public static final int OV_SLOT_HDR_SIZE = 0x80;

    public static final int OV_SLOT_W          = 0x00;
    public static final int OV_SLOT_H          = 0x04;
    public static final int OV_SLOT_FLAGS      = 0x08;
    public static final int OV_SLOT_FRAME_ID   = 0x10;   // long: MC render-frame counter (frame seq)
    public static final int OV_SLOT_CAM_X      = 0x18;   // double
    public static final int OV_SLOT_CAM_Y      = 0x20;
    public static final int OV_SLOT_CAM_Z      = 0x28;
    public static final int OV_SLOT_CAM_YAW    = 0x30;   // float, MC degrees
    public static final int OV_SLOT_CAM_PITCH  = 0x34;
    public static final int OV_SLOT_CAM_ROLL   = 0x38;   // always 0 on 1.20.1
    public static final int OV_SLOT_VFOV_DEG   = 0x3C;
    public static final int OV_SLOT_ASPECT     = 0x40;
    public static final int OV_SLOT_NEAR_Z     = 0x44;
    public static final int OV_SLOT_FAR_Z      = 0x48;
    public static final int OV_SLOT_CAMERA_MODE = 0x4C;
    public static final int OV_SLOT_DEPTH_OFF  = 0x50;
    public static final int OV_SLOT_DEPTH_BYTES = 0x54;

    /** OverlaySlotFlags (mirror of the protocol enum). */
    public static final int OV_FLAG_BOTTOM_UP      = 1 << 0;
    public static final int OV_FLAG_POSE_VALID     = 1 << 1;
    public static final int OV_FLAG_HAS_DEPTH      = 1 << 2;
    public static final int OV_FLAG_DEPTH_LINEAR   = 1 << 3;
    public static final int OV_FLAG_DEPTH_REVERSED = 1 << 4;

    /** Sentinel for OV_SLOT_DEPTH_OFF when a frame carries no depth layer. */
    public static final int OV_NO_DEPTH = 0xFFFFFFFF;

    /** Near clip plane Minecraft hardcodes in its projection (blocks). */
    public static final float MC_NEAR_PLANE = 0.05f;
}
