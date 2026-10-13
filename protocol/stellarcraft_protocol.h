// StellarCraft shared-memory protocol
// Stellar Blade (UE4.26 + UE4SS C++ mod)  <->  Minecraft (Fabric mod)
//
// This header is the single source of truth for the byte layout.
// The Java side mirrors it in dev/stellarcraft/link/Proto.java;
// if you change anything here, change it there too and bump kVersion.
//
// All multi-byte values are little-endian. The Stellar Blade mod creates
// the mapping; Minecraft opens it.
//
// Coordinates in this protocol are ALWAYS in Minecraft space (Y-up, Z-south),
// unless explicitly noted otherwise.
//
// Inspired by SkyCraft's protocol design (github.com/chasmlol/SkyCraft).
#pragma once

#include <cstdint>

namespace stellarcraft::proto
{
    inline constexpr std::uint32_t kMagic = 0x43544153;  // "STAC" (STellarCrAft)
    inline constexpr std::uint32_t kVersion = 6;         // 6: packed jump-heavy visual style; layouts unchanged
    inline constexpr wchar_t       kMappingName[] = L"Local\\StellarCraft_v1";

    // ---- Coordinate / scale mapping -------------------------------------------------------
    //
    // Unreal (Stellar Blade) is Z-up, centimeters. Minecraft is Y-up, blocks (1 block = 1 m).
    // Stellar Blade uses UE4.26, so positions are in UU (Unreal Units, cm by convention).
    //
    //    mc.x =  sb.x / 100.0
    //    mc.y =  sb.z / 100.0
    //    mc.z = -sb.y / 100.0       (Unreal +Y is "forward/north"; MC -Z is north)
    //
    // Exact yaw/pitch sign and offset are pinned down in Phase 0 with a unit test
    // (a real character rotated by a known amount). TODO(tune): confirm these.
    inline constexpr double kUnitsPerBlock = 100.0;  // 100 UU (1 m) per MC block

    // ---- Region offsets (byte layout of the shared memory file mapping) ------------------
    inline constexpr std::uint64_t kOffHeader        = 0x0;
    inline constexpr std::uint64_t kOffSbState       = 0x100;    // SB -> MC, seqlock
    inline constexpr std::uint64_t kOffMcState       = 0x200;    // MC -> SB, seqlock
    inline constexpr std::uint64_t kOffInputRing     = 0x1000;   // SB -> MC, SPSC ring
    inline constexpr std::uint64_t kOffEventRing     = 0x10000;  // MC -> SB, SPSC ring
    inline constexpr std::uint64_t kOffCollisionRing = 0x20000;  // SB -> MC, SPSC ring (collision shapes)
    inline constexpr std::uint64_t kCollisionRingBytes = 32ull << 20;  // 32 MiB
    inline constexpr std::uint64_t kOffOverlayCtl    = kOffCollisionRing + kCollisionRingBytes; // overlay control block (OverlayCtl + slot headers)
    inline constexpr std::uint64_t kOverlayCtlBytes  = 0x1000;  // 4 KiB reserved for OverlayCtl + 3 OverlaySlotHdr
    inline constexpr std::uint64_t kOffOverlayPixels = kOffOverlayCtl + kOverlayCtlBytes;
    inline constexpr std::uint32_t kMaxOverlayW = 3840;
    inline constexpr std::uint32_t kMaxOverlayH = 2160;
    inline constexpr std::uint64_t kOverlaySlotBytes = std::uint64_t(kMaxOverlayW) * kMaxOverlayH * 4;
    inline constexpr std::uint32_t kOverlaySlots = 3;              // triple-buffered

    // ---- Reserved depth layer (v2) ---------------------------------------------------------
    //
    // NOT PRODUCED YET — the region is reserved so the layout is already final when we start
    // exporting depth, i.e. filling it later moves nothing. A section object commits pages
    // lazily, so 3 x 33 MB of address space costs nothing until it is actually written.
    //
    // When it goes live: MC renders the world layer's depth into the slot's depth region as
    // float32, sets kOverlayHasDepth (plus kOverlayDepthLinear / kOverlayDepthReversed as
    // appropriate) and points OverlaySlotHdr::depthOffset at it. A consumer can then depth-
    // test MC's world against the host's depth buffer instead of flat-compositing an overlay
    // — see docs/GTA5_PASSTHROUGH_ANALYSIS.md §2.2 for the technique this is modelled on.
    inline constexpr std::uint64_t kOverlayDepthSlotBytes =
        std::uint64_t(kMaxOverlayW) * kMaxOverlayH * 4;  // float32 per pixel
    inline constexpr std::uint64_t kOffOverlayDepth = kOffOverlayPixels + kOverlaySlotBytes * kOverlaySlots;
    inline constexpr std::uint64_t kOverlayDepthBytes = kOverlayDepthSlotBytes * kOverlaySlots;

    inline constexpr std::uint64_t kOffRenderRing    = kOffOverlayDepth + kOverlayDepthBytes;
    inline constexpr std::uint64_t kRenderRingBytes  = 64ull << 20;  // 64 MiB
    inline constexpr std::uint32_t kNativePoseSlots = 19; // hands/body/manifest/16 entities
    inline constexpr std::uint32_t kNativePoseStride = 0x18000;
    // SCPOSE1 payload sequence: high 8 bits = persistent topology generation,
    // low 24 bits = publication serial. Footer and CRC cover the complete value.
    // Slot-header seq below remains a separate, ordinary odd/even seqlock.
    struct NativePoseHeader {
        std::uint32_t seq, bytes, mcPid, sbPid, world, entity;
        std::uint64_t uuidHigh, uuidLow, timeMs;
        std::uint8_t reserved[16];
    };
    static_assert(sizeof(NativePoseHeader)==64);
    static_assert(kNativePoseSlots*kNativePoseStride<=kRenderRingBytes);

    // Appearance region sizing (forward-declared here so kMappingBytes can reference it;
    // the full structs/constants live in the Appearance section below).
    inline constexpr std::uint32_t kSkinW     = 64;
    inline constexpr std::uint32_t kSkinH     = 64;
    inline constexpr std::uint32_t kCapeW     = 64;
    inline constexpr std::uint32_t kCapeH     = 32;
    inline constexpr std::uint64_t kSkinPixelsBytes = std::uint64_t(kSkinW) * kSkinH * 4;  // 16 KiB
    inline constexpr std::uint64_t kCapePixelsBytes = std::uint64_t(kCapeW) * kCapeH * 4;  // 8 KiB
    inline constexpr std::uint64_t kAppearanceHeaderBytes = 0x80;
    inline constexpr std::uint64_t kAppearanceBytes =
        kAppearanceHeaderBytes + kSkinPixelsBytes + kCapePixelsBytes;

    inline constexpr std::uint64_t kOffAppearance    = kOffRenderRing + kRenderRingBytes;  // MC -> SB skin/cape textures
    inline constexpr std::uint64_t kOffActorTable = kOffAppearance + kAppearanceBytes;
    inline constexpr std::uint64_t kOffCombat = kOffActorTable + 0x4040;
    inline constexpr std::uint32_t kCombatSlots = 128;
    struct CombatHit {
        std::uint32_t seq, flags;
        std::uint64_t id, timeMs;
        std::uint32_t playerGuid, rawSource, sbPid, world;
        float damage; std::uint32_t sourceGuid;
        double x,y,z;
        std::uint64_t reserved;
    };
    static_assert(sizeof(CombatHit)==80);
    inline constexpr std::uint64_t kCombatBytes = 0x80 + kCombatSlots * sizeof(CombatHit);
    inline constexpr std::uint64_t kMappingBytes = kOffCombat + kCombatBytes;

    // ---- Header @0x0 ----------------------------------------------------------------------
    struct Header
    {
        std::uint32_t magic;
        std::uint32_t version;
        std::uint32_t sbPid;            // Stellar Blade process id
        std::uint32_t mcPid;            // Minecraft process id
        std::uint64_t sbHeartbeatMs;    // GetTickCount64() at last SB frame
        std::uint64_t mcHeartbeatMs;    // GetTickCount64() at last MC frame
    };
    static_assert(sizeof(Header) == 0x20);

    // ---- SB -> MC state @0x100 (seqlock: seq is odd while writing) -----------------------
    //
    // AUTHORITY MODEL (mirrors SekiroCraft-Passthrough):
    //
    //   Stellar Blade is the AUTHORITY. It decides, every frame, whether MC owns the
    //   keyboard/mouse. Two independent concepts are deliberately kept apart:
    //
    //     * kSbControlSteve  — CHARACTER ownership. A pure latch owned by SB (F4).
    //                          "Is Steve the avatar right now?" It says nothing about
    //                          who receives input, and is valid even while MC sits on
    //                          its title screen.
    //     * kSbEdit          — INPUT authority. SB asserts this only when a real,
    //                          playable scene exists AND SB's own UI is not up. It is
    //                          what MC actually honours before replaying events.
    //     * kSbNativeUI      — SB's own UI/menu owns input. MC must drop held keys and
    //                          must not draw its title screen or HUD.
    //
    //   F4 therefore works at any moment (it only flips CHARACTER ownership), while
    //   SB menus keep working because kSbEdit is cleared the instant SB opens a menu.
    enum SbFlags : std::uint32_t
    {
        kSbInGame     = 1u << 0,  // a save/level is loaded and the player exists
        kSbMenuOpen   = 1u << 1,  // a SB menu owns input; MC should drop held keys
        kSbLoading    = 1u << 2,  // level streaming / loading screen in progress
        kSbPaused     = 1u << 3,  // SB is paused; freeze MC simulation
        kSbControlSteve = 1u << 4, // F4 latch: the MC player (Steve) is the active avatar
        kSbEdit       = 1u << 5,  // SB grants MC keyboard/mouse ownership this frame
        kSbNativeUi   = 1u << 6,  // SB's own UI owns input; MC must stay passive
    };

    // Convenience: MC may replay forwarded input only in this exact situation.
    inline constexpr std::uint32_t kSbInputAuthorityMask =
        kSbEdit | kSbMenuOpen | kSbNativeUi;

    struct SbState
    {
        std::uint32_t seq;
        std::uint32_t flags;          // SbFlags
        std::uint32_t levelId;        // stable id for the current level/map (hash of level name)
        std::uint32_t teleportSeq;    // MC teleports its player to pos when this changes
        double        posX, posY, posZ;  // SB player feet, MC coords (authoritative spawn/anchor)
        float         yaw, pitch;        // authoritative look (MC degrees)
        std::uint32_t viewportW, viewportH;
        float         gameHour;          // 0..24, drives MC daylight
        std::uint32_t pad;
        std::uint64_t motionTimeMs;
        std::uint64_t motionFrame;
        std::uint32_t motionEpoch, contactFlags; // host PID; ground=1, blocked X/Y/Z=2/4/8
        double velocityX, velocityY, velocityZ; // actual native velocity, blocks/tick
    };
    static_assert(sizeof(SbState) == 0x70);
    static_assert(sizeof(SbState) <= 0x80); // independent damage seqlock starts at 0x80

    // Independent seqlock in reserved SB-state bytes. Cumulative MC health
    // units avoid sharing the keyboard ring's producer with the combat hook.
    inline constexpr std::uint64_t kPlayerHurtOffset = 0x80;
    struct PlayerHurt { std::uint32_t seq, playerGuid; double total;
        std::uint32_t attackerGuid, blocked; };
    static_assert(sizeof(PlayerHurt)==24);

    // ---- MC -> SB state @0x200 (seqlock) ---------------------------------------------------
    enum McFlags : std::uint32_t
    {
        kMcInWorld   = 1u << 0,
        kMcScreenOpen = 1u << 1,  // an MC GUI screen (inventory, chat, ...) is open
        kMcOnGround  = 1u << 2,
        kMcSneaking  = 1u << 3,
        kMcSprinting = 1u << 4,
        kMcDead      = 1u << 5,
        kMcSwimming  = 1u << 6,
        kMcFlying    = 1u << 7,
        kMcControlSteve = 1u << 8, // M1: MC player (Steve) has taken control; SB should freeze Eve
    };

    struct McState
    {
        std::uint32_t seq;
        std::uint32_t flags;          // McFlags
        double        x, y, z;        // interpolated feet position (MC coords)
        float         yaw, pitch;     // MC rotation (degrees)
        float         eyeHeight;      // blocks above feet
        std::uint32_t teleportAck;    // last SbState::teleportSeq applied
        std::uint32_t guiScale;
        std::uint64_t frameCounter;
        float         fovDeg;         // effective vertical FOV
        float         bobPhase;       // walk-bob phase
        float         bobAmount;      // walk-bob amplitude
        std::uint32_t cameraMode;     // 0 first, 1 third-behind, 2 third-front (MC F5)
        float         cameraDistance;
        std::uint32_t pad;
        // Raw 20 Hz physics ticks so SB can interpolate on its own frame clock
        // exactly like MC's renderer does (no judder from frame-phase mismatch).
        std::int64_t  tickQpc;
        double        prevX, prevY, prevZ;  // feet at previous tick
        double        curX, curY, curZ;     // feet at latest tick
        float         tickEyeO, tickEye;    // smoothed eye height, prev/latest
        float         walkDistO, walkDist;
        float         bobO, bob;
        float         tickMs;               // ms per tick (50 unless changed)
        std::uint32_t tickPad;
        std::uint64_t motionTimeMs;
        std::uint32_t motionEpoch, motionMode; // host PID; ground/falling/creative/glide
        double velocityX, velocityY, velocityZ; // desired displacement per MC tick
        std::uint32_t worldId, motionPad;
    };
    static_assert(sizeof(McState) == 0xE0);
    static_assert(sizeof(McState) <= 0x100);

    // ---- Actor table @0x300 (SB -> MC, seqlock) --------------------------------------------
    // Nearby SB characters (player's current target + enemies + NPCs), mirrored in MC as
    // invisible hittable proxy entities. The real SB actor is drawn by SB itself.
    inline constexpr std::uint32_t kMaxActors = 256;

    enum ActorFlags : std::uint32_t
    {
        kActorHostile   = 1u << 0,  // hostile to the player right now
        kActorDead      = 1u << 1,
        kActorBoss      = 1u << 2,
        kActorInCombat  = 1u << 3,
        kActorInvuln    = 1u << 4,  // cannot be hit (cutscene, shielded, etc.)
    };

    struct ActorRecord
    {
        std::uint32_t uid;            // stable per-actor id (hash of actor ptr / FName)
        std::uint32_t flags;          // ActorFlags
        float         x, y, z;        // feet, MC coords
        float         yaw;            // MC degrees
        float         width;          // blocks (capsule radius)
        float         height;         // blocks (capsule height)
        float         healthFrac;     // 0..1
        std::uint16_t level;
        std::uint16_t pad;
        std::uint32_t nativeGuid, nativeType, world, generation;
        std::uint64_t reserved;
    };
    static_assert(sizeof(ActorRecord) == 64);

    struct ActorTable
    {
        std::uint32_t seq;
        std::uint32_t count;
        std::uint8_t  pad[0x40 - 8];
        ActorRecord   actors[kMaxActors];
    };
    static_assert(sizeof(ActorTable) == 0x40 + 64 * kMaxActors);

    // ---- Input ring @0x1000 (SB produces, MC consumes) --------------------------------------
    // SPSC byte ring. head written by SB, tail by MC.
    inline constexpr std::uint64_t kInputRingHeadOff = 0x00;
    inline constexpr std::uint64_t kInputRingTailOff = 0x40;
    inline constexpr std::uint64_t kInputRingDataOff = 0x80;
    inline constexpr std::uint64_t kInputRingBytes  = 0x10000 - kInputRingDataOff;  // ~64 KiB

    enum InputType : std::uint16_t
    {
        kInKey         = 1,   // code = virtual-key code, a = 1 press / 0 release
        // code = GLFW mouse button id (the numbering Minecraft's MouseHandler.onPress
        // expects, which is also what vanilla binds): 0 = LEFT (keyAttack),
        // 1 = RIGHT (keyUse), 2 = MIDDLE (keyPickItem), 3 = X1, 4 = X2. a = press/release.
        // NOTE: this is NOT the Windows WM_* button numbering.
        kInMouseButton = 2,
        kInScroll      = 3,   // a = wheel notches * 120 (positive = up)
        kInCursor      = 4,   // a, b = absolute cursor position in overlay pixels
        kInText        = 5,   // a = unicode code point
        kInReleaseAll  = 6,   // release every held key/button (input focus left MC)
        kInHurt        = 7,   // SB hit the player: code = HurtKind, a = damage*100, b = attacker uid, c = HurtFlags
        kInOpenMenu    = 8,   // open MC's pause/options menu
        kInMouseDelta  = 9,   // a/b = relative raw mouse counts, gameplay only
        kInLookSync    = 10,  // a/b = initial MC yaw/pitch * 1000 on world/control entry
    };

    enum HurtKind : std::uint16_t
    {
        kHurtMelee     = 0,
        kHurtProjectile = 1,
        kHurtMagic     = 2,
        kHurtOther     = 3,
    };

    enum HurtFlags : std::uint32_t
    {
        kHurtBlockedInSb = 1u << 0,  // the hit was blocked in SB (MC may still apply reduced dmg)
        kHurtPowerAttack = 1u << 1,
    };

    // ---- Event ring @0x10000 (MC produces, SB consumes) -------------------------------------
    inline constexpr std::uint64_t kEventRingHeadOff = 0x00;  // written by MC
    inline constexpr std::uint64_t kEventRingTailOff = 0x40;  // written by SB
    inline constexpr std::uint64_t kEventRingDataOff = 0x80;
    inline constexpr std::uint64_t kEventRingBytes  = 0x10000 - kEventRingDataOff;

    enum McEventType : std::uint32_t
    {
        kEvHitActor    = 1,   // uid, a = MC damage (after MC modifiers), b/c = knockback dir x/z, d = knockback strength
        kEvPlayerDied  = 2,   // the MC player died: kill the SB player
        kEvExplosion   = 3,   // MC explosion: a/b/c = centre, d = radius
        kEvSkillUse    = 4,   // the player used an SB skill in MC (reserved for progression)
        kEvBlockChange = 5,   // a block changed: a/b/c = the block's MIN CORNER in MC world
                              // coords, flags = kBlkPlace / kBlkBreak / kBlkClearAll. SB mirrors
                              // the change as a real collidable actor in its own world.
        kEvExplosionHit = 6,  // M1: per-actor explosion damage: uid, a = scaled damage, d = knockback (falloff)
        kEvBlockLight = 7,    // a/b/c = block min corner; d = actual emission 0..15
        kEvPearlTeleport = 8, // a/b/c = native projectile impact in MC coordinates
        kEvHitOrigin = 9,     // uid/flags/weapon match next hit; a/b/c = source MC position; d bits = low32 GetTickCount64
        kEvHitDirection = 10, // optional confirmed arrow segment; a/b/c = normalized MC direction; d bits = same time
    };

    // McEvent.flags values for kEvBlockChange. Mirrored in Proto.java (EV_BLOCK_*) and in
    // sb-mod/lua/main.lua (BLOCK_PLACE / BLOCK_BREAK / BLOCK_CLEAR_ALL).
    enum BlockAction : std::uint32_t
    {
        kBlkBreak    = 0,     // the block at a/b/c is gone        -> destroy its SB actor
        kBlkPlace    = 1,     // a block now occupies a/b/c        -> spawn its SB actor
        kBlkClearAll = 2,     // remove every mirrored block actor -> sent before a full re-sync
    };

    enum HitFlags : std::uint32_t
    {
        kHitCritical   = 1u << 0,
        kHitProjectile = 1u << 1,
        kHitSweep      = 1u << 2,
        kHitFire       = 1u << 3,
    };

    enum HitWeapon : std::uint32_t
    {
        kWeaponUnarmed = 0,
        kWeaponBlade   = 1,  // swords
        kWeaponAxe     = 2,
        kWeaponBlunt   = 3,  // maces, pickaxes, shovels, hoes
        kWeaponPierce  = 4,  // tridents, spears
        kWeaponArrow   = 5,  // arrows / projectiles
    };
    inline constexpr std::uint32_t kHitStyleJumpHeavy = 0x100;

    struct McEvent
    {
        std::uint32_t type;
        std::uint32_t uid;         // actor uid for kEvHitActor / kEvSkillUse
        float         a, b, c, d;
        std::uint32_t flags;       // unique event serial for hit/context events
        std::uint32_t weapon;      // low8 HitWeapon; bit8 kHitStyleJumpHeavy (v6)
    };
    static_assert(sizeof(McEvent) == 32);

    // ---- Collision ring @0x20000 (SB produces, MC consumes) ---------------------------------
    // Streams AABBs representing SB's world geometry so MC's collision system can walk/climb
    // on it. Each entry is an axis-aligned box (MC coords) bucketed per 16^3 section.
    inline constexpr std::uint64_t kCollisionRingHeadOff = 0x00;  // written by SB
    inline constexpr std::uint64_t kCollisionRingTailOff = 0x40;  // written by MC
    inline constexpr std::uint64_t kCollisionRingDataOff = 0x80;
    inline constexpr std::uint64_t kCollisionRingDataBytes = kCollisionRingBytes - kCollisionRingDataOff;

    enum CollisionOp : std::uint32_t
    {
        kColSection = 1,   // CollisionSectionHeader + CollisionBox[count]
        kColEvict   = 2,   // CollisionEvict: remove a whole section
        kColClear   = 3,   // drop all collision data (level change)
        kColGround  = 4,   // CollisionGroundHeader + CollisionGroundCell[count] — SB ground
                           // height field, used by MC's pick raycast so blocks can be placed
    };

    struct CollisionBox
    {
        float minX, minY, minZ;   // MC coords
        float maxX, maxY, maxZ;
    };
    static_assert(sizeof(CollisionBox) == 24);

    struct CollisionSectionHeader
    {
        std::int32_t  sx, sy, sz;  // section coords (section = 16 blocks)
        std::uint32_t count;       // number of CollisionBox that follow
    };

    struct CollisionEvict
    {
        std::int32_t sx, sy, sz;
        std::uint32_t pad;
    };

    // Ground height-field patch. Cells are addressed on a fixed 0.5-block lattice; the
    // consumer indexes them relative to (originGx, originGz). Each cell either has a
    // surface (a set of 1-byte flags) or is a confirmed miss (open air, cleared).
    inline constexpr std::uint32_t kGroundCellStride = 9;  // 9x9 patch per message
    inline constexpr float         kGroundCellSize   = 0.5f;

    struct CollisionGroundHeader
    {
        std::int32_t  originGx, originGz;  // grid coord of cell (0,0) in this patch
        std::uint32_t count;               // number of cells that follow (<= 81)
        std::uint32_t pad;
    };
    static_assert(sizeof(CollisionGroundHeader) == 16);

    struct CollisionGroundCell
    {
        std::int32_t  gx, gz;   // absolute grid coords
        float         topY;     // surface top in MC coords (valid only when hit != 0)
        std::uint32_t hit;      // 1 = surface present, 0 = confirmed empty
    };
    static_assert(sizeof(CollisionGroundCell) == 16);

    // ---- Overlay triple-buffer @ kOffOverlayPixels ------------------------------------------
    // state: bits 0-1 = index of the "middle" slot, bit 2 = middle holds an unread frame.
    // Writer (MC) renders into its private back slot, then xchg(state, back | kDirty) and keeps
    // the returned index as its new back slot. Reader (SB) does xchg(state, front) only when
    // the dirty bit is set.
    inline constexpr std::uint32_t kOverlayDirty = 1u << 2;

    enum OverlaySlotFlags : std::uint32_t
    {
        kOverlayRowsBottomUp  = 1u << 0,  // rows are stored bottom-up (GL texture origin)
        kOverlayPoseValid     = 1u << 1,  // the camera pose fields are meaningful this frame
        kOverlayHasDepth      = 1u << 2,  // a depth layer is present at depthOffset
        kOverlayDepthLinear   = 1u << 3,  // depth is linear 0..1 (else projected / z-buffer)
        kOverlayDepthReversed = 1u << 4,  // 1.0 = near plane (reversed-Z), 0.0 = far
    };

    /** Sentinel for {@link OverlaySlotHdr::depthOffset} when a frame carries no depth. */
    inline constexpr std::uint32_t kOverlayNoDepth = 0xFFFFFFFFu;

    struct OverlayCtl
    {
        std::uint32_t state;
        std::uint32_t pad; // publication sequence: odd while slot 0 is being written, even when complete
        std::uint64_t framesPublished;
    };

    struct OverlaySlotHdr
    {
        std::uint32_t width;
        std::uint32_t height;
        std::uint32_t flags;      // OverlaySlotFlags
        std::uint32_t pad;        // keeps frameId 8-byte aligned
        std::uint64_t frameId;    // MC render-frame counter; monotonic -> doubles as the frame seq

        // --- the pose this frame was RENDERED WITH (MC coords, degrees) --------------------
        //
        // Per-slot rather than in OverlayCtl on purpose: a consumer that is a frame or two
        // behind (or that re-reads an older slot) must be able to tell exactly which pose the
        // pixels it is holding were rendered with. That is the input a re-projection needs to
        // map this frame onto the host's *current* camera and hide the link's latency instead
        // of showing it as overlay lag. Modelled on universal-modder's GTA5 passthrough; see
        // docs/GTA5_PASSTHROUGH_ANALYSIS.md §2.2. Nothing consumes it yet — it is carried so
        // the data is available (and its accuracy observable) before anything depends on it.
        double        camX, camY, camZ;
        float         camYaw;      // same convention as McState::yaw
        float         camPitch;    // same convention as McState::pitch
        float         camRoll;     // 0 on 1.20.1: Camera has no roll field before 1.21
        float         vFovDeg;     // effective vertical FOV actually used to render this frame
        float         aspect;      // width / height as rendered
        float         nearZ;       // near clip plane, in blocks (MC hardcodes 0.05)
        float         farZ;        // far clip plane, in blocks (GameRenderer.getDepthFar())
        std::uint32_t cameraMode;  // mirror of McState::cameraMode at capture time
        std::uint32_t depthOffset; // byte offset from kOffOverlayDepth, or kOverlayNoDepth
        std::uint32_t depthBytes;  // 0 when this frame carries no depth layer
        std::uint32_t pad2;
        std::uint8_t  reserved[0x80 - 0x5C];
    };
    static_assert(sizeof(OverlaySlotHdr) == 0x80);

    // ---- Appearance @ kOffAppearance (MC produces, SB consumes) ----------------------------
    // The Minecraft player's skin + cape, so Stellar Blade can render the MC player
    // (Steve) with the player's own appearance instead of the default texture.
    //
    // Layout: a seqlock-protected header followed by two fixed-size RGBA8 textures.
    //   Skin: 64x64 RGBA8 = 16 KiB (MC default skin resolution).
    //   Cape: 64x32 RGBA8 = 8 KiB  (MC default cape resolution).
    //
    // The seqlock guards the header only; the pixel buffers are written after the header
    // is committed, so the reader copies the header, then reads pixels (a torn pixel read
    // self-heals next frame). seq increments on every change so SB can detect and refresh.

    enum AppearanceFlags : std::uint32_t
    {
        kAppearanceHasSkin = 1u << 0,   // a valid skin texture is present
        kAppearanceHasCape = 1u << 1,   // a valid cape texture is present
        kAppearanceSlim    = 1u << 2,   // skin uses the "slim" (Alex) arm model
        kAppearanceDefault = 1u << 3,   // skin is the fallback Steve/Alex default (offline/custom)
        kAppearanceCapeOffline = 1u << 4, // cape is the offline/default fallback
    };

    struct AppearanceHeader
    {
        std::uint32_t seq;              // seqlock; odd while writing
        std::uint32_t flags;            // AppearanceFlags
        std::uint32_t skinW, skinH;     // actual skin dimensions (<= kSkinW/H)
        std::uint32_t capeW, capeH;     // actual cape dimensions (<= kCapeW/H); 0 if none
        std::uint64_t skinHash;         // FNV-1a of skin pixels (change detection)
        std::uint64_t capeHash;         // FNV-1a of cape pixels
        std::uint8_t  reserved[kAppearanceHeaderBytes - 0x28]; // pad to 0x80 (fields above are 0x28 bytes)
    };
    static_assert(sizeof(AppearanceHeader) == kAppearanceHeaderBytes);

    // Pixel buffers immediately follow the header (no padding):
    //   skin pixels @ kOffAppearance + kAppearanceHeaderBytes                    (kSkinPixelsBytes)
    //   cape pixels @ kOffAppearance + kAppearanceHeaderBytes + kSkinPixelsBytes (kCapePixelsBytes)
}
