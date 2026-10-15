local file=assert(io.open(assert(arg[1]),"rb"));local text=file:read("*a");file:close()
local begin=assert(text:find("local gUidByName",1,true))
local finish=assert(text:find("-- =====================================================================================",begin,true))
local events,applied,published,registry={},0,{},{}
local exposure,lastDamage=0,0
local shieldOnly=false
local clock=0
local contexts={}
local actorClass="CH_NPC_Raven_Blueprint_C"
local actor={BroadcastSpawnInfo={ActorGuid=0,CharacterDataId=0},bCanBeDamaged=true,
    BodyCollision={GetScaledCapsuleRadius=function()return 200 end,GetScaledCapsuleHalfHeight=function()return 300 end}}
function actor:IsValid() return true end
function actor:GetAddress() return 123 end
function actor:GetClass() return {GetFName=function()return{ToString=function()return actorClass end}end} end
function actor:K2_GetActorLocation() return{X=0,Y=0,Z=300}end
function actor:K2_GetActorRotation()return{Yaw=0}end
local env=setmetatable({World={epoch=0,levelId=1234,accepts=function(_,epoch)return epoch==0 end},UNITS_PER_BLOCK=100,gFrameOff={X=0,Y=0,Z=0},gBuildWorldId=55,
    os={clock=function()return clock end},FindAllOf=function()return{actor}end,
    write_actor=function(...)published[#published+1]={...}end,set_actor_count=function(n,world)registry={n,world}end,
    native_enemy_damage=function(guid,kind,damage,budget,cause,address,x,y,z,serial,uid,world,pid,eventKind,weapon,seq,timeLow,dx,dy,dz)
        assert(guid==0 and kind==0 and address==123);if damage>0 then applied=applied+1;lastDamage=damage;assert(budget==200)end
        if x then contexts[#contexts+1]={x,y,z,serial,uid,world,pid,eventKind,weapon,seq,timeLow,dx,dy,dz}end
        local loss=damage/budget*(shieldOnly and 10000 or 130000)
        return{ready=true,applied=damage>0,guid=901,type=501,maximum=130000,before=130000,
            after=shieldOnly and 130000 or 130000-loss,shieldBefore=10000,
            shieldAfter=shieldOnly and 10000-loss or 10000,shieldMaximum=10000,
            damageRoute=shieldOnly and "shield" or "hp",sourceKey=70001}
    end,
    read_events=function()local x=events;events={};return x end,
    read_mc_state=function()return{mcPid=10,worldId=55,flags=257,seq=20}end,
    UEHelpers={GetPlayer=function()return{BroadcastSpawnInfo={ActorGuid=228},
        K2_GetActorLocation=function()return{X=100,Y=-200,Z=50}end}end},
    NativeWorld={melee_visible=function()return true end,native_exposure=function()return exposure end},
}, {__index=_G})
local characterClass={IsValid=function()return true end}
local gameplay={IsValid=function()return true end}
function gameplay:GetAllActorsOfClass(world,cls,out)
    assert(world and cls==characterClass)
    for _,value in ipairs(env.FindAllOf()) do
        out[#out+1]={get=function()return value end,IsValid=function()error("parameter is not a UObject")end}
    end
end
env.World.context={world={}}
env.StaticFindObject=function(path)return path=="/Script/Engine.Character" and characterClass or gameplay end
local body=text:sub(begin,finish-1).."\nreturn {mirror=mirror_actors,events=apply_events,class=is_enemy_class,hits=consume_native_hits}\n"
local api=assert(load(body,"combat routing","t",env))()
assert(api.class("CH_NPC_Raven_Blueprint_C") and not api.class("CH_NPC_Adam_Blueprint_C"))
api.mirror({X=0,Y=0,Z=0},{GetAddress=function()return 100 end})
assert(#published==1 and published[1][7]==4 and published[1][8]==6 and published[1][4]==0)
assert(registry[1]==1 and registry[2]==55)
local uid=published[1][1]
shieldOnly=true
events={{type=9,uid=uid,a=-1,b=.5,c=2,timeLow=100,flags=1,weapon=1},
    {type=1,uid=uid,a=7,d=0,flags=1,weapon=1},{type=1,uid=uid,a=7,d=0,flags=1,weapon=1}}
api.events();assert(applied==1,"repeated damage event must only apply once")
assert(#contexts==1 and contexts[1][1]==100 and contexts[1][2]==-200 and contexts[1][3]==50
    and contexts[1][4]==1 and contexts[1][5]==uid and contexts[1][6]==55 and contexts[1][7]==10
    and contexts[1][8]==1 and contexts[1][9]==1 and contexts[1][10]==20 and contexts[1][11]==100,
    "one successful melee event carries authoritative host origin and exact session")
shieldOnly=false
events={{type=6,uid=uid,a=56,b=1,c=2,d=3,flags=2,weapon=0}}
api.events();assert(applied==1,"fully occluded TNT must not damage")
assert(#contexts==1,"occluded TNT must not prepare a hit context")
shieldOnly=true
exposure=.5
events={{type=9,uid=uid,a=1,b=2,c=3,timeLow=101,flags=5,weapon=0},
    {type=6,uid=uid,a=22,b=1,c=2,d=3,flags=5,weapon=0}}
api.events();assert(applied==2 and math.abs(lastDamage-9.75)<1e-6,
    "MC exposure .5 and native exposure .5 must combine before the quadratic damage formula")
assert(#contexts==2 and contexts[2][1]==-100 and contexts[2][2]==-300 and contexts[2][3]==200
    and contexts[2][4]==5 and contexts[2][8]==6,"TNT centre uses the native XYZ mapping")
local hitFlags,hitDamage
local hit={id=1,time=1,player=228,source=1000020,damage=2,world=55,sourceGuid=901,sourceType=501,sourceX=100,sourceY=200,sourceZ=300}
env.nativeHits=function()return{hit}end
env.publishHit=function(id,time,player,source,damage,guid,x,y,z,flags)
    hitFlags,hitDamage=flags,damage;if flags==1 then assert(guid==901 and x==-1 and y==3 and z==-2)end
end
api.hits();assert(hitFlags==1,"unique verified source has a direction")
assert(hitDamage==2.5,"verified Alpha boss receives a 25 percent incoming bonus")
hit.damage=19;api.hits();assert(hitDamage==20,"boss bonus preserves the 20 HP cap");hit.damage=2
hit.sourceGuid=0;api.hits();assert(hitFlags==0,"raw effect key is never a direction identity")
assert(hitDamage==2,"unverified source receives no bonus")
hit.sourceGuid=901;hit.sourceType=502;api.hits();assert(hitFlags==0,"changed native type rejected")
assert(hitDamage==2,"mismatched source type receives no bonus")
hit.sourceType=501;clock=1;api.hits();assert(hitFlags==0,"stale enemy registry rejected")
assert(hitDamage==2,"expired source receives no bonus")
for _,case in ipairs({{"CH_M_NA_43_Blueprint_C",2.5},{"CH_M_NA_56_Blueprint_C",2.5},
    {"CH_M_NA_52_Blueprint_C",2},{"CH_M_NA_48_Blueprint_C",2},{"CH_M_NA_Unknown_Blueprint_C",2}}) do
    actorClass=case[1];clock=clock+.25
    api.mirror({X=0,Y=0,Z=0},{GetAddress=function()return 100 end})
    api.hits();assert(hitDamage==case[2],"incoming tier tuning: "..actorClass)
end
actorClass="CH_NPC_Raven_Blueprint_C"
env.FindAllOf=function()return{actor,actor,actor}end
clock=3;published={};api.mirror({X=0,Y=0,Z=0},{GetAddress=function()return 101 end})
assert(published[1][1]~=uid,"native world replacement must allocate a new identity")
api.hits();assert(hitFlags==0,"three actors sharing a source key must remain ambiguous")
assert(hitDamage==2,"ambiguous source receives no bonus")
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
local worldOps=0
env.enqueue_block_op=function()worldOps=worldOps+1 end
env.pearl_teleport=function()worldOps=worldOps+1 end
env.NativeWorld.explosion=function()worldOps=worldOps+1 end
for _,kind in ipairs({3,5,7,8}) do
    events={{type=kind,weapon=1233,a=0,b=0,c=0,d=0,flags=0,uid=0}};api.events()
    assert(worldOps==0,"late world event crossed native generation")
end
events={{type=5,weapon=1234,a=0,b=0,c=0,d=0,flags=0,uid=0}};api.events()
assert(worldOps==1,"current generation block event rejected")
env.FindAllOf=function()return {}end
actor.IsValid=function()error("freed cached actor touched")end
events={{type=1,uid=published[1][1],a=7,d=0,flags=12,weapon=1}};api.events()
assert(applied==8,"removed actor still received delayed damage")
gameplay.IsValid=function()return false end
clock=clock+1
assert(not pcall(api.mirror,{X=0,Y=0,Z=0},{GetAddress=function()return 101 end}) and registry[1]==0,
    "failed World query must clear the published target table")
print("PASS: unique damage/context retained; packed jump-heavy pairing, unknown bits and mismatched style exclusion; shield-only duplicate/arrow/heavy/TNT and existing directions")
