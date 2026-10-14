-- UE4SS can return distinct Lua wrappers for the same UObject address.
local source=assert(io.open(arg[1],"rb")):read("*a")
local actors,destroyed,time=0,0,0
local valid={IsValid=function() return true end}
local function actor(address)
    return setmetatable({StaticMeshComponent={SetMobility=function() end,SetCollisionEnabled=function() end},
        GetAddress=function() return address end,K2_DestroyActor=function() destroyed=destroyed+1 end,
        K2_SetActorLocation=function() end,SetActorHiddenInGame=function() end}, {__index=valid})
end
local world={SpawnActor=function() actors=actors+1;return actor(1000+actors) end}
local function pawn(address)
    return setmetatable({GetAddress=function() return address end,GetWorld=function() return world end,
        K2_GetActorLocation=function() return {X=1,Y=2,Z=90} end}, {__index=valid})
end
local frame="SCPOSE1\n"..string.pack("<I4I8I4I4I4",1,os.time()*1000,0,1,0)
local env=setmetatable({require=function() return {} end,print=function() end,
    package={loadlib=function() return function() return frame end end},StaticFindObject=function() return valid end,
    os={clock=function() time=time+.03;return time end,time=os.time}}, {__index=_G})
local visuals=assert(load(source,"@block_visuals.lua","t",env))()
for i=1,1000 do visuals.update_body(pawn(500),0,90) end
assert(actors==1 and destroyed==0,"Repeated pawn wrappers recreated native body")
visuals.hide_body();visuals.update_body(pawn(500),1,90)
assert(actors==1,"F4 re-entry recreated same native body")
visuals.update_body(pawn(600),1,90)
assert(actors==2 and destroyed==1,"A genuinely new pawn must replace its body")
valid.IsValid=function()error("old body validated")end
visuals.forget_world();visuals.forget_world()
valid.IsValid=function()return true end
visuals.update_body(pawn(600),1,90)
assert(actors==3 and destroyed==1,"same-address new epoch must recreate body without touching old body")
print("PASS: 1000 distinct wrappers, one body actor; F4 reuse; real pawn replacement")
