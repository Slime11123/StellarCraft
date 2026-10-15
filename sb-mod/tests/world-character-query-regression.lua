-- Real query implementation. The global UObject scan and Level.Actors access are poisoned.
local source=assert(io.open(assert(arg[1]),"rb")):read("*a")
local first=assert(source:find("local function collect_character_actors",1,true))
local last=assert(source:find("local function enemy_stats",first,true))
local globalCalls,queryCalls=0,0
local valid=true
local character={IsValid=function()return true end}
local gameplay={IsValid=function()return valid end}
function gameplay:GetAllActorsOfClass(world,cls,out)
    assert(cls==character and world)
    queryCalls=queryCalls+1
    -- d3d1004 ArrayProperty uses Operation::GetParam for each ObjectProperty.
    -- It returns RemoteUnrealParam, not the Actor. Only :get() yields a UObject.
    for _,actor in ipairs(world.rows) do
        out[#out+1]={get=function()return actor end,IsValid=function()error("parameter is not an Actor")end}
    end
end
local a,b={IsValid=function()return true end},{IsValid=function()return true end}
local worldA=setmetatable({rows={a}}, {__index=function()error("Level.Actors must not be polled")end})
local worldB={rows={b}}
local env=setmetatable({FindAllOf=function()globalCalls=globalCalls+1;error("global UObject scan")end,
    StaticFindObject=function(path)return path=="/Script/Engine.Character" and character or gameplay end}, {__index=_G})
local query=assert(load(source:sub(first,last-1).."\nreturn collect_character_actors","actual World actor query","t",env))()
for i=1,1000 do assert(query(worldA)[1]==a) end
assert(query(worldB)[1]==b and globalCalls==0 and queryCalls==1001,"query crossed World or used global scan")
worldA.rows={};assert(#query(worldA)==0,"empty world must stay empty without fallback")
worldA.rows={b};assert(query(worldA)[1]==b,"fresh World result was replaced by cached Actor")
worldA.rows={{IsValid=function()return false end}};assert(#query(worldA)==0,"invalid unwrapped actor retained")
local before=queryCalls;valid=false
assert(query(worldA)==nil and query(nil)==nil and queryCalls==before and globalCalls==0)
valid=true;gameplay.GetAllActorsOfClass=function()error("reflection query rejected")end
assert(query(worldA)==nil and globalCalls==0,"query failure must not reintroduce a heavy fallback")
print("PASS: 1000 live World queries with zero global scans; distinct/empty/changed World results; unavailable/failed query rejects without stale Actors or fallback")
