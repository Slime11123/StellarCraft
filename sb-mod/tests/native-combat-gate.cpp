#include "../src/native_combat.h"
#include <fstream>
#include <vector>
#include <cassert>
#include <cstdio>
// Offline gate test: map selected bytes from the installed executable. Never
// attach to the game or invoke any game code.
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
    auto mem=static_cast<unsigned char*>(VirtualAlloc(nullptr,0x7070000,MEM_COMMIT|MEM_RESERVE,PAGE_READWRITE));
    assert(mem);std::memcpy(mem,file.data(),4096);
    const unsigned offsets[]={0x1AD6AB0,0x1BA8720,0x1A97B20,0xE36F9B,0x1BA875F,0x1A97CB4,0x2274D66,0x201B1EF,0x201B260,0x201B2A7,0x1A350F9};
    const unsigned sizes[]={32,32,16,6,36,7,10,9,10,16,25};
    for(int i=0;i<11;i++) {
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
    assert(native_combat::certified(base));
    assert(native_combat::identity_certified(base));
    for(int i=0;i<11;i++) for(unsigned j=0;j<sizes[i];j++) {
        mem[offsets[i]+j]^=1;assert(!native_combat::identity_certified(base));mem[offsets[i]+j]^=1;
    }
    auto header=reinterpret_cast<IMAGE_NT_HEADERS64*>(mem+dos->e_lfanew);
    header->FileHeader.TimeDateStamp^=1;assert(!native_combat::certified(base));header->FileHeader.TimeDateStamp^=1;
    header->OptionalHeader.SizeOfImage^=1;assert(!native_combat::certified(base));header->OptionalHeader.SizeOfImage^=1;
    header->FileHeader.Machine^=1;assert(!native_combat::certified(base));header->FileHeader.Machine^=1;
    assert(!native_combat::certified(0));
    VirtualFree(mem,0,MEM_RELEASE);
    std::puts("PASS: combat and UE/native identity gates; 199 mutated bytes, build metadata and inaccessible address rejected; no game calls");
}
