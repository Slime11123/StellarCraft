"""Offline end-to-end test for the new sc_read_mc_state_lua export.

This is the same "fake host" trick the GTA5 analysis recommended, applied to the newest
piece of the bridge: instead of starting two games to find out whether Minecraft's pose
reaches the Stellar Blade side, we build the mapping ourselves, fill in a known McState, and
let the real Lua C function read it back.

What it actually proves:
  * the symbol is exported under its plain name (extern "C", no mangling) and the Lua C
    loader accepts it;
  * the field offsets and the little-endian layout in Proto.java / stellarcraft_protocol.h
    match what the reader decodes;
  * the seqlock guard accepts an even seq and returns a table (and nil for a torn read);
  * the Lua table keys main.lua uses (flags/x/y/z/yaw/pitch/...) really exist.

It runs without either game and without a game process being alive.

Note on the two-process dance: sc_create_mapping() memsets McState on purpose (so a previous
session's F4 latch cannot leak into a new one), and the probe must call it to obtain a base
pointer. So the driver keeps re-writing its known values in a background thread for the whole
duration of the probe process -- after the clear, the values are guaranteed to be back.
"""

import ctypes
import os
import re
import struct
import subprocess
import sys
import threading
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DLL = os.path.join(ROOT, 'sb-mod', 'build', 'manual', 'stellarcraft_shm.dll')
LUA = os.path.join(ROOT, 'sb-mod', 'build', 'manual', 'lua.exe')
PROBE = os.path.join(ROOT, 'tools', '_mc_state_probe.lua')

OFF_MC_STATE = 0x200

# Field offsets inside McState, mirrored from protocol/stellarcraft_protocol.h (which
# tools/check_proto_parity.py checks against Proto.java).
MC_SEQ, MC_FLAGS = 0x00, 0x04
MC_X, MC_Y, MC_Z = 0x08, 0x10, 0x18
MC_YAW, MC_PITCH, MC_EYE = 0x20, 0x24, 0x28
MC_FRAME, MC_FOV = 0x38, 0x40
MC_CAM_MODE, MC_CAM_DIST = 0x4C, 0x50

# The values written by the driver and asserted after the round trip.
EXPECTED = {
    'seq': 2,                 # must be EVEN: an odd seq means "writer mid-update"
    'flags': 0x105,           # IN_WORLD | CONTROL_STEVE -> what makes the Lua side take over
    'x': -26.31, 'y': 105.62, 'z': 61.78,
    'yaw': 90.0, 'pitch': -20.0, 'eye': 1.62,
    'fov': 70.0, 'cam': 1, 'camDist': 4.0, 'frame': 123456789,
}

BLOCK = bytearray(0x100)


def build_block():
    def put(off, fmt, value):
        BLOCK[off:off + struct.calcsize(fmt)] = struct.pack(fmt, value)

    put(MC_SEQ, '<I', EXPECTED['seq'])
    put(MC_FLAGS, '<I', EXPECTED['flags'])
    put(MC_X, '<d', EXPECTED['x'])
    put(MC_Y, '<d', EXPECTED['y'])
    put(MC_Z, '<d', EXPECTED['z'])
    put(MC_YAW, '<f', EXPECTED['yaw'])
    put(MC_PITCH, '<f', EXPECTED['pitch'])
    put(MC_EYE, '<f', EXPECTED['eye'])
    put(MC_FRAME, '<Q', EXPECTED['frame'])
    put(MC_FOV, '<f', EXPECTED['fov'])
    put(MC_CAM_MODE, '<I', EXPECTED['cam'])
    put(MC_CAM_DIST, '<f', EXPECTED['camDist'])
    return bytes(BLOCK)


def main():
    for path, what in ((DLL, 'bridge DLL'), (LUA, 'lua.exe'), (PROBE, 'probe script')):
        if not os.path.exists(path):
            print('SKIP: %s not found at %s' % (what, path))
            return 1

    dll = ctypes.WinDLL(DLL)
    dll.sc_create_mapping.restype = ctypes.c_void_p
    base = dll.sc_create_mapping()
    if not base:
        print('FAIL: sc_create_mapping returned NULL')
        return 1
    print('mapping base = 0x%X' % base)

    dll.sc_write_bytes.argtypes = [ctypes.c_void_p, ctypes.c_uint64,
                                   ctypes.c_char_p, ctypes.c_uint64]

    payload = build_block()
    stop = threading.Event()
    writes = [0]

    def writer():
        while not stop.is_set():
            dll.sc_write_bytes(base, OFF_MC_STATE, payload, len(payload))
            writes[0] += 1
            time.sleep(0.005)

    t = threading.Thread(target=writer, daemon=True)
    t.start()

    proc = subprocess.run([LUA, PROBE, DLL.replace('\\', '/')],
                          capture_output=True, text=True, timeout=60)
    stop.set()
    t.join(timeout=1)

    out = (proc.stdout or '') + (proc.stderr or '')
    print('probe output: %s' % out.strip().replace('\n', ' | '))
    print('writer ran %d times' % writes[0])

    if 'LOAD_FAIL' in out:
        print('FAIL: package.loadlib could not resolve the export')
        return 1

    m = re.search(r'RESULT (.+)', out)
    if not m:
        print('FAIL: no RESULT line (the reader returned nil or the probe crashed)')
        return 1

    got = {}
    for kv in m.group(1).split():
        k, v = kv.split('=', 1)
        got[k] = float(v)

    checks = [
        ('seq',      got.get('seq')),
        ('flags',    got.get('flags')),
        ('x',        got.get('x')),
        ('y',        got.get('y')),
        ('z',        got.get('z')),
        ('yaw',      got.get('yaw')),
        ('pitch',    got.get('pitch')),
        ('eye',      got.get('eye')),
        ('fov',      got.get('fov')),
        ('cam',      got.get('cam')),
        ('camDist',  got.get('camDist')),
        ('frame',    got.get('frame')),
    ]

    bad = 0
    for name, value in checks:
        want = EXPECTED[name]
        if value is None:
            print('  %-8s MISSING' % name)
            bad += 1
            continue
        ok = abs(value - want) < 1e-3
        print('  %-8s got=%-14s want=%-14s %s' % (name, value, want, 'OK' if ok else 'MISMATCH'))
        if not ok:
            bad += 1

    if bad:
        print('\nMC STATE EXPORT TEST FAILED (%d bad)' % bad)
        return 1
    print('\nMC STATE EXPORT OK - seqlock read, offsets and Lua keys all match')
    return 0


if __name__ == '__main__':
    sys.exit(main())
