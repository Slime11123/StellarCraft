"""Offline end-to-end test of the v2 frame-pose path, with no game running.

Creates the shared-memory section ourselves, writes a slot-0 header containing a KNOWN pose,
then runs tools/probe_shm.py against it and asserts the reader reports exactly those values.
That exercises the real offset arithmetic on both sides, so a wrong field offset fails here
instead of silently producing garbage in-game.

This is the "fake host" idea from docs/GTA5_PASSTHROUGH_ANALYSIS.md §3.4, in its smallest
useful form: verify the wire format without launching either game.

Run:  python tools/test_pose_pipeline.py
"""
import ctypes
import os
import struct
import subprocess
import sys

NAME = "Local\\StellarCraft_v1"

# Protocol v2 layout (must match protocol/stellarcraft_protocol.h; check_proto_parity.py
# enforces that, this test just consumes it).
OFF_HEADER = 0x0
OFF_OVERLAY_CTL = 0x20000 + (32 << 20)
OFF_OVERLAY_PIXELS = OFF_OVERLAY_CTL + 0x1000
OVERLAY_SLOT_BYTES = 3840 * 2160 * 4
OVERLAY_SLOTS = 3
OFF_OVERLAY_DEPTH = OFF_OVERLAY_PIXELS + OVERLAY_SLOT_BYTES * OVERLAY_SLOTS
OVERLAY_DEPTH_BYTES = OVERLAY_SLOT_BYTES * OVERLAY_SLOTS
MAPPING_BYTES = OFF_OVERLAY_DEPTH + OVERLAY_DEPTH_BYTES + (64 << 20) + 0x80 + 64 * 64 * 4 + 64 * 32 * 4

SLOT_HDR = OFF_OVERLAY_CTL + 0x10
K_MAGIC = 0x43544153
K_VERSION = 3
NO_DEPTH = 0xFFFFFFFF

# The values we plant, and the exact strings probe_shm.py must print for them.
POSE = dict(x=123.5, y=64.25, z=-456.75, yaw=90.0, pitch=-30.5, roll=0.0,
            vfov=71.25, aspect=1.7778, near=0.05, far=512.0, cam=1)
WIDTH, HEIGHT = 854, 480


def main():
    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    k32.CreateFileMappingW.restype = ctypes.c_void_p
    k32.MapViewOfFile.restype = ctypes.c_void_p

    h = k32.CreateFileMappingW(ctypes.c_void_p(-1), None, 0x04,
                               MAPPING_BYTES >> 32, MAPPING_BYTES & 0xFFFFFFFF, NAME)
    if not h:
        print("CreateFileMappingW failed: err=%d (already open? is a game running?)"
              % ctypes.get_last_error())
        return 1
    p = k32.MapViewOfFile(ctypes.c_void_p(h), 0x000F001F, 0, 0, MAPPING_BYTES)
    if not p:
        print("MapViewOfFile failed: err=%d" % ctypes.get_last_error())
        return 1
    print("created %s (%d bytes = %.1f MiB)" % (NAME, MAPPING_BYTES, MAPPING_BYTES / 1048576.0))

    buf = (ctypes.c_char * MAPPING_BYTES).from_address(p)

    # Header: magic + version (so probe reports them), plus a fake framesPublished.
    struct.pack_into("<IIIIQQ", buf, OFF_HEADER, K_MAGIC, K_VERSION, 999, 1234, 0, 0)
    struct.pack_into("<Q", buf, OFF_OVERLAY_CTL + 0x08, 4242)   # framesPublished

    # Slot 0 header: dimensions, flags, frame id, then the pose.
    struct.pack_into("<IIIIQ", buf, SLOT_HDR, WIDTH, HEIGHT, 0x01 | 0x02, 0, 777)
    struct.pack_into("<ddd", buf, SLOT_HDR + 0x18, POSE['x'], POSE['y'], POSE['z'])
    struct.pack_into("<fffffff", buf, SLOT_HDR + 0x30,
                     POSE['yaw'], POSE['pitch'], POSE['roll'],
                     POSE['vfov'], POSE['aspect'], POSE['near'], POSE['far'])
    struct.pack_into("<III", buf, SLOT_HDR + 0x4C, POSE['cam'], NO_DEPTH, 0)

    # One opaque pixel so the pixel-sanity section has something to report.
    struct.pack_into("<BBBB", buf, OFF_OVERLAY_PIXELS, 255, 128, 0, 255)

    here = os.path.dirname(os.path.abspath(__file__))
    r = subprocess.run([sys.executable, os.path.join(here, "probe_shm.py")],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = r.stdout
    print("\n---- probe_shm.py output ----")
    print(out.rstrip())
    print("-----------------------------")

    # Every planted value must come back exactly as formatted by the reader.
    expect = [
        "magic=0x%08X version=%d" % (K_MAGIC, K_VERSION),
        "width=%d height=%d" % (WIDTH, HEIGHT),
        "frameId=777",
        "poseValid(1<<1)=True",
        "hasDepth(1<<2)=False",
        "pos=(123.50, 64.25, -456.75)",
        "yaw=90.0 pitch=-30.5 roll=0.0",
        "vfov=71.25",
        "aspect=1.7778",
        "near=0.050",
        "far=512.0",
        "cameraMode=1",
        "depthOffset=NONE bytes=0",
    ]
    bad = [e for e in expect if e not in out]
    print()
    for e in expect:
        print("  %-42s %s" % (e, "OK" if e in out else "<<< NOT FOUND"))
    if bad:
        print("\nPOSE PIPELINE FAILED: %d expectation(s) missing" % len(bad))
        return 1
    print("\nPOSE PIPELINE OK - reader agrees with the writer on every field")
    return 0


if __name__ == "__main__":
    sys.exit(main())
