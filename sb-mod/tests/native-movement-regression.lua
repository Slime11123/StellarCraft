-- Runs the actual Lua movement implementation against a deterministic native capsule.
local path = assert(arg[1], "main.lua path required")
local file = assert(io.open(path, "rb"))
local source = file:read("*a"); file:close()
local native = {X=-13500,Y=19000,Z=90}
local jumps, stops = 0, 0
local half=90
local pawn = {CapsuleComponent={GetScaledCapsuleHalfHeight=function() return half end,
    SetCapsuleHalfHeight=function(_,height) assert(height==75 or height==90);half=height end}}
local anim={RootMotionMode=2,IsValid=function() return true end}
function anim:SetRootMotionMode(mode) self.RootMotionMode=mode end
pawn.Mesh={GetAnimInstance=function() return anim end}
function pawn:IsValid() return true end
function pawn:GetAddress() return 100 end
function pawn:K2_GetActorLocation() return {X=native.X,Y=native.Y,Z=native.Z} end
function pawn:K2_SetActorLocation(loc, sweep)
    assert(sweep, "movement must sweep native collision")
    native.X=math.max(-13600,loc.X); native.Y=loc.Y
    if pawn.CharacterMovement.MovementMode==5 then
        native.Z=math.max(90,math.min(300,loc.Z)) -- native floor and ceiling
    else
        assert(math.abs(loc.Z-native.Z)<=15, "hidden MC terrain must never move the capsule vertically")
        native.Z=loc.Z
    end
end
function pawn:K2_SetActorRotation() end
function pawn:Jump() error("MC must bypass Eve's jump state machine") end
function pawn:LaunchCharacter(velocity,xyOverride,zOverride)
    assert(velocity.X==0 and velocity.Y==0 and velocity.Z==1100 and not xyOverride and zOverride)
    jumps=jumps+1
end
function pawn:StopJumping() stops=stops+1 end
function pawn:ConsumeMovementInputVector() end
local movement={GravityScale=1,MovementMode=1,JumpZVelocity=500,MaxStepHeight=45,MaxWalkSpeed=600,bUseJumpFallingGravityCurve=true,
    MaxWalkSpeedCrouched=300,bOrientRotationToMovement=true,bUseControllerDesiredRotation=true,Velocity={X=30,Y=40,Z=0}}
function movement:IsValid() return true end
function movement:IsMovingOnGround() return not self.falling end
-- SB applies the gravity scale twice; a linear mock would hide the real bug.
function movement:GetGravityZ() return -980*self.GravityScale*self.GravityScale end
function movement:StopMovementImmediately() self.Velocity={X=0,Y=0,Z=0} end
function movement:SetMovementMode(mode) self.MovementMode=mode end
pawn.CharacterMovement=movement
pawn.JumpMaxCount=2;pawn.JumpMaxHoldTime=.1;pawn.AnimRootMotionTranslationScale=1
local pc = {}
function pc:IsMoveInputIgnored() return self.move or false end
function pc:IsLookInputIgnored() return self.look or false end
function pc:SetIgnoreMoveInput(on) self.move=on end
function pc:SetIgnoreLookInput(on) self.look=on end
function pc:IsValid() return true end
function pc:GetAddress() return 200 end
function pc:GetControlRotation() return {Yaw=0,Pitch=0} end
function pc:SetControlRotation() end
local helpers={GetPlayer=function() return pawn end,GetPlayerController=function() return pc end}
local testTime=0
local env=setmetatable({os={clock=function() return testTime end},advanceTime=function(dt) testTime=testTime+dt end,
    require=function(name)
        if name=="world_lifecycle" then return {new=function()return {context={pawn=pawn,pc=pc}}end} end
        return name=="UEHelpers" and helpers or {}
    end,
    print=function() end}, {__index=_G})
local tests=[[
local pawn=UEHelpers.GetPlayer()
local world={name="World /Game/Maps/E05_GreatDesert_P.E05_GreatDesert_P"}
function world:IsValid() return true end
function world:GetFullName() return self.name end
function pawn:GetWorld() return world end
local first=native_level_id(pawn)
assert(first~=0 and first==native_level_id(pawn))
world.name="World /Game/Maps/E04_Xion_P.E04_Xion_P"
assert(native_level_id(pawn)~=0 and native_level_id(pawn)~=first)
world.name="";assert(native_level_id(pawn)==0)
world.name=nil;assert(native_level_id(pawn)==0)
function world:IsValid() return false end
assert(native_level_id(pawn)==0)
function pawn:GetWorld() error("world unavailable") end
assert(native_level_id(pawn)==0)
world.name="World /Game/Maps/E05_GreatDesert_P.E05_GreatDesert_P"
function world:IsValid() return true end
function pawn:GetWorld() return world end
local st={protocolVersion=6,flags=257,x=10,y=106,z=55,yaw=90,pitch=0,eyeHeight=1.62,teleportAck=0,vx=0,vz=0}
read_mc_state=function() return st end
update_mc_camera=function() end
apply_first_person_boom=function() end
restore_native_camera=function() end
local published
write_player=function(flags,x,y,z,yaw,pitch,seq,frame,contacts,vx,vy,vz,level)
    assert(level==first);published={x=x,y=y,z=z,seq=seq}
end
local function ack() publish_player_frame(); st.x,st.y,st.z,st.teleportAck=published.x,published.y,published.z,published.seq end
steveMode=true; apply_mc_transform(); ack()
local initialPhysics=gNativePhysics
local controller=UEHelpers.GetPlayerController()
for i=1,1000 do
    local p=setmetatable({}, {__index=pawn,__newindex=function(_,k,v)pawn[k]=v end})
    local c=setmetatable({}, {__index=controller,__newindex=function(_,k,v)controller[k]=v end})
    isolate_native_input(p,c)
    assert(gNativePhysics==initialPhysics and gNativePhysics.rootMotionMode==2,
        "fresh wrappers for the same native pawn/controller reinitialized locomotion")
end
assert(UEHelpers.GetPlayerController():IsMoveInputIgnored(),"Eve input is still active")
assert(UEHelpers.GetPlayer().CharacterMovement.MaxWalkSpeed==0,"Eve locomotion is still active")
assert(UEHelpers.GetPlayer().JumpMaxCount==1,"MC must not trigger Eve double-jump")
assert(UEHelpers.GetPlayer().Mesh:GetAnimInstance().RootMotionMode==1,"Eve root motion can cancel MC launch velocity")
assert(not UEHelpers.GetPlayer().CharacterMovement.bUseJumpFallingGravityCurve,"Eve jump curve must not alter MC gravity")
assert(math.abs(UEHelpers.GetPlayer().CharacterMovement:GetGravityZ()+3200)<.001,"native world gravity must be normalized")
local off={X=gFrameOff.X,Y=gFrameOff.Y,Z=gFrameOff.Z}
local bx,by,bz=block_ue_center(12,106,56)
st.flags=265;apply_mc_transform()
assert(capsule_half_height(UEHelpers.GetPlayer())==75,"Shift did not shrink native capsule")
assert(UEHelpers.GetPlayer():K2_GetActorLocation().Z==75,"crouch moved native feet")
st.flags=257;apply_mc_transform()
assert(capsule_half_height(UEHelpers.GetPlayer())==90,"standing did not restore native capsule")
assert(UEHelpers.GetPlayer():K2_GetActorLocation().Z==90,"standing moved native feet")
for i=1,1000 do st.y=1000; apply_mc_transform() end
assert(gTeleportSerial==1, "height drift must not create a teleport storm")
st.flags=769; apply_mc_transform(); apply_mc_transform()
st.flags=257; apply_mc_transform()
local movement=UEHelpers.GetPlayer().CharacterMovement
movement.falling=true;st.flags=769;apply_mc_transform();apply_mc_transform()
st.flags=257;apply_mc_transform();movement.falling=false
st.vx=10;advanceTime(.05);apply_mc_transform();assert(gTeleportSerial==1,"wall rejection must not issue a teleport")
assert((gMotionFeedback.contacts&2)~=0,"wall rejection must report a collision")
st.vx=0
-- Identical intent must cover identical distance at different native frame rates.
st.vz=.1
local begin=UEHelpers.GetPlayer():K2_GetActorLocation().Y
for i=1,120 do advanceTime(1/120);apply_mc_transform() end
local distance120=UEHelpers.GetPlayer():K2_GetActorLocation().Y-begin
begin=UEHelpers.GetPlayer():K2_GetActorLocation().Y
for i=1,30 do advanceTime(1/30);apply_mc_transform() end
assert(math.abs(distance120-(UEHelpers.GetPlayer():K2_GetActorLocation().Y-begin))<.0001,"host cadence changed movement speed")
st.vz=0
for i=1,1000 do st.vx=10;advanceTime(1/120);apply_mc_transform() end
assert(gTeleportSerial==1,"sustained wall contact created a teleport loop")
st.vx=0
ack(); apply_mc_transform()
st.flags=385;advanceTime(.05);apply_mc_transform()
assert(movement.MovementMode==5 and movement.GravityScale==0,"creative flight did not disable native gravity")
st.flags=897
for i=1,40 do advanceTime(.05);apply_mc_transform() end
assert(UEHelpers.GetPlayer():K2_GetActorLocation().Z==300,"flight did not ascend to the native ceiling")
st.flags=385
for i=1,20 do advanceTime(.05);apply_mc_transform() end
assert(UEHelpers.GetPlayer():K2_GetActorLocation().Z==300,"flight drifted while hovering at ceiling")
st.flags=393
for i=1,40 do advanceTime(.05);apply_mc_transform() end
assert(UEHelpers.GetPlayer():K2_GetActorLocation().Z==90,"Shift did not descend to native ground")
st.flags=257;advanceTime(.05);apply_mc_transform()
assert(movement.MovementMode==3 and math.abs(movement:GetGravityZ()+3200)<.001,"ending flight did not restore calibrated falling")
st.motionMode=3;st.vy=.1;advanceTime(.05);apply_mc_transform()
assert(movement.MovementMode==5 and movement.GravityScale==0,"glide double-applied native gravity")
local glideZ=UEHelpers.GetPlayer():K2_GetActorLocation().Z
assert(glideZ>90,"glide discarded MC vertical intent")
st.vy=-1
for i=1,10 do advanceTime(.05);apply_mc_transform() end
assert(UEHelpers.GetPlayer():K2_GetActorLocation().Z==90 and (gMotionFeedback.contacts&5)==5,"glide landing did not return floor contact")
st.motionMode=0;st.vy=0;advanceTime(.05);apply_mc_transform()
assert(movement.MovementMode==3,"glide landing did not restore falling physics")
st.flags=385;advanceTime(.05);apply_mc_transform()
steveMode=false; apply_mc_transform()
assert(movement.MovementMode==3,"F4 left Eve in flight")
assert(not UEHelpers.GetPlayerController():IsMoveInputIgnored(),"F4 did not restore native input")
assert(UEHelpers.GetPlayer().CharacterMovement.MaxWalkSpeed==600,"F4 did not restore Eve movement")
assert(UEHelpers.GetPlayer().JumpMaxCount==2,"F4 did not restore Eve jumps")
assert(UEHelpers.GetPlayer().Mesh:GetAnimInstance().RootMotionMode==2,"F4 did not restore Eve root motion")
assert(UEHelpers.GetPlayer().CharacterMovement.bUseJumpFallingGravityCurve,"F4 did not restore Eve jump curve")
assert(UEHelpers.GetPlayer().CharacterMovement.GravityScale==1,"F4 did not restore Eve gravity")
local player=UEHelpers.GetPlayer(); local loc=player:K2_GetActorLocation()
player:K2_SetActorLocation({X=loc.X+250,Y=loc.Y+100,Z=loc.Z},true)
steveMode=true; apply_mc_transform()
assert(gFrameOff.X==off.X and gFrameOff.Y==off.Y and gFrameOff.Z==off.Z, "F4 must not recalibrate block origin")
local nx,ny,nz=block_ue_center(12,106,56)
assert(nx==bx and ny==by and nz==bz, "existing block moved after F4")
ack(); apply_mc_transform()
local expected=UEHelpers.GetPlayer():K2_GetActorLocation()
assert(math.abs(-(st.x-gFrameOff.X)*100-expected.X)<0.001, "re-entry did not align MC to Eve")
return true
]]
source=assert(source:gsub("init%(%)%s*$",tests))
assert(load(source,"@main.lua", "t",env))()
assert(jumps==1 and stops==3, "jump must occur once per grounded key edge; reject air/flight jumps")
assert(native.Z==90, "height drift buried the native capsule")
print("PASS: crouch/stand feet, height drift x1000, jump edges, flight ascent/hover/descent/native ceiling/floor, F4 restoration and re-entry")
