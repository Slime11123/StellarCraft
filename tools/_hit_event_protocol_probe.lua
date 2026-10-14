local dll=assert(arg[1])
assert(package.loadlib(dll,"sc_create_mapping_lua"))()
local read=assert(package.loadlib(dll,"sc_read_events_lua"))
local publish=assert(package.loadlib(dll,"sc_write_player_lua"))
print("READY");io.stdout:flush()
for line in io.lines() do
    if line=="map-a" then
        publish(1,10,20,30,0,0,0,1,1,0,0,0,0x12345678,0x11223344)
    elseif line=="map-b" then
        publish(1,10,20,30,0,0,0,2,1,0,0,0,0x87654321,0x55667788)
    elseif line=="map-invalid" then
        publish(0,10,20,30,0,0,0,3,0,0,0,0,0x87654321)
    elseif line=="map-missing" then
        publish(1,10,20,30,0,0)
    end
    local events=read()
    if line=="batch" then
        assert(#events==3 and events[1].type==9 and events[2].type==10 and events[3].type==1)
        for i=1,2 do
            local e=events[i]
            assert(e.uid==42 and e.flags==22 and e.weapon==5 and e.timeLow==0x7FC12345 and e.d==nil)
        end
        assert(events[1].a==1 and events[1].b==2 and events[1].c==3)
        assert(events[3].a==7 and math.abs(events[3].d-.3)<1e-6)
    else assert(#events==0) end
    print("PASS");io.stdout:flush()
end
