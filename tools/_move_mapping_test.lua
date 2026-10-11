
local UNITS_PER_BLOCK = 100.0

-- ---- extracted from main.lua: publish_player_frame() ----
local function ue_to_mc(loc)
    local mcX = loc.X / UNITS_PER_BLOCK
    local mcY = loc.Z / UNITS_PER_BLOCK
    local mcZ = -loc.Y / UNITS_PER_BLOCK
    return { X = mcX, Y = mcY, Z = mcZ }
end

-- ---- extracted from main.lua: apply_mc_transform() ----
local function mc_to_ue(gMoveAnchorSb, gMoveAnchorMc, st, now)
    targetX = gMoveAnchorSb.X + (st.x - gMoveAnchorMc.X) * UNITS_PER_BLOCK
    targetY = gMoveAnchorSb.Y - (st.z - gMoveAnchorMc.Z) * UNITS_PER_BLOCK
    targetZ = now.Z
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
