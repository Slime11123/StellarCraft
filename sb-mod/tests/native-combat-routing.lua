local file=assert(io.open(assert(arg[1]),"rb"));local text=file:read("*a");file:close()
local begin=assert(text:find("local gUidByName",1,true))
local finish=assert(text:find("-- =====================================================================================",begin,true))
local events,applied,published,registry={},0,{},{}
local exposure,lastDamage=0,0
local clock=0
local actor={BroadcastSpawnInfo={ActorGuid=0,CharacterDataId=0},bCanBeDamaged=true,
    BodyCollision={GetScaledCapsuleRadius=function()return 200 end,GetScaledCapsuleHalfHeight=function()return 300 end}}
function actor:IsValid() return true end
function actor:GetAddress() return 123 end
function actor:GetClass() return {GetFName=function()return{ToString=function()return "CH_NPC_Raven_Blueprint_C"end}end} end
function actor:K2_GetActorLocation() return{X=0,Y=0,Z=300}end
function actor:K2_GetActorRotation()return{Yaw=0}end
local env=setmetatable({UNITS_PER_BLOCK=100,gFrameOff={X=0,Y=0,Z=0},gBuildWorldId=55,
    os={clock=function()return clock end},FindAllOf=function()return{actor}end,
    write_actor=function(...)published[#published+1]={...}end,set_actor_count=function(n,world)registry={n,world}end,
    native_enemy_damage=function(guid,kind,damage,budget,cause,address)
        assert(guid==0 and kind==0 and address==123);if damage>0 then applied=applied+1;lastDamage=damage;assert(budget==200)end
        return{ready=true,guid=901,type=501,maximum=130000,before=130000,after=130000-damage/budget*130000,sourceKey=70001}
    end,
    read_events=function()local x=events;events={};return x end,
    read_mc_state=function()return{mcPid=10,worldId=55,flags=257}end,
    UEHelpers={GetPlayer=function()return{BroadcastSpawnInfo={ActorGuid=228}}end},
    NativeWorld={melee_visible=function()return true end,native_exposure=function()return exposure end},
}, {__index=_G})
local body=text:sub(begin,finish-1).."\nreturn {mirror=mirror_actors,events=apply_events,class=is_enemy_class,hits=consume_native_hits}\n"
local api=assert(load(body,"combat routing","t",env))()
assert(api.class("CH_NPC_Raven_Blueprint_C") and not api.class("CH_NPC_Adam_Blueprint_C"))
api.mirror({X=0,Y=0,Z=0},{GetAddress=function()return 100 end})
assert(#published==1 and published[1][7]==4 and published[1][8]==6 and published[1][4]==0)
assert(registry[1]==1 and registry[2]==55)
local uid=published[1][1]
events={{type=1,uid=uid,a=7,d=0,flags=1,weapon=1},{type=1,uid=uid,a=7,d=0,flags=1,weapon=1}}
api.events();assert(applied==1,"repeated damage event must only apply once")
events={{type=6,uid=uid,a=56,b=1,c=2,d=3,flags=2,weapon=0}}
api.events();assert(applied==1,"fully occluded TNT must not damage")
exposure=.5
events={{type=6,uid=uid,a=22,b=1,c=2,d=3,flags=5,weapon=0}}
api.events();assert(applied==2 and math.abs(lastDamage-9.75)<1e-6,
    "MC exposure .5 and native exposure .5 must combine before the quadratic damage formula")
local hitFlags
env.nativeHits=function()return{{id=1,time=1,player=228,source=70001,damage=2,world=55}}end
env.publishHit=function(id,time,player,source,damage,guid,x,y,z,flags)hitFlags=flags end
api.hits();assert(hitFlags==1,"unique verified source has a direction")
env.FindAllOf=function()return{actor,actor,actor}end
clock=3;published={};api.mirror({X=0,Y=0,Z=0},{GetAddress=function()return 101 end})
assert(published[1][1]~=uid,"native world replacement must allocate a new identity")
api.hits();assert(hitFlags==0,"three actors sharing a source key must remain ambiguous")
events={{type=1,uid=uid,a=7,d=0,flags=3,weapon=1}};api.events();assert(applied==2,"old world uid rejected")
env.read_mc_state=function()return{mcPid=10,worldId=56,flags=257}end
events={{type=1,uid=published[1][1],a=7,d=0,flags=4,weapon=1}};api.events();assert(applied==2,"foreign MC world rejected")
print("PASS: Raven whitelist/NPC exclusion, capsule size, damage replay, combined TNT exposure, ambiguous source rejection, world identity")
