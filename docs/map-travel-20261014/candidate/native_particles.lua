-- Minecraft resource-pack sprite sequences, rendered by bounded UE mesh objects.
local Visuals=require("block_visuals")
local UEHelpers=require("UEHelpers")
local M={}
local dir="D:/SteamLibrary/steamapps/common/StellarBlade/SB/Binaries/Win64/ue4ss/Mods/StellarCraft/block-assets/"
local frames,live,pool,emitters={},{},{},{}
local created=0
local function sprites(kind)
    if frames[kind] then return frames[kind] end
    local file=io.open(dir..kind..".frames","r");if not file then return end
    local result={};for id in file:read("*a"):gmatch("%d+") do result[#result+1]=tonumber(id) end
    file:close();if #result==0 then return end;frames[kind]=result;return result
end
local function emit(world,kind,loc,velocity,scale,life)
    local models=sprites(kind);if not models then return end
    local s=table.remove(pool)
    if not s then
        if created>=64 then return end
        local a=world:SpawnActor(StaticFindObject("/Script/Engine.StaticMeshActor"),loc,{Pitch=0,Yaw=0,Roll=0})
        if not a or not a:IsValid() then return end
        a.StaticMeshComponent:SetMobility(2);a.StaticMeshComponent:SetCollisionEnabled(0)
        local ok,c=pcall(Visuals.attach_model,a,models[1],true)
        if not ok then a:K2_DestroyActor();return end
        if c then c:SetCollisionEnabled(0);c:SetCastShadow(false) end
        s={actor=a,component=c,model=models[1]};created=created+1
    end
    if not s.actor:IsValid() then created=created-1;return end
    if s.model~=models[1] then Visuals.replace_model(s.actor,s.component,models[1],true);s.model=models[1] end
    s.kind,s.models,s.loc,s.velocity,s.scale,s.life=kind,models,loc,velocity,scale,life
    s.born=os.clock();s.actor:K2_SetActorLocation(loc,false,{},true)
    s.actor:SetActorScale3D({X=scale,Y=scale,Z=scale})
    s.actor:SetActorHiddenInGame(false);live[#live+1]=s
end
local function wave(world,e)
    for i=1,6 do
        local angle=(i+e.wave*6)*2.399963
        local distance=(.15+(i%3)*.15)*e.radius*100
        emit(world,"explosion",{X=e.loc.X+math.cos(angle)*distance,Y=e.loc.Y+math.sin(angle)*distance,
            Z=e.loc.Z+(i%3-1)*e.radius*25},{X=0,Y=0,Z=0},4,.3+(i%4)*.05)
    end
    e.wave=e.wave+1
end
function M.explosion(world,loc,radius)
    if #emitters>=4 then return end
    local e={loc=loc,radius=math.max(1,math.min(8,radius)),wave=0,born=os.clock()}
    wave(world,e);emitters[#emitters+1]=e
    for i=1,8 do
        local a=i*2.399963
        emit(world,"smoke",loc,{X=math.cos(a)*90,Y=math.sin(a)*90,Z=60+(i%3)*25},1.5,1.1)
    end
end
function M.smoke(world,loc) emit(world,"smoke",loc,{X=0,Y=0,Z=30},.45,.8) end
function M.tick(world,at,controller)
    local now=os.clock()
    for i=#emitters,1,-1 do
        local e=emitters[i]
        if e.wave>=6 then table.remove(emitters,i)
        elseif now-e.born>=e.wave*.05 then wave(world,e) end
    end
    local pc=controller or UEHelpers.GetPlayerController()
    local camera=pc and pc:IsValid() and pc.PlayerCameraManager
    local rotation=camera and camera:IsValid() and camera:GetCameraRotation()
    for i=#live,1,-1 do
        local s=live[i];local age=now-s.born
        local distant=at and (s.loc.X-at.X)^2+(s.loc.Y-at.Y)^2+(s.loc.Z-at.Z)^2>4800^2
        if not s.actor:IsValid() or age>=s.life or distant then
            if s.actor:IsValid() then s.actor:SetActorHiddenInGame(true);pool[#pool+1]=s else created=created-1 end
            table.remove(live,i)
        else
            local model=s.models[math.min(#s.models,1+math.floor(age/s.life*#s.models))]
            if model~=s.model then
                Visuals.replace_model(s.actor,s.component,model,true);s.model=model
            end
            s.actor:K2_SetActorLocation({X=s.loc.X+s.velocity.X*age,Y=s.loc.Y+s.velocity.Y*age,
                Z=s.loc.Z+s.velocity.Z*age},false,{},true)
            local scale=s.scale*(s.kind=="smoke" and 1+age or 1+.35*age/s.life)
            s.actor:SetActorScale3D({X=scale,Y=scale,Z=scale})
            if rotation then s.actor:K2_SetActorRotation(rotation,true) end
        end
    end
end
function M.stats() return {live=#live,pooled=#pool,objects=created,emitters=#emitters} end
function M.forget_world()
    live,pool,emitters={},{},{};created=0
end
function M.reset()
    for _,list in ipairs({live,pool}) do for _,s in ipairs(list) do
        if s.actor:IsValid() then s.actor:K2_DestroyActor() end
    end end
    live,pool,emitters={}, {}, {};created=0
end
return M
