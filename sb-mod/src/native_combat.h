#pragma once
#include <windows.h>
#include <cstdint>
#include <cstring>
#include <cmath>

// Stellar Blade Steam build SHA256 573AAFF1...FA87545C, verified offline.
// The server stat entry calls ApplyStatDiff, publishes its result and handles death.
// Addresses/ABI corroborated against control-panel native/god sources and the local exe.
// No patches or hooks: calls run only from Lua's existing GameThread callback.
namespace native_combat {
struct Result { bool ready{}, applied{}, invulnerable{}; std::uint32_t sourceKey{}; float before{}, after{}, maximum{}, shieldBefore{}, shieldAfter{}, shieldMaximum{}; const char* damageRoute="refused"; const char* reason="unavailable"; };
template<class T> bool read(std::uintptr_t at,T& out) {
    __try { out=*reinterpret_cast<const T*>(at);return true; }
    __except(EXCEPTION_EXECUTE_HANDLER) {return false;}
}
inline bool bytes(std::uintptr_t at,const unsigned char* expected,size_t n) {
    __try {return std::memcmp(reinterpret_cast<const void*>(at),expected,n)==0;}
    __except(EXCEPTION_EXECUTE_HANDLER) {return false;}
}
inline bool certified(std::uintptr_t base) {
    IMAGE_DOS_HEADER dos{};IMAGE_NT_HEADERS64 nt{};
    if(!read(base,dos)||dos.e_magic!=IMAGE_DOS_SIGNATURE||dos.e_lfanew<0||dos.e_lfanew>0x10000||!read(base+dos.e_lfanew,nt)) return false;
    if(nt.Signature!=IMAGE_NT_SIGNATURE||nt.FileHeader.Machine!=IMAGE_FILE_MACHINE_AMD64||nt.FileHeader.TimeDateStamp!=0x6A6A3B74||nt.OptionalHeader.SizeOfImage!=0x15981000) return false;
    static const unsigned char lookup[]={0x40,0x53,0x48,0x83,0xec,0x20,0x8b,0x81,0x78,0x01,0,0,0x3b,0x81,0xa4,0x01,0,0,0x74,0x55,0x4c,0x63,0x89,0xb8,0x01,0,0,0x4c,0x8d,0x91,0xa8,0x01};
    static const unsigned char apply[]={0x89,0x54,0x24,0x10,0x4c,0x8b,0xdc,0x55,0x53,0x56,0x57,0x41,0x57,0x48,0x8d,0x6c,0x24,0x80,0x48,0x81,0xec,0x80,0x01,0,0,0x48,0x8b,0x41,0x60,0x49,0x8b,0xd8};
    static const unsigned char cache[]={0x48,0x83,0xec,0x28,0x48,0x8b,0x05,0x3d,0xaa,0x59,0x05,0x48,0x85,0xc0,0x75,0x4c};
    static const unsigned char thread[]={0x89,0x05,0x97,0x45,0x23,0x06};
    static const unsigned char stateGate[]={0x83,0xff,0x01,0x75,0x23,0x80,0xb9,0x14,0x04,0,0,0,0x0f,0x85,0x2a,0x0f,0,0,0x8b,0x80,0,0x26,0,0,0x83,0xe8,0x03,0x83,0xf8,0x02,0x0f,0x86,0x18,0x0f,0,0};
    // stat=7 dispatch, native zero-shield effects, and setter clamp against MaxShield.
    static const unsigned char statDispatch[]={0x83,0xe9,0x01,0x0f,0x84,0x8c,0x01,0x00,0x00,0x83,0xe9,0x06,0x0f,0x84,0xc3,0x00,0x00,0x00,0x83,0xf9,0x05,0x0f,0x85,0xd5,0x0d,0x00,0x00};
    static const unsigned char statPool[]={0x49,0x8b,0x06,0xf3,0x0f,0x10,0xb4,0xb8,0x18,0x01,0x00,0x00};
    static const unsigned char zeroShield[]={0x49,0x8b,0x1e,0x48,0x85,0xdb,0x74,0x10,0xf3,0x0f,0x2c,0x83,0x34,0x01,0x00,0x00,0x85,0xc0,0x0f,0x8f,0x03,0x0d,0x00,0x00,0x8b,0x9b,0x0c,0x01,0x00,0x00,0xe8,0x95,0xf5,0xee,0xff,0x48,0x8b,0xc8,0x44,0x8b,0xc3,0xe8,0x9a,0x1d,0xef,0xff,0x48,0x85,0xc0,0x0f,0x84,0xe4,0x0c,0x00,0x00,0x48,0x8b,0xb8,0x58,0x01,0x00,0x00,0x48,0x63,0x80,0x60,0x01,0x00,0x00,0x48,0x8d,0x34,0xc7,0x48,0x3b,0xfe,0x0f,0x84,0xc9,0x0c,0x00,0x00,0x66,0x0f,0x1f,0x44,0x00,0x00,0xe8,0x5b,0xf5,0xee,0xff,0x48,0x8b,0xc8,0x4c,0x8b,0xc7,0xe8,0xa0,0x20,0xef,0xff,0x48,0x85,0xc0,0x74,0x45,0xf2,0x0f,0x10,0x05,0xcb,0x53,0x56,0x05,0x8b,0x0d,0xcd,0x53,0x56,0x05,0x8b,0x58,0x10,0xf2,0x0f,0x11,0x44,0x24,0x70,0x89,0x4c,0x24,0x78,0xe8,0x2b,0xf1,0xee,0xff,0x48,0x8b,0xc8,0x4c,0x89,0x64,0x24,0x30,0x48,0x8d,0x44,0x24,0x70,0x44,0x88,0x64,0x24,0x28,0x44,0x8b,0xcb,0x48,0x89,0x44,0x24,0x20,0x4d,0x8b,0xc7,0x49,0x8b,0xd7,0xe8,0x96,0x7e,0xf2,0xff,0x48,0x83,0xc7,0x08,0x48,0x3b,0xfe,0x75,0x9d};
    static const unsigned char shieldSetter[]={0x0f,0x2f,0xfe,0x73,0x30,0x80,0x7d,0x77,0x00,0x75,0x2a,0xba,0x04,0x00,0x00,0x00,0x48,0x8b,0xce,0xe8,0xfe,0xf9,0xff,0xff,0x84,0xc0,0x0f,0x85,0x00,0x09,0x00,0x00,0xba,0x03,0x00,0x00,0x00,0x48,0x8b,0xce,0xe8,0xe9,0xf9,0xff,0xff,0x84,0xc0,0x0f,0x85,0xeb,0x08,0x00,0x00,0xf3,0x0f,0x10,0x96,0x34,0x01,0x00,0x00,0x0f,0x28,0xca,0xf3,0x0f,0x58,0xcf,0x0f,0x2f,0xce,0x77,0x05,0x0f,0x57,0xc0,0xeb,0x0c,0xf3,0x0f,0x10,0x86,0x38,0x01,0x00,0x00,0xf3,0x0f,0x5d,0xc1,0xf3,0x0f,0x2c,0xc0,0x66,0x0f,0x6e,0xc0,0x0f,0x5b,0xc0,0xf3,0x0f,0x11,0x86,0x34,0x01,0x00,0x00,0xf3,0x0f,0x10,0x8e,0x38,0x01,0x00,0x00,0x0f,0x2f,0xce,0x76,0x17,0xf3,0x0f,0x5d,0xc1,0xf3,0x0f,0x2c,0xc0,0x66,0x0f,0x6e,0xc0,0x0f,0x5b,0xc0,0xf3,0x0f,0x11,0x86,0x34,0x01,0x00,0x00,0x0f,0x2e,0xd0,0x0f,0x84,0x86,0x08,0x00,0x00,0xe9,0x98,0xfd,0xff,0xff};
    static const unsigned char statJump[]={0x41,0x8d,0x57,0xff,0x4c,0x8d,0x0d,0xa3,0xb2,0x59,0xfe,0x0f,0x28,0xf8,0x83,0xfa,0x28,0x0f,0x87,0x91,0x0c,0x00,0x00,0x48,0x63,0xc2,0x41,0x0f,0xb6,0x84,0x01,0x90,0x5a,0xa6,0x01,0x41,0x8b,0x8c,0x81,0x54,0x5a,0xa6,0x01,0x49,0x03,0xc9,0xff,0xe1};
    static const unsigned char shieldIndex[]={0x03};
    static const unsigned char shieldEntry[]={0x2a,0x51,0xa6,0x01};
    return bytes(base+0x1AD6AB0,lookup,sizeof lookup)&&bytes(base+0x1BA8720,apply,sizeof apply)
        &&bytes(base+0x1A97B20,cache,sizeof cache)&&bytes(base+0xE36F9B,thread,sizeof thread)
        &&bytes(base+0x1BA875F,stateGate,sizeof stateGate)
        &&bytes(base+0x1BA8893,statDispatch,sizeof statDispatch)
        &&bytes(base+0x1BA882B,statPool,sizeof statPool)
        &&bytes(base+0x1BA8968,zeroShield,sizeof zeroShield)
        &&bytes(base+0x1A6512A,shieldSetter,sizeof shieldSetter)
        &&bytes(base+0x1A64D52,statJump,sizeof statJump)
        &&bytes(base+0x1A65A96,shieldIndex,sizeof shieldIndex)
        &&bytes(base+0x1A65A60,shieldEntry,sizeof shieldEntry);

}
using Lookup=void*(__fastcall*)(void*,std::uint32_t);
using Apply=void(__fastcall*)(void*,int,void*,float,std::uint32_t,void*,std::uint32_t,std::uint32_t,std::uint32_t,std::uint8_t,std::uint8_t);
inline bool invoke_lookup(std::uintptr_t base,void* manager,std::uint32_t guid,void*& owner,Lookup lookup=nullptr,bool* completed=nullptr) {
    if(completed) *completed=false;
    __try {owner=(lookup?lookup:reinterpret_cast<Lookup>(base+0x1AD6AB0))(manager,guid);if(completed) *completed=true;return owner!=nullptr;}
    __except(EXCEPTION_EXECUTE_HANDLER) {return false;}
}
inline bool invoke_apply_stat(std::uintptr_t base,void* owner,int statType,float amount,std::uint32_t cause,Apply apply=nullptr) {
    if((statType!=1&&statType!=7)||!std::isfinite(amount)||amount<=0) return false;
    __try {(apply?apply:reinterpret_cast<Apply>(base+0x1BA8720))(owner,statType,nullptr,-amount,cause,nullptr,0,0,0,0,0);return true;}
    __except(EXCEPTION_EXECUTE_HANDLER) {return false;}
}
inline float converted_damage(float current,float maximum,float mcDamage,float budget) {
    return std::fmin(current,maximum*std::fmin(mcDamage/budget,1.0f));
}
struct Identity { std::uint32_t guid{},type{}; std::uintptr_t entity{}; };
struct ControlGuid { std::uint32_t words[4]{}; };
inline bool identity_certified(std::uintptr_t base) {
    // Local character facade -> UE actor manager. The native game passes its
    // FSBActor ControlGUID (+0x4A4) to that manager's live UObject lookup.
    static const unsigned char cache[]={0x48,0x8b,0x05,0x9d,0xa8,0x59,0x05};
    static const unsigned char control[]={0xe8,0xb2,0x2b,0x06,0x00,0x48,0x8b,0xc8,0x4c,0x8d,0x83,0xa4,0x04,0x00,0x00,0x48,0x8d,0x54,0x24,0x30,0xe8,0x4e,0xfc,0x83,0x00};
    static const unsigned char bridge[]={0x48,0x8b,0x49,0x48,0x48,0x8b,0xda,0x48,0x85,0xc9};
    static const unsigned char key[]={0x0f,0x10,0x02,0x8b,0x41,0x58,0x48,0x8b,0xf1};
    static const unsigned char match[]={0x48,0x63,0xd0,0x41,0x8b,0xca,0x48,0xc1,0xe2,0x05};
    static const unsigned char value[]={0x48,0x8b,0x5a,0x10,0x48,0x85,0xdb,0x74,0xe0,0xf7,0x43,0x08,0x00,0x80,0x01,0x00};
    return certified(base)&&bytes(base+0x1A97CB4,cache,sizeof cache)
        &&bytes(base+0x1A350F9,control,sizeof control)
        &&bytes(base+0x2274D66,bridge,sizeof bridge)&&bytes(base+0x201B1EF,key,sizeof key)
        &&bytes(base+0x201B260,match,sizeof match)&&bytes(base+0x201B2A7,value,sizeof value);
}
inline bool lookup_character(std::uintptr_t base,void* manager,const std::uint32_t* control,void*& object) {
    using Find=void*(__fastcall*)(void*,const std::uint32_t*);
    __try {object=reinterpret_cast<Find>(base+0x201B1E0)(manager,control);return object!=nullptr;}
    __except(EXCEPTION_EXECUTE_HANDLER) {return false;}
}
inline Identity identity(std::uintptr_t object) {
    Identity out;
    const auto base=reinterpret_cast<std::uintptr_t>(GetModuleHandleW(nullptr));DWORD thread=0;
    if(!object||!identity_certified(base)||!read(base+0x706B538,thread)||thread!=GetCurrentThreadId()) return out;
    std::uintptr_t facade=0,manager=0,client=0,holder=0,array=0;std::int32_t count=0;
    if(!read(base+0x7032558,facade)||!facade||!read(facade+0x48,manager)||!manager
        ||!read(base+0x7032570,client)||!client||!read(client+0xc8,holder)||!holder
        ||!read(holder+0x28,array)||!array||!read(holder+0x30,count)||count<1||count>4096) return out;
    for(int i=0;i<count;i++) {
        std::uintptr_t entity=0,vt=0,iface=0;ControlGuid control;std::uint32_t guid=0,type=0;
        if(!read(array+8u*i,entity)||!entity||!read(entity,vt)||vt!=base+0x5BF6730
            ||!read(entity+0x10,iface)||iface!=base+0x5BF67D0
            ||!read(entity+0x4a4,control)||!(control.words[0]|control.words[1]|control.words[2]|control.words[3])) continue;
        void* resolved=nullptr;
        if(!lookup_character(base,reinterpret_cast<void*>(manager),control.words,resolved)
            ||reinterpret_cast<std::uintptr_t>(resolved)!=object) continue;
        if(!read(entity+0x30,guid)||!guid||guid>=0x10000000||!read(entity+0x10c,type)||!type||type==100) return {};
        // Duplicate mappings are ambiguous, even when the native GUID agrees.
        if(out.guid) return {};
        out={guid,type,entity};
    }
    return out;
}
// Both pools are read from the same live entity; invalid shield data never means zero.
inline bool pools(std::uintptr_t entity,Result& out) {
    return read(entity+0x11c,out.before)&&read(entity+0x120,out.maximum)
        &&read(entity+0x134,out.shieldBefore)&&read(entity+0x138,out.shieldMaximum)
        &&std::isfinite(out.before)&&std::isfinite(out.maximum)&&out.maximum>=1&&out.maximum<=1e7
        &&out.before>=0&&out.before<=out.maximum+1
        &&std::isfinite(out.shieldBefore)&&std::isfinite(out.shieldMaximum)
        &&out.shieldMaximum>=0&&out.shieldMaximum<=1e7&&out.shieldBefore>=0&&out.shieldBefore<=out.shieldMaximum;
}
inline bool enemy_entity(std::uintptr_t base,void* owner,std::uint32_t guid,std::uint32_t type,std::uintptr_t& entity) {
    std::uintptr_t vt=0,iface=0;std::uint32_t actualGuid=0,actualType=0;
    return read(reinterpret_cast<std::uintptr_t>(owner)+0x60,entity)&&entity&&read(entity,vt)&&read(entity+0x10,iface)
        &&vt==base+0x5BF6730&&iface==base+0x5BF67D0&&read(entity+0x30,actualGuid)&&actualGuid==guid
        &&read(entity+0x10c,actualType)&&actualType==type&&actualType!=100;
}
// Injectable call targets permit offline testing of this exact settlement path.
// The production wrapper below always uses the certified executable addresses.
inline Result actor_at(std::uintptr_t base,std::uint32_t guid,std::uint32_t expectedType,float mcDamage,float budget,std::uint32_t cause,
    Lookup lookup=nullptr,Apply apply=nullptr) {
    Result out;static bool faulted=false;
    if(faulted||!certified(base)) {out.reason="unsupported build";return out;}
    DWORD gameThread=0;
    if(!read(base+0x706B538,gameThread)||gameThread!=GetCurrentThreadId()) {out.reason="wrong thread";return out;}
    if(guid==0||guid>=0x10000000||expectedType==0||expectedType==100) {out.reason="invalid enemy identity";return out;}
    void* manager=nullptr;
    if(!read(base+0x7032568,manager)||!manager) {out.reason="server not ready";return out;}
    void* owner=nullptr;
    if(!invoke_lookup(base,manager,guid,owner,lookup)) {out.reason="enemy not live";return out;}
    std::uintptr_t entity=0;
    if(!enemy_entity(base,owner,guid,expectedType,entity)) {out.reason="enemy identity mismatch";return out;}
    if(!pools(entity,out)) {out.reason="native pools unavailable";return out;}
    std::uint8_t immune=0;std::uint32_t state=0;
    if(!read(reinterpret_cast<std::uintptr_t>(owner)+0x104,out.sourceKey)
        ||!read(reinterpret_cast<std::uintptr_t>(owner)+0x414,immune)||!read(entity+0x2600,state)) {out.reason="state unavailable";return out;}
    out.invulnerable=immune!=0||(state>=3&&state<=5);
    out.ready=true;out.after=out.before;out.shieldAfter=out.shieldBefore;out.reason="ready";
    if(mcDamage==0) return out;
    if(out.invulnerable) {out.reason="native invulnerability";return out;}
    if(!std::isfinite(mcDamage)||mcDamage<0||!std::isfinite(budget)||budget<=0||budget>10000||out.before<=0) {out.reason="invalid damage";return out;}
    const bool shield=out.shieldBefore>0;
    // Double arithmetic avoids overflow for finite but very large MC damage.
    const double raw=shield?double(out.shieldMaximum)*double(mcDamage)/double(budget)
        :double(converted_damage(out.before,out.maximum,mcDamage,budget));
    const float amount=static_cast<float>(std::fmin(double(shield?out.shieldBefore:out.before),raw));
    if(!std::isfinite(amount)||amount<=0) {out.reason="invalid converted damage";return out;}
    if(!invoke_apply_stat(base,owner,shield?7:1,amount,cause,apply)) {faulted=true;out.reason="native call failed";return out;}
    // Never read the old pool after native effects/death. Re-resolve and authenticate.
    void* afterOwner=nullptr;std::uintptr_t afterEntity=0;Result current;bool lookupCompleted=false;
    if(!invoke_lookup(base,manager,guid,afterOwner,lookup,&lookupCompleted)) {
        if(!lookupCompleted) {out.reason="result lookup failed";return out;}
        if(shield) {out.reason="shield result unavailable";return out;}
        out.after=0; // Existing HP death/unregistration behavior.
    } else {
        std::uint32_t key=0;
        if(afterOwner!=owner||!enemy_entity(base,afterOwner,guid,expectedType,afterEntity)||afterEntity!=entity
            ||!read(reinterpret_cast<std::uintptr_t>(afterOwner)+0x104,key)||key!=out.sourceKey||!pools(afterEntity,current)) {
            out.reason="result identity/pools unavailable";return out;
        }
        out.after=current.before;out.shieldAfter=current.shieldBefore;
        if(shield&&out.after!=out.before) {out.reason="shield route changed health";return out;}
    }
    out.applied=shield?out.shieldAfter<out.shieldBefore:out.after<out.before;
    out.damageRoute=out.applied?(shield?"shield":"hp"):"refused";
    out.reason=out.applied?(shield?"shield decreased":"health decreased"):"damage refused";return out;
}
inline Result actor(std::uint32_t guid,std::uint32_t expectedType,float mcDamage,float budget,std::uint32_t cause) {
    return actor_at(reinterpret_cast<std::uintptr_t>(GetModuleHandleW(nullptr)),guid,expectedType,mcDamage,budget,cause);
}
}
