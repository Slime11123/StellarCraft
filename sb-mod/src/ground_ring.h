#pragma once
#include <cstdint>
#include <cstring>
#include <atomic>

struct GroundHeader { int32_t originGx, originGz; uint32_t count, pad; };
struct GroundCell { int32_t gx, gz; float topY; uint32_t hit; };

inline bool write_ground_message(uint8_t* ring, int32_t originGx, int32_t originGz,
                                const GroundCell* cells, uint32_t count,
                                uint64_t capacity = (32ULL << 20) - 0x80)
{
    auto* head = reinterpret_cast<volatile uint64_t*>(ring);
    auto* tail = reinterpret_cast<volatile uint64_t*>(ring + 0x40);
    const uint64_t h = *head, t = *tail;
    const uint64_t total = 4 + sizeof(GroundHeader) + uint64_t(count) * sizeof(GroundCell);
    if (h < t || total > capacity || h - t + total > capacity) return false;
    uint8_t* data = ring + 0x80;
    auto put = [&](uint64_t offset, const void* source, uint64_t n) {
        const auto* bytes = static_cast<const uint8_t*>(source);
        for (uint64_t i = 0; i < n; ++i) data[(h + offset + i) % capacity] = bytes[i];
    };
    const uint32_t op = 4;
    const GroundHeader header{originGx, originGz, count, 0};
    put(0, &op, 4);
    put(4, &header, sizeof(header));
    put(4 + sizeof(header), cells, uint64_t(count) * sizeof(GroundCell));
    std::atomic_thread_fence(std::memory_order_release);
    *head = h + total;
    return true;
}
