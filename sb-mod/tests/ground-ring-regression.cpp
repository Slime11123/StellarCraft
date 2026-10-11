#include "../src/ground_ring.h"
#include "../src/pose_frame.h"
#include <cassert>
#include <iostream>
#include <vector>

int main() {
    constexpr uint64_t capacity = 257; // force headers and cells across the wrap boundary
    std::vector<uint64_t> storage((capacity + 0x80 + 7) / 8);
    auto* ring = reinterpret_cast<uint8_t*>(storage.data());
    auto* head = reinterpret_cast<uint64_t*>(ring);
    auto* tail = reinterpret_cast<uint64_t*>(ring + 0x40);
    auto get = [&](uint64_t cursor, void* out, size_t n) {
        for (size_t i=0; i<n; ++i) static_cast<uint8_t*>(out)[i]=ring[0x80+(cursor+i)%capacity];
    };
    for (int batch=0; batch<2000; ++batch) {
        // Keep two messages unread to ensure the second never overwrites the first.
        for (int n=0; n<2; ++n) {
            GroundCell cell{batch*2+n, -batch, 192.0f+float(n), 1};
            assert(write_ground_message(ring, cell.gx, cell.gz, &cell, 1, capacity));
        }
        for (int n=0; n<2; ++n) {
            uint32_t op; GroundHeader header; GroundCell cell;
            get(*tail,&op,4); get(*tail+4,&header,sizeof(header)); get(*tail+20,&cell,sizeof(cell));
            assert(op==4 && header.count==1 && cell.gx==batch*2+n && cell.gz==-batch);
            assert(cell.topY==192.0f+float(n) && cell.hit==1);
            *tail += 36;
        }
        assert(*head==*tail);
    }
    GroundCell cell{};
    for (int i=0; i<7; ++i) assert(write_ground_message(ring,0,0,&cell,1,capacity));
    const auto before=*head;
    assert(!write_ground_message(ring,0,0,&cell,1,capacity) && *head==before);
    std::cout << "PASS: 4000 ground messages, wrap, unread preservation, full ring\n";
    std::vector<uint8_t> pose(76);
    std::memcpy(pose.data(),"SCPOSE1\n",8);
    uint32_t seq=7,count=1;
    std::memcpy(pose.data()+8,&seq,4);std::memcpy(pose.data()+20,&count,4);
    std::memcpy(pose.data()+68,&seq,4);
    uint32_t crc=pose_crc(pose.data(),68);std::memcpy(pose.data()+72,&crc,4);
    assert(pose_valid(pose.data(),pose.size()));
    assert(!pose_valid(pose.data(),pose.size()-1));
    pose[30]^=1; assert(!pose_valid(pose.data(),pose.size()));pose[30]^=1;
    pose[68]^=1; assert(!pose_valid(pose.data(),pose.size()));
    std::cout << "PASS: pose frame, partial write, checksum, sequence mismatch\n";
}
