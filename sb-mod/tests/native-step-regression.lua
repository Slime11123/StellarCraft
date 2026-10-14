local file=assert(io.open(arg[1],"rb"));local source=file:read("*a");file:close()
local native={X=0,Y=0,Z=90}
local height,ceiling,normal=40,1000,1
local pawn={CapsuleComponent={GetScaledCapsuleHalfHeight=function() return 90 end}}
function pawn:IsValid() return true end
function pawn:GetAddress() return 100 end
function pawn:K2_GetActorLocation() return {X=native.X,Y=native.Y,Z=native.Z} end
function pawn:K2_SetActorLocation(loc,sweep)
    if not sweep then native={X=loc.X,Y=loc.Y,Z=loc.Z};return end
    local x=loc.X
    if x~=native.X and x>=30 and loc.Z-90<height then x=0 end
    native={X=x,Y=loc.Y,Z=math.min(ceiling,math.max(x>=30 and height+90 or 90,loc.Z))}
end
local glib={IsValid=function() return true end}
function glib:LineTraceSingle(_,start,finish,channel,complex,ignored,debug,hit)
    assert(ignored[1]==pawn)
    hit.Location={X=start.X,Y=start.Y,Z=height};hit.Normal={X=0,Y=0,Z=normal}
    return height<=start.Z and height>=finish.Z
end
local env=setmetatable({require=function(name) return name=="world_lifecycle" and {new=function()return {}end} or {} end,print=function() end,
    StaticFindObject=function() return glib end}, {__index=_G})
env.pawn=pawn
env.reset=function(h,c,n) height=h;ceiling=c;normal=n or 1;native={X=0,Y=0,Z=90} end
local tests=[[
local start={X=0,Y=0,Z=90}
reset(40,1000);local result=native_step(pawn,start,start,40,0,60)
assert(result.X==40 and math.abs(result.Z-130)<.01,"walkable step did not ascend")
reset(70,1000);result=native_step(pawn,start,start,40,0,60)
assert(result.X==0 and result.Z==90,"step height limit allowed a wall")
reset(40,110);result=native_step(pawn,start,start,40,0,60)
assert(result.X==0 and result.Z==90,"blocked headroom did not roll back")
reset(40,1000,.4);result=native_step(pawn,start,start,40,0,60)
assert(result.X==0 and result.Z==90,"unwalkable slope was treated as a step")
return true
]]
source=assert(source:gsub("init%(%)%s*$",tests))
assert(load(source,"@main.lua","t",env))()
print("PASS: native step ascent, height limit, blocked headroom rollback, steep surface rejection")
