local path=arg[1]
local time,frame=1,nil
local written,actors={},{}
local smokeCount=0
local particles={reset=function()end,tick=function()end,smoke=function()smokeCount=smokeCount+1 end,explosion=function()end}
local cameraRotation={Pitch=-35,Yaw=34,Roll=0}
local pc={PlayerCameraManager={IsValid=function()return true end,GetCameraRotation=function()return cameraRotation end},
    IsValid=function()return true end,GetControlRotation=function()return {Pitch=0,Yaw=214,Roll=0} end}
local function actor(loc)
    local a={loc=loc,alive=true}
    function a:IsValid() return self.alive end
    function a:K2_GetActorLocation() return {X=self.loc.X,Y=self.loc.Y,Z=self.loc.Z} end
    function a:K2_SetActorLocation(p) self.loc=p end
    function a:K2_DestroyActor() self.alive=false end
    function a:SetActorHiddenInGame(hidden) self.hidden=hidden end
    function a:K2_SetActorRotation(rotation) self.rotation=rotation end
    function a:SetActorScale3D(scale) self.scale=scale end
    a.StaticMeshComponent={SetMobility=function()end,SetCollisionEnabled=function()end}
    a.PointLightComponent={SetMobility=function()end,SetCastShadows=function()end,SetLightColor=function()end,
        SetIntensity=function(_,v) a.intensity=v end,SetAttenuationRadius=function()end,SetVisibility=function()end}
    actors[#actors+1]=a;return a
end
local world={GetAddress=function()return 123 end}
function world:SpawnActor(_,loc) return actor(loc) end
local pawn=actor({X=0,Y=0,Z=90});function pawn:GetWorld() return world end
local glib={}
function glib:SphereTraceSingle() return false end
function glib:LineTraceSingle(_,a,b,channel,complex,ignored,debug,hit)
    if a.Z==b.Z and a.Z>1 then
        if b.X<-300 then hit.Location={X=-300,Y=b.Y,Z=b.Z};hit.Normal={Z=0};return true end
        return false
    end
    hit.Location={X=a.X,Y=a.Y,Z=0};hit.Normal={Z=1};return true
end
local off={X=0,Y=0,Z=0}
local fileIo={}
function fileIo.open(name,mode)
    if mode=="r" then return {read=function()return "777" end,close=function()end} end
    local parts={};return {write=function(_,s) parts[#parts+1]=s end,close=function()written[name]=table.concat(parts)end}
end
local env=setmetatable({os={clock=function()return time end,time=function()return 100 end},io=fileIo,
    require=function(name) return name=="native_particles" and particles or name=="UEHelpers" and {GetPlayerController=function()return pc end}
        or {update_entity=function()end,attach_model=function(_,_,doubleSided) assert(doubleSided,"particle missing back face");time=time+.1 end} end,
    package={loadlib=function(_,name) return function()return name:find("entities") and frame or nil end end},
    StaticFindObject=function(name)return name:find("SystemLibrary") and glib or {} end,print=function()end},{__index=_G})
local M=assert(loadfile(path,"t",env))()
local function manifest(world,id,x,y,z,kind,fuse)
    local body=string.pack("<I4I4",0,world)..string.rep("\0",36)
    if id then body=body..string.pack("<I4ffffffffff",id,x,y,z,0,-.1,0,.6,1.8,kind or 0,fuse or 0)..string.rep("\0",44) end
    return "SCPOSE1\n"..string.pack("<I4I8I4",math.floor(time*100),100000,id and 3 or 1)..body..string.rep("\0",8)
end
M.light(pawn,off,1,0,1,15,7)
local lamp=actors[#actors];assert(lamp.intensity==1000)
frame=manifest(7,4,0,-.1,0);M.tick(pawn,off,7)
assert(lamp.alive,"newly placed light lost on initial world bind")
local entity=actors[3];assert(entity.alive)
local feedback;for _,value in pairs(written)do feedback=value end
assert(feedback and feedback:find("4 0.000000 0.010000 0.000000"),"native floor did not clamp hidden-world gravity")
M.light(pawn,off,1,0,1,0,7);assert(not lamp.alive,"extinguished light not removed")
time=1.2;frame=manifest(8,5,1,0,1);M.tick(pawn,off,7)
assert(entity.alive,"foreign manifest should not overwrite current entity")
time=1.6;M.tick(pawn,off,7);assert(not entity.alive,"stale entity was retained")
time=2;frame=manifest(8,5,1,0,1);M.tick(pawn,off,8);assert(actors[#actors].alive)
M.reset();assert(not actors[#actors].alive)
time=3;frame=manifest(8,6,0,5,0,5);M.tick(pawn,off,8)
time=3.1;frame=manifest(8,6,10,5,0,5);M.tick(pawn,off,8)
for _,value in pairs(written) do if value:find("6 3.000000 5.000000 0.000000") then feedback=value end end
assert(feedback and feedback:find("6 3.000000 5.000000 0.000000"),"Fast arrow passed the native wall")
time=3.2;frame=manifest(8,7,0,5,0,2,60);M.tick(pawn,off,8)
assert(smokeCount==1,"TNT fuse did not emit smoke")
time=3.3;frame=manifest(8,7,0,5,0,2,60);M.tick(pawn,off,8)
assert(smokeCount==1,"Paused/unchanged fuse kept emitting smoke")
M.reset()
print('PASS: native floor/light/world/stale gates, fast arrow wall trace and fuse-driven smoke')
