-- StellarCraft — Stellar Blade side (Path B + Direction A: Lua bridge DLL).
--
-- The shared-memory bridge DLL (stellarcraft_shm.dll) self-initializes in DllMain:
-- it creates the Local\StellarCraft_v1 mapping, writes magic/version, and starts a
-- heartbeat thread. It also exports lua_CFunction wrappers (sc_write_player_lua, ...)
-- that we call from here via package.loadlib to push the player transform into the
-- shared memory every frame.
--
-- Coordinate mapping (from docs/DESIGN.md):
--   Unreal: Z-up, centimeters. Minecraft: Y-up, blocks (1 block = 1 m = 100 UU).
--     mc.x = -sb.x / 100
--     mc.y = sb.z / 100     (UE Z-up -> MC Y-up)
--     mc.z = -sb.y / 100    (UE +Y forward -> MC -Z north)
--   yaw/pitch in degrees; exact sign/offset will be pinned during integration.

local UEHelpers = require("UEHelpers")
local BlockVisuals = require("block_visuals")
local NativeWorld = require("native_world")

local DLL_PATH = "D:/SteamLibrary/steamapps/common/StellarBlade/SB/Binaries/Win64/ue4ss/Mods/StellarCraft/stellarcraft_shm.dll"

local UNITS_PER_BLOCK = 100.0
local gFrameOff = nil
local gTeleportSerial = 0
local gMotionFeedback = {frame=0,contacts=0,vx=0,vy=0,vz=0}
local gBuildWorldId = nil
local gBlockActors = {}
local gGroundIgnore, gGroundIgnorePawn = nil, nil
local function ground_ignore(pawn)
    if not gGroundIgnore or gGroundIgnorePawn~=pawn then
        gGroundIgnore={pawn};gGroundIgnorePawn=pawn
        for _,actor in pairs(gBlockActors) do
            if actor:IsValid() then gGroundIgnore[#gGroundIgnore+1]=actor end
        end
    end
    return gGroundIgnore
end
local function select_build_frame(worldId)
    local pawn=UEHelpers.GetPlayer()
    if not pawn or not pawn:IsValid() or not gFrameOff then return end
    local name=pawn:GetWorld():GetFullName()
    local hash=0
    for i=1,#name do hash=(hash*131+name:byte(i))%4294967296 end
    local path=DLL_PATH:match("^(.*)/").."/block-assets/world-"..string.format("%.0f-%.0f",worldId,hash)..".origin"
    local file=io.open(path,"r")
    if file then
        local x,y,z=file:read("*n","*n","*n");file:close()
        if x and y and z and math.abs(x)<3e7 and math.abs(y)<2e7 and math.abs(z)<3e7 then
            gFrameOff={X=x,Y=y,Z=z};gTeleportSerial=gTeleportSerial+1
            print("[StellarCraft] restored persistent build frame "..tostring(worldId).."\n")
        end
    else
        file=io.open(path,"w")
        if file then file:write(string.format("%.17g %.17g %.17g\n",gFrameOff.X,gFrameOff.Y,gFrameOff.Z));file:close() end
    end
    gBuildWorldId=worldId
end
local gCapsulePawn, gCapsuleHalf = nil, 90.0

local function capsule_half_height(pawn)
    if gCapsulePawn == pawn then return gCapsuleHalf end
    local ok, height = pcall(function() return pawn.CapsuleComponent:GetScaledCapsuleHalfHeight() end)
    gCapsulePawn, gCapsuleHalf = pawn, ok and height or 90.0
    return gCapsuleHalf
end

-- Bridge functions (loaded via package.loadlib).
local write_player = nil
local write_actor  = nil
local set_actor_count = nil
local read_mc_control = nil
local create_mapping = nil
local set_control = nil
local write_ground = nil
local read_events = nil
local read_mc_state = nil
local native_enemy_damage = nil
local native_player_guard = nil

-- Forward declaration for the native-block plumbing, which is defined further down (it needs
-- helpers declared later in the file) but has to be reachable from the event pump above it.
local enqueue_block_op = nil
local pearl_teleport = nil

-- Tracked MC control state (for change detection / logging).
local wasSteve = false

-- SB-side control latch: F4 press toggles this, and we hold it as a persistent LEVEL in
-- SbState.flags (bit 4 = CONTROL_STEVE). MC reads the level directly (no edge detection),
-- so "press once = Steve mode, press again = Eve mode" works without race conditions.
local steveMode = false

-- MC-side INPUT authority mirror (McFlags bit 8 = CONTROL_STEVE in MC -> SB direction).
-- This is deliberately SEPARATE from steveMode above: the F4 latch is CHARACTER ownership
-- and works at any time (even while MC sits on its title screen); the MC bit is INPUT
-- ownership and MC only asserts it once it can actually receive forwarded input.
local mcInput = false

local function load_bridge()
    -- package.loadlib(path, name) returns the C function with the given name, which
    -- must be a lua_CFunction (int (*)(lua_State*)). Our DLL exports those.
    write_player     = package.loadlib(DLL_PATH, "sc_write_player_lua")
    write_actor      = package.loadlib(DLL_PATH, "sc_write_actor_lua")
    set_actor_count  = package.loadlib(DLL_PATH, "sc_set_actor_count_lua")
    read_mc_control  = package.loadlib(DLL_PATH, "sc_read_mc_control_lua")
    create_mapping   = package.loadlib(DLL_PATH, "sc_create_mapping_lua")
    set_control      = package.loadlib(DLL_PATH, "sc_set_control_lua")
    write_ground     = package.loadlib(DLL_PATH, "sc_write_ground_lua")
    read_events      = package.loadlib(DLL_PATH, "sc_read_events_lua")
    read_mc_state    = package.loadlib(DLL_PATH, "sc_read_mc_state_lua")
    native_enemy_damage = package.loadlib(DLL_PATH,"sc_enemy_damage_lua")
    native_player_guard = package.loadlib(DLL_PATH,"sc_player_guard_lua")

    -- Explicitly create the shared-memory mapping. We do NOT rely on DllMain auto-create
    -- because package.loadlib may FreeLibrary the DLL right after loading, which would
    -- trigger DllMain DETACH and tear down the mapping. Explicitly calling
    -- sc_create_mapping_lua (re)creates it so Minecraft can open it.
    if create_mapping then
        local ok, created = pcall(create_mapping)
        if ok and created then
            print("[StellarCraft] SHM bridge DLL loaded; mapping created\n")
        else
            print("[StellarCraft] SHM bridge DLL loaded; mapping create FAILED\n")
        end
    end

    if write_player then
        print("[StellarCraft] lua bridge ready\n")
        return true
    else
        print("[StellarCraft] FAILED to load lua bridge functions\n")
        return false
    end
end

-- Read the player's transform and push it into shared memory.
-- Returns true if the player pawn was valid and we wrote a frame.
local function publish_player_frame()
    local pawn = UEHelpers.GetPlayer()
    if not pawn or not pawn:IsValid() then
        return false
    end

    local ok, loc = pcall(function() return pawn:K2_GetActorLocation() end)
    if not ok or not loc then
        return false
    end

    -- Unreal (cm, Z-up) -> Minecraft (blocks, Y-up).
    local mcX = -loc.X / UNITS_PER_BLOCK
    local mcY = (loc.Z - capsule_half_height(pawn)) / UNITS_PER_BLOCK
    local mcZ = -loc.Y / UNITS_PER_BLOCK
    if gFrameOff then
        mcX, mcY, mcZ = mcX + gFrameOff.X, mcY + gFrameOff.Y, mcZ + gFrameOff.Z
    end

    -- Sample the camera actually displayed. Stellar Blade's custom camera rig does not
    -- follow PlayerController.ControlRotation, so that property can remain zero/stale.
    local yaw, pitch = 0.0, 0.0
    local pc = UEHelpers.GetPlayerController()
    if pc and pc:IsValid() then
        local ok2, rot = pcall(function() return pc:GetControlRotation() end)
        if ok2 and rot then
            local pcm = pc.PlayerCameraManager
            if pcm and pcm:IsValid() then rot = pcm:GetCameraRotation() end
            yaw   = rot.Yaw + 90.0
            pitch = -rot.Pitch
        end
    end

    -- flags: bit 0 = IN_GAME, bit 4 = CONTROL_STEVE (the latched CHARACTER level),
    -- bit 5 = EDIT (MC owns keyboard/mouse right now), bit 6 = NATIVE_UI (SB's own UI owns
    -- input, so MC must stay passive).
    --
    -- AUTHORITY: Stellar Blade is authoritative and computes these three levels every frame
    -- from real signals — the canonical implementation lives in the overlay process (it owns
    -- the input hooks and polls "is the game window focused"), and the C++ writer merges
    -- whatever it published with the bits we pass here. We pass 0 for bits 5/6 so we never
    -- fight the overlay when it is running; when it is NOT running (no input forwarding is
    -- possible at all) the C++ side synthesises EDIT from the F4 latch + the MC input bit
    -- published just below, which keeps /scripts-only debugging usable.
    local flags = 1
    if steveMode then flags = flags | 0x10 end

    local contacts=gMotionFeedback.contacts
    if not gMotionFeedback.gliding then
        contacts=contacts&~1
        pcall(function() if pawn.CharacterMovement:IsMovingOnGround() then contacts=contacts|1 end end)
    end
    local vy=gMotionFeedback.vy
    pcall(function() if not gMotionFeedback.gliding then vy=pawn.CharacterMovement.Velocity.Z/2000 end end)
    write_player(flags, mcX, mcY, mcZ, yaw, pitch, gTeleportSerial,
        gMotionFeedback.frame,contacts,gMotionFeedback.vx,vy,gMotionFeedback.vz)
    return true
end

-- Ground sampling (9x9 patch, 0.5 m cells) around the player, pushed to MC as a height
-- field so its pick raycast can hit native ground and blocks can be placed.
--
-- We use UE4SS's UKismetSystemLibrary:LineTraceSingle (a downward trace per cell). The
-- MC-side contract: cell grid coords are floor(mcX / 0.5); topY is the surface top in MC
-- coords. A miss is reported as hit=0 so MC marks the cell as open air.
local GROUND_CELL = 0.5
local GROUND_RADIUS = 4      -- 9x9 patch -> indices -4..+4

local function sample_ground(pc, world, center)
    local glib = StaticFindObject("/Script/Engine.Default__KismetSystemLibrary")
    if not glib then return end

    -- Report EVERYTHING in Minecraft's own frame (see gFrameOff). The trace itself has to run
    -- in Unreal space around the pawn, but the numbers Minecraft receives are grid cells and a
    -- surface height, and those are only meaningful in the player's frame -- which is where
    -- Minecraft's pick raycast and block placement operate.
    local off = gFrameOff
    local ox = off and off.X or 0.0
    local oy = off and off.Y or 0.0
    local oz = off and off.Z or 0.0

    -- Trace channel: ECC_WorldStatic (=0). KismetSystemLibrary uses ETraceTypeQuery,
    -- whose WorldStatic entry is 0.
    local cells = {}
    local pawn=UEHelpers.GetPlayer()
    local ignored=ground_ignore(pawn)
    local baseGx = math.floor((-center.X / UNITS_PER_BLOCK + ox) / GROUND_CELL)
    local baseGz = math.floor((-center.Y / UNITS_PER_BLOCK + oz) / GROUND_CELL)
    for dz = -GROUND_RADIUS, GROUND_RADIUS do
        for dx = -GROUND_RADIUS, GROUND_RADIUS do
            local gx, gz = baseGx + dx, baseGz + dz
            local sx = -((gx + 0.5) * GROUND_CELL - ox) * UNITS_PER_BLOCK
            local sz = -((gz + 0.5) * GROUND_CELL - oz) * UNITS_PER_BLOCK
            local start = { X = sx, Y = sz, Z = center.Z + 125.0 }  -- 1.25 m above feet
            local finish = { X = sx, Y = sz, Z = center.Z - 1000.0 }

            local hit = {}
            local ok, found = pcall(function()
                return glib:LineTraceSingle(UEHelpers.GetPlayer(), start, finish, 0, false,
                    ignored, 0, hit, true, {R=0,G=0,B=0,A=0}, {R=0,G=0,B=0,A=0}, 0.0)
            end)

            -- MC grid coords: gx from mcX/0.5, gz from mcZ/0.5 (note MC z = -sbY/100).
            local mcX = -sx / UNITS_PER_BLOCK + ox
            local mcZ = -sz / UNITS_PER_BLOCK + oz

            if ok and found and hit and hit.Location then
                -- Surface height, translated into MC's Y. With the pawn standing on Stellar
                -- Blade's ground this lands on the player's own feet level, which is what makes
                -- a block placed "at the ground in front of me" appear there.
                local topY = hit.Location.Z / UNITS_PER_BLOCK + oy
                cells[#cells + 1] = { gx = gx, gz = gz, y = topY, hit = 1 }
            else
                cells[#cells + 1] = { gx = gx, gz = gz, y = 0.0, hit = 0 }
            end
        end
    end

    local originGx = math.floor((-center.X / UNITS_PER_BLOCK + ox) / GROUND_CELL + 0.5) - GROUND_RADIUS
    local originGz = math.floor((-center.Y / UNITS_PER_BLOCK + oz) / GROUND_CELL + 0.5) - GROUND_RADIUS
    pcall(write_ground, originGx, originGz, cells)
end

local gLastGroundMs, gLastAimMs = 0, 0
local gGroundCenter = nil
local function publish_ground_frame()
    if not write_ground or not steveMode or not gFrameOff then return end
    local now = os.clock() * 1000.0

    local pawn = UEHelpers.GetPlayer()
    if not pawn or not pawn:IsValid() then return end
    local okLoc, loc = pcall(function() return pawn:K2_GetActorLocation() end)
    if not okLoc or not loc then return end

    local okWorld, world = pcall(function() return pawn:GetWorld() end)
    if not okWorld or not world then return end
    -- One view trace covers the full placement reach (the old 2 m patch did not).
    if now - gLastAimMs >= 75 and read_mc_state then
        gLastAimMs = now
        local okSt, st = pcall(read_mc_state)
        local glib = StaticFindObject("/Script/Engine.Default__KismetSystemLibrary")
        if okSt and st and glib then
            local yaw, pitch = math.rad(st.yaw - 90), math.rad(-st.pitch)
            local start = { X=loc.X, Y=loc.Y, Z=loc.Z-capsule_half_height(pawn)+(st.eyeHeight or 1.62)*UNITS_PER_BLOCK }
            local finish = { X=start.X+math.cos(pitch)*math.cos(yaw)*550,
                             Y=start.Y+math.cos(pitch)*math.sin(yaw)*550, Z=start.Z+math.sin(pitch)*550 }
            local hit = {}
            local okHit, found = pcall(function() return glib:LineTraceSingle(pawn, start, finish, 0, false,
                ground_ignore(pawn), 0, hit, true, {R=0,G=0,B=0,A=0}, {R=0,G=0,B=0,A=0}, 0.0) end)
            if okHit and found and hit.Location and hit.Normal and hit.Normal.Z > 0.2 then
                local gx=math.floor((-hit.Location.X/UNITS_PER_BLOCK+gFrameOff.X)/GROUND_CELL)
                local gz=math.floor((-hit.Location.Y/UNITS_PER_BLOCK+gFrameOff.Z)/GROUND_CELL)
                pcall(write_ground, gx, gz, {{gx=gx,gz=gz,y=hit.Location.Z/UNITS_PER_BLOCK+gFrameOff.Y,hit=1}})
            end
        end
    end
    local moved = not gGroundCenter or (loc.X-gGroundCenter.X)^2+(loc.Y-gGroundCenter.Y)^2 > 625
    if now-gLastGroundMs >= (moved and 300 or 1500) then
        gLastGroundMs, gGroundCenter = now, {X=loc.X,Y=loc.Y}
        pcall(sample_ground, UEHelpers.GetPlayerController(), world, loc)
    end
end

-- =====================================================================================
-- Actor mirror (SB -> MC) + damage applier (MC -> SB)
--
-- These two halves complete the combat loop that TNT (and melee / arrows) needs:
--
--   mirror_actors()  publishes each nearby enemy into the actor table, so Minecraft
--                    spawns an invisible, hittable ActorProxy for it. Without this the
--                    proxies never exist and MC has nothing to damage.
--
--   apply_events()   drains the MC->SB event ring. Minecraft's ExplosionMixin writes one
--                    kEvExplosionHit per proxy in blast range; we resolve the uid back to
--                    the real actor and apply the damage through Stellar Blade's own
--                    pipeline so the Naytiba reacts (health, stagger, aggro, death).
--
-- Enemy identification follows docs/SDK_MAPPING.md: every character is an ASBCharacter,
-- and enemies are named CH_M_NA_* (Monster-Naytiba). We match on the class-name prefix so
-- new enemy types are picked up without a code change.
-- =====================================================================================

-- Stable uid table: the same actor keeps the same uid across frames (keyed by class name +
-- its engine address), so Minecraft's ActorProxy set does not churn -- which matters because
-- an explosion event carries only the uid.
local gUidByName = {}
local gUidCounter = 0
local gActorsByName = {}    -- uid -> actor object (for damage application)

local function uid_for(name)
    local id = gUidByName[name]
    if id then return id end
    gUidCounter = gUidCounter + 1
    id = gUidCounter
    gUidByName[name] = id
    return id
end

-- True when a class name looks like a Naytiba / enemy character.
local function is_enemy_class(name)
    if not name then return false end
    return string.find(name, "CH_M_NA", 1, true) ~= nil
end

-- Read a float/number field defensively (returns fallback when missing).
local function actor_num(actor, field, fallback)
    local ok, v = pcall(function() return actor[field] end)
    if ok and type(v) == "number" then return v end
    return fallback
end

-- ---------------------------------------------------------------------------
-- Damage conversion: raw MC damage -> native HP, using the "budget" model
-- copied from SekiroCraft-Passthrough. Full write-up: docs/HP_CONVERSION.html
--
-- Sekiro's rule is a BUDGET, not an exchange rate: 40 points of MC damage empty
-- one normal enemy health bar and 200 empty one boss phase bar; the enemy's
-- actual HP never enters the maths. Stellar Blade's HP spans ~950x
-- (Creeper 384 .. Elder Naytiba 366,000), so one global rate would need ~246
-- sword hits on the final boss. We keep Sekiro's model and widen it from its 2
-- tiers to the 5 tiers Stellar Blade itself uses.
--
--     native_HP_lost = MaxHP * (mcDamage / TIER_BUDGET[tier])
--
-- i.e. one point of MC damage is worth MaxHP/budget native HP for that enemy.
-- Tuning is now a one-table edit here -- no jar rebuild.
--
-- Keys are the UE4SS internal class names from the SDK dump, lower-cased and
-- stripped of the "_Blueprint_C" suffix (CH_M_NA_52_Blueprint_C -> ch_m_na_52).
-- ---------------------------------------------------------------------------
local TIER_BUDGET = {
    Minion  = 20,   -- 0.5 Sekiro bars -- ~3 diamond-sword hits
    Warrior = 40,   -- 1 Sekiro bar    -- exactly Sekiro's normal enemy
    Elite   = 100,  -- 2.5 bars        -- ~14 hits
    Alpha   = 200,  -- 5 bars          -- exactly Sekiro's boss phase, ~29 hits
    Elder   = 200,  -- 5 bars per form
}

local ENEMY_HP_TABLE = {
    -- Naytiba Minion (budget 20)
    ["ch_m_na_52"] = { tier = "Minion", hp = 1200 }, -- Thornhead
    ["ch_m_na_11"] = { tier = "Minion", hp = 384 }, -- Creeper
    ["ch_m_na_41"] = { tier = "Minion", hp = 1550 }, -- Test Subject
    ["ch_m_na_12_3"] = { tier = "Minion", hp = 2000 }, -- Boarlet
    ["ch_m_na_12_1"] = { tier = "Minion", hp = 2250 }, -- Mite
    ["ch_m_na_12_2"] = { tier = "Minion", hp = 2250 }, -- Mutated Mite
    ["ch_m_na_14"] = { tier = "Minion", hp = 2800 }, -- Skulling
    ["ch_m_na_47"] = { tier = "Minion", hp = 3200 }, -- Lesser Lurker
    ["ch_m_na_32"] = { tier = "Minion", hp = 4400 }, -- Aid Drone
    ["ch_m_na_29_var3"] = { tier = "Minion", hp = 4400 }, -- Broken Droid
    ["ch_m_na_28"] = { tier = "Minion", hp = 4400 }, -- Tentacle
    -- Naytiba Warrior (budget 40)
    ["ch_m_na_27"] = { tier = "Warrior", hp = 1860 }, -- Guard Turret
    ["ch_m_na_03"] = { tier = "Warrior", hp = 3360 }, -- Cricket Boomer
    ["ch_m_na_40_type_c"] = { tier = "Warrior", hp = 3360 }, -- Cannon Guardian
    ["ch_m_na_45"] = { tier = "Warrior", hp = 3525 }, -- Lurker
    ["ch_m_na_20_a"] = { tier = "Warrior", hp = 3532 }, -- Hive
    ["ch_m_na_51"] = { tier = "Warrior", hp = 4200 }, -- Beholder
    ["ch_m_na_35"] = { tier = "Warrior", hp = 4390 }, -- Infector
    ["ch_m_na_09"] = { tier = "Warrior", hp = 4590 }, -- Hydra
    ["ch_m_na_12_4"] = { tier = "Warrior", hp = 4900 }, -- Mutated Boarlet
    ["ch_m_na_19"] = { tier = "Warrior", hp = 5040 }, -- Skull Gunner
    ["ch_m_na_40_type_b"] = { tier = "Warrior", hp = 6190 }, -- Heavy Guardian
    ["ch_m_na_03_var01"] = { tier = "Warrior", hp = 6230 }, -- Cricket Slasher
    ["ch_m_na_40"] = { tier = "Warrior", hp = 6500 }, -- Guardian
    ["ch_m_na_11_typeb"] = { tier = "Warrior", hp = 6500 }, -- Mutated Creeper
    ["ch_m_na_48"] = { tier = "Warrior", hp = 6800 }, -- Turret Droid
    ["ch_m_na_09_typeb"] = { tier = "Warrior", hp = 7800 }, -- Mutated Hydra
    ["ch_m_na_44_b"] = { tier = "Warrior", hp = 8190 }, -- Hedgeboar
    ["ch_m_na_37"] = { tier = "Warrior", hp = 8260 }, -- Defiler
    ["ch_m_na_34"] = { tier = "Warrior", hp = 8500 }, -- Waster
    ["ch_m_na_30"] = { tier = "Warrior", hp = 8500 }, -- Lump
    ["ch_m_na_08"] = { tier = "Warrior", hp = 8510 }, -- Barnacle
    ["ch_m_na_03_var02"] = { tier = "Warrior", hp = 9200 }, -- Cricket Butcher
    ["ch_m_na_18"] = { tier = "Warrior", hp = 10000 }, -- Skull Fighter
    ["ch_m_na_24"] = { tier = "Warrior", hp = 10920 }, -- Sentryborg
    ["ch_m_na_07_02_a"] = { tier = "Warrior", hp = 11500 }, -- Dozer
    ["ch_m_na_37_type_b"] = { tier = "Warrior", hp = 11860 }, -- Mutated Defiler
    ["ch_m_na_17"] = { tier = "Warrior", hp = 12500 }, -- Skull Trooper
    ["ch_m_na_29_var1"] = { tier = "Warrior", hp = 13650 }, -- Droid
    ["ch_m_na_23"] = { tier = "Warrior", hp = 13650 }, -- Houndborg
    ["ch_m_na_49"] = { tier = "Warrior", hp = 15000 }, -- Old Droid
    ["ch_m_na_16"] = { tier = "Warrior", hp = 17050 }, -- Skull Berserker
    ["ch_m_na_29"] = { tier = "Warrior", hp = 17280 }, -- Battle Droid
    ["ch_m_na_46"] = { tier = "Warrior", hp = 18940 }, -- Lesser Behemoth
    ["ch_m_na_57"] = { tier = "Warrior", hp = 33300 }, -- Cocoon
    ["ch_m_na_20_var2"] = { tier = "Warrior", hp = 50400 }, -- Machine Hive
    ["ch_m_na_29_var2"] = { tier = "Warrior", hp = 53812 }, -- Heavy Droid
    -- Elite Naytiba (budget 100)
    ["ch_m_na_43"] = { tier = "Elite", hp = 30000 }, -- Brute
    ["ch_m_na_46_var01"] = { tier = "Elite", hp = 37840 }, -- Behemoth
    ["ch_m_na_39"] = { tier = "Elite", hp = 44000 }, -- Abaddon
    ["ch_m_na_07"] = { tier = "Elite", hp = 49500 }, -- Corrupter
    ["ch_m_na_31"] = { tier = "Elite", hp = 73600 }, -- Belial
    ["ch_m_na_36"] = { tier = "Elite", hp = 100000 }, -- Maelstrom
    ["ch_m_na_13"] = { tier = "Elite", hp = 104000 }, -- Stalker
    ["ch_m_na_15"] = { tier = "Elite", hp = 117000 }, -- Juggernaut
    ["ch_m_na_26"] = { tier = "Elite", hp = 248400 }, -- Karakuri
    ["ch_m_na_22"] = { tier = "Elite", hp = 276000 }, -- Democrawler
    -- Alpha Naytiba (budget 200)
    ["ch_m_na_05"] = { tier = "Alpha", hp = 55000 }, -- Gigas
    ["ch_m_na_33"] = { tier = "Alpha", hp = 100000 }, -- Demogorgon
    ["ch_m_na_21"] = { tier = "Alpha", hp = 130000 }, -- Tachy
    ["ch_m_na_42"] = { tier = "Alpha", hp = 306000 }, -- Unidentified Naytiba
    ["ch_npc_raven"] = { tier = "Alpha", hp = 336000 }, -- Raven
    -- Elder Naytiba (budget 200 per form)
    ["ch_m_na_55"] = { tier = "Elder", hp = 91500 }, -- Adam (first form)
    ["ch_m_na_56"] = { tier = "Elder", hp = 366000 }, -- Elder Naytiba (true form)
}

-- Used when neither the live MaxHP property nor the table yields a number.
local TIER_FALLBACK_HP = {
    Minion  = 2800,
    Warrior = 8500,
    Elite   = 100000,
    Alpha   = 130000,
    Elder   = 228750,
}

-- CH_M_NA_52_Blueprint_C -> ch_m_na_52 ; CH_M_NA_42_C -> ch_m_na_42
local function normalize_class_name(raw)
    if not raw then return nil end
    local s = string.lower(raw)
    s = string.gsub(s, "_blueprint_c$", "")
    if ENEMY_HP_TABLE[s] == nil then
        local trimmed = string.gsub(s, "_c$", "")
        if ENEMY_HP_TABLE[trimmed] ~= nil then s = trimmed end
    end
    return s
end

-- Resolve tier + max HP for an actor.
--
-- MaxHP is preferred over the table because it follows difficulty (Story/Normal/
-- Hard) and NG+; the table is the fallback, and its `tier` always decides the
-- budget. Unknown enemy classes default to the Warrior tier so they still take
-- sane damage instead of one-shotting or becoming sponges.
local function resolve_enemy(actor, clsName)
    local key = normalize_class_name(clsName)
    local entry = key and ENEMY_HP_TABLE[key] or nil
    local tier = (entry and entry.tier) or "Warrior"

    local maxHp = actor_num(actor, "MaxHP", nil)
    if not maxHp or maxHp <= 0 then
        maxHp = (entry and entry.hp) or TIER_FALLBACK_HP[tier] or 8500
    end

    return tier, maxHp
end

-- Enumerate every live ASBCharacter-ish actor and publish the enemies within range.
--
-- Enumeration is deliberately layered, because UE4SS versions differ in which helpers are
-- available: we prefer walking the persistent level's actor array (cheap, no allocation)
-- and fall back to FindAllOf("Character") when the level walk is unavailable.
--
-- Filtering is by class-name prefix (CH_M_NA = Monster-Naytiba, per docs/SDK_MAPPING.md),
-- so a generated SDK is NOT required — only the generic reflection API.
local MIRROR_RADIUS_BLOCKS = 64.0

-- Returns a plain array of candidate actors, or nil when no strategy worked.
local function collect_character_actors(world)
    -- Strategy 1: walk UWorld -> PersistentLevel -> Actors.
    if world then
        local okLevel, level = pcall(function() return world.PersistentLevel end)
        if okLevel and level then
            local okActors, actors = pcall(function() return level.Actors end)
            if okActors and type(actors) == "table" then
                local list = {}
                -- TArray from UE4SS supports .Length / indexing; iterate leniently.
                local n = tonumber(actors.Length or actors.Num or #actors) or 0
                for i = 1, n do
                    local okA, a = pcall(function() return actors[i] end)
                    if okA and a then list[#list + 1] = a end
                end
                return list
            end
        end
    end

    -- Strategy 2: global FindAllOf (slower but always present).
    local okAll, all = pcall(function() return FindAllOf("Character") end)
    if okAll and type(all) == "table" then return all end

    return nil
end

local function enemy_stats(actor,damage,budget,cause)
    if not native_enemy_damage then return nil end
    local ok,result=pcall(function()
        local info=actor.BroadcastSpawnInfo
        return native_enemy_damage(info.ActorGuid,info.CharacterDataId,damage or 0,budget or 1,cause or 0)
    end)
    return ok and result or nil
end
local gLastActorSample = -1
local gActorCandidates, gActorWorld, gActorRefresh = nil, nil, -10
local function mirror_actors(center, world)
    if not write_actor or not set_actor_count then return end
    local now = os.clock()
    if now - gLastActorSample < 0.2 then return end
    gLastActorSample = now

    -- Rescan/classify the registry slowly; cached enemy positions still refresh at 5 Hz.
    local worldAddress=world and world:GetAddress() or 0
    if not gActorCandidates or gActorWorld~=worldAddress or now-gActorRefresh>2 then
        local candidates=collect_character_actors(world)
        if candidates then
            gActorCandidates={}
            for _,actor in ipairs(candidates) do
                local okEnemy,enemy=pcall(function() return actor:IsValid() and is_enemy_class(actor:GetClass():GetFName():ToString()) end)
                if okEnemy and enemy then gActorCandidates[#gActorCandidates+1]=actor end
            end
            gActorWorld,gActorRefresh=worldAddress,now
        end
    end
    local all = gActorCandidates
    if not all then return end

    -- Report enemy positions in Minecraft's frame too (see gFrameOff). `center` already
    -- arrives translated by the caller; the per-actor coordinates need the same translation,
    -- otherwise every proxy would spawn a few hundred blocks away from the player -- which is
    -- how enemies ended up unhittable.
    local off = gFrameOff
    local ox = off and off.X or 0.0
    local oy = off and off.Y or 0.0
    local oz = off and off.Z or 0.0

    local count = 0
    gActorsByName = {}

    for _, actor in ipairs(all) do
        local okValid, valid = pcall(function() return actor:IsValid() end)
        if okValid and valid then
            local okName, clsName = pcall(function()
                return actor:GetClass():GetFName():ToString()
            end)
            clsName = okName and clsName or nil

            -- Skip the player pawn (that is Eve/Steve themselves) and anything that is
            -- not an enemy character class.
            if is_enemy_class(clsName) then
                local okLoc, loc = pcall(function() return actor:K2_GetActorLocation() end)
                if okLoc and loc then
                    local mx = -loc.X / UNITS_PER_BLOCK + ox
                    local my = loc.Z / UNITS_PER_BLOCK + oy
                    local mz = -loc.Y / UNITS_PER_BLOCK + oz

                    local dx, dy, dz = mx - center.X, my - center.Y, mz - center.Z
                    if (dx * dx + dy * dy + dz * dz) <= (MIRROR_RADIUS_BLOCKS * MIRROR_RADIUS_BLOCKS) then
                        -- Stable key: class name + the actor's address, so the same actor
                        -- keeps its uid every frame.
                        local addr = "?"
                        local okAddr, a = pcall(function() return actor:GetAddress() end)
                        if okAddr and a then addr = tostring(a) end
                        local name = clsName .. "@" .. addr
                        local uid = uid_for(name)

                        local yaw = 0.0
                        local okRot, rot = pcall(function() return actor:K2_GetActorRotation() end)
                        if okRot and rot then yaw = rot.Yaw + 90.0 end

                        -- Health fraction, when the actor exposes it (several SB enemy
                        -- blueprints do; fall back to 1.0 so the proxy still spawns).
                        local healthFrac = actor_num(actor, "HealthPercent", 1.0)
                        local stats=enemy_stats(actor)
                        if stats and stats.ready and stats.maximum>0 then healthFrac=math.max(0,math.min(1,stats.after/stats.maximum)) end

                        write_actor(uid, 0, mx, my, mz, yaw, 0.6, 1.8, healthFrac)
                        gActorsByName[uid] = actor
                        count = count + 1
                        if count >= 256 then break end
                    end
                end
            end
        end
    end

    set_actor_count(count)
end

-- Apply damage to a real SB actor.
--
-- `mcDamage` is the RAW Minecraft damage (diamond sword = 7, TNT at the centre ~= 56).
-- The conversion to native HP happens here, using the per-tier budget table above, because
-- this side is the only one that can see the actor's class name and MaxHP:
--
--     native_HP_lost = MaxHP * (mcDamage / TIER_BUDGET[tier])
--
-- The bridge verifies this build, GameThread, server GUID/type and live HP;
-- Stellar Blade's server stat pipeline owns damage, replication and death.
--
-- Returns: applied, nativeDamage, tier, maxHp
local function damage_actor(actor, mcDamage, knockback)
    if not actor or not actor:IsValid() then return false, 0, nil, 0 end

    local okName, clsName = pcall(function()
        return actor:GetClass():GetFName():ToString()
    end)
    local tier, maxHp = resolve_enemy(actor, okName and clsName or nil)
    local budget = TIER_BUDGET[tier] or TIER_BUDGET.Warrior

    local cause=0
    pcall(function() cause=UEHelpers.GetPlayer().BroadcastSpawnInfo.ActorGuid end)
    local stats=enemy_stats(actor,mcDamage,budget,cause)
    if stats then
        local info=actor.BroadcastSpawnInfo
        print(string.format("[StellarCraft-Combat] guid=%d type=%d HP %.1f -> %.1f / %.1f: %s\n",info.ActorGuid,info.CharacterDataId,stats.before,stats.after,stats.maximum,tostring(stats.reason)))
        return stats.applied,math.max(0,stats.before-stats.after),tier,stats.maximum
    end
    return false,0,tier,maxHp
end

-- Drain the MC->SB event ring and dispatch.
--
-- Event types we handle (protocol McEventType):
--   kEvExplosionHit = 6  -> per-actor explosion damage (TNT)
--   kEvHitActor     = 1  -> melee / arrow damage
local gLastEventLog = 0

local function apply_events()
    if not read_events then return end
    local ok, events = pcall(read_events)
    if not ok or type(events) ~= "table" then return end

    for _, ev in ipairs(events) do
        local t = ev.type
        if t == 5 then
            -- kEvBlockChange: Minecraft is telling us a block appeared or disappeared, so the
            -- matching actor can be spawned/destroyed in Stellar Blade's own world. Queued,
            -- not spawned inline: a full re-sync arrives as a burst of a few hundred events
            -- and spawning them all in one frame would stall it.
            if enqueue_block_op then
                enqueue_block_op(math.floor(ev.flags + 0.5),
                                 math.floor(ev.a + 0.5),
                                 math.floor(ev.b + 0.5),
                                 math.floor(ev.c + 0.5), ev.uid, ev.d)
            end
        elseif t == 7 then
            if enqueue_block_op then
                local offset=string.unpack("<f",string.pack("<I4",math.floor(ev.flags)%4294967296))
                enqueue_block_op(4,ev.a,ev.b,ev.c,ev.d,offset)
            end
        elseif t == 3 then
            NativeWorld.explosion(UEHelpers.GetPlayer(),gFrameOff,ev.a,ev.b,ev.c,ev.d)
        elseif t == 8 then
            if pearl_teleport then pearl_teleport(ev.a,ev.b,ev.c) end
        elseif t == 6 or t == 1 then
            local actor = gActorsByName[ev.uid]
            if actor then
                local applied, nativeDmg, tier, maxHp = damage_actor(actor, ev.a, ev.d)
                -- Log sparingly: explosions are bursty and spam the console otherwise.
                local now = os.clock()
                if now - gLastEventLog > 0.5 then
                    gLastEventLog = now
                    print(string.format(
                        "[StellarCraft] event type=%d uid=%d mc=%.2f -> native=%.0f tier=%s maxHp=%.0f applied=%s\n",
                        t, ev.uid, ev.a, nativeDmg or 0, tostring(tier), maxHp or 0,
                        tostring(applied)))
                end
            end
        end
    end
end

-- =====================================================================================
-- Movement + camera takeover (MC -> Stellar Blade)
--
-- Reference: SekiroCraft-Passthrough `src/movement_hook.cpp/.hpp`. Their detour runs at
-- ChrPhysicsModule's final position store and OVERWRITES the native candidate position with
-- Minecraft's target:
--
--     candidate[0] = target.x; candidate[1] = target.y; candidate[2] = target.z;
--
-- with the comment "Position ownership is enforced by the candidate store and input
-- isolation." We have no such hook and do not need one: the same ownership is expressed
-- through UE reflection by writing the pawn's transform every frame, so whatever Stellar
-- Blade's own physics does in between is simply overwritten on the next tick.
--
-- WHY THIS IS REQUIRED AT ALL. Stellar Blade's camera is attached to Eve's pawn. Hiding her
-- mesh takes her off the screen, but the camera still sits on her. If the pawn never moves,
-- then walking with WASD moves only Minecraft's (invisible) camera: the scenery stands still,
-- and every SB-side consumer that is centred on the pawn -- the ground sampler, the actor
-- mirror -- drifts away from where the player actually is. That is the "移动没做好" symptom.
--
-- MAPPING. Position is fed as a DELTA from an anchor taken when STEVE control starts:
--
--     target = anchorAtEntry + (mcNow - mcAtEntry) * UNITS_PER_BLOCK
--
-- A delta rather than an absolute conversion, for three reasons:
--   1. it cannot jump on entry -- the camera stays exactly where the player was looking;
--   2. it assumes nothing about the two worlds sharing an origin (Minecraft's terrain sits at
--      its own Y, Stellar Blade's ground at its own Z; an absolute mapping would bury the
--      camera underground or strand it in the sky);
--   3. it makes 1 MC block = 1 m hold regardless of either world's native units.
--
-- Yaw/pitch ARE absolute: MC's orientation is the authority, so the camera looks where MC
-- looks. The sign convention is the exact inverse of publish_player_frame() (mcYaw = -sbYaw).
-- =====================================================================================

local MC_FLAG_IN_WORLD      = 0x001   -- McFlags bit 0
local MC_FLAG_CONTROL_STEVE = 0x100   -- McFlags bit 8

local gMoveAnchorSb = nil    -- pawn UE location when STEVE control started
local gMoveAnchorMc = nil    -- MC feet position at the same moment
local gYawOffset    = 0.0    -- (sbYaw + mcYaw) measured at entry; normally 0
local gBoomSaved    = nil    -- { arm = <original TargetArmLength>, socket = {...} }
local gBoomApplied  = false
local gLastMoveLogMs = 0
local gMoveErrMax   = 0.0
local gMcCamera, gCameraController, gPreviousViewTarget, gPreviousControlRotation = nil, nil, nil, nil
local gCameraPose, gBodyLook, gViewTargetCheck = nil, nil, 0
local gHandErrorAt = -10
local gJumpHeld = false
local gJumpProbe = nil
local gNativeInput, gNativePhysics = nil, nil
local gFlightClock, gFlightVelocity = nil, 0.0
local gStepDiagnosticAt = -10

local function restore_native_input()
    if gNativeInput and gNativeInput.pc:IsValid() then
        if not gNativeInput.move then gNativeInput.pc:SetIgnoreMoveInput(false) end
        if not gNativeInput.look then gNativeInput.pc:SetIgnoreLookInput(false) end
    end
    if gNativePhysics and gNativePhysics.pawn:IsValid() then
        local saved = gNativePhysics
        if saved.crouching then
            pcall(function()
                local loc=saved.pawn:K2_GetActorLocation()
                local delta=saved.capsuleHalf-capsule_half_height(saved.pawn)
                saved.pawn:K2_SetActorLocation({X=loc.X,Y=loc.Y,Z=loc.Z+delta},true,{},false)
                saved.pawn.CapsuleComponent:SetCapsuleHalfHeight(saved.capsuleHalf,true)
                gCapsulePawn=nil
            end)
        end
        if saved.flying then
            pcall(function() saved.component:StopMovementImmediately(); saved.component:SetMovementMode(3,0) end)
        end
        for name, value in pairs(saved.movement) do pcall(function() saved.component[name]=value end) end
        for name, value in pairs(saved.character) do pcall(function() saved.pawn[name]=value end) end
        if saved.anim and saved.anim:IsValid() then
            pcall(function() saved.anim:SetRootMotionMode(saved.rootMotionMode) end)
        end
    end
    gNativeInput, gNativePhysics = nil, nil
    gFlightClock, gFlightVelocity = nil, 0.0
    if BlockVisuals.hide_body then BlockVisuals.hide_body() end
end

local function isolate_native_input(pawn, pc)
    if not gNativeInput or gNativeInput.pc ~= pc then
        restore_native_input()
        local move, look = pc:IsMoveInputIgnored(), pc:IsLookInputIgnored()
        gNativeInput={pc=pc,move=move,look=look}
        if not move then pc:SetIgnoreMoveInput(true) end
        if not look then pc:SetIgnoreLookInput(true) end
        local component = pawn.CharacterMovement
        if component and component:IsValid() then
            local movement, character = {}, {}
            for name, value in pairs({JumpZVelocity=1100.0,MaxStepHeight=60.0,bUseJumpFallingGravityCurve=false,
                MaxWalkSpeed=0.0,MaxWalkSpeedCrouched=0.0,bOrientRotationToMovement=false,bUseControllerDesiredRotation=false}) do
                pcall(function() movement[name]=component[name]; component[name]=value end)
            end
            -- SB's virtual gravity getter applies GravityScale twice. Measure its
            -- scale response instead of assuming UE's linear/default gravity.
            -- Live SB launches at 940 cm/s rose 92 cm. 1100 cm/s compensates its
            -- native displacement solver and targets MC's roughly 1.25 m apex.
            pcall(function()
                movement.GravityScale=component.GravityScale
                component.GravityScale=1.0
                local baseGravity=math.abs(component:GetGravityZ())
                component.GravityScale=2.0
                local doubledGravity=math.abs(component:GetGravityZ())
                local exponent=math.log(doubledGravity/baseGravity)/math.log(2.0)
                if baseGravity > 1 and exponent > 0.1 and exponent < 4 then
                    component.GravityScale=(3200.0/baseGravity)^(1.0/exponent)
                    print(string.format("[StellarCraft] MC gravity calibrated base=%.1f exponent=%.2f new=%.1f scale=%.4f\n",
                        baseGravity,exponent,component:GetGravityZ(),component.GravityScale))
                else
                    component.GravityScale=movement.GravityScale
                end
            end)
            for name,value in pairs({JumpMaxCount=1,JumpMaxHoldTime=0.0,AnimRootMotionTranslationScale=0.0}) do
                pcall(function() character[name]=pawn[name]; pawn[name]=value end)
            end
            component:StopMovementImmediately()
            gNativePhysics={pawn=pawn,component=component,movement=movement,character=character,
                flightGravity=component.GravityScale,flying=false,capsuleHalf=capsule_half_height(pawn),crouching=false}
            -- A zero translation scale still leaves root motion enabled: the idle
            -- animation can replace the capsule's launch velocity with zero. MC
            -- owns locomotion, so ignore animation root motion until returning to Eve.
            pcall(function()
                local anim=pawn.Mesh:GetAnimInstance()
                if anim and anim:IsValid() then
                    gNativePhysics.anim=anim;gNativePhysics.rootMotionMode=anim.RootMotionMode
                    anim:SetRootMotionMode(1) -- ERootMotionMode::IgnoreRootMotion
                    print("[StellarCraft] MC root motion ignored; saved="..tostring(gNativePhysics.rootMotionMode).."\n")
                end
            end)
        end
        print("[StellarCraft] MC locomotion: native move/look isolated; MC horizontal physics, single jump\n")
    end
    -- A pending native input vector/animation must not add Eve's acceleration to MC's move.
    pcall(function() pawn:ConsumeMovementInputVector() end)
    if gNativePhysics then
        pcall(function()
            local velocity=gNativePhysics.component.Velocity
            if velocity.X ~= 0 or velocity.Y ~= 0 then
                gNativePhysics.component.Velocity={X=0,Y=0,Z=velocity.Z}
            end
        end)
    end
end

local function native_step(pawn,start,blocked,targetX,targetY,height)
    local glib=StaticFindObject("/Script/Engine.Default__KismetSystemLibrary")
    if not glib or not glib:IsValid() then return blocked end
    local half=capsule_half_height(pawn)
    local dx,dy=targetX-start.X,targetY-start.Y
    local length=math.sqrt(dx*dx+dy*dy)
    if length<0.01 then return blocked end
    local radius=35.0
    pcall(function() radius=pawn.CapsuleComponent:GetScaledCapsuleRadius() end)
    local probeX,probeY=targetX+dx/length*(radius+4),targetY+dy/length*(radius+4)
    local hit={}
    -- Probe past the capsule's front edge; its centre is still behind a step when blocked.
    local found=glib:LineTraceSingle(pawn,{X=probeX,Y=probeY,Z=start.Z-half+height+2},
        {X=probeX,Y=probeY,Z=start.Z-half-2},0,false,{pawn},0,hit,true,
        {R=0,G=0,B=0,A=0},{R=0,G=0,B=0,A=0},0)
    local diagnostic=os.clock()-gStepDiagnosticAt>2
    if diagnostic then
        gStepDiagnosticAt=os.clock()
        print(string.format("[StellarCraft-Step] probe=%s normal=%s floor=%s feet=%.1f radius=%.1f delta=%.2f\n",
            tostring(found),tostring(hit.Normal and hit.Normal.Z),tostring(hit.Location and hit.Location.Z),start.Z-half,radius,length))
    end
    if not found or not hit.Location or not hit.Normal or hit.Normal.Z<0.65 then return blocked end
    local rise=hit.Location.Z+half-start.Z
    if rise<0.5 or rise>height then return blocked end
    pawn:K2_SetActorLocation({X=blocked.X,Y=blocked.Y,Z=start.Z+height+2},true,{},false)
    local lifted=pawn:K2_GetActorLocation()
    pawn:K2_SetActorLocation({X=targetX,Y=targetY,Z=lifted.Z},true,{},false)
    local forward=pawn:K2_GetActorLocation()
    -- Stop at the verified tread height. Sweeping all the way to the old floor
    -- lands on the capsule's rounded edge below this tread and repeats the same
    -- collision/correction for several frames. UE gravity settles the small gap
    -- while forward input carries the centre over the supported front edge.
    pawn:K2_SetActorLocation({X=forward.X,Y=forward.Y,Z=hit.Location.Z+half},true,{},false)
    local landed=pawn:K2_GetActorLocation()
    local before=(blocked.X-targetX)^2+(blocked.Y-targetY)^2
    local after=(landed.X-targetX)^2+(landed.Y-targetY)^2
    if after<before and landed.Z<=start.Z+height+1 and landed.Z>=start.Z-1 then
        if gNativePhysics then gNativePhysics.stepSupportAt=os.clock() end
        return landed
    end
    pawn:K2_SetActorLocation(blocked,false,{},true)
    return blocked
end

pearl_teleport=function(x,y,z)
    local pawn=UEHelpers.GetPlayer()
    if not pawn or not pawn:IsValid() or not gFrameOff or not steveMode then return end
    local glib=StaticFindObject("/Script/Engine.Default__KismetSystemLibrary")
    local at={X=-(x-gFrameOff.X)*100,Y=-(z-gFrameOff.Z)*100,Z=(y-gFrameOff.Y)*100}
    local half=capsule_half_height(pawn);local radius=pawn.CapsuleComponent:GetScaledCapsuleRadius()
    -- Try beside the impact, searching only nearby real floors and capsule-sized headroom.
    for _,delta in ipairs({{0,0},{radius+8,0},{-radius-8,0},{0,radius+8},{0,-radius-8}}) do
        local hit={};local px,py=at.X+delta[1],at.Y+delta[2]
        local color={R=0,G=0,B=0,A=0}
        if glib:LineTraceSingle(pawn,{X=px,Y=py,Z=at.Z+120},{X=px,Y=py,Z=at.Z-500},0,false,{pawn},0,hit,true,color,color,0)
            and hit.Location and hit.Normal and hit.Normal.Z>.65 then
            local candidate={X=px,Y=py,Z=hit.Location.Z+half+2}
            local overlap={}
            -- A sphere through the full capsule height catches ceiling and wall encroachment.
            local occupied=glib:SphereTraceSingle(pawn,{X=px,Y=py,Z=candidate.Z-half+radius},
                {X=px,Y=py,Z=candidate.Z+half-radius},radius,0,false,{pawn},0,overlap,true,color,color,0)
            if not occupied then
                pawn:K2_SetActorLocation(candidate,false,{},true)
                pawn.CharacterMovement:StopMovementImmediately()
                gTeleportSerial=gTeleportSerial+1;gJumpProbe=nil;gFlightVelocity=0
                print(string.format("[StellarCraft-Pearl] safe native teleport %.1f %.1f %.1f serial=%d\n",candidate.X,candidate.Y,candidate.Z,gTeleportSerial))
                return
            end
        end
    end
    print("[StellarCraft-Pearl] impact has no safe capsule landing; teleport rejected\n")
end

local function restore_native_camera()
    if gCameraController and gCameraController:IsValid() and gPreviousViewTarget and gPreviousViewTarget:IsValid() then
        gCameraController:SetViewTargetWithBlend(gPreviousViewTarget, 0.0, 0, 0.0, false)
        if gPreviousControlRotation then gCameraController:SetControlRotation(gPreviousControlRotation) end
        print("[StellarCraft] restored native view target " .. gPreviousViewTarget:GetFullName() .. "\n")
    end
    if gMcCamera and gMcCamera:IsValid() then gMcCamera:K2_DestroyActor() end
    gMcCamera, gCameraController, gPreviousViewTarget, gPreviousControlRotation = nil, nil, nil, nil
    gCameraPose, gBodyLook, gViewTargetCheck = nil, nil, 0
end

local function update_mc_camera(pawn, pc, st, off)
    if not gMcCamera or not gMcCamera:IsValid() then
        local cls = StaticFindObject("/Script/Engine.CameraActor")
        assert(cls and cls:IsValid(), "CameraActor unavailable")
        gCameraController = pc
        gPreviousViewTarget = pc:GetViewTarget()
        local previous = pc:GetControlRotation()
        gPreviousControlRotation = { Pitch = previous.Pitch, Yaw = previous.Yaw, Roll = previous.Roll }
        gMcCamera = pawn:GetWorld():SpawnActor(cls, pawn:K2_GetActorLocation(), { Pitch = 0, Yaw = 0, Roll = 0 })
        assert(gMcCamera and gMcCamera:IsValid(), "MC camera creation failed")
        local camera = gMcCamera.CameraComponent
        -- Keep native scene lighting, but prevent cinematic blur from softening MC pixels/skin.
        local post = camera.PostProcessSettings
        post.bOverride_MotionBlurAmount = true
        post.MotionBlurAmount = 0.0
        post.bOverride_DepthOfFieldScale = true
        post.DepthOfFieldScale = 0.0
        post.bOverride_DepthOfFieldFocalDistance = true
        post.DepthOfFieldFocalDistance = 0.0
        post.bOverride_DepthOfFieldNearBlurSize = true
        post.DepthOfFieldNearBlurSize = 0.0
        post.bOverride_DepthOfFieldFarBlurSize = true
        post.DepthOfFieldFarBlurSize = 0.0
        post.bOverride_DepthOfFieldDepthBlurAmount = true
        post.DepthOfFieldDepthBlurAmount = 0.0
        camera.PostProcessBlendWeight = 1.0
        print("[StellarCraft] native MC CameraActor created\n")
    end
    local yaw, pitch = st.yaw - 90.0, -st.pitch
    local loc = pawn:K2_GetActorLocation()
    local x, y, z = loc.X, loc.Y, loc.Z - capsule_half_height(pawn) + st.eyeHeight * UNITS_PER_BLOCK
    local mode, distance = st.cameraMode or 0, (st.cameraDistance or 4) * UNITS_PER_BLOCK
    if mode ~= 0 then
        local direction = mode == 1 and -1 or 1
        local ry, rp = math.rad(yaw), math.rad(pitch)
        x = x + direction * distance * math.cos(rp) * math.cos(ry)
        y = y + direction * distance * math.cos(rp) * math.sin(ry)
        z = z + direction * distance * math.sin(rp)
        if mode == 2 then yaw, pitch = yaw + 180.0, -pitch end
    end
    local camera = gMcCamera.CameraComponent
    local vertical = math.max(10, math.min(150, st.fovDeg or 70))
    if not gCameraPose or math.abs(x-gCameraPose.x)+math.abs(y-gCameraPose.y)+math.abs(z-gCameraPose.z) > 0.01 then
        gMcCamera:K2_SetActorLocation({ X = x, Y = y, Z = z }, false, {}, true)
    end
    if not gCameraPose or yaw ~= gCameraPose.yaw or pitch ~= gCameraPose.pitch then
        gMcCamera:K2_SetActorRotation({ Pitch = pitch, Yaw = yaw, Roll = 0 }, false)
    end
    if not gCameraPose or vertical ~= gCameraPose.vertical then
        camera:SetFieldOfView(math.deg(2 * math.atan(math.tan(math.rad(vertical) / 2) * 16 / 9)))
        camera.bConstrainAspectRatio = false
    end
    -- Repeatedly restarting the blend forces native camera work even while standing still.
    -- Recover a view target taken by a native cutscene, while setting it once on handoff.
    local clock = os.clock()
    if not gCameraPose or clock-gViewTargetCheck > 0.25 then
        gViewTargetCheck = clock
        if pc:GetViewTarget():GetAddress() ~= gMcCamera:GetAddress() then pc:SetViewTargetWithBlend(gMcCamera, 0.0, 0, 0.0, false) end
    end
    gCameraPose = {x=x,y=y,z=z,yaw=yaw,pitch=pitch,vertical=vertical}
    local handOk, handError = pcall(BlockVisuals.update_hand, gMcCamera)
    if not handOk and os.clock() - gHandErrorAt > 5 then
        gHandErrorAt = os.clock()
        print("[StellarCraft] native hand update failed: " .. tostring(handError) .. "\n")
    end
end

-- THE FRAME OFFSET (this is the fix for "放置方块没成功 / 方块看不见").
--
-- Stellar Blade's world and Minecraft's world do NOT share an origin -- measured live, the
-- pawn sits at MC-equivalent (-137.8, 0.9, -191.8) while the MC player is at (-19.6, 99.0,
-- 55.7): about 118 blocks apart in X, 98 in Y and 247 in Z.
--
-- Everything the SB side reports to MC (the ground height field, the actor table) was being
-- converted with the raw UE->MC formula, i.e. in the PAWN's frame. Minecraft consumes those
-- numbers in the PLAYER's frame, so the "virtual ground" was published a couple of hundred
-- blocks away from the player: aiming at Stellar Blade's ground placed the block out there,
-- which is both "placement did nothing" and "I cannot see the block".
--
-- The offset is one constant translation, valid for the whole session because the pawn is
-- moved by exactly the player's delta (so `pawnFrame - playerFrame` never changes). Measured
-- at the same instant as the movement anchor, and applied at every UE->MC reporting boundary.
-- gFrameOff is declared before the ground/actor functions so they share the same upvalue.

local function ue_to_mc_frame(ueX, ueY, ueZ)
    local off = gFrameOff
    if not off then return -ueX / UNITS_PER_BLOCK, ueZ / UNITS_PER_BLOCK, -ueY / UNITS_PER_BLOCK end
    return -ueX / UNITS_PER_BLOCK + off.X,
           ueZ / UNITS_PER_BLOCK + off.Y,
           -ueY / UNITS_PER_BLOCK + off.Z
end

-- Pull the camera onto the pawn for a first-person view.
--
-- The boom's arm length is what makes Stellar Blade third person: with a non-zero length the
-- camera sits N cm BEHIND Eve. That offset is invisible while she is visible, but we composite
-- Minecraft's own rendering (the character, the placed blocks) which is produced from
-- Minecraft's first-person camera -- so the two views would be parallax-shifted against each
-- other. Zeroing the arm puts the Stellar Blade camera on top of Minecraft's.
--
-- The original value is captured once and restored when STEVE mode ends, and it is re-asserted
-- every frame because the game animates this property itself (sprint / lock-on / aim all move
-- the camera, and would otherwise win the fight half the time).
local function apply_first_person_boom(on)
    local pawn = UEHelpers.GetPlayer()
    if not pawn or not pawn:IsValid() then return end
    local okBoom, boom = pcall(function() return pawn.CameraBoom end)
    if not okBoom or not boom or not boom:IsValid() then return end

    if gBoomSaved == nil then
        local okArm, arm = pcall(function() return boom.TargetArmLength end)
        gBoomSaved = { arm = (okArm and type(arm) == "number") and arm or nil }
        local okSo, so = pcall(function() return boom.SocketOffset end)
        if okSo and so then
            gBoomSaved.socket = { X = so.X, Y = so.Y, Z = so.Z }
        end
        print(string.format(
            "[StellarCraft] camera boom: arm=%s socket=%s\n",
            gBoomSaved.arm and string.format("%.1f", gBoomSaved.arm) or "?",
            gBoomSaved.socket and string.format("(%.0f,%.0f,%.0f)",
                gBoomSaved.socket.X, gBoomSaved.socket.Y, gBoomSaved.socket.Z) or "?"))
    end

    if on then
        pcall(function() boom.TargetArmLength = 0.0 end)
    elseif gBoomApplied and gBoomSaved.arm then
        pcall(function() boom.TargetArmLength = gBoomSaved.arm end)
    end
    gBoomApplied = on
end

local function apply_mc_transform()
    if not read_mc_state then return end
    local ok, st = pcall(read_mc_state)
    if not ok or type(st) ~= "table" or st.protocolVersion~=3 then st={flags=0} end

    local mcOwns  = (st.flags & MC_FLAG_CONTROL_STEVE) ~= 0
    local inWorld = (st.flags & MC_FLAG_IN_WORLD) ~= 0
    local active  = steveMode and mcOwns and inWorld

    -- CameraActor supplies the MC view; keep Eve's spring arm available for the return to F4.
    apply_first_person_boom(false)

    if not active then
        if gMcCamera then restore_native_camera() end
        if gNativeInput then restore_native_input() end
        gMoveAnchorSb, gMoveAnchorMc = nil, nil
        if gJumpHeld then
            pcall(function() UEHelpers.GetPlayer():StopJumping() end)
        end
        gJumpHeld = false
        return
    end

    local pawn = UEHelpers.GetPlayer()
    if not pawn or not pawn:IsValid() then return end
    local owner = UEHelpers.GetPlayerController()
    if owner and owner:IsValid() then isolate_native_input(pawn, owner) end
    if gNativePhysics then
        local physics=gNativePhysics
        local crouching=(st.flags&8)~=0 and (st.flags&128)==0 and st.motionMode~=3
        if crouching~=physics.crouching then
            local oldHalf=capsule_half_height(pawn)
            local newHalf=crouching and math.min(75,physics.capsuleHalf) or physics.capsuleHalf
            local loc=pawn:K2_GetActorLocation()
            if newHalf>oldHalf then pawn:K2_SetActorLocation({X=loc.X,Y=loc.Y,Z=loc.Z+newHalf-oldHalf},true,{},false) end
            pawn.CapsuleComponent:SetCapsuleHalfHeight(newHalf,true)
            gCapsulePawn=nil
            if newHalf<oldHalf then pawn:K2_SetActorLocation({X=loc.X,Y=loc.Y,Z=loc.Z+newHalf-oldHalf},true,{},false) end
            physics.crouching=crouching
        end
    end

    -- Anchor: where the pawn and the MC player were when control actually started. Taken on the
    -- first frame that is actually active (not on the F4 press), so MC still being on its title
    -- screen cannot freeze a bogus origin.
    if not gMoveAnchorSb then
        local okLoc, loc = pcall(function() return pawn:K2_GetActorLocation() end)
        if not okLoc or not loc then return end
        gMoveAnchorSb = { X = loc.X, Y = loc.Y, Z = loc.Z }
        gMoveAnchorMc = { X = st.x, Y = st.y, Z = st.z }

        -- Frame offset: how far the pawn's UE->MC conversion is from the player's real MC
        -- coordinates. See the gFrameOff comment above.
        if not gFrameOff then gFrameOff = {
            X = st.x + loc.X / UNITS_PER_BLOCK,
            Y = st.y - (loc.Z - capsule_half_height(pawn)) / UNITS_PER_BLOCK,
            Z = st.z - (-loc.Y / UNITS_PER_BLOCK),
        } end
        -- Preserve the calibrated world frame through F4. Eve may have walked elsewhere;
        -- reconcile MC to that location instead of moving every existing block's origin.
        gTeleportSerial = gTeleportSerial + 1
        gJumpHeld = (st.flags & 512) ~= 0

        -- Measure the yaw relationship instead of assuming it: on the first frame the camera
        -- must keep pointing where it already points, and only later frames should track MC.
        gYawOffset = -90.0 -- MC +Z maps to UE -Y, including block meshes and collision.
        print(string.format(
            "[StellarCraft] move anchor sb=(%.0f,%.0f,%.0f) mc=(%.2f,%.2f,%.2f) yawOffset=%.2f frameOff=(%.2f,%.2f,%.2f)\n",
            gMoveAnchorSb.X, gMoveAnchorSb.Y, gMoveAnchorSb.Z,
            gMoveAnchorMc.X, gMoveAnchorMc.Y, gMoveAnchorMc.Z, gYawOffset,
            gFrameOff.X, gFrameOff.Y, gFrameOff.Z))
        return
    end

    -- UE is Z-up/cm, Minecraft is Y-up/blocks -- the same mapping publish_player_frame() uses,
    -- inverted for the write direction.
    --
    -- HORIZONTAL ONLY. The vertical axis is deliberately NOT followed.
    --
    -- Following MC's Y drove the pawn straight through Stellar Blade's terrain: walking off a
    -- one-block step in Minecraft dropped the pawn a metre into the ground, Stellar Blade then
    -- treated it as falling, and its landing/fall camera logic fought our per-frame writes --
    -- which is exactly the "疯狂抽搐" plus "走两步就卡地板下面". Minecraft's terrain and
    -- Stellar Blade's ground sit at unrelated heights, so there is no Y delta worth applying.
    --
    -- Keeping the pawn's own Z lets Stellar Blade's own gravity/collision hold it on its own
    -- ground, which is the only sane vertical reference we have.
    local okNow, now = pcall(function() return pawn:K2_GetActorLocation() end)
    if not okNow or not now then return end

    local targetX, targetY = now.X, now.Y
    local targetZ = now.Z

    -- Vanilla MC owns the creative double-space toggle. The host capsule owns
    -- flight height and swept collisions; never follow hidden MC world height.
    local flying = (st.flags & 128) ~= 0
    local gliding = st.motionMode==3 and (st.flags&32)==0
    local airborne = flying or gliding
    local flightNow = os.clock()
    local flightDt = math.min(math.max(flightNow-(gFlightClock or flightNow),0),0.05)
    gFlightClock = flightNow
    -- Integrate MC's tick displacement at the host cadence. Never chase a render
    -- position that already includes an older native collision correction.
    if (st.flags & 2)==0 and (st.flags & 32)==0 then
        targetX=now.X-(st.vx or 0)*UNITS_PER_BLOCK*20*flightDt
        targetY=now.Y-(st.vz or 0)*UNITS_PER_BLOCK*20*flightDt
        if gliding then targetZ=now.Z+(st.vy or 0)*UNITS_PER_BLOCK*20*flightDt end
    end
    if gNativePhysics then
        local physics, movement = gNativePhysics, gNativePhysics.component
        if airborne ~= physics.flying then
            movement:StopMovementImmediately()
            movement.PendingLaunchVelocity={X=0,Y=0,Z=0}
            movement.GravityScale=airborne and 0.0 or physics.flightGravity
            movement:SetMovementMode(airborne and 5 or 3,0) -- Flying / Falling
            physics.flying=airborne
            gFlightVelocity=0.0
            gJumpProbe=nil
            print(string.format("[StellarCraft] creative flight %s mode=%s gravity=%.4f z=%.1f\n",
                flying and "on" or "off",tostring(movement.MovementMode),movement.GravityScale,now.Z))
        end
        if flying then
            if movement.MovementMode ~= 5 then movement:SetMovementMode(5,0) end
            movement.Velocity={X=0,Y=0,Z=0}
            local direction=0
            if (st.flags & 2) == 0 then
                direction=((st.flags & 512) ~= 0 and 1 or 0)-((st.flags & 8) ~= 0 and 1 or 0)
            end
            -- Default MC creative flight: .05 * 3 vertical input per tick,
            -- .6 drag, 20 ticks/sec -> 7.5 blocks/sec at sustained input.
            local goal=direction*750.0
            local previous=gFlightVelocity
            gFlightVelocity=goal+(previous-goal)*(.6^(flightDt*20.0))
            if (st.flags & 2) ~= 0 then gFlightVelocity=0 end
            targetZ=now.Z+(previous+gFlightVelocity)*0.5*flightDt
        end
        if gliding then
            if movement.MovementMode~=5 then movement:SetMovementMode(5,0) end
            movement.Velocity={X=0,Y=0,Z=0}
        end
    end

    local sbYaw = st.yaw - 90.0

    -- Only touch the actor when it is actually somewhere else: a write every frame while the
    -- player stands still is pure churn for Stellar Blade's movement component.
    local mdx = now.X - targetX
    local mdy = now.Y - targetY
    local acknowledged = st.teleportAck == gTeleportSerial
    if (acknowledged and (mdx * mdx + mdy * mdy) > 0.000001) or math.abs(targetZ-now.Z)>0.01 then
        pcall(function()
            pawn:K2_SetActorLocation({ X = acknowledged and targetX or now.X,
                Y = acknowledged and targetY or now.Y, Z = targetZ }, true, {}, false)
        end)
    end
    -- Resolve against UE collision, then reconcile MC so a blocked target cannot accumulate.
    local actual = pawn:K2_GetActorLocation()
    if acknowledged and not airborne and (actual.X-targetX)^2+(actual.Y-targetY)^2>1.0 then
        local groundedOk,grounded=pcall(function() return pawn.CharacterMovement:IsMovingOnGround() end)
        -- Sweeping over a rounded stair edge can temporarily put the SB movement
        -- component in Falling. Continue a step from recent native floor support,
        -- but never turn an upward jump or flight into an automatic stair climb.
        local clock=os.clock()
        local descending=true
        pcall(function() descending=pawn.CharacterMovement.Velocity.Z<=1 end)
        if groundedOk and grounded and gNativePhysics then gNativePhysics.stepSupportAt=clock end
        local supported=groundedOk and grounded or (gNativePhysics and
            clock-(gNativePhysics.stepSupportAt or -10)<0.18)
        if supported and descending then
            local stepOk,stepped=pcall(native_step,pawn,now,actual,targetX,targetY,60.0)
            if stepOk then actual=stepped end
            if not stepOk and clock-gStepDiagnosticAt>2 then
                gStepDiagnosticAt=clock;print("[StellarCraft-Step] failed: "..tostring(stepped).."\n")
            end
        end
    end
    if flying and math.abs(actual.Z-targetZ)>0.1 then gFlightVelocity=0 end
    local nativeY = (actual.Z - capsule_half_height(pawn)) / UNITS_PER_BLOCK + gFrameOff.Y
    local ex, ey = actual.X - targetX, actual.Y - targetY
    local contacts=0
    if math.abs(ex)>.1 then contacts=contacts|2 end
    if airborne and math.abs(actual.Z-targetZ)>.1 then contacts=contacts|4 end
    if math.abs(ey)>.1 then contacts=contacts|8 end
    local dt=math.max(flightDt,.00001)*20*UNITS_PER_BLOCK
    if gliding and (contacts&4)~=0 and (st.vy or 0)<0 then contacts=contacts|1 end
    gMotionFeedback={frame=st.frame or 0,contacts=contacts,gliding=gliding,
        vx=-(actual.X-now.X)/dt,vy=(actual.Z-now.Z)/dt,vz=-(actual.Y-now.Y)/dt}
    -- Height errors are corrections, never jump commands. Only a real key press may jump.
    local jumpHeld = (st.flags & 512) ~= 0
    if gJumpProbe then
        gJumpProbe.peak = math.max(gJumpProbe.peak, actual.Z)
        if os.clock()-gJumpProbe.time < 0.7 and os.clock()-(gJumpProbe.last or 0) > 0.05 then
            gJumpProbe.last=os.clock()
            pcall(function()
                local m=pawn.CharacterMovement
                print(string.format("[StellarCraft] jump sample t=%.3f dz=%.1f vz=%.1f mode=%s pending=%.1f root=%s disableFall=%s curve=%s gravityZ=%.1f\n",
                    os.clock()-gJumpProbe.time,actual.Z-gJumpProbe.z,m.Velocity.Z,tostring(m.MovementMode),m.PendingLaunchVelocity.Z,
                    tostring(pawn.Mesh:GetAnimInstance().RootMotionMode),tostring(m.bDisableFalling),tostring(m.bUseJumpFallingGravityCurve),m:GetGravityZ()))
            end)
        end
        if os.clock() - gJumpProbe.time > 1.5 then
            print(string.format("[StellarCraft] jump result rise=%.1fcm z=%.1f\n",gJumpProbe.peak-gJumpProbe.z,actual.Z))
            gJumpProbe = nil
        end
    end
    if not airborne and jumpHeld and not gJumpHeld then
        -- Eve's CanJump/animation state can reject ACharacter::Jump while her move
        -- input is isolated. Launch the native capsule once, only from native ground;
        -- UE still resolves gravity, ceilings and landing. Never launch from MC height.
        local movement = pawn.CharacterMovement
        local okGround, grounded = pcall(function() return movement:IsMovingOnGround() end)
        pcall(function()
            print(string.format("[StellarCraft] jump edge ground=%s mode=%s custom=%s vz=%.1f launch=%.1f gravity=%.2f canJump=%s\n",
                tostring(grounded),tostring(movement.MovementMode),tostring(movement.CustomMovementMode),
                movement.Velocity.Z,movement.JumpZVelocity,movement.GravityScale,tostring(pawn:CanJump())))
        end)
        if okGround and grounded then
            gJumpProbe={time=os.clock(),z=actual.Z,peak=actual.Z}
            local okLaunch, launchError = pcall(function()
                pawn:LaunchCharacter({X=0,Y=0,Z=movement.JumpZVelocity},false,true)
            end)
            if not okLaunch then print("[StellarCraft] native jump failed: "..tostring(launchError).."\n") end
            pcall(function() print("[StellarCraft] launch pendingZ="..tostring(movement.PendingLaunchVelocity.Z).."\n") end)
        end
    end
    if not jumpHeld and gJumpHeld then pcall(function() pawn:StopJumping() end) end
    gJumpHeld = jumpHeld

    local pc = UEHelpers.GetPlayerController()
    if pc and pc:IsValid() then
        update_mc_camera(pawn, pc, st, gFrameOff)
        if BlockVisuals.update_body then
            local bodyOk,bodyErr=pcall(BlockVisuals.update_body,pawn,st.cameraMode or 0,capsule_half_height(pawn))
            if not bodyOk and os.clock()-gHandErrorAt>5 then
                gHandErrorAt=os.clock();print("[StellarCraft] native body update failed: "..tostring(bodyErr).."\n")
            end
        end
        -- Control rotation is what the player camera manager samples; the pawn's own yaw is
        -- written too so a camera rig that inherits the actor rotation (instead of the control
        -- rotation) follows as well. Yaw only -- pitch on the body would tilt the mesh.
        if not gBodyLook or gBodyLook.yaw ~= sbYaw or gBodyLook.pitch ~= st.pitch then
            pcall(function() pc:SetControlRotation({ Pitch = -st.pitch, Yaw = sbYaw, Roll = 0.0 }) end)
            pcall(function() pawn:K2_SetActorRotation({ Pitch = 0.0, Yaw = sbYaw, Roll = 0.0 }, false) end)
            gBodyLook = {yaw=sbYaw,pitch=st.pitch}
        end
    end

    -- Throttled verification. Reads back the pawn AND Stellar Blade's real camera, because a
    -- silent failure (UE4SS refusing the FVector/FRotator tables, a non-movable pawn, the game
    -- re-asserting its own camera) is indistinguishable from "movement does nothing" without it.
    local nowMs = os.clock() * 1000.0
    if nowMs - gLastMoveLogMs > 2000.0 then
        gLastMoveLogMs = nowMs
        local okNow2, now2 = pcall(function() return pawn:K2_GetActorLocation() end)
        if okNow2 and now2 then
            local ex, ey = now2.X - targetX, now2.Y - targetY
            local err = math.sqrt(ex * ex + ey * ey)
            if err > gMoveErrMax then gMoveErrMax = err end

            local camTxt = "camera=?"
            local pcm = nil
            pcall(function() pcm = pc and pc.PlayerCameraManager end)
            if pcm and pcm:IsValid() then
                local okCL, cl = pcall(function() return pcm:GetCameraLocation() end)
                local okCR, cr = pcall(function() return pcm:GetCameraRotation() end)
                local okF, fov = pcall(function() return pcm:GetFOVAngle() end)
                if okCL and cl and okCR and cr then
                    camTxt = string.format(
                        "camera=(%.0f,%.0f,%.0f) camRot=(p%.1f y%.1f) camFov=%.1f",
                        cl.X, cl.Y, cl.Z, cr.Pitch, cr.Yaw, (okF and fov) or -1.0)
                end
            end

            print(string.format(
                "[StellarCraft] move pawn=(%.0f,%.0f,%.0f) wantXY=(%.0f,%.0f) err=%.1f maxErr=%.1f mcY=%.2f wantYaw=%.1f %s\n",
                now2.X, now2.Y, now2.Z, targetX, targetY, err, gMoveErrMax, st.y, sbYaw, camTxt))
        end
    end
end

-- =====================================================================================
-- Native block actors: Minecraft blocks as REAL Stellar Blade objects
--
-- A placed block must NOT be a pixel in the overlay layer. It has to be an object that lives
-- in Stellar Blade's own world, so that Stellar Blade's own systems treat it as scenery --
-- most importantly so that a Naytiba walking into it is actually STOPPED by it. An overlay
-- pixel has no depth and no collision; it can only ever be a picture.
--
-- So each block becomes an AStaticMeshActor holding a 1 m cube:
--   * mobility MOVABLE first -- AStaticMeshActor defaults to Static, and a Static component
--     silently refuses to move or scale at runtime (which would leave every block at the
--     world origin at scale 1);
--   * collision set to BlockAll *and* then explicitly to WorldStatic + Block-everything.
--     SetCollisionProfileName only LOGS A WARNING if the profile is unknown, so the explicit
--     calls are what actually guarantee the block is not pass-through;
--   * scale 1.0, because the engine cube is 100 uu = 1 m and one Minecraft block is 1 m.
--
-- Position comes from the same gFrameOff translation the movement code uses, so a block lands
-- where the player is looking in Stellar Blade rather than a couple of hundred blocks away.
--
-- Wire format: kEvBlockChange (type 5, reserved in the protocol for exactly this) with
-- a/b/c = the block's MIN CORNER in Minecraft coordinates and flags = 1 place / 0 break /
-- 2 clear-everything.
-- =====================================================================================

local EV_BLOCK_CHANGE = 5
local BLOCK_PLACE     = 1
local BLOCK_BREAK     = 0
local BLOCK_CLEAR_ALL = 2

-- Engine content is only cooked when something in the game references it, so a shipped title
-- may or may not have the basic cube. Try the usual paths, then fall back to scanning the
-- loaded StaticMeshes. A candidate is only accepted when it reports REAL BOUNDS (see
-- mesh_usable): a StaticMesh without render data yields an empty scene proxy, and that is a
-- documented way to take a UE renderer down.
local BLOCK_MESH_PATHS = {
    "/Engine/BasicShapes/Cube.Cube",
    "/Engine/BasicShapes/Cube",
    "/Engine/EngineMeshes/Cube.Cube",
    "/Engine/EngineMeshes/Cube",
}

local BLOCK_OPS_PER_TICK = 8   -- a 256-block re-sync must not spawn 256 actors in one frame
local BLOCK_FAIL_LIMIT   = 3   -- consecutive failures before the feature gives up for the session

-- gBlockActors is shared with the native-ground sampler; MC cubes use their own MC raycast.
local gBlockModels = {}
local gBlockQueue   = {}       -- pending {action, x, y, z}
local gBlockMesh    = nil
local gBlockMeshName = nil     -- full path of the chosen mesh (so the log identifies it)
local gBlockScale   = 1.0      -- uniform scale that makes the mesh an exact 1 m cube
local gBlockClass   = nil
local gBlockLive    = 0
local gBlockFailed  = 0
local gBlockDropped = 0
local gBlockFirstErr = nil
local gBlockLastLogMs = 0
local gBlockSearchDone = false
local gBlockTrace   = true     -- step-by-step breadcrumbs, FIRST spawn only
local gTickTrace    = false    -- per-stage breadcrumbs for the first tick after F4 -> STEVE
local gBlockColText = "?"      -- last read-back collision state, shown in the periodic status line
local gBlockFailStreak = 0
local gBlockDisabled = false

local function block_key(x, y, z)
    return string.format("%d,%d,%d", x, y, z)
end

local function static_object(path)
    local ok, o = pcall(StaticFindObject, path)
    if ok and o and o:IsValid() then return o end
    return nil
end

-- Uniform scale that turns the mesh into an exactly 1 m cube: the largest half-extent is
-- normalised to the 50 cm half-extent of a 1 m cube. Uniform, not per axis, because a
-- non-uniformly scaled blocking collision body is unreliable in PhysX -- with a cube-like mesh
-- the other axes land inside the 1 m cell anyway.
local function block_scale_for(ext)
    local half = math.max(ext.X, ext.Y, ext.Z)
    if half <= 0.0 then return 1.0 end
    local s = 50.0 / half                 -- a 1 m cube reports a half-extent of 50 cm
    if s < 0.01 then s = 0.01 end         -- sane guards: a 100 m mesh is not a block
    if s > 100.0 then s = 100.0 end
    return s
end

-- Half-extents (cm) of a mesh's built bounds. UE reports BoxExtent as a HALF size, so the
-- canonical 1 m engine cube comes back as (50, 50, 50).
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

-- A mesh is usable when it has real geometry AND collision. Two independent traps:
--   * zero bounds = no render data (a stripped editor asset or a cook placeholder), and
--     building a scene proxy for one of those fails hard rather than warning;
--   * no BodySetup = no collision geometry at all, so the block would render and never stop
--     anything -- which is precisely the "方块挡不住怪" failure the player would see.
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

local function resolve_block_class()
    if gBlockClass then return gBlockClass end
    gBlockClass = static_object("/Script/Engine.StaticMeshActor")
    if not gBlockClass then
        print("[StellarCraft] block spawn FAILED: AStaticMeshActor class not found\n")
    end
    return gBlockClass
end

-- MC block (x,y,z) spans [x,x+1] on every axis; its CENTRE maps to the cube's centre.
local function block_ue_center(x, y, z)
    local off = gFrameOff
    local ox = off and off.X or 0.0
    local oy = off and off.Y or 0.0
    local oz = off and off.Z or 0.0
    local cx, cy, cz = x + 0.5, y + 0.5, z + 0.5
    return -(cx - ox) * UNITS_PER_BLOCK,
           -(cz - oz) * UNITS_PER_BLOCK,
            (cy - oy) * UNITS_PER_BLOCK
end

-- =====================================================================================
-- Spawn one native block.
--
-- THE API RULE HERE (chosen by elimination, not by taste): use
--     world:SpawnActor(UClass, {X,Y,Z}, {Yaw,Pitch,Roll})
-- -- UE4SS's own helper (see src/LuaType/LuaUWorld.cpp), which converts the two structs in C++
-- and reports a wrong shape as a clean Lua error. The first version used
--     UGameplayStatics:BeginDeferredActorSpawnFromClass(world, cls, xform, 0, nil)  ...  gs.FinishSpawningActor(actor, xform)
-- which marshals a TSubclassOf + a by-value FTransform from a nested table + a byte enum + an
-- Owner in one call, and additionally used DOT access for FinishSpawningActor (dot returns an
-- UNBOUND function, so `actor` became `self` and the deferred actor was never finished).
--
-- THE CRASH RULE, corrected by measurement: the two crashes of 2026-10-08 are the SAME fault
-- at the SAME address -- UE4SS.dll+0x4E431A, `mov rax,[r15+0x70]` with r15 = 0 -- and the
-- second one happened AFTER the spawn call above had already been replaced. So the spawn call
-- was NOT the villain; the block that was executing both times was the COLLISION setup, and it
-- is that block which no longer writes anything (see the comment at `collision-readback`).
-- Lesson: identical fault addresses across two different code revisions mean the shared code
-- is the culprit -- not the thing you most recently changed.
--
-- Every risky step below is wrapped in ONE pcall and narrated by `step()` on the first block
-- only: a native crash leaves no Lua error, so the breadcrumb IS the stack trace.
-- =====================================================================================
local function spawn_block(x, y, z, modelId, nativeOffset)
    if gBlockDisabled then return false end

    local key = block_key(x, y, z)
    local existing = gBlockActors[key]
    if existing and existing:IsValid() then
        if gBlockModels[key] == modelId then return true end
        existing:K2_DestroyActor()
        gBlockActors[key] = nil
    end

    -- Breadcrumbs for the first spawn only: if the game does die in here, the last line in
    -- UE4SS.log names the exact call, which is worth far more than a second guess.
    local trace = gBlockTrace
    local function step(name)
        if trace then print("[StellarCraft] block step: " .. name .. "\n") end
    end

    local createdActor
    local ok, err = pcall(function()
        step("mesh")
        local mesh = resolve_block_mesh()
        if not mesh then error("no usable StaticMesh available for the block") end

        step("class")
        local cls = resolve_block_class()
        if not cls then error("AStaticMeshActor class not found") end

        step("world")
        local pawn = UEHelpers.GetPlayer()
        if not pawn or not pawn:IsValid() then error("no player pawn") end
        local world = pawn:GetWorld()
        if not world or not world:IsValid() then error("no world") end

        local ux, uy, uz = block_ue_center(x, y, z)
        if nativeOffset and nativeOffset==nativeOffset and math.abs(nativeOffset)<=1 then uz=uz+nativeOffset*100 end
        local loc = { X = ux, Y = uy, Z = uz }

        step("spawn")
        local actor = world:SpawnActor(cls, loc, { Yaw = 0.0, Pitch = 0.0, Roll = 0.0 })
        createdActor = actor
        if not actor or not actor:IsValid() then error("SpawnActor returned an invalid actor") end
        step("spawned")

        local smc = actor.StaticMeshComponent
        if not smc or not smc:IsValid() then error("spawned actor has no StaticMeshComponent") end

        -- MOBILITY FIRST. AStaticMeshActor spawns with a STATIC component, and a static
        -- component silently DISCARDS runtime transform changes -- UE logs a warning and
        -- returns, so the block would keep the mesh's native size (the engine's
        -- /Engine/EngineMeshes/Cube is 2.56 m across, not 1 m) and the grid would be wrong.
        -- Movable also gets dynamic lighting, which runtime-spawned geometry needs.
        step("mobility")
        smc:SetMobility(2)                  -- EComponentMobility::Movable

        step("mesh-assign")
        smc:SetStaticMesh(mesh)
        if gBlockScale and math.abs(gBlockScale - 1.0) > 0.001 then
            actor:SetActorScale3D({ X = gBlockScale, Y = gBlockScale, Z = gBlockScale })
        end

        step("native-texture-and-model")
        BlockVisuals.attach(actor, smc, modelId)

        -- Collision: NOTHING IS WRITTEN HERE, ON PURPOSE.
        --
        -- This step used to call SetCollisionProfileName("BlockAll", true) plus
        -- SetCollisionObjectType / SetCollisionEnabled / SetCollisionResponseToAllChannels /
        -- SetSimulatePhysics / SetVisibility -- and Stellar Blade died right here TWICE
        -- (2026-10-08 23:04 and 23:17) with the exact same fault:
        --   UE4SS.dll+0x4E431A, `mov rax,[r15+0x70]` with r15 = 0
        -- i.e. a NULL DEREFERENCE INSIDE UE4SS'S LUA GLUE -- in argument marshalling, not in
        -- game code. (The identical address across both runs, including the run whose spawn
        -- call we had already replaced, is what proves it is these calls and not the spawn.)
        --
        -- A freshly spawned AStaticMeshActor's component already carries UE's default profile
        -- (BlockAll = WorldStatic + QueryAndPhysics + Block on every channel), so those writes
        -- were never needed. Instead of writing we READ the state back with ZERO-ARGUMENT
        -- getters: no parameters to marshal, which is the one shape of call this bug cannot
        -- reach. If the readback ever reports something else, re-add exactly ONE setter with
        -- its own breadcrumb -- never the whole block.
        step("collision-readback")
        local colEnabled, colObj, finalScale = nil, nil, nil
        pcall(function() colEnabled = smc:GetCollisionEnabled() end)
        pcall(function() colObj = smc:GetCollisionObjectType() end)
        pcall(function()
            local s = actor:GetActorScale3D()
            if s then finalScale = string.format("%.3f", s.X) end
        end)
        if trace then
            -- Read back what the actor actually ended up with. The scale matters as much as the
            -- collision: it is the difference between a 1 m block and a 2.5 m slab.
            gBlockColText = string.format("en=%s/ot=%s/scale=%s",
                tostring(colEnabled), tostring(colObj), tostring(finalScale))
            print(string.format(
                "[StellarCraft] block state: collisionEnabled=%s objType=%s scale=%s (want 3=QueryAndPhysics / 0=WorldStatic / 1.000)\n",
                tostring(colEnabled), tostring(colObj), tostring(finalScale)))
            if colEnabled ~= nil and colEnabled ~= 3 then
                print("[StellarCraft] block collision: NOT blocking (enabled~=3) -- if monsters walk " ..
                      "through, re-add SetCollisionEnabled(3) alone, with its own breadcrumb\n")
            end
        end

        gBlockActors[key] = actor
        gGroundIgnore=nil
        gBlockModels[key] = modelId
        gBlockFailStreak = 0
    end)

    gBlockTrace = false     -- one narrated block is enough

    if not ok then
        if createdActor and createdActor:IsValid() then pcall(function() createdActor:K2_DestroyActor() end) end
        gBlockFailed = gBlockFailed + 1
        gBlockFailStreak = gBlockFailStreak + 1
        if not gBlockFirstErr then
            gBlockFirstErr = tostring(err)
            print(string.format("[StellarCraft] block spawn FAILED at (%d,%d,%d): %s\n",
                x, y, z, tostring(err)))
        end
        if gBlockFailStreak >= BLOCK_FAIL_LIMIT then
            gBlockDisabled = true
            print(string.format(
                "[StellarCraft] block spawning DISABLED for this session (%d consecutive " ..
                "failures); last error: %s\n", gBlockFailStreak, tostring(err)))
        end
        return false
    end
    return true
end

local function despawn_block(x, y, z)
    local key = block_key(x, y, z)
    local actor = gBlockActors[key]
    gBlockActors[key] = nil
    gGroundIgnore=nil
    if actor and actor:IsValid() then
        pcall(function() actor:K2_DestroyActor() end)
        return true
    end
    return false
end

local function clear_block_actors()
    NativeWorld.reset()
    local n = 0
    for k, actor in pairs(gBlockActors) do
        if actor and actor:IsValid() then
            pcall(function() actor:K2_DestroyActor() end)
            n = n + 1
        end
        gBlockActors[k] = nil
        gGroundIgnore=nil
    end
    return n
end

-- Wired into the event pump (see the forward declaration at the top of the file).
enqueue_block_op = function(action, x, y, z, modelId, nativeOffset)
    -- The initial re-sync can arrive one tick before the camera anchor. Keep it until calibrated.
    if #gBlockQueue >= 4096 then
        gBlockDropped = gBlockDropped + 1
        return
    end
    gBlockQueue[#gBlockQueue + 1] = { action, x, y, z, modelId, nativeOffset }
end

-- Applies at most BLOCK_OPS_PER_TICK queued block operations, so a re-sync burst of a few
-- hundred actors spreads over a second instead of stalling one frame.
local function drain_block_queue()
    if not gFrameOff then return end
    if #gBlockQueue == 0 then return end

    -- Take the pending list wholesale and start a fresh one for whatever cannot be serviced
    -- this tick. (The previous version cleared entries in place and re-appended to the same
    -- table; `#` on a table with holes is undefined in Lua, so ops could be appended on top of
    -- entries that had not been read yet and were silently lost.)
    local pending = gBlockQueue
    gBlockQueue = {}

    local budget = BLOCK_OPS_PER_TICK
    for i = 1, #pending do
        local op = pending[i]
        if budget <= 0 then
            gBlockQueue[#gBlockQueue + 1] = op   -- keep for the next tick
        else
            budget = budget - 1
            if op[1] == BLOCK_PLACE then
                spawn_block(op[2], op[3], op[4], op[5], op[6])
            elseif op[1] == BLOCK_BREAK then
                despawn_block(op[2], op[3], op[4])
            elseif op[1] == 3 then
                select_build_frame(op[5])
            elseif op[1] == 4 then
                NativeWorld.light(UEHelpers.GetPlayer(),gFrameOff,op[2],op[3],op[4],op[5],gBuildWorldId,op[6])
            else
                clear_block_actors()
            end
        end
    end

    -- Periodic status line, so "blocks do not appear" can be diagnosed from the log alone.
    local now = os.clock() * 1000.0
    if now - gBlockLastLogMs > 2000.0 then
        gBlockLastLogMs = now
        local live = 0
        for _, actor in pairs(gBlockActors) do
            if actor and actor:IsValid() then live = live + 1 end
        end
        gBlockLive = live
        print(string.format(
            "[StellarCraft] blocks live=%d queued=%d spawned_ok=%d failed=%d dropped=%d " ..
            "scale=%.3f%s mesh=%s | %s\n",
            live, #gBlockQueue, gBlockLive, gBlockFailed, gBlockDropped,
            gBlockScale, gBlockDisabled and " [DISABLED]" or "",
            tostring(gBlockMeshName), tostring(gBlockColText)))
    end
end

-- Hide / show Eve's own body mesh so the player sees STEVE's first-person view instead of
-- clipping into Eve's model.
-- Stellar Blade drives the camera through Eve's pawn, so ML's first-person camera would sit
-- INSIDE her head: her hair / shoulders / arms fill the screen. Hiding the pawn's skeletal
-- mesh (plus every other primitive component on it) removes her from the view without
-- touching the camera, movement or camera boom — exactly the "replace Eve with Steve"
-- behaviour, with Steve himself rendered by the Minecraft client and composited by the
-- overlay on top.
--
-- We hide the mesh with SetVisibility(false, true) (propagate to children so attached
-- meshes / caps / accessories go too) and restore it with SetVisibility(true, true). The
-- initial state is remembered so we only touch it on an actual Steve/Eve transition.
local gEveHidden = nil

local function apply_eve_visibility(hidden)
    if gEveHidden == hidden then return end

    local pawn = UEHelpers.GetPlayer()
    if not pawn or not pawn:IsValid() then return end

    -- ACharacter::Mesh (offset 0x328) is the main skeletal mesh component.
    local okMesh, mesh = pcall(function() return pawn.Mesh end)
    if okMesh and mesh and mesh:IsValid() then
        pcall(function() mesh:SetVisibility(not hidden, true) end)
        -- Hidden-in-game is the flag the renderer actually honours for gameplay meshes;
        -- set both so neither path can re-show her.
        pcall(function() mesh:SetHiddenInGame(hidden, true) end)
        gEveHidden = hidden
        print(string.format("[StellarCraft] Eve mesh %s\n", hidden and "hidden" or "shown"))
    else
        print("[StellarCraft] Eve mesh not found (pawn.Mesh invalid)\n")
    end
end

local gProfile, gProfileSince = {}, os.clock()
local gGuardActive,gGuardBlocked,gGuardIdentity,gGuardObserved=nil,0,nil,0
local function protect_mc_host()
    if not native_player_guard then return end
    if not (steveMode and mcInput) then
        native_player_guard(0,0,false)
        if gGuardActive then print("[StellarCraft-PlayerGuard] active=false; MC control released\n") end
        gGuardActive=false
        return
    end
    local identity,netGuid=0,0
    local identityDetail="unavailable"
    local okIdentity=pcall(function()
        local pawn,pc=UEHelpers.GetPlayer(),UEHelpers.GetPlayerController()
        if pawn and pawn:IsValid() and pc and pc:IsValid() then
            local state=pc.PlayerState
            if state and state:IsValid() then
                local ack=pc.AcknowledgedPawn
                local acknowledged=ack and ack:IsValid() and ack:GetAddress()==pawn:GetAddress()
                netGuid=state.ActorNetGuid
                identityDetail=string.format("playerId=%s netGuid=%s state=%s acknowledged=%s",tostring(state.PlayerId),tostring(netGuid),state:GetFullName(),tostring(acknowledged))
                if state:GetFullName():match("^SBNetworkPlayerState ") and acknowledged and state.PlayerId>0 then identity=state.PlayerId end
            end
        end
    end)
    local result=native_player_guard(identity,netGuid,okIdentity and identity~=0 and steveMode and mcInput)
    if result.observed and result.observed~=gGuardObserved then
        print(string.format("[StellarCraft-PlayerGuardDiag] calls=%d guid=%d stat=%d amount=%.1f thread=%d gameThread=%d matchBits=%d\n",result.observed,result.seenGuid,result.seenStat,result.seenAmount,result.seenThread,result.gameThread,result.seenMatch))
        gGuardObserved=result.observed
    end
    if result.active~=gGuardActive or result.blocked~=gGuardBlocked or identityDetail~=gGuardIdentity then
        print(string.format("[StellarCraft-PlayerGuard] active=%s playerId=%d EveHP=%.1f blocked=%d mcTotal=%.3f\n",
            tostring(result.active),identity,result.hp,result.blocked,result.total))
        print("[StellarCraft-PlayerGuard] identity="..identityDetail.." read="..tostring(okIdentity).."\n")
        gGuardActive,gGuardBlocked,gGuardIdentity=result.active,result.blocked,identityDetail
    end
end
local function tick()
    -- Full-chain breadcrumbs for the FIRST tick after entering STEVE (armed by the F4 handler).
    -- The per-tick chain is long -- transform, publish, enemy mirror, event pump, block
    -- spawning, ground sampling -- and a native crash anywhere in it leaves no Lua error and no
    -- stack behind. One narrated tick turns "it crashed again" into a precise pointer.
    local ttrace = gTickTrace
    local profile = gProfile[steveMode and "MC" or "EVE"]
    if not profile then profile={};gProfile[steveMode and "MC" or "EVE"]=profile end
    local lastStage,lastClock=nil,os.clock()
    local function ts(name)
        local clock=os.clock()
        if lastStage then
            local entry=profile[lastStage] or {total=0,count=0,max=0}
            local elapsed=(clock-lastClock)*1000
            entry.total,entry.count,entry.max=entry.total+elapsed,entry.count+1,math.max(entry.max,elapsed)
            profile[lastStage]=entry
        end
        lastStage,lastClock=name,clock
        if ttrace then print("[StellarCraft] tick step: " .. name .. "\n") end
    end
    ts("begin")

    -- Mirror the MC-side input-ownership bit (McFlags bit 8 = CONTROL_STEVE) locally. This
    -- runs BEFORE publish_player_frame so the level we publish each frame is current.
    --
    -- This is the INPUT half of the handoff: MC asserts the bit only once it is genuinely
    -- ready to receive forwarded input, which is exactly SekiroCraft's `mcStatus` model.
    if read_mc_control then
        local ok, flags = pcall(read_mc_control)
        if ok and flags then
            mcInput = (flags & 256) ~= 0
        end
    end

    -- Movement / camera takeover, BEFORE everything that measures the pawn's position: the
    protect_mc_host()
    -- ground sampler and the actor mirror are both centred on the pawn, so it has to be moved
    -- into place first or they would keep describing where the player used to be.
    ts("transform")
    local transformOk, transformErr = pcall(apply_mc_transform)
    if not transformOk then print("[StellarCraft] camera/transform failed: " .. tostring(transformErr) .. "\n") end

    ts("publish")
    local ok2, wrote = pcall(publish_player_frame)
    if not ok2 then
        print(string.format("[StellarCraft] publish_player_frame failed: %s\n", tostring(wrote)))
    end

    -- Mirror nearby enemies into the actor table so Minecraft can spawn hittable proxies,
    -- then apply any damage Minecraft forwarded (TNT explosions, melee, arrows).
    --
    -- Both halves only matter while a world is loaded; on the title screen the actor
    -- enumeration returns nothing and the event ring is empty, so this is cheap.
    ts("mirror")
    local pawn = UEHelpers.GetPlayer()
    if pawn and pawn:IsValid() then
        local okLoc, loc = pcall(function() return pawn:K2_GetActorLocation() end)
        if okLoc and loc then
            local okWorld, world = pcall(function() return pawn:GetWorld() end)
            -- Translated into Minecraft's frame, so the proxies spawn around the player.
            local cx, cy, cz = ue_to_mc_frame(loc.X, loc.Y, loc.Z)
            pcall(mirror_actors, { X = cx, Y = cy, Z = cz }, (okWorld and world) or nil)
        end
    end

    ts("events")
    pcall(apply_events)

    -- Walk the queued native-block operations (a few per tick, see BLOCK_OPS_PER_TICK).
    ts("blocks")
    pcall(drain_block_queue)
    ts("nativeWorld")
    local nativeWorldOk,nativeWorldErr=pcall(NativeWorld.tick,pawn,gFrameOff,gBuildWorldId)
    if not nativeWorldOk then print("[StellarCraft-World] tick failed: "..tostring(nativeWorldErr).."\n") end

    -- Sample the native ground around the player and stream it to Minecraft, so MC's pick
    -- raycast can land on Stellar Blade's ground and the player can place blocks.
    ts("ground")
    publish_ground_frame()

    -- Hide Eve's body while STEVE owns the character (the F4 latch). This is CHARACTER
    -- ownership, so it follows steveMode alone — NOT the input-authority bits, and NOT
    -- whether MC has loaded a world yet.
    --
    -- (Previously we also required "MC is in a world", which meant F4 appeared to do
    -- nothing until MC had loaded a save. Eve now disappears the moment F4 is pressed and
    -- MC takes over rendering the avatar, which is what "随时能按 F4，立即生效" requires.)
    if steveMode ~= wasSteve then
        wasSteve = steveMode
        if steveMode then
            print("[StellarCraft] STEVE mode (MC owns the character)\n")
        else
            print("[StellarCraft] EVELYN mode (Stellar Blade owns the character)\n")
        end
    end
    -- Re-assert every frame (cheap, idempotent): the game can re-show the mesh on cutscenes
    -- or respawn, and we want Steve to stay unobstructed.
    ts("visibility")
    apply_eve_visibility(steveMode)

    ts("complete")
    if os.clock()-gProfileSince>10 then
        for mode,stages in pairs(gProfile) do
            local parts={}
            for _,name in ipairs({"begin","transform","publish","mirror","events","blocks","ground","visibility"}) do
                local entry=stages[name]
                if entry then parts[#parts+1]=string.format("%s=%.2f/max%.2fms",name,entry.total/entry.count,entry.max) end
            end
            print("[StellarCraft-Profile] "..mode.." "..table.concat(parts," ").."\n")
        end
        gProfile,gProfileSince={},os.clock()
    end
    if ttrace then
        gTickTrace = false      -- one narrated tick is enough
        ts("first tick complete")
    end

    return false -- keep looping
end

local function init()
    if not load_bridge() then
        return
    end

    -- Reset to EVE on every startup. The mapping can outlive a session (the game keeps it
    -- alive between launches), so the F4 latch may still be set from the last time the player
    -- switched to Steve. Force it clear here so the game ALWAYS boots in EVE mode.
    steveMode = false
    wasSteve = false
    if set_control then
        local ok, cleared = pcall(set_control, 0)
        if ok then
            print(string.format("[StellarCraft] start-up state = EVE (sbFlags=0x%X)\n", cleared or 0))
        end
    end

    -- F4 hotkey (detected on the SB side, where the player actually presses it): flip the
    -- control latch. We hold the result as a persistent LEVEL in SbState.flags, so MC reads
    -- "who owns the player" directly — press once = Steve, press again = Eve.
    if set_control then
        RegisterKeyBind(Key.F4, function()
            steveMode = not steveMode
            -- Narrate the whole first tick of this STEVE session (see tick()). Armed here rather
            -- than once per launch, so it also covers the second and third switch.
            if steveMode then gTickTrace = true end
            local ok, newflags = pcall(set_control, steveMode and 1 or 0)
            if ok then
                print(string.format("[StellarCraft] F4 pressed; control=%s (sbFlags=0x%X)\n",
                    steveMode and "STEVE" or "EVE", newflags or 0))
            end
        end)
        print("[StellarCraft] F4 hotkey registered (level-latch)\n")
    end

    -- UObject creation and mesh/material updates belong on the UE game thread. Avoid
    -- queueing another tick while one is pending, including during loading stalls.
    local tickPending = false
    LoopAsync(8, function()
        if not tickPending then
            tickPending = true
            ExecuteInGameThread(function()
                local ok, err = pcall(tick)
                tickPending = false
                if not ok then print("[StellarCraft] tick failed: " .. tostring(err) .. "\n") end
            end)
        end
        return false
    end)
end

init()
