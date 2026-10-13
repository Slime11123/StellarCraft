#include "../src/native_player_damage.h"
#include <fstream>
#include <vector>
#include <cassert>
#include <cstdio>
// Any attempt to install a hook during this offline test is a failure.
extern "C" MH_STATUS WINAPI MH_Initialize() {assert(false);return MH_ERROR_NOT_INITIALIZED;}
extern "C" MH_STATUS WINAPI MH_CreateHook(LPVOID,LPVOID,LPVOID*) {assert(false);return MH_ERROR_NOT_INITIALIZED;}
extern "C" MH_STATUS WINAPI MH_RemoveHook(LPVOID) {assert(false);return MH_ERROR_NOT_INITIALIZED;}
extern "C" MH_STATUS WINAPI MH_EnableHook(LPVOID) {assert(false);return MH_ERROR_NOT_INITIALIZED;}
// Offline gate test: map selected bytes from the installed executable. Never
// attach to the game or invoke any game code.
int main(int argc,char** argv) {
    assert(argc==2);
    // This process is the offline test executable, not the certified game.
    // A rejected refresh must preserve the prior lease; explicit release clears it.
    native_player_damage::installed=true;native_player_damage::enabled=true;
    native_player_damage::watched=0x1234;native_player_damage::refreshed=123;
    assert(native_player_damage::update(257,0,true));
    assert(native_player_damage::enabled&&native_player_damage::watched==0x1234&&native_player_damage::refreshed==123);
    assert(native_player_damage::update(0,0,false));
    assert(!native_player_damage::enabled&&native_player_damage::watched==0);
    native_player_damage::installed=false;
    std::ifstream input(argv[1],std::ios::binary);
    std::vector<unsigned char> file((std::istreambuf_iterator<char>(input)),{});
    assert(file.size()>4096);
    auto dos=reinterpret_cast<const IMAGE_DOS_HEADER*>(file.data());
    auto nt=reinterpret_cast<const IMAGE_NT_HEADERS64*>(file.data()+dos->e_lfanew);
    auto sections=IMAGE_FIRST_SECTION(nt);
    auto mem=static_cast<unsigned char*>(VirtualAlloc(nullptr,0x7070000,MEM_COMMIT|MEM_RESERVE,PAGE_READWRITE));
    assert(mem);std::memcpy(mem,file.data(),4096);
    const unsigned offsets[]={0x1AD6AB0,0x1BA8720,0x1A97B20,0xE36F9B,0x1A64CE0,0x1BA881E,0x1A97C44,0x1CC2590,0x1AAC32C,0x1C71B1F,0x1C97E3E,0x1BA87E6,0x1BA8823,0x1B1F7FC,0x1BA875F,0x1BA87B3,0x1AD6A10,0x1AD6A60,0x1BC609A,0x1BC64CF,0x1A30C90};
    const unsigned sizes[]={32,32,16,6,32,5,12,74,6,13,14,61,20,66,36,41,33,25,4,4,25};
    for(int i=0;i<21;i++) {
        bool found=false;
        for(unsigned j=0;j<nt->FileHeader.NumberOfSections;j++) {
            auto& s=sections[j];auto delta=offsets[i]-s.VirtualAddress;
            if(offsets[i]>=s.VirtualAddress&&delta+sizes[i]<=s.SizeOfRawData) {
                std::memcpy(mem+offsets[i],file.data()+s.PointerToRawData+delta,sizes[i]);found=true;break;
            }
        }
        assert(found);
    }
    const auto base=reinterpret_cast<std::uintptr_t>(mem);
    assert(native_player_damage::certified(base));
    // Synthetic hash maps reproduce the game lookup without running game code.
    std::array<unsigned char,0x200> manager{},effect{},target{},owner{},logicSlots{},actorSlots{};
    std::array<unsigned char,0x500> entity{},victim{};
    auto address=[](auto& data){return reinterpret_cast<std::uintptr_t>(data.data());};
    auto set=[](auto& data,std::size_t offset,auto value){std::memcpy(data.data()+offset,&value,sizeof value);};
    set(manager,0x120,address(logicSlots));set(manager,0x128,1);set(manager,0x168,1);set(manager,0x158,0);
    set(manager,0x170,address(actorSlots));set(manager,0x178,1);set(manager,0x1b8,1);set(manager,0x1a8,0);
    set(logicSlots,0,123u);set(logicSlots,8,address(effect));set(logicSlots,16,-1);
    set(actorSlots,0,901u);set(actorSlots,8,address(owner));set(actorSlots,16,-1);
    set(effect,0,base+0x5C0BD18);set(effect,0x20,123u);set(effect,0x104,1000020u);
    set(effect,0x58,address(target));set(effect,0x60,address(owner));
    set(target,0,base+0x5C0BEE0);set(target,0x60,address(victim));
    set(owner,0,base+0x5C0BEE0);set(owner,0x20,901u);set(owner,0x60,address(entity));
    set(entity,0,base+0x5BF6730);set(entity,0x10,base+0x5BF67D0);
    set(entity,0x30,901u);set(entity,0x10c,55048001u);
    set(entity,0x4cc,100.f);set(entity,0x4d0,200.f);set(entity,0x4d4,300.f);
    auto source=[&]{return native_player_damage::hit_source(base,address(manager),123,1000020,address(victim));};
    auto found=source();assert(found.guid==901&&found.type==55048001&&found.x==100&&found.y==200&&found.z==300);
    set(effect,0x104,1000016u);assert(!source().guid);set(effect,0x104,1000020u);
    set(effect,0x20,124u);assert(!source().guid);set(effect,0x20,123u);
    set(effect,0x58,address(owner));assert(!source().guid);set(effect,0x58,address(target));
    set(entity,0x30,902u);assert(!source().guid);set(entity,0x30,901u);
    set(entity,0x10c,100u);assert(!source().guid);set(entity,0x10c,55048001u);
    set(entity,0x4cc,std::nanf(""));assert(!source().guid);set(entity,0x4cc,100.f);
    set(manager,0x158,-1);assert(!source().guid);set(manager,0x158,0);
    set(manager,0x168,3);assert(!source().guid);set(manager,0x168,1);
    set(manager,0x154,1);assert(!source().guid);set(manager,0x154,0);
    set(logicSlots,0,999u);set(logicSlots,16,0);assert(!source().guid);
    set(logicSlots,16,999);assert(!source().guid);set(logicSlots,0,123u);set(logicSlots,16,-1);
    set(owner,0x20,0u);assert(!source().guid);set(owner,0x20,901u);
    set(actorSlots,8,address(target));assert(!source().guid);set(actorSlots,8,address(owner));
    set(owner,0,base+0x5C0BEE8);assert(!source().guid);set(owner,0,base+0x5C0BEE0);
    assert(!native_player_damage::hit_source(base,0,123,1000020,address(victim)).guid);
    std::puts("PASS: effect origin/position, victim/GUID/type/vtable, dead/free/stale source, malformed hash and cyclic/out-of-range chain rejection");
    unsigned mutations=0;
    for(int i=0;i<21;i++) for(unsigned j=0;j<sizes[i];j++) {
        mem[offsets[i]+j]^=1;assert(!native_player_damage::certified(base));mem[offsets[i]+j]^=1;
        ++mutations;
    }
    auto header=reinterpret_cast<IMAGE_NT_HEADERS64*>(mem+dos->e_lfanew);
    header->FileHeader.TimeDateStamp^=1;assert(!native_player_damage::certified(base));header->FileHeader.TimeDateStamp^=1;
    header->OptionalHeader.SizeOfImage^=1;assert(!native_player_damage::certified(base));header->OptionalHeader.SizeOfImage^=1;
    header->FileHeader.Machine^=1;assert(!native_player_damage::certified(base));header->FileHeader.Machine^=1;
    assert(!native_player_damage::certified(0));
    VirtualFree(mem,0,MEM_RELEASE);
    std::printf("PASS: combat/player/effect source gates; %u mutated bytes, metadata and inaccessible address rejected; no game calls\n",mutations);
}
