// Compile-time + runtime check of the protocol layout after the v2 pose/depth change.
#include "../../protocol/stellarcraft_protocol.h"
#include <cstddef>   // offsetof
#include <cstdio>

using namespace stellarcraft::proto;
static_assert(offsetof(SbState,motionTimeMs)==0x40);
static_assert(offsetof(SbState,motionFrame)==0x48);
static_assert(offsetof(SbState,motionEpoch)==0x50);
static_assert(offsetof(SbState,contactFlags)==0x54);
static_assert(offsetof(SbState,velocityX)==0x58);
static_assert(offsetof(SbState,velocityY)==0x60);
static_assert(offsetof(SbState,velocityZ)==0x68);
static_assert(offsetof(McState,motionTimeMs)==0xB0);
static_assert(offsetof(McState,motionEpoch)==0xB8);
static_assert(offsetof(McState,motionMode)==0xBC);
static_assert(offsetof(McState,velocityX)==0xC0);
static_assert(offsetof(McState,velocityY)==0xC8);
static_assert(offsetof(McState,velocityZ)==0xD0);

// The header's own static_asserts already run here; add offset checks for the new fields.
static_assert(offsetof(OverlaySlotHdr, width) == 0x00);
static_assert(offsetof(OverlaySlotHdr, height) == 0x04);
static_assert(offsetof(OverlaySlotHdr, flags) == 0x08);
static_assert(offsetof(OverlaySlotHdr, frameId) == 0x10);
static_assert(offsetof(OverlaySlotHdr, camX) == 0x18);
static_assert(offsetof(OverlaySlotHdr, camY) == 0x20);
static_assert(offsetof(OverlaySlotHdr, camZ) == 0x28);
static_assert(offsetof(OverlaySlotHdr, camYaw) == 0x30);
static_assert(offsetof(OverlaySlotHdr, camPitch) == 0x34);
static_assert(offsetof(OverlaySlotHdr, camRoll) == 0x38);
static_assert(offsetof(OverlaySlotHdr, vFovDeg) == 0x3C);
static_assert(offsetof(OverlaySlotHdr, aspect) == 0x40);
static_assert(offsetof(OverlaySlotHdr, nearZ) == 0x44);
static_assert(offsetof(OverlaySlotHdr, farZ) == 0x48);
static_assert(offsetof(OverlaySlotHdr, cameraMode) == 0x4C);
static_assert(offsetof(OverlaySlotHdr, depthOffset) == 0x50);
static_assert(offsetof(OverlaySlotHdr, depthBytes) == 0x54);
static_assert(offsetof(OverlaySlotHdr, reserved) == 0x5C);

// OverlayCtl is 16 bytes, so slot 0's header starts at +0x10 (what overlay.cpp reads).
static_assert(sizeof(OverlayCtl) == 0x10);
static_assert(kOverlayCtlBytes == 0x1000);
static_assert(sizeof(OverlayCtl) + 3 * sizeof(OverlaySlotHdr) <= kOverlayCtlBytes);

// The reserved depth region must sit exactly between the colour slots and RenderRing.
static_assert(kOffOverlayDepth == kOffOverlayPixels + kOverlaySlotBytes * kOverlaySlots);
static_assert(kOffRenderRing == kOffOverlayDepth + kOverlayDepthBytes);
static_assert(kOverlayDepthSlotBytes == kOverlaySlotBytes);  // both are 4 bytes/pixel

int main()
{
    // Exact decimal values, so tools/check_proto_parity.py can compare them against the
    // Java mirror (Proto.java) without reimplementing the layout.
    std::printf("kVersion=%u\n", kVersion);
    std::printf("OverlaySlotHdr=%u\n", (unsigned)sizeof(OverlaySlotHdr));
    std::printf("OverlayCtl=%u\n", (unsigned)sizeof(OverlayCtl));
    std::printf("kOffOverlayCtl=%llu\n", (unsigned long long)kOffOverlayCtl);
    std::printf("kOffOverlayPixels=%llu\n", (unsigned long long)kOffOverlayPixels);
    std::printf("kOverlaySlotBytes=%llu\n", (unsigned long long)kOverlaySlotBytes);
    std::printf("kOffOverlayDepth=%llu\n", (unsigned long long)kOffOverlayDepth);
    std::printf("kOverlayDepthBytes=%llu\n", (unsigned long long)kOverlayDepthBytes);
    std::printf("kOffRenderRing=%llu\n", (unsigned long long)kOffRenderRing);
    std::printf("kOffAppearance=%llu\n", (unsigned long long)kOffAppearance);
    std::printf("kMappingBytes=%llu\n", (unsigned long long)kMappingBytes);
    std::printf("LAYOUT OK\n");
    return 0;
}
