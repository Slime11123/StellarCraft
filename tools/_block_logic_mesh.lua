
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

local function block_scale_for(ext)
    local half = math.max(ext.X, ext.Y, ext.Z)
    if half <= 0.0 then return 1.0 end
    local s = 50.0 / half                 -- a 1 m cube reports a half-extent of 50 cm
    if s < 0.01 then s = 0.01 end         -- sane guards: a 100 m mesh is not a block
    if s > 100.0 then s = 100.0 end
    return s
end

local function mesh_extent(mesh)
    local ext = nil
    pcall(function()
        local b = mesh:GetBounds()
        if b and b.BoxExtent then
            ext = { X = b.BoxExtent.X, Y = b.BoxExtent.Y, Z = b.BoxExtent.Z }
        end
    end)
    if not ext then
        -- Fall back to the raw property if the accessor call is unavailable.
        pcall(function()
            local b = mesh.ExtendedBounds
            if b and b.BoxExtent then
                ext = { X = b.BoxExtent.X, Y = b.BoxExtent.Y, Z = b.BoxExtent.Z }
            end
        end)
    end
    return ext
end

local function mesh_usable(mesh)
    local e = mesh_extent(mesh)
    if not e then return false, nil end
    if math.max(e.X, e.Y, e.Z) < 1.0 then return false, e end
    local hasBody = false
    pcall(function()
        local b = mesh.BodySetup
        hasBody = (b ~= nil) and b:IsValid() or false
    end)
    return hasBody, e
end

local function resolve_block_mesh()
    if gBlockMesh then return gBlockMesh end
    if gBlockSearchDone then return nil end
    gBlockSearchDone = true

    -- 1) The canonical engine cube, when the build happens to cook it in.
    for _, path in ipairs(BLOCK_MESH_PATHS) do
        local m = static_object(path)
        if m then
            local usable, ext = mesh_usable(m)
            if usable then
                gBlockMesh, gBlockMeshName = m, path
                gBlockScale = block_scale_for(ext)
                print(string.format(
                    "[StellarCraft] block mesh = %s ext=(%.1f,%.1f,%.1f) scale=%.3f\n",
                    path, ext.X, ext.Y, ext.Z, gBlockScale))
                return m
            end
        end
    end

    -- 2) Otherwise take whatever the running game already has loaded. The shortlist is logged
    --    with FULL PATHS so a wrong pick can be corrected from the log without another round.
    local okAll, all = pcall(FindAllOf, "StaticMesh")
    if not okAll or type(all) ~= "table" then
        print("[StellarCraft] block mesh: no candidate path resolved and FindAllOf failed\n")
        return nil
    end

    local shortlist = {}
    local bestScore, bestMesh, bestFull, bestExt = nil, nil, nil, nil
    for _, m in ipairs(all) do
        local okN, n = pcall(function() return m:GetFName():ToString() end)
        if okN and n then
            local low = string.lower(n)
            local rank = nil
            if low == "cube" then rank = 1
            elseif string.find(low, "cube", 1, true) then rank = 2
            elseif string.find(low, "box", 1, true) then rank = 3 end

            if rank and #shortlist < 12 then
                local okFull, full = pcall(function() return m:GetFullName() end)
                shortlist[#shortlist + 1] = tostring(okFull and full or n)
            end
            if rank then
                local usable, ext = mesh_usable(m)
                if usable then
                    -- Score = name rank, then distance from a 1 m cube. The second term makes
                    -- the pick prefer the engine's own unit cube (/Engine/BasicShapes/Cube, a
                    -- 1 m box WITH a box simple-collision) over the larger decorative meshes
                    -- that merely share the name "cube".
                    local half = math.max(ext.X, ext.Y, ext.Z)
                    local score = rank * 1000000 + math.min(500000, math.abs(half - 50.0))
                    if not bestScore or score < bestScore then
                        local okFull, full = pcall(function() return m:GetFullName() end)
                        bestScore, bestMesh = score, m
                        bestFull, bestExt = tostring(okFull and full or n), ext
                    end
                end
            end
        end
    end

    print(string.format(
        "[StellarCraft] block mesh search: %d StaticMeshes loaded, box-ish: %s\n",
        #all, (#shortlist > 0) and table.concat(shortlist, ", ") or "(none)"))

    if not bestMesh then
        print(string.format(
            "[StellarCraft] block mesh: NO USABLE mesh among %d loaded StaticMeshes\n", #all))
        return nil
    end

    gBlockMesh, gBlockMeshName = bestMesh, bestFull
    gBlockScale = block_scale_for(bestExt)
    print(string.format("[StellarCraft] block mesh = %s ext=(%.1f,%.1f,%.1f) scale=%.3f\n",
        bestFull, bestExt.X, bestExt.Y, bestExt.Z, gBlockScale))
    return gBlockMesh
end

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

