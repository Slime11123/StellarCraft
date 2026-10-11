"""Read the StellarCraft shared-memory mapping and report the overlay pipeline state.

Usage:  python probe_shm.py
Prints McState.flags (STEVE bit), overlay framesPublished, slot0 header, and a
sample of the published pixels so we can tell whether MC is publishing UI frames.
"""
import ctypes
import ctypes.wintypes as w
import sys

kMappingName = "Local\\StellarCraft_v1"
kOffMcState = 0x200
kOffOverlayCtl = 0x20000 + (32 << 20)          # 0x2020000
kOffOverlayPixels = kOffOverlayCtl + 0x1000

FILE_MAP_ALL_ACCESS = 0x000F001F

k32 = ctypes.WinDLL("kernel32", use_last_error=True)
k32.OpenFileMappingW.restype = w.HANDLE
k32.OpenFileMappingW.argtypes = [w.DWORD, w.BOOL, w.LPCWSTR]
k32.MapViewOfFile.restype = ctypes.c_void_p
k32.MapViewOfFile.argtypes = [w.HANDLE, w.DWORD, w.DWORD, w.DWORD, ctypes.c_size_t]

h = k32.OpenFileMappingW(FILE_MAP_ALL_ACCESS, False, kMappingName)
if not h:
    print("OpenFileMapping failed (err=%d) -- Stellar Blade / shm DLL not running?" % ctypes.get_last_error())
    sys.exit(1)

base = k32.MapViewOfFile(h, FILE_MAP_ALL_ACCESS, 0, 0, 0)
if not base:
    print("MapViewOfFile failed (err=%d)" % ctypes.get_last_error())
    sys.exit(1)

kTotal = kOffOverlayPixels + 3840 * 2160 * 4
buf = (ctypes.c_ubyte * kTotal).from_address(base)
b = bytes(buf)

import struct

# Header @0
magic, version, sbpid, mcpid = struct.unpack_from("<IIII", b, 0)
sbhb, mchb = struct.unpack_from("<QQ", b, 0x10)
print("== header ==")
print("  magic=0x%08X version=%d sbPid=%d mcPid=%d" % (magic, version, sbpid, mcpid))
print("  sbHeartbeat=%d mcHeartbeat=%d" % (sbhb, mchb))

# SbState @0x100
sbseq, sbflags = struct.unpack_from("<II", b, 0x100)
print("== SbState @0x100 ==")
print("  seq=%d flags=0x%X  CONTROL_STEVE(sb,1<<4)=%s" % (sbseq, sbflags, bool(sbflags & 0x10)))

# McState @0x200
mcseq, mcflags = struct.unpack_from("<II", b, kOffMcState)
print("== McState @0x200 ==")
print("  seq=%d flags=0x%X" % (mcseq, mcflags))
print("  kMcInWorld(1<<0)      = %s" % bool(mcflags & 0x01))
print("  kMcScreenOpen(1<<1)   = %s" % bool(mcflags & 0x02))
print("  kMcControlSteve(1<<8) = %s   <-- overlay shows UI only when this is ON" % bool(mcflags & 0x100))

# OverlayCtl @ kOffOverlayCtl
ctl = kOffOverlayCtl
state, pad, frames = struct.unpack_from("<IIQ", b, ctl)
print("== OverlayCtl @0x%X ==" % ctl)
print("  state=0x%X dirty(1<<2)=%s framesPublished=%d" % (state, bool(state & 4), frames))

# OverlaySlotHdr[0] @ ctl+0x10 (protocol v2: 0x80 bytes, carries the frame pose)
sh = ctl + 0x10
sw, sh_, sflags, spad, sfid = struct.unpack_from("<IIIIQ", b, sh)
print("== Slot0 hdr @0x%X ==" % sh)
print("  width=%d height=%d flags=0x%X frameId=%d" % (sw, sh_, sflags, sfid))
print("  bottomUp(1<<0)=%s  poseValid(1<<1)=%s  hasDepth(1<<2)=%s"
      % (bool(sflags & 1), bool(sflags & 2), bool(sflags & 4)))

# v2 camera pose, the pose this frame was RENDERED WITH (see protocol §OverlaySlotHdr).
camX, camY, camZ = struct.unpack_from("<ddd", b, sh + 0x18)
camYaw, camPitch, camRoll, vfov, aspect, nearZ, farZ = struct.unpack_from("<fffffff", b, sh + 0x30)
cameraMode, depthOff, depthBytes = struct.unpack_from("<III", b, sh + 0x4C)
print("  pose pos=(%.2f, %.2f, %.2f)  yaw=%.1f pitch=%.1f roll=%.1f"
      % (camX, camY, camZ, camYaw, camPitch, camRoll))
print("  vfov=%.2f  aspect=%.4f  near=%.3f  far=%.1f  cameraMode=%d" % (vfov, aspect, nearZ, farZ, cameraMode))
print("  depthOffset=%s bytes=%d  (0xFFFFFFFF = reserved, not produced yet)"
      % ("NONE" if depthOff == 0xFFFFFFFF else "0x%X" % depthOff, depthBytes))

# Pixel sanity: count NON-BLACK pixels in slot0.
#
# Test the COLOUR, not the alpha. glReadPixels fills the alpha channel with 255 everywhere, so
# an "alpha != 0" test reported 100% transparent-looking pixels for an empty frame and was
# useless. The overlay derives its alpha from the colour precisely because the frame's black
# means "world, hide it" -- so black IS the transparent case here, and this probe has to use
# the same rule the overlay does.
if 0 < sw <= 3840 and 0 < sh_ <= 2160:
    n = sw * sh_
    off = kOffOverlayPixels
    nz = 0
    step = max(1, n // 40000)
    cnt = 0
    for i in range(0, n, step):
        o = off + i * 4
        if (b[o] | b[o + 1] | b[o + 2]) != 0:
            nz += 1
        cnt += 1
    print("== slot0 pixels ==")
    print("  non-black sampled = %d / %d  (%.2f%%)   <- non-black = UI (the overlay's alpha rule)"
          % (nz, cnt, 100.0 * nz / max(1, cnt)))
    if nz:
        for i in range(0, n, step):
            px = b[off + i * 4: off + i * 4 + 4]
            if px[3] != 0:
                print("  first opaque px: RGBA=%s at idx %d" % (tuple(px), i))
                break
