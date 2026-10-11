"""Round-trip test for the MC <-> Stellar Blade position mapping, run against the REAL
expressions in sb-mod/lua/main.lua.

Sign errors in a coordinate mapping are the single most likely bug in this kind of code and
they are invisible until someone walks in the wrong direction for three minutes. So rather
than restating the formula here (a copy can agree with itself while disagreeing with the
shipped file), this test EXTRACTS the two halves straight out of main.lua and composes them
into a program:

    publish_player_frame()   UE (Z-up, cm)  ->  MC (Y-up, blocks)
    apply_mc_transform()     MC delta       ->  UE target

and then asserts they are exact inverses, plus the three axis directions.

Run: python tools/test_move_mapping.py
"""

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'sb-mod', 'lua', 'main.lua')
LUA = os.path.join(ROOT, 'sb-mod', 'build', 'manual', 'lua.exe')

# UE -> MC, as shipped.
UE_TO_MC = [
    ('x', r'local mcX = loc\.X / UNITS_PER_BLOCK'),
    ('y', r'local mcY = loc\.Z / UNITS_PER_BLOCK'),
    ('z', r'local mcZ = -loc\.Y / UNITS_PER_BLOCK'),
]

# MC delta -> UE target, as shipped. HORIZONTAL ONLY: the vertical axis is deliberately not
# derived from Minecraft (following it drove the pawn through Stellar Blade's terrain), so the
# Z term must be the pawn's own current Z.
MC_TO_UE = [
    ('X', r'targetX = gMoveAnchorSb\.X \+ \(st\.x - gMoveAnchorMc\.X\) \* UNITS_PER_BLOCK'),
    ('Y', r'targetY = gMoveAnchorSb\.Y - \(st\.z - gMoveAnchorMc\.Z\) \* UNITS_PER_BLOCK'),
    ('Z', r'targetZ = now\.Z'),
]

LUA_PROGRAM = r"""
local UNITS_PER_BLOCK = 100.0

-- ---- extracted from main.lua: publish_player_frame() ----
local function ue_to_mc(loc)
    @@UE_TO_MC@@
    return { X = mcX, Y = mcY, Z = mcZ }
end

-- ---- extracted from main.lua: apply_mc_transform() ----
local function mc_to_ue(gMoveAnchorSb, gMoveAnchorMc, st, now)
    @@MC_TO_UE@@
    return { X = targetX, Y = targetY, Z = targetZ }
end

local fails = 0
local function check(name, got, want, tol)
    tol = tol or 1e-4
    local ok = math.abs(got - want) <= tol
    if not ok then fails = fails + 1 end
    print(string.format("  %-34s got=%-14.5f want=%-14.5f %s", name, got, want, ok and "OK" or "MISMATCH"))
end

-- 1. Round trip: a UE point mapped into MC and back must land exactly where it started.
local loc = { X = 1234.5, Y = -6789.0, Z = 321.0 }
local mc = ue_to_mc(loc)
local back = mc_to_ue(loc, mc, { x = mc.X, y = mc.Y, z = mc.Z }, loc)
print(string.format(
    "round trip  ue=(%.1f,%.1f,%.1f) -> mc=(%.3f,%.3f,%.3f) -> ue=(%.1f,%.1f,%.1f)",
    loc.X, loc.Y, loc.Z, mc.X, mc.Y, mc.Z, back.X, back.Y, back.Z))
check("round trip X", back.X, loc.X)
check("round trip Y", back.Y, loc.Y)
check("round trip Z", back.Z, loc.Z)

-- 2. Direction / scale checks. One Minecraft block must be exactly one metre (100 uu), and the
--    axis correspondence must be MC Y up -> UE Z up, MC +Z -> UE -Y.
local anchorSb = { X = 0.0, Y = 0.0, Z = 0.0 }
local anchorMc = { X = 0.0, Y = 0.0, Z = 0.0 }

local now = { Z = 777.0 }   -- the pawn's own transform; its Z must pass through untouched
local function step(dx, dy, dz)
    return mc_to_ue(anchorSb, anchorMc, { x = dx, y = dy, z = dz }, now)
end

local east = step(1, 0, 0)
check("MC +1 X -> UE dX (east)", east.X, 100.0)
check("MC +1 X -> UE dY (unchanged)", east.Y, 0.0)
check("MC +1 X -> UE Z (pawn's own, absolute)", east.Z, now.Z, 1e-6)

-- The vertical axis must be PASSED THROUGH, never derived from Minecraft: MC Y and SB Z sit
-- at unrelated heights, and following it is what used to drop the pawn through the floor.
local up = step(0, 1, 0)
check("MC +1 Y -> UE dZ (NOT followed)", up.Z, now.Z, 1e-6)
check("MC +1 Y -> UE dX (unchanged)", up.X, 0.0)
check("MC +1 Y -> UE dY (unchanged)", up.Y, 0.0)

local north = step(0, 0, 1)
check("MC +1 Z -> UE dY (north)", north.Y, -100.0)
check("MC +1 Z -> UE dX (unchanged)", north.X, 0.0)
check("MC +1 Z -> UE Z (pawn's own, absolute)", north.Z, now.Z, 1e-6)

if fails > 0 then
    print(string.format("\nMOVE MAPPING TEST FAILED (%d bad)", fails))
    os.exit(1)
end
print("\nMOVE MAPPING OK - horizontal round trip exact, 1 block = 1 m, vertical passed through")
"""


def extract(patterns, text):
    out = []
    for name, pat in patterns:
        m = re.search(pat, text)
        if not m:
            return None, 'pattern not found for %r: %s' % (name, pat)
        out.append(m.group(0))
    return out, None


def main():
    for path, what in ((SRC, 'main.lua'), (LUA, 'lua.exe')):
        if not os.path.exists(path):
            print('SKIP: %s not found at %s' % (what, path))
            return 1

    text = open(SRC, encoding='utf-8').read()

    ue_lines, err = extract(UE_TO_MC, text)
    if err:
        print('FAIL: ' + err)
        return 1
    mc_lines, err = extract(MC_TO_UE, text)
    if err:
        print('FAIL: ' + err)
        return 1

    # The extracted lines are now STATEMENTS (`targetX = ...`), not table fields, so they
    # must be joined verbatim -- no trailing commas.
    body = '\n    '.join(mc_lines)
    program = (LUA_PROGRAM
               .replace('@@UE_TO_MC@@', '\n    '.join(ue_lines))
               .replace('@@MC_TO_UE@@', body))

    prog_path = os.path.join(ROOT, 'tools', '_move_mapping_test.lua')
    with open(prog_path, 'w', encoding='utf-8') as f:
        f.write(program)

    proc = subprocess.run([LUA, prog_path.replace('\\', '/')],
                          capture_output=True, text=True, timeout=60)
    print(proc.stdout)
    if proc.stderr:
        print(proc.stderr)
    return proc.returncode


if __name__ == '__main__':
    sys.exit(main())
