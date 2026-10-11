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
struct Result { bool ready{}, applied{}; float before{}, after{}, maximum{}; const char* reason="unavailable"; };
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
    return bytes(base+0x1AD6AB0,lookup,sizeof lookup)&&bytes(base+0x1BA8720,apply,sizeof apply)
        &&bytes(base+0x1A97B20,cache,sizeof cache)&&bytes(base+0xE36F9B,thread,sizeof thread);
}
using Lookup=void*(__fastcall*)(void*,std::uint32_t);
using Apply=void(__fastcall*)(void*,int,void*,float,std::uint32_t,void*,std::uint32_t,std::uint32_t,std::uint32_t,std::uint8_t,std::uint8_t);
inline bool invoke_lookup(std::uintptr_t base,void* manager,std::uint32_t guid,void*& owner) {
    __try {owner=reinterpret_cast<Lookup>(base+0x1AD6AB0)(manager,guid);return owner!=nullptr;}
    __except(EXCEPTION_EXECUTE_HANDLER) {return false;}
}
inline bool invoke_apply(std::uintptr_t base,void* owner,float amount,std::uint32_t cause) {
    __try {reinterpret_cast<Apply>(base+0x1BA8720)(owner,1,nullptr,-amount,cause,nullptr,0,0,0,0,0);return true;}
    __except(EXCEPTION_EXECUTE_HANDLER) {return false;}
}
inline Result actor(std::uint32_t guid,std::uint32_t expectedType,float mcDamage,float budget,std::uint32_t cause) {
    Result out;static bool faulted=false;
    const auto base=reinterpret_cast<std::uintptr_t>(GetModuleHandleW(nullptr));
    if(faulted||!certified(base)) {out.reason="unsupported build";return out;}
    DWORD gameThread=0;
    if(!read(base+0x706B538,gameThread)||gameThread!=GetCurrentThreadId()) {out.reason="wrong thread";return out;}
    if(guid==0||guid>=0x10000000||expectedType==0||expectedType==100) {out.reason="invalid enemy identity";return out;}
    void* manager=nullptr;
    if(!read(base+0x7032568,manager)||!manager) {out.reason="server not ready";return out;}
    void* owner=nullptr;
    if(!invoke_lookup(base,manager,guid,owner)) {out.reason="enemy not live";return out;}
    std::uintptr_t entity=0,vt=0,iface=0;std::uint32_t actualGuid=0,actualType=0;
    if(!read(reinterpret_cast<std::uintptr_t>(owner)+0x60,entity)||!entity||!read(entity,vt)||!read(entity+0x10,iface)
        ||vt!=base+0x5BF6730||iface!=base+0x5BF67D0||!read(entity+0x30,actualGuid)||actualGuid!=guid
        ||!read(entity+0x10c,actualType)||actualType!=expectedType||actualType==100
        ||!read(entity+0x11c,out.before)||!read(entity+0x120,out.maximum)
        ||!std::isfinite(out.before)||!std::isfinite(out.maximum)||out.maximum<1||out.maximum>1e7||out.before<0||out.before>out.maximum+1) {
        out.reason="enemy identity mismatch";return out;
    }
    out.ready=true;out.after=out.before;out.reason="ready";
    if(mcDamage<=0) return out;
    if(!std::isfinite(mcDamage)||!std::isfinite(budget)||budget<=0||budget>10000||out.before<=0) {out.reason="invalid damage";return out;}
    const float amount=std::fmin(out.before,std::fmax(1.0f,out.maximum*std::fmin(mcDamage/budget,1.0f)));
    if(!invoke_apply(base,owner,amount,cause)) {faulted=true;out.reason="native call failed";return out;}
    // The death path can unregister the actor. Re-resolve before reading its pool.
    void* afterOwner=nullptr;std::uintptr_t afterEntity=0;
    if(!invoke_lookup(base,manager,guid,afterOwner)||!afterOwner) out.after=0;
    else if(!read(reinterpret_cast<std::uintptr_t>(afterOwner)+0x60,afterEntity)||afterEntity!=entity||!read(afterEntity+0x11c,out.after)||!std::isfinite(out.after)) {
        out.reason="result unavailable";return out;
    }
    out.applied=out.after<out.before;out.reason=out.applied?"health decreased":"damage refused";return out;
}
}
