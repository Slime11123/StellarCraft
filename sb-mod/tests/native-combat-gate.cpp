#include "../src/native_combat.h"
#include "../src/native_hit_context.h"
#include "../src/native_hit_show.h"
#include <fstream>
#include <vector>
#include <cassert>
#include <cstdio>
// Offline gate test: map selected bytes from the installed executable. Never
// attach to the game or invoke any game code.
static int showCalls=0;
static bool expectedHeavy=false;
static void* __fastcall stub_construct(void* value) {
    auto& payload=*static_cast<native_hit_show::Payload*>(value);
    std::memset(payload.data,0,sizeof payload.data);
    const std::uintptr_t table=0xABC;native_hit_show::store(payload,0,table);return value;
}
static void __fastcall stub_show(void* facade,const native_combat::ControlGuid* control,
    const native_hit_show::String* text,float value,bool hitLevel,const void* data,bool absolute,bool original,bool immediate) {
    assert(facade==reinterpret_cast<void*>(0x123)&&control->words[3]==4);
    assert(std::wcscmp(text->data,expectedHeavy?L"Hit/Result_Hit_Stand_Sword_StrongAttack":L"Hit/Result_Hit_Stand_Sword_LightAttack_NoAnim")==0);
    assert(text->count==std::wcslen(text->data)+1&&text->capacity==text->count);
    assert(value==0&&hitLevel==expectedHeavy&&!absolute&&original&&immediate);
    const auto* payload=static_cast<const native_hit_show::Payload*>(data);
    native_hit_context::Vec3 source,position,local;
    std::uint64_t bone=1;native_combat::ControlGuid target;
    std::memcpy(&source,payload->data+0x28,sizeof source);std::memcpy(&position,payload->data+0x34,sizeof position);
    std::memcpy(&local,payload->data+0x6C,sizeof local);std::memcpy(&bone,payload->data+0x4C,sizeof bone);
    std::memcpy(&target,payload->data+0x88,sizeof target);
    assert(source.x==0&&position.x==100&&bone==0&&local.x==0&&local.y==0&&local.z==0&&target.words[3]==4);
    ++showCalls;
}
int main(int argc,char** argv) {
    assert(argc==2);
    assert(native_combat::converted_damage(6500,6500,7,40)==1137.5f);
    assert(native_combat::converted_damage(130000,130000,7,200)==4550.f);
    assert(native_combat::converted_damage(6500,6500,56,40)==6500.f);
    assert(native_combat::converted_damage(100,6500,7,40)==100.f);
    assert(native_combat::converted_damage(100,100,.01f,40)<1.f);
    std::ifstream input(argv[1],std::ios::binary);
    std::vector<unsigned char> file((std::istreambuf_iterator<char>(input)),{});
    assert(file.size()>4096);
    auto dos=reinterpret_cast<const IMAGE_DOS_HEADER*>(file.data());
    auto nt=reinterpret_cast<const IMAGE_NT_HEADERS64*>(file.data()+dos->e_lfanew);
    auto sections=IMAGE_FIRST_SECTION(nt);
    auto mem=static_cast<unsigned char*>(VirtualAlloc(nullptr,0x7120000,MEM_COMMIT|MEM_RESERVE,PAGE_READWRITE));
    assert(mem);std::memcpy(mem,file.data(),4096);
    const unsigned offsets[]={0x1AD6AB0,0x1BA8720,0x1A97B20,0xE36F9B,0x1BA875F,0x1A97CB4,0x2274D66,0x201B1EF,0x201B260,0x201B2A7,0x1A350F9,0x1A30C90,0x5BF6818,
        0x1C49290,0x22857E0,0x1C50365,0x1A64B40,0x243E1E0,0xF2FB60,0x710DDA8,0x5C11110,0x1C50120,0x2459E0A};
    const unsigned sizes[]={32,32,16,6,36,7,10,9,10,16,25,25,8,0xDE,0x676,0x53,0xC5,0x200,6,12,8,0x2E,0x11};
    for(int i=0;i<23;i++) {
        bool found=false;
        for(unsigned j=0;j<nt->FileHeader.NumberOfSections;j++) {
            auto& s=sections[j];auto delta=offsets[i]-s.VirtualAddress;
            if(offsets[i]>=s.VirtualAddress&&delta+sizes[i]<=s.SizeOfRawData) {
                std::memcpy(mem+offsets[i],file.data()+s.PointerToRawData+delta,sizes[i]);found=true;break;
            }
            if(offsets[i]==0x710DDA8&&offsets[i]>=s.VirtualAddress&&delta+sizes[i]<=s.Misc.VirtualSize) {
                // Constructor's FVector zero constant is in zero-initialized
                // virtual data, without raw bytes in this PE section.
                std::memset(mem+offsets[i],0,sizes[i]);found=true;break;
            }
        }
        assert(found);
    }
    const auto base=reinterpret_cast<std::uintptr_t>(mem);
    assert(native_combat::certified(base));
    assert(native_combat::identity_certified(base));
    assert(*reinterpret_cast<std::uintptr_t*>(mem+0x5BF6818)==nt->OptionalHeader.ImageBase+0x1A30C90);
    *reinterpret_cast<std::uintptr_t*>(mem+0x5BF6818)=base+0x1A30C90;
    assert(native_hit_context::position_certified(base));
    assert(*reinterpret_cast<std::uintptr_t*>(mem+0x5C11110)==nt->OptionalHeader.ImageBase+0xF2FB60);
    *reinterpret_cast<std::uintptr_t*>(mem+0x5C11110)=base+0xF2FB60;
    assert(native_hit_show::certified(base));
    for(int i=0;i<23;i++) for(unsigned j=0;j<sizes[i];j++) {
        mem[offsets[i]+j]^=1;assert(!native_hit_show::certified(base));mem[offsets[i]+j]^=1;
    }
    auto header=reinterpret_cast<IMAGE_NT_HEADERS64*>(mem+dos->e_lfanew);
    header->FileHeader.TimeDateStamp^=1;assert(!native_combat::certified(base));header->FileHeader.TimeDateStamp^=1;
    header->OptionalHeader.SizeOfImage^=1;assert(!native_combat::certified(base));header->OptionalHeader.SizeOfImage^=1;
    header->FileHeader.Machine^=1;assert(!native_combat::certified(base));header->FileHeader.Machine^=1;
    assert(!native_combat::certified(0));
    assert(!native_hit_context::position_certified(0));
    assert(!native_hit_show::certified(0));
    using namespace native_hit_context;
    Request request{1,42,10,55,1,1,{0,0,0}};
    request.hostLevel=77;
    native_combat::Result damage;damage.ready=true;damage.applied=true;damage.before=100;damage.after=80;
    native_combat::Identity target{901,501,0x10000};native_combat::ControlGuid control{{1,2,3,4}};
    Session session;
    const auto hit=prepare(session,request,damage,target,target,control,{100,200,300},true);
    assert(hit.ready&&hit.request.uid==42&&hit.control.words[3]==4);
    native_hit_show::Payload payload;
    assert(native_hit_show::construct(stub_construct,payload));
    assert(!native_hit_show::fill(payload,hit,0xABD));
    assert(native_hit_show::fill(payload,hit,0xABC));
    assert(native_hit_show::invoke(stub_show,reinterpret_cast<void*>(0x123),control,payload));
    assert(showCalls==1);
    assert(native_hit_show::heavy_level(1)&&!native_hit_show::heavy_level(1.01f)
        &&!native_hit_show::heavy_level(NAN)&&!native_hit_show::heavy_level(-1));
    assert(native_hit_target::heavy_path(L"/Game/Art/Character/Monster/CH_M_NA_48/"));
    assert(!native_hit_target::heavy_path(L"/Game/Art/Character/Monster/CH_M_NA_40/"));
    assert(!native_hit_target::heavy_path(L"/Game/Art/Character/Monster/CH_M_NA_45/"));
    expectedHeavy=true;assert(native_hit_show::invoke(stub_show,reinterpret_cast<void*>(0x123),control,payload,true));
    assert(showCalls==2);expectedHeavy=false;
    auto invalidHit=hit;invalidHit.ready=false;assert(!native_hit_show::fill(payload,invalidHit,0xABC));
    std::vector<unsigned char> ue(0x3180),actor(0x600);
    const auto object=reinterpret_cast<std::uintptr_t>(ue.data()),entity=reinterpret_cast<std::uintptr_t>(actor.data());
    assert(native_hit_target::safe_state(entity)&&native_hit_target::empty_tags(object));
    std::uint32_t state[4]={6,1,0xFFFFFFFFu,0};
    auto* stateMap=reinterpret_cast<native_hit_target::SparseMap*>(actor.data()+0x560);
    const auto mapData=reinterpret_cast<std::uintptr_t>(state);std::int32_t one=1;std::uint32_t bit=1;
    std::memcpy(stateMap->bytes,&mapData,8);std::memcpy(stateMap->bytes+8,&one,4);std::memcpy(stateMap->bytes+12,&one,4);
    std::memcpy(stateMap->bytes+0x28,&one,4);std::memcpy(stateMap->bytes+0x10,&bit,4);
    for(auto id:{6u,7u,8u}) {state[0]=id;assert(!native_hit_target::safe_state(entity));}
    state[1]=0;assert(native_hit_target::safe_state(entity));
    auto* tags=reinterpret_cast<native_hit_target::SparseMap*>(ue.data()+0x3128);*tags=*stateMap;
    assert(!native_hit_target::empty_tags(object)); // Presence declines even if reference count is zero.
    std::memcpy(tags->bytes+0x34,&one,4);assert(!native_hit_target::empty_tags(object)); // Inconsistent free/bit count.
    assert(native_hit_target::sampled_path(L"/Game/Art/Character/Monster/CH_M_NA_45/"));
    assert(!native_hit_target::sampled_path(L"/Game/Art/Character/Monster/CH_M_NA_28/"));
    assert(!native_hit_target::sampled_path(L"/Game/Art/Character/NPC/CH_Emil_Shop/"));
    assert(!native_hit_target::resource_path(object));
    const wchar_t path[]=L"/Game/Art/Character/Monster/CH_M_NA_45/";
    native_hit_target::Array text{reinterpret_cast<std::uintptr_t>(path),static_cast<std::int32_t>(sizeof path/sizeof *path),static_cast<std::int32_t>(sizeof path/sizeof *path)};
    std::memcpy(ue.data()+0x1DE8,&text,sizeof text);assert(native_hit_target::resource_path(object));
    text.count--;std::memcpy(ue.data()+0x1DE8,&text,sizeof text);assert(!native_hit_target::resource_path(object));
    text.count++;std::memcpy(ue.data()+0x1DE8,&text,sizeof text);
    std::uintptr_t chunks[1],chunk[3]={object,0,0};chunks[0]=reinterpret_cast<std::uintptr_t>(chunk);
    *reinterpret_cast<std::uintptr_t*>(mem+0x6DD7240)=reinterpret_cast<std::uintptr_t>(chunks);
    *reinterpret_cast<std::int32_t*>(mem+0x6DD7254)=1;*reinterpret_cast<std::int32_t*>(mem+0x6DD725C)=1;
    *reinterpret_cast<std::uintptr_t*>(ue.data())=base+0x1000;
    assert(native_hit_target::live_object(base,object));
    chunk[1]=0x30000000;assert(!native_hit_target::live_object(base,object));chunk[1]=0;
    chunk[0]++;assert(!native_hit_target::live_object(base,object));chunk[0]--;
    *reinterpret_cast<std::int32_t*>(ue.data()+0xC)=1;assert(!native_hit_target::live_object(base,object));
    *reinterpret_cast<std::int32_t*>(ue.data()+0xC)=0;
    *tags={};std::memset(actor.data()+0x560,0,0x50);
    *reinterpret_cast<DWORD*>(mem+0x706B538)=GetCurrentThreadId();
    auto scoped=hit;scoped.target.entity=entity;
    assert(native_hit_target::eligible(base,object,scoped));
    *reinterpret_cast<DWORD*>(mem+0x706B538)=GetCurrentThreadId()+1;
    assert(!native_hit_target::eligible(base,object,scoped));
    assert(std::fabs(hit.direction.x*hit.direction.x+hit.direction.y*hit.direction.y+hit.direction.z*hit.direction.z-1)<1e-6);
    assert(!prepare(session,request,damage,target,target,control,{100,200,300},true).ready);
    request.serial=2;assert(prepare(session,request,damage,target,target,control,{100,200,300},true).ready);
    auto reject=[&](Request r,native_combat::Result d,native_combat::Identity after,native_combat::ControlGuid c,Vec3 pos,bool fresh=true) {
        Session independent;assert(!prepare(independent,r,d,target,after,c,pos,fresh).ready);
    };
    auto d=damage;d.applied=false;reject(request,d,target,control,{100,200,300});
    d=damage;d.ready=false;reject(request,d,target,control,{100,200,300});
    d=damage;d.after=0;reject(request,d,target,control,{100,200,300});
    d=damage;d.after=100;reject(request,d,target,control,{100,200,300});
    d=damage;d.invulnerable=true;reject(request,d,target,control,{100,200,300});
    d=damage;d.before=NAN;reject(request,d,target,control,{100,200,300});
    auto r=request;r.pid=0;reject(r,damage,target,control,{100,200,300});
    r=request;r.world=0;reject(r,damage,target,control,{100,200,300});
    r=request;r.hostLevel=0;reject(r,damage,target,control,{100,200,300});
    r=request;r.uid=0;reject(r,damage,target,control,{100,200,300});
    r=request;r.serial=0;reject(r,damage,target,control,{100,200,300});
    r=request;r.weapon=5;reject(r,damage,target,control,{100,200,300});
    r=request;r.kind=3;reject(r,damage,target,control,{100,200,300});
    r=request;r.source.x=NAN;reject(r,damage,target,control,{100,200,300});
    reject(request,damage,target,control,{INFINITY,0,0});
    reject(request,damage,target,control,{0,0,0});
    reject(request,damage,target,control,{1601,0,0});
    reject(request,damage,target,control,{100,200,300},false);
    auto replaced=target;replaced.guid++;reject(request,damage,replaced,control,{100,200,300});
    replaced=target;replaced.type++;reject(request,damage,replaced,control,{100,200,300});
    replaced=target;replaced.entity++;reject(request,damage,replaced,control,{100,200,300});
    reject(request,damage,target,{}, {100,200,300});
    r=request;r.kind=6;r.serial=3;assert(prepare(session,r,damage,target,target,control,{2000,0,0},true).ready);
    reject(r,damage,target,control,{3201,0,0});
    r=request;r.pid=11;r.serial=1;assert(prepare(session,r,damage,target,target,control,{100,200,300},true).ready);
    r.world=56;assert(prepare(session,r,damage,target,target,control,{100,200,300},true).ready);
    r.hostLevel=78;assert(prepare(session,r,damage,target,target,control,{100,200,300},true).ready);
    r=request;r.timeLow=1;reject(r,damage,target,control,{100,200,300});
    r=request;r.timeLow=0xFFFFFE0Bu;reject(r,damage,target,control,{100,200,300}); // 501ms old, including wrap
    r.timeLow=0xFFFFFE0Cu;Session wrap;assert(prepare(wrap,r,damage,target,target,control,{100,200,300},true).ready);
    r=request;r.weapon=5;r.hasTrajectory=true;r.trajectory={0,1,0};Session arrow;
    auto trajectory=prepare(arrow,r,damage,target,target,control,{100,0,0},true);
    assert(trajectory.ready&&trajectory.direction.x==0&&trajectory.direction.y==1&&trajectory.direction.z==0);
    r.trajectory={0,0,0};reject(r,damage,target,control,{100,0,0});
    r.trajectory={NAN,0,0};reject(r,damage,target,control,{100,0,0});
    r=request;r.weapon=0x101;Session heavy;
    const auto heavyHit=prepare(heavy,r,damage,target,target,control,{100,200,300},true);
    assert(heavyHit.ready&&heavyHit.request.heavyRequested&&heavyHit.request.weapon==1);
    r.weapon=0x201;reject(r,damage,target,control,{100,200,300});
    r.weapon=0x105;r.hasTrajectory=true;r.trajectory={0,1,0};reject(r,damage,target,control,{100,200,300});
    r.weapon=0x101;r.kind=6;reject(r,damage,target,control,{100,200,300});
    r.kind=1;r.weapon=0x106;reject(r,damage,target,control,{100,200,300});
    VirtualFree(mem,0,MEM_RELEASE);
    std::puts("PASS: combat/show gates; 2989 mutated bytes rejected; packed heavy/illegal style rejection; LIGHT/HEAVY nine-argument stub and original resistance comparison; no game calls");
}
