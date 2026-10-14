#pragma once
#include "native_combat.h"
#include "../third_party/minhook/include/MinHook.h"
#include <atomic>
#include <intrin.h>
#include <array>
#include <mutex>

// Only the verified local Eve is protected. The game's authoritative HP debit
// becomes an MC health fraction; the client replay must never produce a second hit.
namespace native_player_damage {
using Diff=bool(__fastcall*)(void*,int,void*,float,std::uint8_t,std::uint8_t,int);
inline Diff original=nullptr;
inline std::atomic<std::uintptr_t> watched{0};
inline std::atomic<std::uint32_t> guid{0},attacker{0},blocked{0};
inline std::atomic<ULONGLONG> refreshed{0};
inline std::atomic<double> total{0};
inline std::atomic<bool> enabled{false};
inline std::atomic<std::uint64_t> worldGeneration{0};
struct HitSource {std::uint32_t guid{},type{};float x{},y{},z{};};
struct Hit {std::uint64_t id,time;std::uint32_t player,source,world;float damage;std::uint32_t sourceObject;HitSource origin;};
inline std::mutex hitMutex;
inline std::array<Hit,128> hits{};
inline std::uint64_t nextHit=0,readHit=0;
inline std::atomic<std::uint32_t> world{0};
inline void record(float damage,int source,std::uint32_t sourceObject,HitSource origin,std::uint64_t generation) {
    std::lock_guard<std::mutex> lock(hitMutex);
    if(!enabled||generation!=worldGeneration) return;
    auto id=++nextHit;
    hits[id%hits.size()]={id,GetTickCount64(),guid.load(),source>0?static_cast<std::uint32_t>(source):0,world.load(),damage,sourceObject,origin};
    if(nextHit-readHit>hits.size()) readHit=nextHit-hits.size();
}
inline bool installed=false;
inline std::atomic<unsigned> observed{0},observedGuid{0},observedStat{0},observedThread{0},observedGameThread{0},observedMatch{0};
inline std::atomic<float> observedAmount{0};
// The game has just resolved the effect on this same stat callback. Read its
// hash chain, not sparse/free slots, and never invoke game code on a worker.
inline std::uintptr_t source_lookup(std::uintptr_t manager,std::size_t offset,std::uint32_t key) {
    using native_combat::read;
    std::uintptr_t entries=0,hash=0;std::int32_t count=0,free=0,buckets=0,index=-1;
    if(!key||!read(manager+offset,entries)||!entries||!read(manager+offset+8,count)
        ||!read(manager+offset+0x34,free)||count<1||count>16384||free<0||free>=count
        ||!read(manager+offset+0x48,buckets)||buckets<1||buckets>16384||(buckets&(buckets-1))
        ||!read(manager+offset+0x40,hash)) return 0;
    if(!hash) hash=manager+offset+0x38;
    if(!read(hash+4u*(key&(buckets-1)),index)) return 0;
    for(int n=0;n<64&&index>=0&&index<count;n++) {
        const auto slot=entries+24u*index;std::uint32_t found=0;std::uintptr_t object=0,again=0;std::int32_t next=-1;
        if(!read(slot,found)||!read(slot+8,object)||!read(slot+16,next)) return 0;
        if(found==key) {
            std::uint32_t live=0;
            if(!object||!read(object+0x20,live)||live!=key||!read(manager+offset,again)||again!=entries) return 0;
            return object;
        }
        if(next==index) return 0;
        index=next;
    }
    return 0;
}
inline HitSource hit_source(std::uintptr_t base,std::uintptr_t manager,std::uint32_t objectId,std::uint32_t rawKey,std::uintptr_t victim) {
    using native_combat::read;
    HitSource out;std::uintptr_t effect=source_lookup(manager,0x120,objectId),vt=0,target=0,owner=0,entity=0,iface=0;
    std::uint32_t key=0,id=0;
    if(!effect||!read(effect,vt)||vt!=base+0x5C0BD18||!read(effect+0x104,key)||key!=rawKey
        ||!read(effect+0x58,target)||!target||!read(target,vt)||vt!=base+0x5C0BEE0
        ||!read(target+0x60,entity)||entity!=victim
        ||!read(effect+0x60,owner)||!owner||!read(owner,vt)||vt!=base+0x5C0BEE0
        ||!read(owner+0x20,id)||source_lookup(manager,0x170,id)!=owner
        ||!read(owner+0x60,entity)||!entity||entity==victim
        ||!read(entity,vt)||vt!=base+0x5BF6730||!read(entity+0x10,iface)||iface!=base+0x5BF67D0
        ||!read(entity+0x30,out.guid)||out.guid!=id||id>=0x10000000
        ||!read(entity+0x10c,out.type)||!out.type||out.type==100
        ||!read(entity+0x4cc,out.x)||!read(entity+0x4d0,out.y)||!read(entity+0x4d4,out.z)
        ||!std::isfinite(out.x)||!std::isfinite(out.y)||!std::isfinite(out.z)
        ||std::fabs(out.x)>3e7||std::fabs(out.y)>3e7||std::fabs(out.z)>3e7
        ||source_lookup(manager,0x120,objectId)!=effect||source_lookup(manager,0x170,id)!=owner) return {};
    return out;
}
inline bool certified(std::uintptr_t base) {
    const unsigned char effectLookup[]={0x40,0x53,0x48,0x83,0xec,0x20,0x8b,0x81,0x28,0x01,0,0,0x3b,0x81,0x54,0x01,0,0,0x74,0x55,0x4c,0x63,0x89,0x68,0x01,0,0,0x4c,0x8d,0x91,0x58,0x01,0};
    const unsigned char effectChain[]={0x48,0x63,0xc8,0x48,0x8d,0x04,0x49,0x41,0x39,0x14,0xc0,0x49,0x8d,0x0c,0xc0,0x74,0x10,0x8b,0x41,0x10,0x83,0xf8,0xff,0x75,0xe7};
    const unsigned char effectVictim[]={0x49,0x89,0x7e,0x58};
    const unsigned char effectOrigin[]={0x4c,0x89,0x41,0x60};
    const unsigned char position[]={0x8b,0x81,0xc4,0x04,0,0,0xf2,0x0f,0x10,0x81,0xbc,0x04,0,0,0xf2,0x0f,0x11,0x02,0x89,0x42,0x08,0x48,0x8b,0xc2,0xc3};
    const unsigned char serverCall[]={0x49,0x8B,0x4F,0x60,0x0F,0x28,0xD8,0x4C,0x8B,0xC3,0x8B,0xD7,0x0F,0x28,0xF8,0xF3,0x0F,0x2C,0x81,0x1C,0x01,0,0,0x89,0x45,0x40,0x8B,0x44,0x24,0x68,0x89,0x44,0x24,0x30,0x0F,0xB6,0x85,0,0x01,0,0,0x88,0x44,0x24,0x28,0x0F,0xB6,0x85,0xF8,0,0,0,0x88,0x44,0x24,0x20,0xE8,0xBD,0xC4,0xEB,0xFF};
    const unsigned char serverResult[]={0x84,0xC0,0x0F,0x84,0x58,0x0E,0,0,0x49,0x8B,0x06,0xF3,0x0F,0x10,0xB4,0xB8,0x18,0x01,0,0};
    const unsigned char echoCall[]={0x8B,0x46,0x20,0x4C,0x8B,0xC7,0xF3,0x0F,0x10,0x5E,0x18,0x49,0x8B,0xCF,0x8B,0x56,0x14,0x89,0x44,0x24,0x30,0x0F,0xB6,0x46,0x1D,0x88,0x44,0x24,0x28,0x0F,0xB6,0x46,0x1C,0x88,0x44,0x24,0x20,0x48,0x89,0x7C,0x24,0x70,0xE8,0xB5,0x54,0xF4,0xFF,0x48,0x63,0x46,0x14,0xF3,0x45,0x0F,0x10,0x84,0x87,0x18,0x01,0,0,0x44,0x0F,0x2E,0x46,0x24};
    const unsigned char worldInit[]={0xe8,0x1c,0x61,0xe2,0xff,0x48,0x8b,0xc8,0xe8,0x74,0x61,0x02,0x00};
    const unsigned char eventorLoad[]={0x48,0x8d,0x15,0xdb,0xca,0xf7,0x03,0x44,0x8b,0x8e,0x80,0,0,0};
    const unsigned char client[]={0x48,0x8b,0x05,0x25,0xa9,0x59,0x05,0x48,0x85,0xc0,0x75,0x4c};
    const unsigned char target[]={0x48,0x83,0xec,0x28,0xe8,0xa7,0x56,0xdd,0xff,0x48,0x8b,0x80,0xc8,0,0,0,0x48,0x85,0xc0,0x74,0x2e,0x48,0x63,0x48,0x3c,0x85,0xc9,0x78,0xf,0x3b,0x48,0x30,0x7d,0xa,0x48,0x8b,0x40,0x28,0x48,0x8b,0xc,0xc8,0xeb,0x2,0x33,0xc9,0x48,0x85,0xc9,0x74,0x10,0x48,0x8b,0x41,0x10,0x48,0x83,0xc1,0x10,0x48,0x83,0xc4,0x28,0x48,0xff,0x60,0x38,0x33,0xc0,0x48,0x83,0xc4,0x28,0xc3};
    const unsigned char playerId[]={0x89,0x83,0xa0,4,0,0};
    // Source passed to ApplyStatDiff is the resolved owner's +0x104 key, not a GUID.
    const unsigned char sourceKey[]={0xe8,0x68,0xf3,0xee,0xff,0x8b,0x95,0xe8,0,0,0,0x48,0x8b,0xc8,0xe8,0x4a,0xe2,0xf2,0xff,0x4c,0x8b,0xe8,0x48,0x85,0xc0,0x74,0xe,0x8b,0x80,0x4,0x1,0,0,0x4d,0x8d,0x77,0x60,0x89,0x44,0x24,0x68};
    const unsigned char diff[]={0x48,0x8b,0xc4,0x55,0x53,0x56,0x41,0x54,0x41,0x57,0x48,0x8d,0x68,0xb9,0x48,0x81,0xec,0,1,0,0,0x0f,0x57,0xc0,0x0f,0x29,0x70,0xc8,0x0f,0x29,0x78,0xb8};
    // Actual server call-site is certified separately below by its CALL target.
    std::int32_t rel=0;unsigned char opcode=0;
    return native_combat::certified(base)&&native_combat::bytes(base+0x1A64CE0,diff,sizeof diff)
        &&native_combat::bytes(base+0x1AD6A10,effectLookup,sizeof effectLookup)
        &&native_combat::bytes(base+0x1AD6A60,effectChain,sizeof effectChain)
        &&native_combat::bytes(base+0x1BC609A,effectVictim,sizeof effectVictim)
        &&native_combat::bytes(base+0x1BC64CF,effectOrigin,sizeof effectOrigin)
        &&native_combat::bytes(base+0x1A30C90,position,sizeof position)
        &&native_combat::bytes(base+0x1BA87E6,serverCall,sizeof serverCall)
        &&native_combat::bytes(base+0x1BA8823,serverResult,sizeof serverResult)
        &&native_combat::bytes(base+0x1B1F7FC,echoCall,sizeof echoCall)
        &&native_combat::bytes(base+0x1A97C44,client,sizeof client)
        &&native_combat::bytes(base+0x1CC2590,target,sizeof target)
        &&native_combat::bytes(base+0x1AAC32C,playerId,sizeof playerId)
        &&native_combat::bytes(base+0x1BA87B3,sourceKey,sizeof sourceKey)
        &&native_combat::bytes(base+0x1C71B1F,worldInit,sizeof worldInit)
        &&native_combat::bytes(base+0x1C97E3E,eventorLoad,sizeof eventorLoad)
        &&native_combat::read(base+0x1BA881E,opcode)&&opcode==0xe8
        &&native_combat::read(base+0x1BA881F,rel)&&0x1BA8823+rel==0x1A64CE0;
}
inline bool engine_stat_thread(DWORD gameThread) {
    if(gameThread==GetCurrentThreadId()) return true;
    // The game's own stat callbacks run on named UE TaskGraph workers. No
    // game calls are issued here; identity remains published by GameThread.
    thread_local const bool taskGraph=[] {
        PWSTR name=nullptr;
        const auto hr=GetThreadDescription(GetCurrentThread(),&name);
        const bool ok=SUCCEEDED(hr)&&name&&wcsncmp(name,L"TaskGraphThreadHP ",18)==0;
        if(name) LocalFree(name);
        return ok;
    }();
    return taskGraph;
}
__declspec(noinline) inline bool __fastcall intercept(void* actor,int stat,void* context,float amount,std::uint8_t a,std::uint8_t b,int source) {
    const auto generation=worldGeneration.load();
    const auto base=reinterpret_cast<std::uintptr_t>(GetModuleHandleW(nullptr));
    const auto caller=reinterpret_cast<std::uintptr_t>(_ReturnAddress());
    const auto watchedActor=watched.load();
    DWORD thread=0;std::uint32_t liveGuid=0,type=0;float maxHp=0;
    if(std::isfinite(amount)&&amount<0&&(stat==1||stat==7)) {
        std::uint32_t seenGuid=0;
        native_combat::read(reinterpret_cast<std::uintptr_t>(actor)+0x30,seenGuid);
        native_combat::read(base+0x706B538,thread);
        observedGuid=seenGuid;observedStat=stat;observedAmount=amount;
        observedThread=GetCurrentThreadId();observedGameThread=thread;
        observedMatch=(reinterpret_cast<std::uintptr_t>(actor)==watched?1:0)|(enabled?2:0)|(GetTickCount64()-refreshed<500?4:0);
        ++observed;
    }
    if(native_combat::read(base+0x706B538,thread)&&engine_stat_thread(thread)
        &&(caller==base+0x1BA8823||caller==base+0x1B1F82B)
        &&enabled&&GetTickCount64()-refreshed<500&&reinterpret_cast<std::uintptr_t>(actor)==watchedActor
        &&native_combat::read(watchedActor+0x30,liveGuid)&&liveGuid==guid
        &&native_combat::read(watchedActor+0x10c,type)&&type==100
        &&std::isfinite(amount)&&amount<0&&(stat==1||stat==7)) {
        ++blocked;
        if(stat==1&&caller==base+0x1BA8823
            &&native_combat::read(watchedActor+0x120,maxHp)&&std::isfinite(maxHp)&&maxHp>=1&&maxHp<=1e7) {
            const auto delta=std::fmin(20.0,-double(amount)*20.0/maxHp);
            double previous=total.load();
            while(!total.compare_exchange_weak(previous,previous+delta)) {}
            attacker=source>0?static_cast<std::uint32_t>(source):0;
            // Certified ApplyStatExecute frame: five pushes + 0x180 allocation;
            // its eighth argument ([rbp+0xE8]) precedes conversion to +0x104.
            std::uint32_t sourceObject=0;
            native_combat::read(reinterpret_cast<std::uintptr_t>(_AddressOfReturnAddress())+0x1f0,sourceObject);
            std::uintptr_t manager=0;HitSource origin;
            if(source>0&&native_combat::read(base+0x7032568,manager)&&manager)
                origin=hit_source(base,manager,sourceObject,static_cast<std::uint32_t>(source),watchedActor);
            record(static_cast<float>(delta),source,sourceObject,origin,generation);
        }
        return false;
    }
    return original(actor,stat,context,amount,a,b,source);
}
inline bool validate_update(std::uint32_t playerId,std::uint32_t netGuid,bool active) {
    const auto base=reinterpret_cast<std::uintptr_t>(GetModuleHandleW(nullptr));
    DWORD thread=0;
    if(!native_combat::read(base+0x706B538,thread)||thread!=GetCurrentThreadId()) return false;
    if(!active) {watched=0;return installed;}
    if(!installed) {
        if(!certified(base)) return false;
        auto status=MH_Initialize();if(status!=MH_OK&&status!=MH_ERROR_ALREADY_INITIALIZED) return false;
        void* entry=reinterpret_cast<void*>(base+0x1A64CE0);
        if(MH_CreateHook(entry,reinterpret_cast<void*>(&intercept),reinterpret_cast<void**>(&original))!=MH_OK) return false;
        if(MH_EnableHook(entry)!=MH_OK) {MH_RemoveHook(entry);original=nullptr;return false;}
        installed=true;
    }
    // In this single-player build both ActorNetGuid and native PlayerId are
    // zero. Prove the selected player against the same local game world's
    // EventorActorGUID instead. The anchored Init call and log load certify
    // that the local-client cache is FSBGameWorld and this GUID lives at +0x80.
    std::uintptr_t client=0,holder=0,array=0,selected=0,vt=0,iface=0;
    std::int32_t index=-1,count=0;std::uint32_t playerGuid=0,eventorGuid=0,type=0;
    if(playerId==0||!native_combat::read(base+0x7032570,client)||!client
        ||!native_combat::read(client+0xc8,holder)||!holder
        ||!native_combat::read(holder+0x3c,index)||!native_combat::read(holder+0x30,count)
        ||index<0||count<1||count>1048576||index>=count
        ||!native_combat::read(holder+0x28,array)||!array
        ||!native_combat::read(array+8u*index,selected)||!selected
        ||!native_combat::read(selected,vt)||vt!=base+0x5BF6730
        ||!native_combat::read(selected+0x10,iface)||iface!=base+0x5BF67D0
        ||!native_combat::read(selected+0x30,playerGuid)||playerGuid==0||playerGuid>=0x10000000
        ||!native_combat::read(client+0x80,eventorGuid)||eventorGuid!=playerGuid
        ||(netGuid!=0&&netGuid!=playerGuid)
        ||!native_combat::read(selected+0x10c,type)||type!=100) return false;
    void* manager=nullptr;void* owner=nullptr;std::uintptr_t entity=0;
    std::uint32_t actualGuid=0;float hp=0,maxHp=0;
    if(!native_combat::read(base+0x7032568,manager)||!manager
        ||!native_combat::invoke_lookup(base,manager,playerGuid,owner)
        ||!native_combat::read(reinterpret_cast<std::uintptr_t>(owner)+0x60,entity)||!entity
        ||!native_combat::read(entity,vt)||vt!=base+0x5BF6730
        ||!native_combat::read(entity+0x10,iface)||iface!=base+0x5BF67D0
        ||!native_combat::read(entity+0x30,actualGuid)||actualGuid!=playerGuid
        ||!native_combat::read(entity+0x10c,type)||type!=100
        ||!native_combat::read(entity+0x11c,hp)||!native_combat::read(entity+0x120,maxHp)
        ||!std::isfinite(hp)||!std::isfinite(maxHp)||hp<=0||maxHp<1||maxHp>1e7||hp>maxHp+1) return false;
    if(guid!=playerGuid) {guid=playerGuid;total=0;blocked=0;}
    watched=entity;refreshed=GetTickCount64();enabled=true;return true;
}
inline bool update(std::uint32_t playerId,std::uint32_t netGuid,bool active) {
    // Explicit release is immediate. A rejected worker-thread refresh must
    // not revoke the still-fresh identity published by GameThread. The hook
    // continues to enforce its 500 ms lease and every live actor check.
    if(!active) {enabled=false;watched=0;return installed;}
    const auto base=reinterpret_cast<std::uintptr_t>(GetModuleHandleW(nullptr));DWORD thread=0;
    if(!native_combat::read(base+0x706B538,thread)||thread!=GetCurrentThreadId()) return installed;
    const bool ready=validate_update(playerId,netGuid,true);
    if(!ready) enabled=false;
    return ready;
}
}
