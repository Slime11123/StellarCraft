#pragma once
#include <limits>

// Only native lookup/apply calls are replaced. The production actor_at path,
// reads, PE gates, identity checks, conversion and readback run unchanged.
namespace shield_test {
struct Fixture {
    unsigned char owner[0x500]{},entity[0x2700]{};
    std::uintptr_t base{};
    std::uint32_t guid=901;
    int calls{},stat{},zeroEvents{},mode{};
    bool live=true;
    float delta{};
    template<class T> void put(unsigned char* data,unsigned offset,T value) {std::memcpy(data+offset,&value,sizeof value);}
    float hp() {float v;std::memcpy(&v,entity+0x11c,4);return v;}
    float shield() {float v;std::memcpy(&v,entity+0x134,4);return v;}
    void reset(float s=80,float maxS=1000,float h=1000,float maxH=1000,std::uint32_t type=501,std::uint32_t id=901) {
        std::memset(owner,0,sizeof owner);std::memset(entity,0,sizeof entity);
        calls=stat=zeroEvents=mode=0;live=true;guid=id;
        put(owner,0x60,reinterpret_cast<std::uintptr_t>(entity));put(owner,0x104,70001u);
        put(entity,0,base+0x5BF6730);put(entity,0x10,base+0x5BF67D0);
        put(entity,0x30,guid);put(entity,0x10c,type);
        put(entity,0x11c,h);put(entity,0x120,maxH);put(entity,0x134,s);put(entity,0x138,maxS);
        *reinterpret_cast<void**>(base+0x7032568)=this;
        *reinterpret_cast<DWORD*>(base+0x706B538)=GetCurrentThreadId();
    }
};
inline Fixture* active=nullptr;
inline void* __fastcall lookup(void* manager,std::uint32_t guid) {
    assert(manager==active&&guid==active->guid);
    if(active->mode==11&&active->calls) RaiseException(EXCEPTION_ACCESS_VIOLATION,0,0,nullptr);
    return active->live?active->owner:nullptr;
}
inline void __fastcall apply(void* owner,int stat,void* other,float delta,std::uint32_t cause,void* extra,
    std::uint32_t a,std::uint32_t b,std::uint32_t c,std::uint8_t d,std::uint8_t e) {
    auto& f=*active;
    assert(owner==f.owner&&!other&&!extra&&cause==228&&!a&&!b&&!c&&!d&&!e);
    assert((stat==1||stat==7)&&std::isfinite(delta)&&delta<0);
    ++f.calls;f.stat=stat;f.delta=delta;
    if(f.mode==9) RaiseException(EXCEPTION_ACCESS_VIOLATION,0,0,nullptr);
    if(f.mode==1) return; // Native immunity/refusal.
    if(stat==7) {
        const auto before=f.shield();
        // stat=7 passes through 1C77645 before the setter truncates the pool.
        const float after=std::trunc(std::fmax(0.f,before+std::trunc(delta)));
        f.put(f.entity,0x134,after);
        if(before>0&&after==0) ++f.zeroEvents; // Stub observation, not real effects certification.
    } else f.put(f.entity,0x11c,std::fmax(0.f,f.hp()+delta));
    if(f.mode==2) f.put(f.entity,0x134,NAN);
    if(f.mode==3) f.live=false;
    if(f.mode==4) f.put(f.entity,0x10c,502u);
    if(f.mode==5) f.put(f.owner,0x104,70002u);
    if(f.mode==6) f.put(f.entity,0x11c,f.hp()-1);
    if(f.mode==7) f.put(f.owner,0x60,std::uintptr_t(1));
    if(f.mode==8) f.put(f.entity,0x30,902u);
}
inline void run(std::uintptr_t base) {
    Fixture f;f.base=base;active=&f;
    auto hit=[&](float mc=1,float budget=40,std::uint32_t type=501) {
        return native_combat::actor_at(base,901,type,mc,budget,228,lookup,apply);
    };
    auto refused=[&](float mc=1,float budget=40,std::uint32_t type=501) {
        const int before=f.calls;const auto r=hit(mc,budget,type);
        assert(!r.applied&&f.calls==before&&std::strcmp(r.damageRoute,"refused")==0);
    };
    // User's actual 44000 HP / 2160 Shield target must not lose its full shield.
    f.reset(2160,2160,44000,44000,205039001);
    auto sample=hit(8,100,205039001);
    assert(sample.applied&&sample.shieldAfter>0&&sample.after==44000);
    assert(std::fabs(f.delta+172.8f)<.001f&&f.calls==1&&f.stat==7);
    assert(sample.shieldAfter==1988);
    for(int i=2;i<=13;++i) {
        sample=hit(8,100,205039001);
        assert(sample.applied&&f.hp()==44000&&f.calls==i);
        assert((i<13)==(f.shield()>0));
    }
    assert(f.zeroEvents==1&&f.shield()==0);
    sample=hit(8,100,205039001);assert(f.stat==1&&sample.after==40480&&f.calls==14);
    f.reset(2160,2160,440000,440000,205039001);sample=hit(8,100,205039001);
    assert(sample.shieldAfter==1988&&sample.after==440000); // HP scaling cannot change shield damage.
    f.put(f.entity,0x134,2160.f);f.put(f.entity,0x138,4320.f);
    sample=hit(8,100,205039001);assert(sample.shieldAfter==1815); // Read the new MaxShield each hit.
    f.reset(2160,2160);sample=hit(.001f,100);
    assert(!sample.applied&&f.hp()==1000&&f.shield()==2160&&f.calls==1&&f.stat==7);
    std::puts("PASS: reported 44000 HP/2160 Shield case requests 172.8 shield damage; native truncation stub leaves 1988; 13 hits break with zero HP loss; next hit costs 3520 HP; MaxShield refresh and HP independence");
    f.reset();auto r=hit();
    assert(r.applied&&r.before==1000&&r.after==1000&&r.shieldBefore==80&&r.shieldAfter==55&&r.shieldMaximum==1000);
    assert(f.calls==1&&f.stat==7&&f.delta==-25&&std::strcmp(r.damageRoute,"shield")==0);
    const auto shieldDamage=r;
    f.reset(10,1000);r=hit();
    assert(r.applied&&f.shield()==0&&f.hp()==1000&&f.delta==-10&&f.zeroEvents==1&&f.calls==1);
    r=hit();assert(r.applied&&f.stat==1&&f.hp()==975&&f.calls==2&&f.zeroEvents==1);
    f.put(f.entity,0x134,80.f);r=hit();assert(f.stat==7&&f.shield()==55&&f.hp()==975);
    f.reset(0,0);r=hit();assert(r.applied&&f.stat==1&&r.after==975);
    f.reset(25,1000);r=hit();assert(f.shield()==0&&f.hp()==1000&&f.zeroEvents==1&&f.calls==1);
    for(auto type:{501u,502u,503u,504u,505u}) for(float budget:{20.f,40.f,100.f,200.f}) {
        f.reset(200,300,2000,2000,type);r=hit(1,budget,type);
        assert(r.applied&&f.stat==7&&f.calls==1&&f.hp()==2000&&f.shield()==200-std::trunc(300/budget));
    }
    const auto firstShield=f.shield(),firstHp=f.hp();
    Fixture second;second.base=base;second.reset(120,120,2000,2000,601,902);active=&second;
    r=native_combat::actor_at(base,902,601,1,40,228,lookup,apply);
    assert(r.applied&&second.shield()==117&&second.hp()==2000&&second.calls==1);
    assert(f.shield()==firstShield&&f.hp()==firstHp);active=&f;
    f.reset(10,80);r=hit((std::numeric_limits<float>::max)(),.00001f);
    assert(r.applied&&f.delta==-10&&f.hp()==1000&&f.calls==1);
    f.reset(0,0,5);r=hit();assert(r.applied&&r.after==0&&f.stat==1);
    f.reset(0,0,5);f.mode=3;r=hit();assert(r.applied&&r.after==0); // Existing HP death path.
    for(int mode=1;mode<=8;++mode) {
        f.reset();f.mode=mode;r=hit();
        assert(!r.applied&&f.calls==1&&f.stat==7);
        if(mode!=6) assert(f.hp()==1000); // Detect unexpected native HP change, never compensate.
        assert(std::strcmp(r.damageRoute,"refused")==0);
    }
    for(float shield:{0.f,80.f}) {
        f.reset(shield,80);f.mode=11;r=hit();
        assert(!r.applied&&f.calls==1&&std::strcmp(r.damageRoute,"refused")==0);
    }
    for(float bad:{NAN,INFINITY,-1.f}) {
        f.reset();f.put(f.entity,0x134,bad);refused();
        f.reset();f.put(f.entity,0x138,bad);refused();
        f.reset();refused(bad);
        f.reset();refused(1,bad);
    }
    f.reset(81,80);refused();f.reset(1,0);refused();f.reset(0,1e8f);refused();
    f.reset();refused(1,0);refused(1,10001);refused(1,40,100);refused(1,40,0);refused(1,40,502);
    f.reset();f.put(f.owner,0x414,std::uint8_t(1));refused();
    for(auto state:{3u,4u,5u}) {f.reset();f.put(f.entity,0x2600,state);refused();}
    f.reset(0,0,0);refused();
    f.reset();*reinterpret_cast<DWORD*>(base+0x706B538)=GetCurrentThreadId()+1;refused();
    f.reset();*reinterpret_cast<void**>(base+0x7032568)=nullptr;refused();
    f.reset();f.live=false;refused();
    f.reset();f.put(f.entity,0,std::uintptr_t(1));refused();
    f.reset();f.put(f.owner,0x60,std::uintptr_t(1));refused();
    // HP and identity remain readable while the Shield field itself faults.
    auto guarded=static_cast<unsigned char*>(VirtualAlloc(nullptr,0x4000,MEM_RESERVE|MEM_COMMIT,PAGE_READWRITE));
    assert(guarded);auto guardedEntity=guarded+0x1000-0x134;
    f.reset();std::memcpy(guardedEntity,f.entity,sizeof f.entity);
    f.put(f.owner,0x60,reinterpret_cast<std::uintptr_t>(guardedEntity));
    DWORD oldProtect=0;assert(VirtualProtect(guarded+0x1000,0x1000,PAGE_NOACCESS,&oldProtect));
    refused();assert(f.hp()==1000);VirtualFree(guarded,0,MEM_RELEASE);
    f.reset();auto badHeader=reinterpret_cast<IMAGE_DOS_HEADER*>(base);badHeader->e_magic^=1;refused();badHeader->e_magic^=1;
    f.reset();r=hit(0);assert(r.ready&&!r.applied&&f.calls==0);
    assert(!native_combat::invoke_apply_stat(base,f.owner,8,1,228,apply)&&f.calls==0);
    assert(!native_combat::invoke_apply_stat(base,f.owner,7,NAN,228,apply)&&f.calls==0);
    // All certified styles accept shield-only loss and retain single-context replay guards.
    using namespace native_hit_context;
    native_combat::Identity target{901,501,123};native_combat::ControlGuid control{{1,2,3,4}};
    for(auto weapon:{1u,0x101u,5u}) for(auto kind:{1u,6u}) {
        if(kind==6&&weapon!=1) continue;
        Request req{1,42,10,55,kind,weapon,{0,0,0}};req.hostLevel=77;
        req.hasTrajectory=weapon==5;req.trajectory={0,1,0};Session session;
        assert(prepare(session,req,shieldDamage,target,target,control,{100,0,0},true).ready);
        assert(!prepare(session,req,shieldDamage,target,target,control,{100,0,0},true).ready);
        Session stale;assert(!prepare(stale,req,shieldDamage,target,target,control,{100,0,0},false).ready);
        auto invalid=shieldDamage;invalid.shieldAfter=NAN;
        assert(!prepare(stale,req,invalid,target,target,control,{100,0,0},true).ready);
    }
    // Verify SEH latches the native path and prevents the next call, even for HP.
    f.reset();f.mode=9;r=hit();assert(!r.applied&&f.calls==1);
    f.reset(0,0);refused();
    active=nullptr;
    std::puts("PASS: real actor settlement with injected native calls; shield/break/next HP/refill/no-slot; type=1/7 ABI; readback/identity/state/thread/version/finite refusal; shield-only styles/replay; no game code executed");
}
}

