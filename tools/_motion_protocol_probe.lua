local dll=assert(arg[1])
assert(package.loadlib(dll,"sc_create_mapping_lua"))()
local motion=assert(package.loadlib(dll,"sc_read_mc_state_lua"))
local entity=assert(package.loadlib(dll,"sc_read_entity_frame_lua"))
print("READY");io.stdout:flush()
for line in io.lines() do
    local state=motion()
    if type(state)=="table" then
        assert(state.protocolVersion==6 and state.x==10 and state.y==200 and state.z==-30)
        assert(state.motionMode==3 and math.abs(state.vy+.1)<1e-12)
    end
    print(type(state).." "..type(entity(7,9,string.pack("<I8I8",11,22))))
    io.stdout:flush()
end
