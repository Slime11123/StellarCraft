-- Offline probe for sc_read_mc_state_lua. Driven by tools/test_mc_state_export.py.
--
-- Run under the standalone lua.exe built next to the bridge DLL (same Lua 5.4.7 core that is
-- statically linked into stellarcraft_shm.dll), so the C function's Lua ABI is exercised for
-- real instead of being assumed.
--
-- argv: 1 = DLL path
--
-- The mapping is created here, which CLEARS McState (sc_create_mapping memsets the state
-- blocks so a previous session's flags cannot leak in). The Python driver therefore rewrites
-- its known McState values in a loop for the whole duration of this process: after the clear,
-- the values are guaranteed to be back.

local dll = arg[1]
assert(dll, "usage: lua _mc_state_probe.lua <dll>")

local create, loadErr = package.loadlib(dll, "sc_create_mapping_lua")
if not create then
    print("LOAD_FAIL " .. tostring(loadErr))
    return
end
local read, readErr = package.loadlib(dll, "sc_read_mc_state_lua")
if not read then
    print("LOAD_FAIL " .. tostring(readErr))
    return
end

create()

-- Busy-wait ~0.5 s of CPU time so the driver's rewrite loop has certainly run since the
-- clear above. os.clock() is CPU time; a busy loop makes it track wall time.
local t0 = os.clock()
while os.clock() - t0 < 0.5 do end

local st = read()
if type(st) ~= "table" then
    print("RESULT nil")
    return
end

print(string.format(
    "RESULT seq=%d flags=%d x=%.4f y=%.4f z=%.4f yaw=%.4f pitch=%.4f eye=%.4f fov=%.4f cam=%d camDist=%.4f frame=%d",
    st.seq, st.flags, st.x, st.y, st.z, st.yaw, st.pitch, st.eyeHeight,
    st.fovDeg, st.cameraMode, st.cameraDistance, st.frame))
