-- Real main.lua and lifecycle gate, with poisoned old UObject wrappers. No game.
local root=assert(arg[1],"Lua source directory required")
local source=assert(io.open(root.."/main.lua","rb")):read("*a")
local Lifecycle=assert(loadfile(root.."/world_lifecycle.lua"))()
local hooks,queued={},{}
local loop,key,clears,writes,oldCalls=nil,nil,0,{},0
local scans=0
local nativeWorld={forget_world=function()clears=clears+1 end}
local visuals={forget_world=function()clears=clears+1 end}
local currentWorld={IsValid=function()return true end,GetAddress=function()return 100 end,
    GetFullName=function()return "World /Game/Map.SameMap" end}
local currentPawn={IsValid=function()return true end,GetAddress=function()return 200 end,
    GetWorld=function()return currentWorld end,
    GetClass=function()return{GetFName=function()return{ToString=function()return "CH_P_EVE_01_Blueprint_C" end}end}end}
local pc={IsValid=function()return true end,IsLocalPlayerController=function()return true end,
    GetWorld=function()return currentWorld end,GetAddress=function()return 300 end,Pawn=currentPawn}
local exports={sc_create_mapping_lua=function()return true end,sc_set_control_lua=function()return 0 end,
    sc_write_player_lua=function(...)writes[#writes+1]={...}end,
    sc_read_events_lua=function()return {} end,sc_set_actor_count_lua=function()end,
    sc_world_barrier_lua=function()end}
local poison=setmetatable({}, {__index=function()oldCalls=oldCalls+1;error("old UObject accessed")end})
local env=setmetatable({
    require=function(name)if name=="world_lifecycle" then return Lifecycle elseif name=="native_world" then return nativeWorld
        elseif name=="block_visuals" then return visuals else return {} end end,
    package={loadlib=function(_,name)return exports[name]end},
    RegisterLoadMapPreHook=function(cb)hooks.pre=cb end,
    RegisterInitGameStatePostHook=function(cb)hooks.ready=cb end,
    RegisterHook=function(name,cb,post)hooks[name]=post or cb end,
    RegisterKeyBind=function(_,cb)key=cb end,Key={F4=115},
    LoopAsync=function(_,cb)loop=cb end,ExecuteInGameThread=function(cb)queued[#queued+1]=cb end,
    FindAllOf=function()scans=scans+1;return {pc}end,print=function()end,
},{__index=_G})
local apiSource=source:gsub("\ninit%(%)%s*$",[[
return {init=init,prepare=prepare_world,suspend=suspend_world,world=World,uid=uid_for,
    seed=function(p)
        gBlockActors={p};gGroundIgnore={p};gGroundIgnorePawn=p;gCapsulePawn=p
        gMcCamera=p;gCameraController=p;gPreviousViewTarget=p
        gNativeInput={pc=p};gNativePhysics={pawn=p};gBlockMesh=p;gBlockClass=p
    end,
    empty=function()return next(gBlockActors)==nil and #gBlockQueue==0 and gMcCamera==nil
        and gGroundIgnore==nil and gCapsulePawn==nil and gNativeInput==nil end}
]])
local api=assert(load(apiSource,"actual main lifecycle","t",env))()
api.init();assert(loop and hooks.pre and hooks.ready)
assert(api.world.phase=="REBINDING" and api.prepare())
local epoch,token=api.world.epoch,api.world.levelId
local mapId=api.world.context.mapId
assert(mapId~=0 and api.prepare() and api.world.context.mapId==mapId,"active refresh lost stable map id")
local scansBefore=scans
for i=1,1000 do assert(api.prepare()) end
assert(scans==scansBefore,"active ticks globally enumerate controllers")
assert(api.world:accepts(epoch) and token~=0)
local uid=api.uid("same actor at same address")
api.seed(poison);loop();assert(#queued==1)
hooks.pre();assert(api.world.phase=="WAITING_NEW_WORLD" and api.world.levelId==0)
assert(api.empty() and oldCalls==0 and not api.world:accepts(epoch))
local n=#writes;key();assert(#writes==n,"F4 while unloading must not publish control")
queued[1]();assert(oldCalls==0 and #writes==n,"queued old tick must do zero work")
local unloaded=api.world.epoch;hooks.pre();assert(api.world.epoch==unloaded,"repeat unload is idempotent")
assert(not api.prepare(),"waiting must not poll old World")
assert(writes[#writes][1]==0 and writes[#writes][13]==0,"inactive clears IN_GAME and level")
-- Reuse every native address and the map name. Epoch and public token still change.
hooks.ready({get=function()return{GetWorld=function()return currentWorld end}end})
assert(api.prepare() and api.world.epoch~=epoch and api.world.levelId~=token)
assert(api.world.context.mapId==mapId,"same-map reload must preserve map id")
assert(api.uid("same actor at same address")>uid,"old damage UID cannot identify a new actor")
assert(oldCalls==0 and clears>=4)
queued={};loop();assert(#queued==1)
hooks.pre();hooks.ready({get=function()return{GetWorld=function()return currentWorld end}end})
assert(api.prepare());loop();assert(#queued==2,"old pending task stalled the new world")
n=#writes;queued[1]();loop()
assert(#queued==2 and #writes==n,"late old completion cleared the new pending task")
hooks["/Script/Engine.Actor:ReceiveEndPlay"]({get=function()return currentPawn end})
assert(api.world.phase=="WAITING_NEW_WORLD")
hooks["/Script/Engine.PlayerController:ClientRestart"]({get=function()return pc end})
assert(api.prepare(),"new pawn must rebind after EndPlay/ClientRestart")
hooks["/Script/Engine.Actor:ReceiveEndPlay"]({get=function()return pc end})
assert(api.world.phase=="WAITING_NEW_WORLD","controller teardown did not invalidate cached context")
hooks["/Script/Engine.PlayerController:ClientRestart"]({get=function()return pc end})
assert(api.prepare())
-- A post-init notification for a different world cannot bind this controller.
hooks.pre();hooks.ready({get=function()return{GetWorld=function()return{IsValid=function()return true end,GetAddress=function()return 999 end}end}end})
assert(not api.prepare() and api.world.phase=="REBINDING")
-- Missing required DLL export fails closed before starting the scheduler.
exports.sc_world_barrier_lua=nil;loop=nil
local blocked=assert(load(apiSource,"missing bridge","t",env))();blocked.init()
assert(loop==nil and blocked.world.phase=="NO_WORLD")
print("PASS: actual main lifecycle; no old UObject calls; old scheduled tick/F4 rejected; repeat unload; same-address/map reuse; UID/token isolation; wrong-world and missing-export rejection")
