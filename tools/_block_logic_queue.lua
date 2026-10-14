
-- ---- stubs -------------------------------------------------------------------------
local BLOCK_OPS_PER_TICK = 8
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
