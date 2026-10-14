#pragma once
#include "native_hit_target.h"
#include "native_hit_show_anchors.h"

namespace native_hit_show {
struct alignas(8) Payload {unsigned char data[0x98]{};};
struct String {const wchar_t* data;std::int32_t count,capacity;};
static_assert(sizeof(Payload)==0x98&&alignof(Payload)==8);
static_assert(sizeof(String)==0x10);
struct Result {bool requested{},heavy{};const char* reason="show unavailable";};
using Construct=void*(__fastcall*)(void*);
using Show=void*(__fastcall*)(void*,const native_combat::ControlGuid*,const String*,float,bool,const void*,bool,bool,bool);
template<class T> inline void store(Payload& payload,std::size_t at,const T& value) {
    std::memcpy(payload.data+at,&value,sizeof value);
}
inline bool certified(std::uintptr_t base) {
    static const unsigned char zero[12]={};
    static const unsigned char type[]={0xb8,0x01,0,0,0,0xc3};
    std::uintptr_t typeEntry=0;
    return native_hit_target::certified(base)
        &&native_combat::bytes(base+0x1C49290,anchors::constructor,sizeof anchors::constructor)
        &&native_combat::bytes(base+0x22857E0,anchors::facade,sizeof anchors::facade)
        &&native_combat::bytes(base+0x1C50365,anchors::caller,sizeof anchors::caller)
        &&native_combat::bytes(base+0x1A64B40,anchors::state,sizeof anchors::state)
        &&native_combat::bytes(base+0x243E1E0,anchors::tags,sizeof anchors::tags)
        &&native_combat::bytes(base+0x710DDA8,zero,sizeof zero)
        &&native_combat::bytes(base+0xF2FB60,type,sizeof type)
        &&native_combat::bytes(base+0x1C50120,anchors::heavyComparison,sizeof anchors::heavyComparison)
        &&native_combat::bytes(base+0x2459E0A,anchors::missingChild,sizeof anchors::missingChild)
        &&native_combat::read(base+0x5C11110,typeEntry)&&typeEntry==base+0xF2FB60;
}
inline bool fill(Payload& payload,const native_hit_context::Context& context,std::uintptr_t vtable) {
    std::uintptr_t actual=0;std::memcpy(&actual,payload.data,sizeof actual);
    if(!context.ready||actual!=vtable||!native_hit_context::position(context.position)
        ||!native_hit_context::position(context.request.source)||!native_hit_context::position(context.direction)) return false;
    // Keep native constructor defaults for absent hit bone, bone-local offset,
    // tags and source ControlGUID. MC has no native caster/skeletal hit bone.
    // Never invent a bone-space direction or attribute the hit to Eve.
    store(payload,0x28,context.request.source);
    store(payload,0x34,context.position);
    store(payload,0x54,context.direction);
    store(payload,0x60,context.direction);
    store(payload,0x88,context.control);
    return true;
}
inline bool construct(Construct call,Payload& payload) {
    __try {return call(&payload)==&payload;}
    __except(EXCEPTION_EXECUTE_HANDLER) {return false;}
}
inline bool heavy_level(float required) {
    // P_Eve_Sword_Normal_StrongAttack2_1 (SkillTable ID 1001007) has HitLevel=1.
    // MC maps to this ordinary strength with zero native gear bonus. Match the
    // original comparison, never force target resistance or borrow Eve gear.
    return std::isfinite(required)&&required>=0&&1.f>=required;
}
inline bool invoke(Show call,void* facade,const native_combat::ControlGuid& control,const Payload& payload,bool heavy=false,bool* fault=nullptr) {
    const wchar_t* path=heavy?L"Hit/Result_Hit_Stand_Sword_StrongAttack":L"Hit/Result_Hit_Stand_Sword_LightAttack_NoAnim";
    const auto length=static_cast<std::int32_t>(std::wcslen(path)+1);
    const String text{path,length,length};
    __try {
        // Original nine-argument ABI. Full strong keys are enabled only after
        // the original level comparison passes; priority/tag rules still apply.
        // Original caller 1C5039B checks RAX before using the show. A null
        // return is a normal native refusal, not successful creation or a fault.
        return call(facade,&control,&text,0.f,heavy,&payload,false,true,true)!=nullptr;
    } __except(EXCEPTION_EXECUTE_HANDLER) {if(fault) *fault=true;return false;}
}
inline Result request(std::uintptr_t object,const native_hit_context::Context& context,float budget) {
    Result out;static bool faulted=false;
    const auto base=reinterpret_cast<std::uintptr_t>(GetModuleHandleW(nullptr));
    if(faulted||!certified(base)) {out.reason="show ABI unavailable";return out;}
    if(!std::isfinite(budget)||budget<=0||budget>100||!native_hit_target::eligible(base,object,context)) {
        out.reason="outside sampled ordinary additive state";return out;
    }
    if(static_cast<std::uint32_t>(GetTickCount64())-context.request.timeLow>500u) {out.reason="expired show request";return out;}
    const auto current=native_combat::identity(object);
    if(current.guid!=context.target.guid||current.type!=context.target.type||current.entity!=context.target.entity) {
        out.reason="show target replaced";return out;
    }
    float resistance=0;
    const bool heavy=context.request.heavyRequested&&native_hit_target::resource_path(object,true)
        &&native_combat::read(current.entity+0x1AC,resistance)&&heavy_level(resistance);
    std::uintptr_t facade=0;
    if(!native_combat::read(base+0x7032558,facade)||!facade) {out.reason="show facade unavailable";return out;}
    Payload payload;
    if(!construct(reinterpret_cast<Construct>(base+0x1C49290),payload)||!fill(payload,context,base+0x5C11108)) {
        faulted=true;out.reason="native hit constructor rejected";return out;
    }
    bool callFault=false;
    if(!invoke(reinterpret_cast<Show>(base+0x22857E0),reinterpret_cast<void*>(facade),context.control,payload,heavy,&callFault)) {
        if(callFault) faulted=true;
        out.reason=callFault?"native show call faulted":"native show creation declined";return out;
    }
    out.requested=true;out.heavy=heavy;
    out.reason=heavy?"native full strong show requested; original priority retained":context.request.heavyRequested
        ?"heavy model/resistance rejected; one original light fallback requested"
        :"native additive show requested; playback subject to original state";return out;
}
}
