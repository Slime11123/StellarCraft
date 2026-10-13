#pragma once
#include "native_hit_context.h"
#include <cwchar>

// Runtime subset of the sampled ordinary-monster contract. Reads only; a
// matching resource path alone is not permission to invoke a show.
namespace native_hit_target {
struct Array {std::uintptr_t data{};std::int32_t count{},capacity{};};
struct SparseMap {unsigned char bytes[0x50]{};};
template<class T> inline T field(const SparseMap& map,std::size_t offset) {
    T value{};std::memcpy(&value,map.bytes+offset,sizeof value);return value;
}
inline bool sparse_header(const SparseMap& map) {
    const auto count=field<std::int32_t>(map,8),capacity=field<std::int32_t>(map,12);
    const auto bits=field<std::int32_t>(map,0x28),free=field<std::int32_t>(map,0x34);
    return count>=0&&count<=capacity&&capacity<=512&&bits==count&&free>=0&&free<=count
        &&(!count||field<std::uintptr_t>(map,0));
}
inline bool bit(const SparseMap& map,int index,bool& active) {
    std::uint32_t word=0;const auto ptr=field<std::uintptr_t>(map,0x20);
    if(ptr) {if(!native_combat::read(ptr+4u*(index/32),word)) return false;}
    else {
        if(index>=128) return false;
        word=field<std::uint32_t>(map,0x10+4u*(index/32));
    }
    active=(word&(1u<<(index%32)))!=0;return true;
}
inline bool safe_state(std::uintptr_t entity) {
    SparseMap map,again;
    if(!native_combat::read(entity+0x560,map)||!sparse_header(map)) return false;
    int activeCount=0;const auto data=field<std::uintptr_t>(map,0);
    for(int i=0;i<field<std::int32_t>(map,8);++i) {
        bool active=false;if(!bit(map,i,active)) return false;
        if(!active) continue;
        ++activeCount;std::uint32_t key=0,count=0;
        if(!native_combat::read(data+16u*i,key)||!native_combat::read(data+16u*i+4,count)) return false;
        // Original resource selection queries states 6, 7 and 8. Do not force
        // the ordinary candidate over those alternate-state branches.
        if(count&&(key==6||key==7||key==8)) return false;
    }
    return activeCount==field<std::int32_t>(map,8)-field<std::int32_t>(map,0x34)
        &&native_combat::read(entity+0x560,again)&&std::memcmp(&map,&again,sizeof map)==0;
}
inline bool empty_tags(std::uintptr_t object) {
    SparseMap map,again;
    // Conservative first subset: any live tag declines the candidate,
    // including count-zero tags (the engine checks presence).
    if(!native_combat::read(object+0x3128,map)||!sparse_header(map)
        ||field<std::int32_t>(map,8)!=field<std::int32_t>(map,0x34)) return false;
    for(int i=0;i<field<std::int32_t>(map,8);++i) {
        bool active=false;if(!bit(map,i,active)||active) return false;
    }
    return native_combat::read(object+0x3128,again)&&std::memcmp(&map,&again,sizeof map)==0;
}
inline bool sampled_path(const wchar_t* path) {
    static const wchar_t* paths[]={
        L"/Game/Art/Character/Monster/CH_M_NA_09/",L"/Game/Art/Character/Monster/CH_M_NA_11/",
        L"/Game/Art/Character/Monster/CH_M_NA_40/",L"/Game/Art/Character/Monster/CH_M_NA_45/",
        L"/Game/Art/Character/Monster/CH_M_NA_46/",L"/Game/Art/Character/Monster/CH_M_NA_47/",
        L"/Game/Art/Character/Monster/CH_M_NA_48/",L"/Game/Art/Character/Monster/CH_M_NA_49/"};
    for(auto expected:paths) if(std::wcscmp(path,expected)==0) return true;
    return false;
}
inline bool heavy_path(const wchar_t* path) {
    // Full directional clips were sampled at these exact paths. Type/variant
    // folders in 40/45 need a separate certificate; their LIGHT stays supported.
    static const wchar_t* paths[]={
        L"/Game/Art/Character/Monster/CH_M_NA_09/",L"/Game/Art/Character/Monster/CH_M_NA_11/",
        L"/Game/Art/Character/Monster/CH_M_NA_46/",L"/Game/Art/Character/Monster/CH_M_NA_47/",
        L"/Game/Art/Character/Monster/CH_M_NA_48/",L"/Game/Art/Character/Monster/CH_M_NA_49/"};
    for(auto expected:paths) if(std::wcscmp(path,expected)==0) return true;
    return false;
}
inline bool resource_path(std::uintptr_t object,bool heavy=false) {
    Array text,again;wchar_t path[128]{};
    if(!native_combat::read(object+0x1DE8,text)||text.count<2||text.count>128
        ||text.capacity<text.count||text.capacity>4096||!text.data) return false;
    for(int i=0;i<text.count;++i) if(!native_combat::read(text.data+2u*i,path[i])) return false;
    return path[text.count-1]==0&&std::wcslen(path)==std::size_t(text.count-1)
        &&(heavy?heavy_path(path):sampled_path(path))
        &&native_combat::read(object+0x1DE8,again)&&std::memcmp(&text,&again,sizeof text)==0;
}
inline bool live_object(std::uintptr_t base,std::uintptr_t object) {
    std::uintptr_t vt=0,chunks=0,chunk=0,slotObject=0;
    std::int32_t index=0,count=0,chunkCount=0;std::uint32_t flags=0;
    if(!object||!native_combat::read(object,vt)||vt<=base||vt>=base+0x15981000
        ||!native_combat::read(object+0xC,index)||index<0
        ||!native_combat::read(base+0x6DD7240,chunks)||!chunks
        ||!native_combat::read(base+0x6DD7254,count)||count<1||count>2000000||index>=count
        ||!native_combat::read(base+0x6DD725C,chunkCount)||chunkCount<1||chunkCount>32
        ||count>chunkCount*65536||index/65536>=chunkCount
        ||!native_combat::read(chunks+8u*(index/65536),chunk)||!chunk) return false;
    const auto slot=chunk+24u*(index%65536);
    return native_combat::read(slot,slotObject)&&slotObject==object
        &&native_combat::read(slot+8,flags)&&!(flags&0x30000000u);
}
inline bool certified(std::uintptr_t base) {
    static const unsigned char state[]={0x48,0x89,0x5c,0x24,0x08,0x8b,0x81,0x68,0x05,0,0,0x4c,0x8b,0xd9,
        0x4c,0x63,0xd2,0x3b,0x81,0x94,0x05,0,0,0x74,0x4c};
    static const unsigned char tag[]={0x49,0x8d,0x8e,0x28,0x31,0,0,0x48,0x8d,0x54,0x24,0x68,
        0xe8,0x70,0xe1,0xa2,0xfe,0x83,0x38,0xff};
    return native_hit_context::position_certified(base)
        &&native_combat::bytes(base+0x1A64B40,state,sizeof state)
        &&native_combat::bytes(base+0x243E34F,tag,sizeof tag);
}
inline bool eligible(std::uintptr_t base,std::uintptr_t object,const native_hit_context::Context& context) {
    DWORD thread=0;
    return context.ready&&context.target.entity&&certified(base)
        &&native_combat::read(base+0x706B538,thread)&&thread==GetCurrentThreadId()
        &&live_object(base,object)&&resource_path(object)&&empty_tags(object)&&safe_state(context.target.entity)
        &&live_object(base,object);
}
}
