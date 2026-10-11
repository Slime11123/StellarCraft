#pragma once
#include <cstdint>
#include <cstring>
#include <array>

inline uint32_t pose_crc(const uint8_t* bytes, size_t size) {
    static const auto table=[] {
        std::array<uint32_t,256> result{};
        for (uint32_t i=0;i<256;++i) {
            uint32_t c=i;
            for (int bit=0;bit<8;++bit) c=(c>>1)^((c&1)?0xEDB88320u:0u);
            result[i]=c;
        }
        return result;
    }();
    uint32_t crc=0xFFFFFFFFu;
    for (size_t i=0;i<size;++i) crc=(crc>>8)^table[(crc^bytes[i])&255];
    return crc^0xFFFFFFFFu;
}
inline bool pose_valid(const uint8_t* bytes, size_t size) {
    if (size<32 || std::memcmp(bytes,"SCPOSE1\n",8)!=0) return false;
    uint32_t seq,count,footer,crc;
    std::memcpy(&seq,bytes+8,4); std::memcpy(&count,bytes+20,4);
    if (count>2048 || size!=32+size_t(count)*44) return false;
    std::memcpy(&footer,bytes+size-8,4); std::memcpy(&crc,bytes+size-4,4);
    return seq==footer && crc==pose_crc(bytes,size-8);
}
