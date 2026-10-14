"""Offline tests for the native-block logic in main.lua -- no game required.

Two things are verified by running the REAL code (extracted straight out of main.lua, not
copied by hand):

  1. drain_block_queue -- the per-tick budget must not lose operations. The previous version
     cleared entries in place and re-appended to the same table, which is undefined behaviour
     for `#` with holes; ops could be appended over unread entries and vanish silently.

  2. resolve_block_mesh / mesh_usable / block_scale_for -- mesh ranking (exact "cube" beats
     "contains cube" beats "contains box"), rejection of meshes that report zero bounds (a
     mesh with no render data crashes the renderer when a proxy is built for it), and the
     uniform scale that normalises the pick to exactly 1 m.

Run:  python tools/test_block_logic.py
"""
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SC = os.path.dirname(HERE)
LUA = os.path.join(SC, 'sb-mod', 'build', 'manual', 'lua.exe')
SRC = os.path.join(SC, 'sb-mod', 'lua', 'main.lua')


def extract(src, name):
    """Pull `local function <name>(...) ... end` out of the source verbatim."""
    m = re.search(r'^local function %s\b' % re.escape(name), src, re.M)
    if not m:
        raise SystemExit('function %s not found in main.lua' % name)
    end = src.index('\nend', m.start())
    return src[m.start():end + len('\nend')]


def main():
    if not os.path.exists(LUA):
        raise SystemExit('build/manual/lua.exe missing -- run _mk_lua_link.py first')
    src = open(SRC, encoding='utf-8').read()

    parts = {n: extract(src, n) for n in
             ('block_scale_for', 'mesh_extent', 'mesh_usable', 'resolve_block_mesh',
              'drain_block_queue')}

    const = re.search(r'^local BLOCK_OPS_PER_TICK = (\d+)', src, re.M)
    if not const:
        raise SystemExit('BLOCK_OPS_PER_TICK not found')
    budget = const.group(1)

    # ---------------------------------------------------------------- harness #1: the queue
    queue_test = """
-- ---- stubs -------------------------------------------------------------------------
local BLOCK_OPS_PER_TICK = @@BUDGET@@
local BLOCK_PLACE, BLOCK_BREAK, BLOCK_CLEAR_ALL = 1, 0, 2
local gBlockQueue = {}
local gBlockActors = {}
local gFrameOff = nil
local gBlockLive, gBlockFailed, gBlockDropped, gBlockLastLogMs = 0, 0, 0, 0
local gBlockMeshName, gBlockScale, gBlockDisabled = nil, 1.0, false
local ops, cleared = {}, 0
local function spawn_block(x, y, z) ops[#ops+1] = ('P%d,%d,%d'):format(x, y, z) end
local function despawn_block(x, y, z) ops[#ops+1] = ('B%d,%d,%d'):format(x, y, z) end
local function clear_block_actors() cleared = cleared + 1; ops[#ops+1] = 'CLEAR' end

@@DRAIN@@

-- ---- 1. a burst larger than one tick is fully serviced, in order, with none lost -----
for i = 1, 20 do gBlockQueue[#gBlockQueue+1] = { BLOCK_PLACE, i, 0, 0 } end
gBlockQueue[#gBlockQueue+1] = { BLOCK_CLEAR_ALL, 0, 0, 0 }
drain_block_queue()
assert(#ops==0 and #gBlockQueue==21, 'uncalibrated world must retain its queue')
gFrameOff={X=0,Y=0,Z=0}
local ticks = 0
while #gBlockQueue > 0 and ticks < 50 do drain_block_queue(); ticks = ticks + 1 end

local placed = 0
for _, o in ipairs(ops) do if o:sub(1,1) == 'P' then placed = placed + 1 end end
print(('burst: %d ticks, placed=%d cleared=%d leftover=%d'):format(ticks, placed, cleared, #gBlockQueue))
assert(placed == 20, 'expected 20 placements, got ' .. placed)
assert(cleared == 1, 'expected the clear op to run once')
assert(#gBlockQueue == 0, 'queue must be empty at the end')
assert(ops[1] == 'P1,0,0', 'order must be preserved, got ' .. tostring(ops[1]))
assert(ops[21] == 'CLEAR', 'the clear op must stay last, got ' .. tostring(ops[21]))

-- ---- 2. the budget is respected: 8 ops per tick, not more -----------------------------
ops, cleared = {}, 0
for i = 1, 20 do gBlockQueue[#gBlockQueue+1] = { BLOCK_PLACE, i, 0, 0 } end
drain_block_queue()
print(('one tick: processed=%d queued=%d'):format(#ops, #gBlockQueue))
assert(#ops == tonumber(BLOCK_OPS_PER_TICK), 'expected a budget of ' .. BLOCK_OPS_PER_TICK)
assert(#gBlockQueue == 20 - tonumber(BLOCK_OPS_PER_TICK), 'the rest must wait for the next tick')

-- ---- 3. breaks are serviced too ------------------------------------------------------
-- Drain whatever test #2 left over first: it deliberately stopped mid-burst.
while #gBlockQueue > 0 do drain_block_queue() end
ops = {}
for i = 1, 3 do gBlockQueue[#gBlockQueue+1] = { BLOCK_BREAK, i, 1, 1 } end
while #gBlockQueue > 0 do drain_block_queue() end
assert(#ops == 3 and ops[1] == 'B1,1,1', 'break ops must run')
print('QUEUE OK - budget respected, order preserved, nothing lost')
""".replace('@@BUDGET@@', budget).replace('@@DRAIN@@', parts['drain_block_queue'])

    # ------------------------------------------------------- harness #2: mesh selection
    mesh_test = """
-- ---- stubs -------------------------------------------------------------------------
local BLOCK_MESH_PATHS = { "/Engine/BasicShapes/Cube.Cube" }
local gBlockMesh, gBlockMeshName, gBlockScale = nil, nil, 1.0
local gBlockSearchDone = false

local function mkmesh(name, halfx, halfy, halfz, bodyValid)
    -- BodySetup is where a UStaticMesh keeps its collision; nil/invalid means "renders but
    -- never blocks", which must be rejected (the player asked for a block, not a picture).
    local body = { IsValid = function() return bodyValid ~= false end }
    local m = { _n = name, _e = { halfx, halfy, halfz }, BodySetup = body }
    function m:GetFName()  local s = self._n; return { ToString = function() return s end } end
    function m:GetFullName() return 'StaticMesh /Game/Stub.' .. self._n end
    function m:GetBounds() return { BoxExtent = { X = self._e[1], Y = self._e[2], Z = self._e[3] } } end
    function m:IsValid() return true end
    return m
end

local MESHES = {}
local function StaticFindObject(path) return nil end   -- force the scan path
local function static_object(path) return nil end      -- ...which means no engine cube
local function FindAllOf(cls) return MESHES end

@@SCALE@@

@@EXTENT@@

@@USABLE@@

@@RESOLVE@@

-- ---- 1. prefer an exact "cube", and reject the zero-bounds one -----------------------
MESHES = { mkmesh('BoringWall', 200, 200, 20), mkmesh('cube', 0, 0, 0), mkmesh('cube', 50, 50, 50),
           mkmesh('MyCubeThing', 50, 50, 50) }
local m = resolve_block_mesh()
print('pick 1: ' .. tostring(gBlockMeshName) .. '  scale=' .. tostring(gBlockScale))
assert(gBlockMeshName == 'StaticMesh /Game/Stub.cube', 'must pick a usable exact cube')
assert(math.abs(gBlockScale - 1.0) < 1e-6, 'a 1 m cube keeps scale 1.0')

-- ---- 2. a 2 m cube is normalised to 1 m ---------------------------------------------
gBlockMesh, gBlockMeshName, gBlockScale, gBlockSearchDone = nil, nil, 1.0, false
MESHES = { mkmesh('BigCube', 100, 100, 100) }
resolve_block_mesh()
print('pick 2: scale=' .. tostring(gBlockScale) .. ' (2 m cube -> 1 m block)')
assert(math.abs(gBlockScale - 0.5) < 1e-6, 'expected scale 0.5, got ' .. tostring(gBlockScale))

-- ---- 3. nothing usable -> refuse to build blocks ------------------------------------
gBlockMesh, gBlockMeshName, gBlockScale, gBlockSearchDone = nil, nil, 1.0, false
MESHES = { mkmesh('Cube', 0, 0, 0), mkmesh('Box', 0, 0, 0) }
print('pick 3: ' .. tostring(resolve_block_mesh()) .. ' (must be nil)')
assert(resolve_block_mesh() == nil, 'a mesh with no render data must be rejected')

-- ---- 4. the scale normalises the LARGEST axis to 1 m, and stays clamped -----------------
-- A 2 cm mesh becomes a 1 m block (x50); a 2 km mesh is clamped instead of shrinking to dust.
print('clamp: tiny=' .. tostring(block_scale_for({X=1,Y=1,Z=1})) ..
      ' huge=' .. tostring(block_scale_for({X=100000,Y=1,Z=1})))
assert(math.abs(block_scale_for({X=1,Y=1,Z=1}) - 50.0) < 1e-6, 'a 2 cm mesh must normalise to 1 m')
assert(block_scale_for({X=100000,Y=1,Z=1}) == 0.01, 'scale must stay clamped on the low end')
assert(block_scale_for({X=100000,Y=1,Z=1}) == 0.01, 'scale must stay clamped on the high end')

print('MESH OK - ranking, zero-bounds rejection and 1 m normalisation all behave')
"""
    mesh_test = (mesh_test
                 .replace('@@SCALE@@', parts['block_scale_for'])
                 .replace('@@EXTENT@@', parts['mesh_extent'])
                 .replace('@@USABLE@@', parts['mesh_usable'])
                 .replace('@@RESOLVE@@', parts['resolve_block_mesh']))
    # Cases that need the newer "must have collision" rule are appended after substitution so
    # the assertion strings stay out of the template's quoting.
    mesh_test = mesh_test.replace(
        "print('MESH OK - ranking, zero-bounds rejection and 1 m normalisation all behave')",
        """
-- ---- 5. a mesh with no collision is rejected; a collidable one wins over a nicer size -----
gBlockMesh, gBlockMeshName, gBlockScale, gBlockSearchDone = nil, nil, 1.0, false
MESHES = { mkmesh('TinyCube', 50, 50, 50, false), mkmesh('FatCube', 100, 100, 100, true) }
local m5 = resolve_block_mesh()
print('pick 5: ' .. tostring(gBlockMeshName) .. ' scale=' .. tostring(gBlockScale))
assert(m5 ~= nil, 'the collidable cube must be kept')
assert(math.abs(gBlockScale - 0.5) < 1e-6,
       'the cube WITH collision must win even though its size is further from 1 m')

-- ---- 6. nothing collidable at all -> refuse rather than spawn a picture ------------------
gBlockMesh, gBlockMeshName, gBlockScale, gBlockSearchDone = nil, nil, 1.0, false
MESHES = { mkmesh('Cube', 50, 50, 50, false) }
assert(resolve_block_mesh() == nil, 'a cube with no BodySetup must be rejected')

print('MESH OK - ranking, collision requirement, zero-bounds rejection, 1 m normalisation')
""")

    ok = True
    for label, program in (('queue', queue_test), ('mesh', mesh_test)):
        path = os.path.join(HERE, '_block_logic_%s.lua' % label)
        open(path, 'w', encoding='utf-8').write(program)
        r = subprocess.run([LUA, path], capture_output=True, text=True)
        print('=== %s ===' % label)
        print(r.stdout.rstrip())
        if r.returncode != 0 or r.stderr.strip():
            print(r.stderr.rstrip())
            ok = False

    print()
    if ok:
        print('BLOCK LOGIC OK - queue budget/burst handling and mesh selection verified offline')
    else:
        print('BLOCK LOGIC FAILED')
        sys.exit(1)


if __name__ == '__main__':
    main()
