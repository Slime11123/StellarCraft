local time,actors,replaced=1,{},0
local camera={Pitch=20,Yaw=90,Roll=0}
local component={SetCollisionEnabled=function()end,SetCastShadow=function()end}
local function actor(loc)
    local a={loc=loc,alive=true,StaticMeshComponent={SetMobility=function()end,SetCollisionEnabled=function()end}}
    function a:IsValid() return self.alive end
    function a:K2_DestroyActor() self.alive=false end
    function a:K2_SetActorLocation(p) self.loc=p end
    function a:SetActorHiddenInGame(v) self.hidden=v end
    function a:SetActorScale3D(v) self.scale=v end
    function a:K2_SetActorRotation(v) self.rotation=v end
    actors[#actors+1]=a;return a
end
local world={SpawnActor=function(_,_,loc)return actor(loc) end}
local env=setmetatable({os={clock=function()return time end},print=function()end,StaticFindObject=function()return {} end,
    require=function(name)
        if name=="UEHelpers" then return {GetPlayerController=function()return {IsValid=function()return true end,
            PlayerCameraManager={IsValid=function()return true end,GetCameraRotation=function()return camera end}} end} end
        return {attach_model=function(a,model,double) assert(double);time=time+.001;a.model=model;return component end,
            replace_model=function(a,c,model,double) assert(c==component and double);a.model=model;replaced=replaced+1 end}
    end,
    io={open=function(name)
        local out={};for i=1,name:find("explosion") and 16 or 8 do out[#out+1]=tostring(100+i) end
        return {read=function()return table.concat(out,"\n") end,close=function()end}
    end}}, {__index=_G})
local M=assert(loadfile(arg[1],"t",env))()
local centre={X=0,Y=0,Z=0}
M.explosion(world,centre,4)
assert(M.stats().live==14,"Initial emitter and smoke burst incomplete")
M.tick(world,centre)
for _,a in ipairs(actors) do assert(a.alive and a.rotation==camera and not a.hidden) end
for i=1,60 do time=time+1/120;M.tick(world,centre) end
assert(replaced>20,"Sprite sequence was not advanced at render cadence")
assert(M.stats().objects<=64,"Particle object budget exceeded")
time=time+2;M.tick(world,centre)
assert(M.stats().live==0 and M.stats().pooled==#actors,"Expired effects did not return to pool")
local count=#actors
M.explosion(world,centre,4)
assert(#actors==count,"Warm explosion created new objects")
for i=1,150 do M.smoke(world,centre) end
assert(M.stats().objects<=64 and M.stats().live<=64,"Smoke was unbounded")
time=time+2;M.tick(world,{X=99999,Y=0,Z=0})
assert(M.stats().live==0,"Distant particles retained live objects")
M.reset();assert(M.stats().objects==0)
for _,a in ipairs(actors) do assert(not a.alive,"Reset leaked particle actor") end
M.smoke(world,centre)
for _,a in ipairs(actors) do
    a.IsValid=function()error("old particle validated")end
    a.K2_DestroyActor=function()error("old particle destroyed")end
end
M.forget_world();M.forget_world()
assert(M.stats().objects==0 and M.stats().live==0 and M.stats().pooled==0)
print("PASS: resource sequence animation, 120Hz updates, camera, warm pool reuse, 64-object limit, distance/lifetime and reset")
