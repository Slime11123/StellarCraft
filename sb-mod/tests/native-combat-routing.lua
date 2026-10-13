local file=assert(io.open(assert(arg[1]),"rb"));local text=file:read("*a");file:close()
local begin=assert(text:find("local gUidByName",1,true))
local finish=assert(text:find("-- =====================================================================================",begin,true))
local events,applied,published,registry={},0,{},{}
local exposure,lastDamage=0,0
local clock=0
local contexts={}
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
    native_enemy_damage=function(guid,kind,damage,budget,cause,address,x,y,z,serial,uid,world,pid,eventKind,weapon,seq,timeLow,dx,dy,dz)
        assert(guid==0 and kind==0 and address==123);if damage>0 then applied=applied+1;lastDamage=damage;assert(budget==200)end
        if x then contexts[#contexts+1]={x,y,z,serial,uid,world,pid,eventKind,weapon,seq,timeLow,dx,dy,dz}end
        return{ready=true,applied=damage>0,guid=901,type=501,maximum=130000,before=130000,after=130000-damage/budget*130000,sourceKey=70001}
    end,
    read_events=function()local x=events;events={};return x end,
    read_mc_state=function()return{mcPid=10,worldId=55,flags=257,seq=20}end,
    UEHelpers={GetPlayer=function()return{BroadcastSpawnInfo={ActorGuid=228},
        K2_GetActorLocation=function()return{X=100,Y=-200,Z=50}end}end},
    NativeWorld={melee_visible=function()return true end,native_exposure=function()return exposure end},
}, {__index=_G})
local body=text:sub(begin,finish-1).."\nreturn {mirror=mirror_actors,events=apply_events,class=is_enemy_class,hits=consume_native_hits}\n"
local api=assert(load(body,"combat routing","t",env))()
assert(api.class("CH_NPC_Raven_Blueprint_C") and not api.class("CH_NPC_Adam_Blueprint_C"))
api.mirror({X=0,Y=0,Z=0},{GetAddress=function()return 100 end})
assert(#published==1 and published[1][7]==4 and published[1][8]==6 and published[1][4]==0)
assert(registry[1]==1 and registry[2]==55)
local uid=published[1][1]
events={{type=9,uid=uid,a=-1,b=.5,c=2,timeLow=100,flags=1,weapon=1},
    {type=1,uid=uid,a=7,d=0,flags=1,weapon=1},{type=1,uid=uid,a=7,d=0,flags=1,weapon=1}}
api.events();assert(applied==1,"repeated damage event must only apply once")
assert(#contexts==1 and contexts[1][1]==100 and contexts[1][2]==-200 and contexts[1][3]==50
    and contexts[1][4]==1 and contexts[1][5]==uid and contexts[1][6]==55 and contexts[1][7]==10
    and contexts[1][8]==1 and contexts[1][9]==1 and contexts[1][10]==20 and contexts[1][11]==100,
    "one successful melee event carries authoritative host origin and exact session")
events={{type=6,uid=uid,a=56,b=1,c=2,d=3,flags=2,weapon=0}}
api.events();assert(applied==1,"fully occluded TNT must not damage")
assert(#contexts==1,"occluded TNT must not prepare a hit context")
exposure=.5
events={{type=9,uid=uid,a=1,b=2,c=3,timeLow=101,flags=5,weapon=0},
    {type=6,uid=uid,a=22,b=1,c=2,d=3,flags=5,weapon=0}}
api.events();assert(applied==2 and math.abs(lastDamage-9.75)<1e-6,
    "MC exposure .5 and native exposure .5 must combine before the quadratic damage formula")
assert(#contexts==2 and contexts[2][1]==-100 and contexts[2][2]==-300 and contexts[2][3]==200
    and contexts[2][4]==5 and contexts[2][8]==6,"TNT centre uses the native XYZ mapping")
local hitFlags
local hit={id=1,time=1,player=228,source=1000020,damage=2,world=55,sourceGuid=901,sourceType=501,sourceX=100,sourceY=200,sourceZ=300}
env.nativeHits=function()return{hit}end
env.publishHit=function(id,time,player,source,damage,guid,x,y,z,flags)
    hitFlags=flags;if flags==1 then assert(guid==901 and x==-1 and y==3 and z==-2)end
end
api.hits();assert(hitFlags==1,"unique verified source has a direction")
hit.sourceGuid=0;api.hits();assert(hitFlags==0,"raw effect key is never a direction identity")
hit.sourceGuid=901;hit.sourceType=502;api.hits();assert(hitFlags==0,"changed native type rejected")
hit.sourceType=501;clock=1;api.hits();assert(hitFlags==0,"stale enemy registry rejected")
env.FindAllOf=function()return{actor,actor,actor}end
clock=3;published={};api.mirror({X=0,Y=0,Z=0},{GetAddress=function()return 101 end})
assert(published[1][1]~=uid,"native world replacement must allocate a new identity")
api.hits();assert(hitFlags==0,"three actors sharing a source key must remain ambiguous")
events={{type=1,uid=uid,a=7,d=0,flags=3,weapon=1}};api.events();assert(applied==2,"old world uid rejected")
env.read_mc_state=function()return{mcPid=10,worldId=56,flags=257}end
events={{type=1,uid=published[1][1],a=7,d=0,flags=4,weapon=1}};api.events();assert(applied==2,"foreign MC world rejected")
env.read_mc_state=function()return{mcPid=10,worldId=55,flags=257,seq=20}end
events={{type=1,uid=published[1][1],a=7,d=0,flags=6,weapon=5}};api.events()
assert(applied==3 and #contexts==2,"unproven arrow origin keeps damage but does not invent a direction")
events={{type=9,uid=published[1][1],a=0,b=0,c=0,timeLow=102,flags=7,weapon=5},
    {type=10,uid=published[1][1],a=0,b=0,c=1,timeLow=102,flags=7,weapon=5},
    {type=1,uid=published[1][1],a=7,d=0,flags=7,weapon=5}};api.events()
assert(applied==4 and #contexts==3 and contexts[3][11]==102 and contexts[3][12]==0
    and contexts[3][13]==-1 and contexts[3][14]==0,"confirmed arrow trajectory reaches native coordinates")
events={{type=9,uid=published[1][1],a=0,b=0,c=0,timeLow=103,flags=8,weapon=5},
    {type=10,uid=published[1][1],a=0,b=0,c=1,timeLow=104,flags=8,weapon=5},
    {type=1,uid=published[1][1],a=7,d=0,flags=8,weapon=5}};api.events()
assert(applied==5 and #contexts==3,"mismatched capture timestamp rejects animation context only")
events={{type=9,uid=published[1][1],a=0,b=0,c=0,timeLow=105,flags=9,weapon=0x101},
    {type=1,uid=published[1][1],a=7,d=0,flags=9,weapon=0x101}};api.events()
assert(applied==6 and #contexts==4 and contexts[4][9]==0x101,"packed heavy source stays paired with its unique HP event")
events={{type=9,uid=published[1][1],a=0,b=0,c=0,timeLow=106,flags=10,weapon=0x201},
    {type=1,uid=published[1][1],a=7,d=0,flags=10,weapon=0x201}};api.events()
assert(applied==7 and #contexts==4,"unknown style rejects only the visual context")
events={{type=9,uid=published[1][1],a=0,b=0,c=0,timeLow=107,flags=11,weapon=0x101},
    {type=1,uid=published[1][1],a=7,d=0,flags=11,weapon=1}};api.events()
assert(applied==8 and #contexts==4,"packed style mismatch does not match a different origin")
print("PASS: unique damage/context retained; packed jump-heavy pairing, unknown bits and mismatched style exclusion; existing arrow/TNT directions")
