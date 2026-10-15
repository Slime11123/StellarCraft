#pragma once
#include "native_combat.h"

// P1 only: prepare immutable hit data after the native damage result. No show,
// animation, effect or other game function is called from this module.
namespace native_hit_context {
struct Vec3 {float x{},y{},z{};};
struct Request {
    std::uint32_t serial{},uid{},pid{},world{},kind{},weapon{};
    Vec3 source{};
    std::uint32_t hostLevel{};
    std::uint32_t timeLow{};
    bool hasTrajectory{};
    Vec3 trajectory{};
    bool heavyRequested{};
};
struct Context {
    bool ready{};
    const char* reason="no hit context";
    native_combat::Identity target{};
    native_combat::ControlGuid control{};
    Request request{};
    Vec3 position{},direction{};
};
struct Session {std::uint32_t pid{},world{},serial{},hostLevel{};};
inline bool position(Vec3 p) {
    return std::isfinite(p.x)&&std::isfinite(p.y)&&std::isfinite(p.z)
        &&std::fabs(p.x)<3e8f&&std::fabs(p.y)<3e8f&&std::fabs(p.z)<3e8f;
}
inline Context prepare(Session& session,const Request& request,const native_combat::Result& damage,
    native_combat::Identity before,native_combat::Identity after,
    native_combat::ControlGuid control,Vec3 target,bool fresh,std::uint32_t nowLow=0) {
    Context out;
    if(!damage.ready||!damage.applied||damage.invulnerable||!std::isfinite(damage.before)||!std::isfinite(damage.after)
        ||!std::isfinite(damage.shieldBefore)||!std::isfinite(damage.shieldAfter)
        ||damage.shieldBefore<0||damage.shieldAfter<0||damage.after<=0
        ||!(damage.after<damage.before||damage.shieldAfter<damage.shieldBefore)) {out.reason="no surviving damaged target";return out;}
    if(!fresh||!request.pid||!request.world||!request.hostLevel||!request.uid||!request.serial) {out.reason="stale hit session";return out;}
    // Unsigned subtraction also handles the 49.7-day Windows low32 wrap.
    if(nowLow-request.timeLow>500u) {out.reason="expired hit capture";return out;}
    if(!before.guid||before.guid!=after.guid||before.type!=after.type||!before.type||before.type==100
        ||!before.entity||before.entity!=after.entity
        ||!(control.words[0]|control.words[1]|control.words[2]|control.words[3])) {
        out.reason="hit target replaced";return out;
    }
    const auto weapon=request.weapon&0xFFu;
    const bool heavy=(request.weapon&0x100u)!=0;
    if((request.weapon&~0x1FFu)||weapon>5||(heavy&&(request.kind!=1||weapon==5))) {
        out.reason="hit style not certified";return out;
    }
    if((request.kind!=1&&request.kind!=6)
        ||(request.kind==1&&weapon==5&&!request.hasTrajectory)) {out.reason="hit source not certified";return out;}
    if(!position(request.source)||!position(target)) {out.reason="invalid hit position";return out;}
    const double x=double(target.x)-request.source.x,y=double(target.y)-request.source.y,z=double(target.z)-request.source.z;
    const double length=std::sqrt(x*x+y*y+z*z);
    // Existing native visibility/damage rules remain authoritative. This limit
    // only rejects implausible animation origins, never changes HP damage.
    const double limit=request.kind==6||weapon==5?3200.0:1600.0;
    if(!std::isfinite(length)||length<.01||length>limit) {out.reason="ambiguous hit direction";return out;}
    Vec3 direction{float(x/length),float(y/length),float(z/length)};
    if(request.kind==1&&weapon==5) {
        const auto v=request.trajectory;
        const double magnitude=std::sqrt(double(v.x)*v.x+double(v.y)*v.y+double(v.z)*v.z);
        if(!position(v)||magnitude<.5||magnitude>1.5) {out.reason="invalid projectile trajectory";return out;}
        direction={float(v.x/magnitude),float(v.y/magnitude),float(v.z/magnitude)};
    }
    if(session.pid!=request.pid||session.world!=request.world||session.hostLevel!=request.hostLevel)
        session={request.pid,request.world,0,request.hostLevel};
    if(request.serial<=session.serial) {out.reason="replayed hit context";return out;}
    session.serial=request.serial;
    out.ready=true;out.reason="hit context prepared";
    out.target=after;out.control=control;out.request=request;out.position=target;
    out.request.weapon=weapon;out.request.heavyRequested=heavy;
    out.direction=direction;
    return out;
}
inline bool position_certified(std::uintptr_t base) {
    // FSBActor position interface (+10), vtable+48 -> 1A30C90:
    // iface+4BC/4C4, hence entity+4CC/4D4. Read only; do not invoke it.
    static const unsigned char getter[]={0x8b,0x81,0xc4,0x04,0,0,0xf2,0x0f,0x10,0x81,0xbc,0x04,0,0,
        0xf2,0x0f,0x11,0x02,0x89,0x42,0x08,0x48,0x8b,0xc2,0xc3};
    std::uintptr_t entry=0;
    return native_combat::identity_certified(base)
        &&native_combat::bytes(base+0x1A30C90,getter,sizeof getter)
        &&native_combat::read(base+0x5BF6818,entry)&&entry==base+0x1A30C90;
}
}
