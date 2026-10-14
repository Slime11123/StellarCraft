// StellarCraft — minimal shared-memory bridge DLL (no UE4SS dependency).
//
// Path B: UE4SS runs a *Lua* mod (main.lua) as the entry point. The Lua mod loads THIS
// DLL via package.loadlib and calls its C functions to create/read/write the shared-memory
// mapping. All game logic (reading the player transform, mirroring actors, forwarding
// input) lives in Lua using UE4SS's Lua API. The optional native combat export is
// certified for one shipping build and called only on GameThread. This DLL does
// not use UE4SS C++ headers or register a C++ mod entry point.
//
// The mapping layout must stay byte-for-byte aligned with protocol/stellarcraft_protocol.h
// (which the Minecraft Fabric mod mirrors in Proto.java).
//
// --- Lua bridge (Direction A) ---
// UE4SS statically links Lua 5.4.7 (lua_Number = double, confirmed from the "%.14g" string
// in UE4SS.dll). package.loadlib requires C functions of type lua_CFunction
// (int (*)(lua_State*)). To marshal player coordinates from Lua into the shared memory,
// we link the *official Lua 5.4.7 core* into this DLL and export a small set of
// lua_CFunction wrappers (sc_write_player_lua, etc.). The lua_State* passed in is the
// UE4SS-owned stack; because we linked the same Lua 5.4.7 core with the same ABI, our
// lua_tonumber/lua_tointeger operate on it correctly.

#define NOMINMAX
#include <windows.h>
#include <algorithm>
#include <cstdint>
#include <cstring>   // memset — clearing stale state on startup
#include <cmath>
#include "ground_ring.h"
#include "pose_frame.h"
#include <vector>
#include <string>
#include "native_combat.h"
#include "native_hit_context.h"
#include "native_hit_show.h"
#include "native_player_damage.h"

// The layout below MUST stay byte-for-byte identical to the protocol header. This file used
// to hardcode every offset, which silently went stale the moment the protocol grew (the v2
// depth reservation moved kOffRenderRing and grew kMappingBytes past the old 192 MiB). Pull
// the header in and let the compiler check our numbers instead of trusting a comment.
#include "../../protocol/stellarcraft_protocol.h"

extern "C" {
#include "lua.h"
#include "lauxlib.h"
}

static native_hit_context::Context prepare_hit_context(lua_State*,const native_combat::Result&,native_combat::Identity);

extern "C" __declspec(dllexport) int sc_enemy_damage_lua(lua_State* L) {
    auto guid=static_cast<uint32_t>(lua_tointeger(L,1)),type=static_cast<uint32_t>(lua_tointeger(L,2));
    auto identity=native_combat::identity(static_cast<std::uintptr_t>(lua_tointeger(L,6)));
    const bool matched=identity.guid&&(!guid||guid==identity.guid)&&(!type||type==identity.type);
    auto result=matched?native_combat::actor(identity.guid,identity.type,
        static_cast<float>(lua_tonumber(L,3)),static_cast<float>(lua_tonumber(L,4)),static_cast<uint32_t>(lua_tointeger(L,5))):native_combat::Result{};
    if(!matched) result.reason="UE/native identity mismatch";
    const auto hit=prepare_hit_context(L,result,identity);
    const auto show=hit.ready?native_hit_show::request(static_cast<std::uintptr_t>(lua_tointeger(L,6)),hit,
        static_cast<float>(lua_tonumber(L,4))):native_hit_show::Result{};
    lua_newtable(L);
    lua_pushinteger(L,identity.guid);lua_setfield(L,-2,"guid");
    lua_pushinteger(L,identity.type);lua_setfield(L,-2,"type");
    lua_pushboolean(L,result.ready);lua_setfield(L,-2,"ready");
    lua_pushboolean(L,result.applied);lua_setfield(L,-2,"applied");
    lua_pushnumber(L,result.before);lua_setfield(L,-2,"before");
    lua_pushnumber(L,result.after);lua_setfield(L,-2,"after");
    lua_pushnumber(L,result.maximum);lua_setfield(L,-2,"maximum");
    lua_pushinteger(L,result.sourceKey);lua_setfield(L,-2,"sourceKey");
    lua_pushboolean(L,result.invulnerable);lua_setfield(L,-2,"invulnerable");
    lua_pushstring(L,result.reason);lua_setfield(L,-2,"reason");
    lua_pushboolean(L,hit.ready);lua_setfield(L,-2,"hitContextReady");
    lua_pushstring(L,hit.reason);lua_setfield(L,-2,"hitContextReason");
    lua_pushboolean(L,show.requested);lua_setfield(L,-2,"hitShowRequested");
    lua_pushstring(L,show.reason);lua_setfield(L,-2,"hitShowReason");
    lua_pushboolean(L,hit.ready&&hit.request.heavyRequested);lua_setfield(L,-2,"jumpHeavy");
    lua_pushboolean(L,show.heavy);lua_setfield(L,-2,"hitShowHeavy");
    if(hit.ready) {
        lua_pushnumber(L,hit.direction.x);lua_setfield(L,-2,"hitDirectionX");
        lua_pushnumber(L,hit.direction.y);lua_setfield(L,-2,"hitDirectionY");
        lua_pushnumber(L,hit.direction.z);lua_setfield(L,-2,"hitDirectionZ");
    }
    return 1;
}

// ---- Protocol constants ---------------------------------------------------------------------
//
// Offsets and sizes are NOT repeated here any more — they come from the protocol header
// included above, which is the single source of truth shared with the Java side. Only the
// mapping SIZE is declared locally, guarded by a static_assert.
namespace P = stellarcraft::proto;

// Windows commits a section's pages lazily, so headroom costs no physical memory until it is
// actually written. That is what makes reserving the v2 depth layer (3 x 33 MB) free until we
// start exporting depth. Must still be >= the protocol layout, or MapViewOfFile on the Java
// side would ask for more than this section actually is.
static constexpr uint64_t   kMappingBytes       = 320ULL * 1024 * 1024; // 320 MiB
static_assert(kMappingBytes >= P::kMappingBytes,
              "mapping is smaller than the protocol layout - bump kMappingBytes");

static constexpr uint32_t   kMagic              = P::kMagic;
static constexpr uint32_t   kVersion            = P::kVersion;
static const wchar_t*       kMappingName        = P::kMappingName;
static constexpr uint64_t   kOffHeader          = P::kOffHeader;
static constexpr uint64_t   kOffSbState         = P::kOffSbState;
static constexpr uint64_t   kOffMcState         = P::kOffMcState;
// Region sizes, used to clear stale state on startup (see sc_create_mapping).
static constexpr uint64_t   kSbStateBytes       = 0x100;
static constexpr uint64_t   kMcStateBytes       = 0x100;

// Header: magic(u32) version(u32) sbPid(u32) mcPid(u32) sbHeartbeatMs(u64) mcHeartbeatMs(u64)
static constexpr uint64_t   kHdrMagic           = 0x00;
static constexpr uint64_t   kHdrVersion         = 0x04;
static constexpr uint64_t   kHdrSbPid           = 0x08;
static constexpr uint64_t   kHdrMcPid           = 0x0C;
static constexpr uint64_t   kHdrSbHb            = 0x10;
static constexpr uint64_t   kHdrMcHb            = 0x18;

// SbState (seqlock): seq(u32) flags(u32) levelId(u32) teleport(u32)
//   posX/Y/Z(double) yaw(f32) pitch(f32) viewW(u32) viewH(u32) gameHour(f32)
static constexpr uint64_t   kSbSeq              = 0x00;
static constexpr uint64_t   kSbFlags            = 0x04;
static constexpr uint64_t   kSbPosX             = 0x10;
static constexpr uint64_t   kSbPosY             = 0x18;
static constexpr uint64_t   kSbPosZ             = 0x20;
static constexpr uint64_t   kSbYaw              = 0x28;
static constexpr uint64_t   kSbPitch            = 0x2C;

static HANDLE   g_hMapping = nullptr;
static uint8_t* g_pBase    = nullptr;

static native_hit_context::Context prepare_hit_context(lua_State* L,const native_combat::Result& result,native_combat::Identity identity) {
    using namespace native_hit_context;
    Context unavailable;
    if(lua_gettop(L)<17||!lua_isnumber(L,7)||!lua_isnumber(L,8)||!lua_isnumber(L,9)||!lua_isinteger(L,17)) return unavailable;
    Request request;
    request.source={float(lua_tonumber(L,7)),float(lua_tonumber(L,8)),float(lua_tonumber(L,9))};
    request.serial=static_cast<uint32_t>(lua_tointeger(L,10));request.uid=static_cast<uint32_t>(lua_tointeger(L,11));
    request.world=static_cast<uint32_t>(lua_tointeger(L,12));request.pid=static_cast<uint32_t>(lua_tointeger(L,13));
    request.kind=static_cast<uint32_t>(lua_tointeger(L,14));request.weapon=static_cast<uint32_t>(lua_tointeger(L,15));
    request.timeLow=static_cast<uint32_t>(lua_tointeger(L,17));
    request.hasTrajectory=lua_isnumber(L,18)&&lua_isnumber(L,19)&&lua_isnumber(L,20);
    if(request.hasTrajectory) request.trajectory={float(lua_tonumber(L,18)),float(lua_tonumber(L,19)),float(lua_tonumber(L,20))};
    if(!result.applied||result.after<=0) {unavailable.reason="no surviving damaged target";return unavailable;}
    const auto base=reinterpret_cast<std::uintptr_t>(GetModuleHandleW(nullptr));
    if(!g_pBase||!position_certified(base)) {unavailable.reason="hit position ABI unavailable";return unavailable;}
    const auto now=GetTickCount64();
    const auto* header=reinterpret_cast<const volatile P::Header*>(g_pBase);
    const auto* mc=reinterpret_cast<const volatile P::McState*>(g_pBase+P::kOffMcState);
    const auto seq=mc->seq;std::atomic_thread_fence(std::memory_order_acquire);
    const bool fresh=!(seq&1u)&&seq==static_cast<uint32_t>(lua_tointeger(L,16))
        &&header->mcPid==request.pid&&header->sbPid==GetCurrentProcessId()
        &&header->mcHeartbeatMs<=now&&now-header->mcHeartbeatMs<500
        &&mc->worldId==request.world&&(mc->flags&P::kMcInWorld)&&(mc->flags&P::kMcControlSteve)
        &&mc->motionEpoch==GetCurrentProcessId()&&mc->motionTimeMs<=now&&now-mc->motionTimeMs<=250;
    std::atomic_thread_fence(std::memory_order_acquire);
    if(!fresh||mc->seq!=seq) {unavailable.reason="stale hit session";return unavailable;}
    const auto* sb=reinterpret_cast<const volatile P::SbState*>(g_pBase+P::kOffSbState);
    const auto sbSeq=sb->seq;std::atomic_thread_fence(std::memory_order_acquire);
    const auto sbFlags=sb->flags;request.hostLevel=sb->levelId;
    std::atomic_thread_fence(std::memory_order_acquire);
    if((sbSeq&1u)||sb->seq!=sbSeq||!request.hostLevel
        ||(sbFlags&(P::kSbInGame|P::kSbControlSteve))!=(P::kSbInGame|P::kSbControlSteve)
        ||(sbFlags&(P::kSbMenuOpen|P::kSbLoading|P::kSbPaused|P::kSbNativeUi))) {
        unavailable.reason=!request.hostLevel?"host map identity unavailable":"host not in MC combat state";return unavailable;
    }
    // The UID must still be the published hostile target in this world. Read a
    // complete actor-table generation, not a partially updated record.
    const auto* table=reinterpret_cast<const volatile P::ActorTable*>(g_pBase+P::kOffActorTable);
    const auto actorSeq=table->seq;const auto count=table->count;
    std::atomic_thread_fence(std::memory_order_acquire);int matches=0;
    if(!(actorSeq&1u)&&count<=P::kMaxActors) for(uint32_t i=0;i<count;++i) {
        const auto& actor=table->actors[i];
        if(actor.uid==request.uid&&actor.world==request.world&&actor.nativeGuid==identity.guid&&actor.nativeType==identity.type
            &&(actor.flags&P::kActorHostile)&&!(actor.flags&(P::kActorDead|P::kActorInvuln))) ++matches;
    }
    std::atomic_thread_fence(std::memory_order_acquire);
    if(matches!=1||table->seq!=actorSeq) {unavailable.reason="hit UID no longer published";return unavailable;}
    const auto after=native_combat::identity(static_cast<std::uintptr_t>(lua_tointeger(L,6)));
    const auto current=native_combat::actor(after.guid,after.type,0,1,0);
    if(!current.ready||current.invulnerable||current.before<=0) {
        unavailable.reason="hit target state changed";return unavailable;
    }
    Vec3 target;native_combat::ControlGuid control;
    if(!after.entity||!native_combat::read(after.entity+0x4CC,target)||!native_combat::read(after.entity+0x4A4,control)) {
        unavailable.reason="hit target position unavailable";return unavailable;
    }
    static Session session;
    return prepare(session,request,result,identity,after,control,target,true,static_cast<uint32_t>(now));
}

// Lifecycle barrier: owned state only, including when UObjects are already gone.
extern "C" __declspec(dllexport) int sc_world_barrier_lua(lua_State*) {
    native_player_damage::enabled=false;
    ++native_player_damage::worldGeneration;
    native_player_damage::watched=0;
    native_player_damage::refreshed=0;
    std::lock_guard<std::mutex> lock(native_player_damage::hitMutex);
    native_player_damage::readHit=native_player_damage::nextHit;
    return 0;
}

extern "C" __declspec(dllexport) int sc_native_hits_lua(lua_State* L) {
    lua_newtable(L);int index=0;
    // Lua must revalidate the captured enemy through GameThread-only game
    // lookups. A worker callback leaves the queue for the next valid consumer.
    DWORD thread=0;const auto base=reinterpret_cast<std::uintptr_t>(GetModuleHandleW(nullptr));
    if(!native_combat::read(base+0x706B538,thread)||thread!=GetCurrentThreadId()) return 1;
    std::lock_guard<std::mutex> lock(native_player_damage::hitMutex);
    while(native_player_damage::readHit<native_player_damage::nextHit) {
        const auto& h=native_player_damage::hits[++native_player_damage::readHit%128];
        lua_newtable(L);
        const char* names[]={"id","time","player","source","world","sourceObject","sourceGuid","sourceType"};
        const std::uint64_t values[]={h.id,h.time,h.player,h.source,h.world,h.sourceObject,h.origin.guid,h.origin.type};
        for(int i=0;i<8;++i) {lua_pushinteger(L,values[i]);lua_setfield(L,-2,names[i]);}
        const char* positions[]={"sourceX","sourceY","sourceZ"};
        const float coordinates[]={h.origin.x,h.origin.y,h.origin.z};
        for(int i=0;i<3;++i) {lua_pushnumber(L,coordinates[i]);lua_setfield(L,-2,positions[i]);}
        lua_pushnumber(L,h.damage);lua_setfield(L,-2,"damage");lua_rawseti(L,-2,++index);
    }
    return 1;
}
extern "C" __declspec(dllexport) int sc_combat_hit_lua(lua_State* L) {
    if(!g_pBase) return 0;
    const auto id=static_cast<uint64_t>(lua_tointeger(L,1)),time=static_cast<uint64_t>(lua_tointeger(L,2));
    const auto player=static_cast<uint32_t>(lua_tointeger(L,3)),source=static_cast<uint32_t>(lua_tointeger(L,4));
    const auto world=static_cast<uint32_t>(lua_tointeger(L,11));
    const auto* mc=reinterpret_cast<P::McState*>(g_pBase+P::kOffMcState);
    if(!id||player!=native_player_damage::guid||world!=mc->worldId||GetTickCount64()-time>500) return 0;
    auto* at=g_pBase+P::kOffCombat+0x80+(id%P::kCombatSlots)*sizeof(P::CombatHit);
    auto* record=reinterpret_cast<P::CombatHit*>(at);
    const auto seq=(record->seq+2u)&~1u;record->seq=seq-1;std::atomic_thread_fence(std::memory_order_seq_cst);
    record->flags=static_cast<uint32_t>(lua_tointeger(L,10));record->id=id;record->timeMs=time;
    record->playerGuid=player;record->rawSource=source;record->sbPid=GetCurrentProcessId();record->world=world;
    record->damage=static_cast<float>(lua_tonumber(L,5));record->sourceGuid=static_cast<uint32_t>(lua_tointeger(L,6));
    record->x=lua_tonumber(L,7);record->y=lua_tonumber(L,8);record->z=lua_tonumber(L,9);
    std::atomic_thread_fence(std::memory_order_seq_cst);record->seq=seq;
    *reinterpret_cast<uint64_t*>(g_pBase+P::kOffCombat)=id;
    return 0;
}

extern "C" __declspec(dllexport) int sc_player_guard_lua(lua_State* L) {
    bool active=lua_toboolean(L,3)!=0;
    if(g_pBase) {
        native_player_damage::world=reinterpret_cast<P::McState*>(g_pBase+kOffMcState)->worldId;
        const auto heartbeat=*reinterpret_cast<uint64_t*>(g_pBase+kHdrMcHb);
        const auto flags=*reinterpret_cast<uint32_t*>(g_pBase+kOffMcState+4);
        active=active&&(flags&P::kMcInWorld)&&heartbeat&&GetTickCount64()-heartbeat<1000;
    } else active=false;
    bool ready=native_player_damage::update(static_cast<uint32_t>(lua_tointeger(L,1)),static_cast<uint32_t>(lua_tointeger(L,2)),active);
    if(g_pBase) {
        auto* s=g_pBase+kOffSbState+P::kPlayerHurtOffset;
        auto* seq=reinterpret_cast<uint32_t*>(s);
        uint32_t next=(*seq+2u)&~1u;*seq=next-1;std::atomic_thread_fence(std::memory_order_seq_cst);
        *reinterpret_cast<uint32_t*>(s+4)=native_player_damage::guid;
        *reinterpret_cast<double*>(s+8)=native_player_damage::total;
        *reinterpret_cast<uint32_t*>(s+16)=native_player_damage::attacker;
        *reinterpret_cast<uint32_t*>(s+20)=native_player_damage::blocked;
        std::atomic_thread_fence(std::memory_order_seq_cst);*seq=next;
    }
    lua_newtable(L);
    lua_pushinteger(L,native_player_damage::observed.load());lua_setfield(L,-2,"observed");
    lua_pushinteger(L,native_player_damage::observedGuid.load());lua_setfield(L,-2,"seenGuid");
    lua_pushinteger(L,native_player_damage::observedStat.load());lua_setfield(L,-2,"seenStat");
    lua_pushnumber(L,native_player_damage::observedAmount.load());lua_setfield(L,-2,"seenAmount");
    lua_pushinteger(L,native_player_damage::observedThread.load());lua_setfield(L,-2,"seenThread");
    lua_pushinteger(L,native_player_damage::observedGameThread.load());lua_setfield(L,-2,"gameThread");
    lua_pushinteger(L,native_player_damage::observedMatch.load());lua_setfield(L,-2,"seenMatch");
    lua_pushboolean(L,ready&&native_player_damage::enabled);lua_setfield(L,-2,"active");
    lua_pushinteger(L,native_player_damage::blocked);lua_setfield(L,-2,"blocked");
    lua_pushnumber(L,native_player_damage::total);lua_setfield(L,-2,"total");
    float hp=0;native_combat::read(native_player_damage::watched+0x11c,hp);
    lua_pushnumber(L,hp);lua_setfield(L,-2,"hp");return 1;
}

// -----------------------------------------------------------------------------------
// create_mapping() -> lightuserdata (base address) or nil on failure.
// -----------------------------------------------------------------------------------
extern "C" __declspec(dllexport) uintptr_t sc_create_mapping()
{
    if (g_pBase) return reinterpret_cast<uintptr_t>(g_pBase);

    g_hMapping = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE,
                                    (DWORD)(kMappingBytes >> 32),
                                    (DWORD)(kMappingBytes & 0xFFFFFFFF),
                                    kMappingName);
    if (!g_hMapping) return 0;

    // CreateFileMappingW keeps an ALREADY-EXISTING mapping alive rather than recreating it,
    // so a mapping left behind by a previous session comes back with its old contents. The
    // stale SbState.flags would still carry the F4 latch (CONTROL_STEVE 0x10) from the last
    // time the player switched to Steve -- making the game boot straight into STEVE mode
    // instead of EVE. We therefore explicitly clear the state regions we own.
    //
    // Clear only the two state blocks (not the rings: a leftover reader tail is harmless
    // because the writers re-publish their cursors, and zeroing the big rings would cost a
    // needless 96 MiB memset on every startup).
    bool fresh = (GetLastError() != ERROR_ALREADY_EXISTS);

    g_pBase = static_cast<uint8_t*>(MapViewOfFile(g_hMapping, FILE_MAP_ALL_ACCESS, 0, 0, kMappingBytes));
    if (!g_pBase) { CloseHandle(g_hMapping); g_hMapping = nullptr; return 0; }

    // Always start in EVE mode: zero SbState (includes flags) and McState so neither side
    // can inherit a stale handoff.
    memset(g_pBase + kOffSbState, 0, kSbStateBytes);
    memset(g_pBase + kOffMcState, 0, kMcStateBytes);
    memset(g_pBase+P::kOffActorTable,0,sizeof(P::ActorTable));
    memset(g_pBase+P::kOffCombat,0,P::kCombatBytes);

    // Init header.
    *reinterpret_cast<uint32_t*>(g_pBase + kOffHeader + kHdrMagic)   = kMagic;
    *reinterpret_cast<uint32_t*>(g_pBase + kOffHeader + kHdrVersion) = kVersion;
    *reinterpret_cast<uint32_t*>(g_pBase + kOffHeader + kHdrSbPid)   = GetCurrentProcessId();
    *reinterpret_cast<uint32_t*>(g_pBase + kOffHeader + kHdrMcPid)   = 0;
    *reinterpret_cast<uint64_t*>(g_pBase + kOffHeader + kHdrSbHb)    = 0;
    *reinterpret_cast<uint64_t*>(g_pBase + kOffHeader + kHdrMcHb)    = 0;

    if (!fresh) {
        // Existing mapping reused: make sure a previous session's overlay cannot keep
        // believing it owns the character before the Lua side publishes its first frame.
        *reinterpret_cast<uint32_t*>(g_pBase + kOffSbState + kSbFlags) = 0;
        *reinterpret_cast<uint32_t*>(g_pBase + kOffMcState + 0x04)     = 0;
    }

    return reinterpret_cast<uintptr_t>(g_pBase);
}

// -----------------------------------------------------------------------------------
// sc_write_heartbeat(base) -> void. Updates the SB heartbeat to GetTickCount64().
// -----------------------------------------------------------------------------------
extern "C" __declspec(dllexport) void sc_write_heartbeat(uintptr_t base)
{
    if (!base) return;
    *reinterpret_cast<uint64_t*>(base + kOffHeader + kHdrSbHb) = GetTickCount64();
}

// -----------------------------------------------------------------------------------
// sc_write_player(base, flags, x, y, z, yaw, pitch) -> void.
// Writes the SB->MC state via a seqlock (odd seq during write, even after).
// x/y/z are in Minecraft space (blocks, Y-up). yaw/pitch in degrees.
// -----------------------------------------------------------------------------------
static void write_player_frame(uintptr_t base,
                                                     int32_t flags,
                                                     double x, double y, double z,
                                                     float yaw, float pitch, uint32_t teleport = 0,
                                                     uint64_t motionFrame = 0, uint32_t contacts = 0,
                                                     double vx = 0, double vy = 0, double vz = 0, uint32_t level = 0, uint32_t map = 0)
{
    if (!base) return;
    uint8_t* s = reinterpret_cast<uint8_t*>(base) + kOffSbState;

    uint32_t seq = (*reinterpret_cast<uint32_t*>(s + kSbSeq) + 2u) & ~1u;
    *reinterpret_cast<uint32_t*>(s + kSbSeq) = seq - 1u;   // begin write (odd)
    std::atomic_thread_fence(std::memory_order_seq_cst);

    // NOTE: do NOT overwrite the whole flags field. The F4 hotkey (sc_set_control_lua) writes
    // the CONTROL_STEVE latch (0x10) directly into SbState.flags outside this function, and
    // the overlay process writes the input-authority bits (0x20 EDIT / 0x40 NATIVE_UI) at
    // 60 Hz from its own thread. The per-frame player mirror must clobber none of them.
    //
    // Bit ownership:
    //   0x0F  transient status      -> Lua        (IN_GAME / MENU_OPEN / LOADING / PAUSED)
    //   0x10  CONTROL_STEVE latch   -> F4 hotkey  (character ownership)
    //   0x20  EDIT                  -> overlay    (MC owns keyboard/mouse right now)
    //   0x40  NATIVE_UI             -> overlay    (SB's own UI owns input)
    //
    // The overlay is the sole author of 0x20/0x40 because it owns the input hooks and knows
    // whether the game window is focused. We merely preserve whatever it last published —
    // including when the overlay is not running, in which case both bits stay 0 and MC
    // correctly treats itself as having no authority (no input can be forwarded without the
    // overlay anyway). For a Lua-only debugging session set SbFlags bit 5 by hand.
    uint32_t cur     = *reinterpret_cast<uint32_t*>(s + kSbFlags);
    uint32_t preserve = cur & 0x70u;            // 0x10 latch | 0x20 EDIT | 0x40 NATIVE_UI
    uint32_t low     = (uint32_t)flags & 0x0Fu; // transient status bits only
    *reinterpret_cast<int32_t*>(s + kSbFlags) = (int32_t)(preserve | low);

    *reinterpret_cast<double*>(s + kSbPosX)  = x;
    *reinterpret_cast<double*>(s + kSbPosY)  = y;
    *reinterpret_cast<double*>(s + kSbPosZ)  = z;
    *reinterpret_cast<float*>(s + kSbYaw)    = yaw;
    *reinterpret_cast<float*>(s + kSbPitch)  = pitch;
    *reinterpret_cast<uint32_t*>(s + 0x0C) = teleport;
    auto* state = reinterpret_cast<P::SbState*>(s);
    state->levelId = (low&P::kSbInGame)?level:0;
    state->mapId = (low&P::kSbInGame)?map:0;
    state->motionTimeMs = GetTickCount64();
    state->motionFrame = motionFrame;
    state->motionEpoch = GetCurrentProcessId();
    state->contactFlags = contacts;
    state->velocityX = vx; state->velocityY = vy; state->velocityZ = vz;

    std::atomic_thread_fence(std::memory_order_release);
    *reinterpret_cast<uint32_t*>(s + kSbSeq) = seq;   // end write (even)
}

// Preserve the existing C export ABI; only the Lua publisher adds map identity.
extern "C" __declspec(dllexport) void sc_write_player(uintptr_t base,int32_t flags,
    double x,double y,double z,float yaw,float pitch,uint32_t teleport=0,
    uint64_t motionFrame=0,uint32_t contacts=0,double vx=0,double vy=0,double vz=0) {
    write_player_frame(base,flags,x,y,z,yaw,pitch,teleport,motionFrame,contacts,vx,vy,vz);
}

// -----------------------------------------------------------------------------------
// sc_read_mc_ready(base) -> int. 1 if the MC side has written its PID (handshake done).
// -----------------------------------------------------------------------------------
extern "C" __declspec(dllexport) int32_t sc_read_mc_ready(uintptr_t base)
{
    if (!base) return 0;
    return *reinterpret_cast<uint32_t*>(base + kOffHeader + kHdrMcPid) != 0 ? 1 : 0;
}

// -----------------------------------------------------------------------------------
// sc_write_bytes(base, offset, data_ptr, len) / sc_read_bytes — generic byte access
// for anything the Lua side needs beyond the typed helpers above.
// -----------------------------------------------------------------------------------
extern "C" __declspec(dllexport) void sc_write_bytes(uintptr_t base, uint64_t offset, const uint8_t* data, uint64_t len)
{
    if (!base || !data) return;
    memcpy(reinterpret_cast<uint8_t*>(base) + offset, data, len);
}

extern "C" __declspec(dllexport) void sc_read_bytes(uintptr_t base, uint64_t offset, uint8_t* out, uint64_t len)
{
    if (!base || !out) return;
    memcpy(out, reinterpret_cast<uint8_t*>(base) + offset, len);
}

// -----------------------------------------------------------------------------------
// sc_get_base() -> uintptr_t. Returns the mapping base address (0 if not created).
// Used by dx12_hook.cpp to read the overlay frame that Minecraft publishes.
// -----------------------------------------------------------------------------------
extern "C" __declspec(dllexport) uintptr_t sc_get_base()
{
    return reinterpret_cast<uintptr_t>(g_pBase);
}

// -----------------------------------------------------------------------------------
// sc_shutdown() -> void. Unmaps and closes (for clean game exit).
// -----------------------------------------------------------------------------------
extern "C" __declspec(dllexport) void sc_shutdown()
{
    if (g_pBase) { UnmapViewOfFile(g_pBase); g_pBase = nullptr; }
    if (g_hMapping) { CloseHandle(g_hMapping); g_hMapping = nullptr; }
}

// -----------------------------------------------------------------------------------
// Lua bridge (lua_CFunction exports) — callable from UE4SS Lua via package.loadlib.
// These operate on the UE4SS-owned lua_State* and the DLL's own g_pBase mapping.
// ===================================================================================

// sc_create_mapping_lua() -> boolean. Explicitly creates the mapping and returns
// true/false. Lua calls this (rather than relying on DllMain auto-create) so the
// mapping survives even if the DLL is unloaded by package.loadlib afterward.
extern "C" __declspec(dllexport) int sc_create_mapping_lua(lua_State* L)
{
    uintptr_t base = sc_create_mapping();
    lua_pushboolean(L, base != 0);
    return 1;
}

// sc_write_player_lua(flags, x, y, z, yaw, pitch) -> nil
// Writes the SB->MC player state via seqlock. x/y/z are in Minecraft space (blocks).
extern "C" __declspec(dllexport) int sc_write_player_lua(lua_State* L)
{
    if (!g_pBase) { lua_pushnil(L); return 1; }

    int    flags = (int)luaL_optinteger(L, 1, 0);
    double x     = luaL_optnumber(L, 2, 0.0);
    double y     = luaL_optnumber(L, 3, 0.0);
    double z     = luaL_optnumber(L, 4, 0.0);
    float  yaw   = (float)luaL_optnumber(L, 5, 0.0);
    float  pitch = (float)luaL_optnumber(L, 6, 0.0);

    uint32_t teleport = (uint32_t)luaL_optinteger(L, 7, 0);
    write_player_frame(reinterpret_cast<uintptr_t>(g_pBase), flags, x, y, z, yaw, pitch, teleport,
        (uint64_t)luaL_optinteger(L,8,0), (uint32_t)luaL_optinteger(L,9,0),
        luaL_optnumber(L,10,0),luaL_optnumber(L,11,0),luaL_optnumber(L,12,0),
        static_cast<uint32_t>(luaL_optinteger(L,13,0)),static_cast<uint32_t>(luaL_optinteger(L,14,0)));
    return 0;
}

// sc_write_actor_lua(uid, flags, x, y, z, yaw, width, height, health) -> nil
// Appends/updates one actor record in the SB->MC actor table (used by Lua to mirror
// Naytiba/characters). x/y/z in blocks, width/height in blocks, health 0..1.
extern "C" __declspec(dllexport) int sc_write_actor_lua(lua_State* L)
{
    if (!g_pBase) { lua_pushnil(L); return 1; }

    // Actor table layout (mirror of Proto.java ACTOR_*): header at OFF_ACTOR_TABLE,
    // count at +0x04, records at +0x40, each 64 bytes.
    const uint64_t offActorTable = P::kOffActorTable;
    const uint64_t kActorCount   = 0x04;
    const uint64_t kActorData    = 0x40;
    const uint64_t kRecordSize   = 64;
    const uint64_t kMaxActors    = 256;

    int   uid    = (int)luaL_optinteger(L, 1, 0);
    int   flags  = (int)luaL_optinteger(L, 2, 0);
    float x      = (float)luaL_optnumber(L, 3, 0.0);
    float y      = (float)luaL_optnumber(L, 4, 0.0);
    float z      = (float)luaL_optnumber(L, 5, 0.0);
    float yaw    = (float)luaL_optnumber(L, 6, 0.0);
    float width  = (float)luaL_optnumber(L, 7, 0.0);
    float height = (float)luaL_optnumber(L, 8, 0.0);
    float health = (float)luaL_optnumber(L, 9, 0.0);

    uint8_t* base = g_pBase + offActorTable;
    uint32_t count = *reinterpret_cast<uint32_t*>(base + kActorCount);

    // Find existing record by uid, else append.
    uint32_t idx = count;
    for (uint32_t i = 0; i < count && i < kMaxActors; i++) {
        uint8_t* rec = base + kActorData + i * kRecordSize;
        if (*reinterpret_cast<int32_t*>(rec + 0x00) == uid) { idx = i; break; }
    }

    if (idx >= kMaxActors) return 0;
    uint8_t* rec = base + kActorData + idx * kRecordSize;

    *reinterpret_cast<int32_t*>(rec + 0x00) = uid;      // AR_UID
    *reinterpret_cast<int32_t*>(rec + 0x04) = flags;    // AR_FLAGS
    *reinterpret_cast<float*>(rec + 0x08)   = x;        // AR_X
    *reinterpret_cast<float*>(rec + 0x0C)   = y;        // AR_Y
    *reinterpret_cast<float*>(rec + 0x10)   = z;        // AR_Z
    *reinterpret_cast<float*>(rec + 0x14)   = yaw;      // AR_YAW
    *reinterpret_cast<float*>(rec + 0x18)   = width;    // AR_WIDTH
    *reinterpret_cast<float*>(rec + 0x1C)   = height;   // AR_HEIGHT
    *reinterpret_cast<float*>(rec + 0x20)   = health;   // AR_HEALTH
    for(int i=0;i<4;++i) *reinterpret_cast<uint32_t*>(rec+40+i*4)=static_cast<uint32_t>(luaL_optinteger(L,10+i,0));

    if (idx == count && count < kMaxActors) {
        *reinterpret_cast<uint32_t*>(base + kActorCount) = count + 1;
    }
    return 0;
}

// sc_set_actor_count_lua(n) -> nil. Resets the actor table count (call at frame start
// before repopulating, so removed actors drop out).
extern "C" __declspec(dllexport) int sc_set_actor_count_lua(lua_State* L)
{
    if (!g_pBase) return 0;
    uint32_t n = (uint32_t)luaL_optinteger(L, 1, 0);
    auto* table=reinterpret_cast<P::ActorTable*>(g_pBase+P::kOffActorTable);
    if(n==0) { table->seq=((table->seq+2u)&~1u)-1; std::atomic_thread_fence(std::memory_order_seq_cst); table->count=0; }
    else table->count=std::min(n,P::kMaxActors);
    if(lua_gettop(L)>1) {
        auto* hdr=g_pBase+P::kOffActorTable;
        *reinterpret_cast<uint64_t*>(hdr+8)=GetTickCount64();
        *reinterpret_cast<uint32_t*>(hdr+16)=GetCurrentProcessId();
        *reinterpret_cast<uint32_t*>(hdr+20)=static_cast<uint32_t>(lua_tointeger(L,2));
        std::atomic_thread_fence(std::memory_order_seq_cst); if(table->seq&1) ++table->seq;
    }
    return 0;
}

// sc_read_mc_control_lua() -> integer. Returns the MC->SB control flags (bit 8 = CONTROL_STEVE,
// i.e. 256), so Lua can freeze/unfreeze Eve based on MC's control state.
extern "C" __declspec(dllexport) int sc_read_mc_control_lua(lua_State* L)
{
    if (!g_pBase) { lua_pushinteger(L, 0); return 1; }
    // MC state at OFF_MC_STATE, flags at +0x04 (seqlock; just read flags non-atomically).
    uint8_t* s = g_pBase + 0x200;
    int32_t flags = *reinterpret_cast<int32_t*>(s + 0x04);
    lua_pushinteger(L, flags);
    return 1;
}

// Read the newest complete slot; concurrent writes are rejected by sequence + CRC.
static int read_pose_frame(lua_State* L, int first, int last, uint32_t id=0, uint32_t world=0,
                           const char* uuid=nullptr)
{
    if(!g_pBase) {lua_pushnil(L);return 1;}
    auto* header=reinterpret_cast<const volatile P::Header*>(g_pBase);
    auto* mc=reinterpret_cast<const volatile P::McState*>(g_pBase+P::kOffMcState);
    const auto now=GetTickCount64();
    for (int slot=first;slot<=last;++slot) {
        auto* at=g_pBase+P::kOffRenderRing+slot*P::kNativePoseStride;
        auto* shared=reinterpret_cast<const volatile P::NativePoseHeader*>(at);
        const auto seq=shared->seq;if(seq&1u) continue;
        std::atomic_thread_fence(std::memory_order_acquire);
        P::NativePoseHeader state;std::memcpy(&state,at,sizeof(state));
        if(state.bytes<32||state.bytes>P::kNativePoseStride-64||state.mcPid!=header->mcPid
            ||!state.mcPid||state.sbPid!=GetCurrentProcessId()||state.world!=mc->worldId
            ||state.timeMs>now||now-state.timeMs>250||id&&state.entity!=id
            ||uuid&&(state.world!=world||std::memcmp(&state.uuidHigh,uuid,16)!=0)) continue;
        std::vector<uint8_t> bytes(state.bytes);std::memcpy(bytes.data(),at+64,state.bytes);
        std::atomic_thread_fence(std::memory_order_acquire);
        if(shared->seq!=seq||!pose_valid(bytes.data(),bytes.size())) continue;
        lua_pushlstring(L,reinterpret_cast<const char*>(bytes.data()),bytes.size());return 1;
    }
    lua_pushnil(L);
    return 1;
}
extern "C" __declspec(dllexport) int sc_read_hand_frame_lua(lua_State* L) { return read_pose_frame(L,0,0); }
extern "C" __declspec(dllexport) int sc_read_body_frame_lua(lua_State* L) { return read_pose_frame(L,1,1); }
extern "C" __declspec(dllexport) int sc_read_entities_frame_lua(lua_State* L) { return read_pose_frame(L,2,2); }
extern "C" __declspec(dllexport) int sc_read_entity_frame_lua(lua_State* L) {
    auto id=luaL_optinteger(L,1,0);
    if(id<=0 || id>0x7FFFFFFF) {lua_pushnil(L);return 1;}
    auto world=(uint32_t)luaL_optinteger(L,2,0);size_t size=0;
    auto* uuid=lua_tolstring(L,3,&size);
    if(!uuid||size!=16) {lua_pushnil(L);return 1;}
    return read_pose_frame(L,3,18,(uint32_t)id,world,uuid);
}

// sc_read_mc_state_lua() -> table | nil
//
// The MC->SB half of the movement feed-forward. Minecraft publishes its interpolated feet
// position, orientation and camera mode into McState (see struct McState in
// protocol/stellarcraft_protocol.h); main.lua reads that here and drives the hidden Stellar
// Blade pawn with it, so MC's movement is what scrolls the world on screen.
//
// This is the UE-reflection analogue of SekiroCraft's movement_hook: there, MC's target
// position is written over the native physics candidate at the final position store
// ("position ownership is enforced by the candidate store and input isolation"); here the
// same ownership is expressed by writing the pawn's transform every frame.
//
// Read under the McState seqlock (the writer bumps `seq` to odd while updating and to even
// when done), so a torn read is retried rather than turning into a teleport to a
// half-written coordinate.
extern "C" __declspec(dllexport) int sc_read_mc_state_lua(lua_State* L)
{
    if (!g_pBase) { lua_pushnil(L); return 1; }
    auto* ms = reinterpret_cast<const volatile P::McState*>(g_pBase + P::kOffMcState);

    int32_t  flags = 0, camMode = 0;
    double   x = 0, y = 0, z = 0;
    float    yaw = 0, pitch = 0, eye = 0, fov = 0, camDist = 0;
    uint64_t frame = 0;
    uint64_t motionTime = 0;
    uint32_t epoch = 0, mode = 0;
    double vx = 0, vy = 0, vz = 0;
    uint32_t seq0 = 0, seq1 = 1, teleportAck = 0;

    for (int attempt = 0; attempt < 8; ++attempt) {
        seq0 = ms->seq;
        if (seq0 & 1u) continue;                                  // writer mid-update
        std::atomic_thread_fence(std::memory_order_acquire);
        flags   = ms->flags;
        teleportAck = ms->teleportAck;
        x       = ms->x;
        y       = ms->y;
        z       = ms->z;
        yaw     = ms->yaw;
        pitch   = ms->pitch;
        eye     = ms->eyeHeight;
        frame   = ms->frameCounter;
        fov     = ms->fovDeg;
        camMode = static_cast<int32_t>(ms->cameraMode);
        camDist = ms->cameraDistance;
        motionTime = ms->motionTimeMs; epoch = ms->motionEpoch; mode = ms->motionMode;
        vx = ms->velocityX; vy = ms->velocityY; vz = ms->velocityZ;
        std::atomic_thread_fence(std::memory_order_acquire);
        seq1 = ms->seq;
        if (seq0 == seq1) break;
    }
    if ((seq0 & 1u) || seq0 != seq1) { lua_pushnil(L); return 1; } // never got a clean read
    const uint64_t now = GetTickCount64();
    if (epoch != GetCurrentProcessId() || motionTime > now || now-motionTime > 250 || mode > 3
        || !std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z)
        || std::abs(x)>3e7 || std::abs(z)>3e7 || std::abs(y)>2e7
        || !std::isfinite(yaw) || !std::isfinite(pitch) || !std::isfinite(eye)
        || !std::isfinite(vx) || !std::isfinite(vy) || !std::isfinite(vz)) {
        lua_pushnil(L); return 1;
    }

    lua_newtable(L);
    lua_pushinteger(L,P::kVersion); lua_setfield(L,-2,"protocolVersion");
    lua_pushinteger(L,reinterpret_cast<P::Header*>(g_pBase)->mcPid);lua_setfield(L,-2,"mcPid");
    lua_pushinteger(L,reinterpret_cast<P::McState*>(g_pBase+P::kOffMcState)->worldId);lua_setfield(L,-2,"worldId");
    lua_pushinteger(L, (lua_Integer)seq0);      lua_setfield(L, -2, "seq");
    lua_pushinteger(L, (lua_Integer)flags);     lua_setfield(L, -2, "flags");
    lua_pushinteger(L, (lua_Integer)teleportAck); lua_setfield(L, -2, "teleportAck");
    lua_pushnumber (L, (lua_Number)x);          lua_setfield(L, -2, "x");
    lua_pushnumber (L, (lua_Number)y);          lua_setfield(L, -2, "y");
    lua_pushnumber (L, (lua_Number)z);          lua_setfield(L, -2, "z");
    lua_pushnumber (L, (lua_Number)yaw);        lua_setfield(L, -2, "yaw");
    lua_pushnumber (L, (lua_Number)pitch);      lua_setfield(L, -2, "pitch");
    lua_pushnumber (L, (lua_Number)eye);        lua_setfield(L, -2, "eyeHeight");
    lua_pushnumber (L, (lua_Number)fov);        lua_setfield(L, -2, "fovDeg");
    lua_pushinteger(L, (lua_Integer)camMode);   lua_setfield(L, -2, "cameraMode");
    lua_pushnumber (L, (lua_Number)camDist);    lua_setfield(L, -2, "cameraDistance");
    lua_pushinteger(L, (lua_Integer)frame);     lua_setfield(L, -2, "frame");
    lua_pushinteger(L,(lua_Integer)mode); lua_setfield(L,-2,"motionMode");
    lua_pushnumber(L,vx); lua_setfield(L,-2,"vx");
    lua_pushnumber(L,vy); lua_setfield(L,-2,"vy");
    lua_pushnumber(L,vz); lua_setfield(L,-2,"vz");
    return 1;
}

// sc_set_control_lua(mode) -> integer. SETS (rather than toggles) the SB->MC "hand control
// to Steve" bit (kSbControlSteve = 1<<4 = 0x10) in SbState.flags to `mode` (0 or 1) and
// returns the new flags. This is a *level* (persistent state), not a pulse: the MC side
// reads it directly as "who owns the player" (Steve vs Eve), matching SkyCraft's
// `minecraftOwnsPlayer` model. The Lua mod latches F4 presses into this level.
extern "C" __declspec(dllexport) int sc_set_control_lua(lua_State* L)
{
    if (!g_pBase) { lua_pushinteger(L, 0); return 1; }
    // SbState at OFF_SB_STATE (0x100), flags at +0x04.
    int mode = (int)luaL_optinteger(L, 1, 0);
    uint8_t* s = g_pBase + 0x100;
    uint32_t flags = *reinterpret_cast<uint32_t*>(s + 0x04);
    if (mode) {
        flags |= 0x10;   // set kSbControlSteve
    } else {
        flags &= ~0x10u; // clear kSbControlSteve
    }
    *reinterpret_cast<uint32_t*>(s + 0x04) = flags;
    lua_pushinteger(L, (lua_Integer)flags);
    return 1;
}

// -----------------------------------------------------------------------------------
// Ground height-field writer (SB -> MC collision ring, op kColGround = 4).
//
// Lua samples the ground with downward line traces and calls
//   sc_write_ground_lua(originGx, originGz, cells)
// where `cells` is an array of { gx, gz, topY, hit }. We serialise them into one
// kColGround message and push it into the collision ring (SPSC: SB is the producer).
//
// Ring layout (mirror of protocol/stellarcraft_protocol.h):
//   base = mapping + kOffCollisionRing (0x20000); head @+0x00, tail @+0x40, data @+0x80.
// -----------------------------------------------------------------------------------
static constexpr uint64_t kOffCollisionRing      = 0x20000;

// sc_write_ground_lua(originGx, originGz, cells) -> boolean
//   cells: Lua array (1-based) of tables { gx=<int>, gz=<int>, y=<number>, hit=<0|1> }
extern "C" __declspec(dllexport) int sc_write_ground_lua(lua_State* L)
{
    if (!g_pBase) { lua_pushboolean(L, 0); return 1; }
    int32_t originGx = (int32_t)luaL_optinteger(L, 1, 0);
    int32_t originGz = (int32_t)luaL_optinteger(L, 2, 0);

    if (!lua_istable(L, 3)) { lua_pushboolean(L, 0); return 1; }
    lua_Integer n = luaL_len(L, 3);
    if (n <= 0 || n > 512) { lua_pushboolean(L, 0); return 1; }

    static GroundCell cells[512];
    uint32_t count = 0;
    for (lua_Integer i = 1; i <= n; ++i) {
        lua_geti(L, 3, i);
        if (lua_istable(L, -1)) {
            lua_getfield(L, -1, "gx");  int32_t gx = (int32_t)lua_tointeger(L, -1); lua_pop(L, 1);
            lua_getfield(L, -1, "gz");  int32_t gz = (int32_t)lua_tointeger(L, -1); lua_pop(L, 1);
            lua_getfield(L, -1, "y");   double  y  = lua_tonumber(L, -1);           lua_pop(L, 1);
            lua_getfield(L, -1, "hit"); int32_t hit = (int32_t)lua_tointeger(L, -1); lua_pop(L, 1);
            cells[count++] = GroundCell{ gx, gz, (float)y, hit ? 1u : 0u };
        }
        lua_pop(L, 1);
    }

    uint8_t* ring = reinterpret_cast<uint8_t*>(g_pBase) + kOffCollisionRing;
    bool ok = write_ground_message(ring, originGx, originGz, cells, count);
    lua_pushboolean(L, ok ? 1 : 0);
    return 1;
}

// -----------------------------------------------------------------------------------
// Event-ring consumer (MC -> SB, SPSC ring, op-less: a stream of fixed 32-byte McEvent).
//
// Minecraft's DamageBridge / ExplosionMixin are the producer; Stellar Blade is the
// consumer. Lua calls sc_read_events_lua() once per tick and receives an array of
// { type, uid, a, b, c, d, flags, weapon } tables, then applies them to its own actors.
//
// Ring layout (mirror of protocol/stellarcraft_protocol.h):
//   base = mapping + kOffEventRing (0x10000); head @+0x00 (written by MC),
//   tail @+0x40 (written by SB), data @+0x80. Capacity = 0x10000 - 0x80.
//
// We advance the tail to the head after copying, so events are consumed exactly once. A
// head/tail pair that has been left inconsistent across launches is clamped defensively
// (if the producer's head is somehow behind our tail, we resync to the head).
// -----------------------------------------------------------------------------------
static constexpr uint64_t kOffEventRing    = 0x10000;
static constexpr uint64_t kEvRingHead      = 0x00;
static constexpr uint64_t kEvRingTail      = 0x40;
static constexpr uint64_t kEvRingData      = 0x80;
static constexpr uint64_t kEvRingCapacity  = 0x10000 - kEvRingData;
static constexpr uint64_t kEventBytes      = 32;   // sizeof(McEvent)

struct LuaMcEvent { uint32_t type, uid; float a, b, c, d; uint32_t flags, weapon; };

// sc_read_events_lua() -> table (array of event tables; empty if none).
extern "C" __declspec(dllexport) int sc_read_events_lua(lua_State* L)
{
    lua_newtable(L);
    if (!g_pBase) return 1;

    uint8_t* ring = reinterpret_cast<uint8_t*>(g_pBase) + kOffEventRing;
    volatile uint64_t* head = reinterpret_cast<volatile uint64_t*>(ring + kEvRingHead);
    volatile uint64_t* tail = reinterpret_cast<volatile uint64_t*>(ring + kEvRingTail);
    uint64_t h = *head;
    uint64_t t = *tail;

    // Defensive resync: never let a bogus cursor make us walk a huge buffer.
    if (t > h || h - t > (kEvRingCapacity * 4)) t = h;
    if (t == h) { *tail = t; return 1; }

    uint8_t* data = ring + kEvRingData;
    int idx = 0;
    while (t + kEventBytes <= h) {
        uint8_t ev[kEventBytes];
        for (uint64_t i = 0; i < kEventBytes; ++i) ev[i] = data[(t + i) % kEvRingCapacity];

        LuaMcEvent e;
        e.type   = *reinterpret_cast<uint32_t*>(ev + 0);
        e.uid    = *reinterpret_cast<uint32_t*>(ev + 4);
        e.a      = *reinterpret_cast<float*>(ev + 8);
        e.b      = *reinterpret_cast<float*>(ev + 12);
        e.c      = *reinterpret_cast<float*>(ev + 16);
        e.d      = *reinterpret_cast<float*>(ev + 20);
        e.flags  = *reinterpret_cast<uint32_t*>(ev + 24);
        e.weapon = *reinterpret_cast<uint32_t*>(ev + 28);

        lua_newtable(L);
        lua_pushinteger(L, (lua_Integer)e.type);   lua_setfield(L, -2, "type");
        lua_pushinteger(L, (lua_Integer)e.uid);    lua_setfield(L, -2, "uid");
        lua_pushnumber (L, (lua_Number) e.a);      lua_setfield(L, -2, "a");
        lua_pushnumber (L, (lua_Number) e.b);      lua_setfield(L, -2, "b");
        lua_pushnumber (L, (lua_Number) e.c);      lua_setfield(L, -2, "c");
        if(e.type==P::kEvHitOrigin||e.type==P::kEvHitDirection) {
            uint32_t timeLow=0;std::memcpy(&timeLow,ev+20,4);
            lua_pushinteger(L,timeLow);lua_setfield(L,-2,"timeLow");
        } else {lua_pushnumber(L,(lua_Number)e.d);lua_setfield(L,-2,"d");}
        lua_pushinteger(L, (lua_Integer)e.flags);  lua_setfield(L, -2, "flags");
        lua_pushinteger(L, (lua_Integer)e.weapon); lua_setfield(L, -2, "weapon");
        lua_seti(L, -2, ++idx);

        t += kEventBytes;
    }

    *tail = t;   // publish consumption AFTER the copies
    return 1;
}

// -----------------------------------------------------------------------------------
// DllMain: create the mapping + start a heartbeat thread automatically on load.
// This avoids the package.loadlib lua_CFunction ABI problem entirely — UE4SS's Lua is
// statically linked (symbols not exported), so we can't cleanly marshal C args from Lua.
// Instead, the DLL self-initializes when loaded: DllMain only calls kernel32 functions
// (CreateFileMapping/MapViewOfFile/CreateThread), which are loader-lock safe.
// -----------------------------------------------------------------------------------

static DWORD WINAPI heartbeat_thread(LPVOID)
{
    for (;;) {
        if (g_pBase) {
            *reinterpret_cast<uint64_t*>(g_pBase + kOffHeader + kHdrSbHb) = GetTickCount64();
        }
        Sleep(16); // ~60fps heartbeat
    }
    return 0;
}

// NOTE: the DX12 overlay hook no longer lives in this DLL. It was moved to a version.dll
// proxy (version_proxy.cpp + dx12_hook.cpp, compiled as version.dll) that loads at
// PROCESS START — the CreateSwapChain* hooks must be installed before the engine creates
// its swap chain, and UE4SS loads Lua mods far too late for that.

BOOL WINAPI DllMain(HINSTANCE, DWORD reason, LPVOID)
{
    if (reason == DLL_PROCESS_ATTACH) {
        sc_create_mapping();
        if (g_pBase) {
            CreateThread(nullptr, 0, heartbeat_thread, nullptr, 0, nullptr);
        }
    } else if (reason == DLL_PROCESS_DETACH) {
        // NOTE: intentionally do NOT call sc_shutdown() here. package.loadlib may
        // FreeLibrary this DLL right after loading it, which would trigger DETACH and
        // tear down the mapping before Minecraft opens it. Leaving the mapping alone
        // lets the kernel hold it until process exit (the OS cleans it up then). The
        // heartbeat thread also dies with the process. This is the key fix for
        // "shared memory disappears after loadlib" (F4 no-op bug).
    }
    return TRUE;
}

