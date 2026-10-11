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
    public static final int K_VERSION = 1;
    public static final String K_MAPPING_NAME = "Local\\StellarCraft_v1";

    /** 100 Unreal Units (1 m) per Minecraft block. */
    public static final double K_UNITS_PER_BLOCK = 100.0;

    // ---- Region offsets -------------------------------------------------------------
    public static final long OFF_HEADER        = 0x0;
    public static final long OFF_SB_STATE      = 0x100;
    public static final long OFF_MC_STATE      = 0x200;
    public static final long OFF_ACTOR_TABLE   = 0x300;
    public static final long OFF_INPUT_RING    = 0x1000;
    public static final long OFF_EVENT_RING    = 0x10000;
    public static final long OFF_COLLISION_RING = 0x20000;
    public static final long COLLISION_RING_BYTES = 32L << 20;
    public static final long OFF_OVERLAY_PIXELS = OFF_COLLISION_RING + COLLISION_RING_BYTES;
    public static final int  MAX_OVERLAY_W = 3840;
    public static final int  MAX_OVERLAY_H = 2160;
    public static final long OVERLAY_SLOT_BYTES = (long) MAX_OVERLAY_W * MAX_OVERLAY_H * 4;
    public static final int  OVERLAY_SLOTS = 3;
    public static final long OFF_RENDER_RING = OFF_OVERLAY_PIXELS + OVERLAY_SLOT_BYTES * OVERLAY_SLOTS;
    public static final long RENDER_RING_BYTES = 64L << 20;

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
    public static final long MAPPING_BYTES = OFF_APPEARANCE + APPEARANCE_BYTES;

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
    public static final int SB_STATE_SIZE = 0x40;
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

    public static final class SbFlags {
        public static final int IN_GAME   = 1 << 0;
        public static final int MENU_OPEN = 1 << 1;
        public static final int LOADING   = 1 << 2;
        public static final int PAUSED    = 1 << 3;
        public static final int CONTROL_STEVE = 1 << 4;
        private SbFlags() {}
    }

    // ---- MC -> SB state (seqlock) ----------------------------------------------------
    public static final int MC_STATE_SIZE = 0xB0; // 176 bytes (was incorrectly 0xC8)
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

    public static final int MC_EVENT_SIZE = 32;

    // ---- Collision ring (SB -> MC) -----------------------------------------------------
    public static final long COLLISION_HEAD_OFF = 0x00;
    public static final long COLLISION_TAIL_OFF = 0x40;
    public static final long COLLISION_DATA_OFF = 0x80;
    public static final long COLLISION_DATA_BYTES = COLLISION_RING_BYTES - COLLISION_DATA_OFF;

    public static final int COL_SECTION = 1;
    public static final int COL_EVICT   = 2;
    public static final int COL_CLEAR   = 3;

    public static final int COLLISION_BOX_SIZE = 24;   // 6 floats
    public static final int COLLISION_SECTION_HEADER_SIZE = 16; // 3 ints + 1 int

    // ---- Overlay triple buffer ---------------------------------------------------------
    public static final int OVERLAY_DIRTY = 1 << 2;
}
