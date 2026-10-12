from pathlib import Path

root=Path(__file__).resolve().parents[1]
def edit(name,old,new):
    p=root/name;s=p.read_text(encoding='utf-8');assert old in s,(name,old[:70]);p.write_text(s.replace(old,new),encoding='utf-8')

edit('protocol/stellarcraft_protocol.h','kVersion = 3;','kVersion = 4;')
edit('protocol/stellarcraft_protocol.h','kOffActorTable    = 0x300;','kOffActorTable    = 0x300;') # declaration moved below the appearance region
edit('protocol/stellarcraft_protocol.h','    inline constexpr std::uint64_t kOffActorTable    = 0x300;    // SB -> MC, seqlock\n','')
edit('protocol/stellarcraft_protocol.h','inline constexpr std::uint64_t kMappingBytes     = kOffAppearance + kAppearanceBytes;', '''inline constexpr std::uint64_t kOffActorTable = kOffAppearance + kAppearanceBytes;
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
    inline constexpr std::uint64_t kMappingBytes = kOffCombat + kCombatBytes;''')
edit('protocol/stellarcraft_protocol.h','char          name[24];       // display name, UTF-8, NUL-terminated (truncated)', 'std::uint32_t nativeGuid, nativeType, world, generation;\n        std::uint64_t reserved;')
edit('mc-mod/src/main/java/dev/stellarcraft/link/Proto.java','K_VERSION = 3;','K_VERSION = 4;')
edit('mc-mod/src/main/java/dev/stellarcraft/link/Proto.java','    public static final long OFF_ACTOR_TABLE   = 0x300;\n','')
edit('mc-mod/src/main/java/dev/stellarcraft/link/Proto.java','public static final long MAPPING_BYTES', 'public static final long MAPPING_BYTES')
p=root/'mc-mod/src/main/java/dev/stellarcraft/link/Proto.java';s=p.read_text();import re
s=re.sub(r'public static final long MAPPING_BYTES\s*=\s*OFF_APPEARANCE \+ APPEARANCE_BYTES;', '''public static final long OFF_ACTOR_TABLE = OFF_APPEARANCE + APPEARANCE_BYTES;
    public static final long OFF_COMBAT = OFF_ACTOR_TABLE + 0x4040;
    public static final int COMBAT_SLOTS = 128;
    public static final int COMBAT_HIT_SIZE = 80;
    public static final int COMBAT_BYTES = 0x80 + COMBAT_SLOTS * COMBAT_HIT_SIZE;
    public static final long MAPPING_BYTES = OFF_COMBAT + COMBAT_BYTES;''',s)
assert 'OFF_COMBAT =' in s;s=s.replace('AR_NAME        = 0x28;','AR_NAME        = 0x28;')
p.write_text(s,encoding='utf-8')

edit('sb-mod/src/shm_bridge.cpp','const uint64_t offActorTable = 0x300;', 'const uint64_t offActorTable = P::kOffActorTable;')
edit('sb-mod/src/shm_bridge.cpp','*reinterpret_cast<uint32_t*>(g_pBase + 0x300 + 0x04) = n;', '''auto* table=reinterpret_cast<P::ActorTable*>(g_pBase+P::kOffActorTable);
    if(n==0) { ++table->seq; std::atomic_thread_fence(std::memory_order_seq_cst); table->count=0; }
    else table->count=std::min(n,P::kMaxActors);
    if(lua_gettop(L)>1) {
        auto* hdr=g_pBase+P::kOffActorTable;
        *reinterpret_cast<uint64_t*>(hdr+8)=GetTickCount64();
        *reinterpret_cast<uint32_t*>(hdr+16)=GetCurrentProcessId();
        *reinterpret_cast<uint32_t*>(hdr+20)=static_cast<uint32_t>(lua_tointeger(L,2));
        std::atomic_thread_fence(std::memory_order_seq_cst); if(table->seq&1) ++table->seq;
    }''')
edit('sb-mod/src/shm_bridge.cpp','*reinterpret_cast<float*>(rec + 0x20)   = health;   // AR_HEALTH', '''*reinterpret_cast<float*>(rec + 0x20)   = health;   // AR_HEALTH
    for(int i=0;i<4;++i) *reinterpret_cast<uint32_t*>(rec+40+i*4)=static_cast<uint32_t>(luaL_optinteger(L,10+i,0));''')
edit('sb-mod/src/shm_bridge.cpp','memset(g_pBase + kOffMcState, 0, kMcStateBytes);','''memset(g_pBase + kOffMcState, 0, kMcStateBytes);
    memset(g_pBase+P::kOffActorTable,0,sizeof(P::ActorTable));
    memset(g_pBase+P::kOffCombat,0,P::kCombatBytes);''')
