#include "../src/native_player_damage.h"
#include <fstream>
#include <vector>
#include <cassert>
#include <cstdio>
// Offline gate test: map selected bytes from the installed executable. Never
// attach to the game or invoke any game code.
int main(int argc,char** argv) {
    assert(argc==2);
    std::ifstream input(argv[1],std::ios::binary);
    std::vector<unsigned char> file((std::istreambuf_iterator<char>(input)),{});
    assert(file.size()>4096);
    auto dos=reinterpret_cast<const IMAGE_DOS_HEADER*>(file.data());
    auto nt=reinterpret_cast<const IMAGE_NT_HEADERS64*>(file.data()+dos->e_lfanew);
    auto sections=IMAGE_FIRST_SECTION(nt);
    auto mem=static_cast<unsigned char*>(VirtualAlloc(nullptr,0x7070000,MEM_COMMIT|MEM_RESERVE,PAGE_READWRITE));
    assert(mem);std::memcpy(mem,file.data(),4096);
    const unsigned offsets[]={0x1AD6AB0,0x1BA8720,0x1A97B20,0xE36F9B,0x1A64CE0,0x1BA881E,0x1A97C44,0x1CC2590,0x1AAC32C,0x1C71B1F,0x1C97E3E,0x1BA87E6,0x1BA8823,0x1B1F7FC};
    const unsigned sizes[]={32,32,16,6,32,5,12,74,6,13,14,61,20,66};
    for(int i=0;i<14;i++) {
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
    for(int i=0;i<14;i++) for(unsigned j=0;j<sizes[i];j++) {
        mem[offsets[i]+j]^=1;assert(!native_player_damage::certified(base));mem[offsets[i]+j]^=1;
    }
    auto header=reinterpret_cast<IMAGE_NT_HEADERS64*>(mem+dos->e_lfanew);
    header->FileHeader.TimeDateStamp^=1;assert(!native_player_damage::certified(base));header->FileHeader.TimeDateStamp^=1;
    header->OptionalHeader.SizeOfImage^=1;assert(!native_player_damage::certified(base));header->OptionalHeader.SizeOfImage^=1;
    header->FileHeader.Machine^=1;assert(!native_player_damage::certified(base));header->FileHeader.Machine^=1;
    assert(!native_player_damage::certified(0));
    VirtualFree(mem,0,MEM_RELEASE);
    std::puts("PASS: installed combat, player identity and stat caller byte gates; 389 mutated bytes, build metadata and inaccessible address rejected; no game calls");
}
