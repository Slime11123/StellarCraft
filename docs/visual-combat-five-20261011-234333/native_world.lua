-- Native entity geometry, collision feedback, dynamic MC lights and explosion particles.
local UEHelpers=require("UEHelpers")
local Visuals=require("block_visuals")
local M={}
local dir="D:/SteamLibrary/steamapps/common/StellarBlade/SB/Binaries/Win64/ue4ss/Mods/StellarCraft/block-assets/"
local readManifest=assert(package.loadlib(dir.."../stellarcraft_shm.dll","sc_read_entities_frame_lua"))
local readEntity=assert(package.loadlib(dir.."../stellarcraft_shm.dll","sc_read_entity_frame_lua"))
local entities,lights,fx,particlePool={},{},{},{}
local particleModel
local rocketModel,rocketPool,trail=nil,{},{}
local lastRead,lastPhysics,lastFeedback,feedbackSeq=0,0,0,0
local manifestSeq,manifestTime,lastSeen,entries=nil,0,0,{}
local worldAddress,buildWorld
local color={R=0,G=0,B=0,A=0}
local function to_ue(x,y,z,off) return {X=-(x-off.X)*100,Y=-(z-off.Z)*100,Z=(y-off.Y)*100} end
local function to_mc(loc,off) return -loc.X/100+off.X,loc.Z/100+off.Y,-loc.Y/100+off.Z end
local function destroy(actor) if actor and actor:IsValid() then pcall(function() actor:K2_DestroyActor() end) end end
local function clear()
    for _,s in pairs(entities) do destroy(s.actor) end
    for _,s in pairs(lights) do destroy(s.actor) end
    for _,s in ipairs(fx) do destroy(s.actor) end
    for _,actor in ipairs(particlePool) do destroy(actor) end
    for _,s in ipairs(trail) do destroy(s.actor) end
    for _,actor in ipairs(rocketPool) do destroy(actor) end
    rocketPool,trail={},{}
    entities,lights,fx,particlePool={},{},{},{};manifestSeq=nil;entries={};lastSeen=0
end
function M.reset() clear() end
function M.bind(pawn,worldId)
    local address=pawn:GetWorld():GetAddress()
    if worldAddress~=address or buildWorld~=worldId then clear();worldAddress=address;buildWorld=worldId end
end
local function spawn(world,loc)
    local actor=world:SpawnActor(StaticFindObject("/Script/Engine.StaticMeshActor"),loc,{Pitch=0,Yaw=0,Roll=0})
    assert(actor and actor:IsValid(),"native entity actor creation failed")
    actor.StaticMeshComponent:SetMobility(2);actor.StaticMeshComponent:SetCollisionEnabled(0)
    return actor
end
function M.light(pawn,off,x,y,z,emission,worldId,nativeOffset)
    if not off or not pawn or not pawn:IsValid() then return end
    M.bind(pawn,worldId)
    local key=string.format("%d:%d:%d",x,y,z)
    if emission<=0 then if lights[key] then destroy(lights[key].actor);lights[key]=nil end;return end
    local state=lights[key]
    if not state or not state.actor:IsValid() then
        local count=0;for _ in pairs(lights) do count=count+1 end
        if count>=64 then return end
        local actor=pawn:GetWorld():SpawnActor(StaticFindObject("/Script/Engine.PointLight"),to_ue(x+.5,y+.6+(nativeOffset or 0),z+.5,off),{Pitch=0,Yaw=0,Roll=0})
        assert(actor and actor:IsValid(),"native point light creation failed")
        actor.PointLightComponent:SetMobility(2)
        actor.PointLightComponent:SetCastShadows(false)
        actor.PointLightComponent:SetLightColor({R=1,G=.76,B=.48,A=1},false)
        state={actor=actor};lights[key]=state
    end
    state.actor.PointLightComponent:SetIntensity(1000*(emission/15)^2)
    state.actor.PointLightComponent:SetAttenuationRadius(100*(emission+1))
    state.emission=emission
    print(string.format("[StellarCraft-Light] %s emission=%d native PointLight\n",key,emission))
end
local function particle_model()
    if particleModel then return particleModel end
    local modelFile=io.open(dir.."explosion.model","r")
    if not modelFile then return end
    local model=tonumber(modelFile:read("*a"));modelFile:close()
    particleModel=model;return model
end
local function particle_actor(world,loc,model)
    local actor=spawn(world,loc)
    local ok,component=pcall(Visuals.attach_model,actor,model,true)
    if not ok then destroy(actor);print("[StellarCraft-TNT] particle mesh failed: "..tostring(component).."\n");return end
    if component then pcall(function() component:SetCastShadow(false) end) end
    return actor
end
function M.explosion(pawn,off,x,y,z,radius)
    if not pawn or not pawn:IsValid() or not off then return end
    local world=pawn:GetWorld();local centre=to_ue(x,y,z,off)
    local model=particle_model();if not model then return end
    local first=#fx+1
    for i=1,math.min(12,24-#fx) do
        local angle=i*2.399963;local speed=80+(i%7)*45
        local actor=table.remove(particlePool) or particle_actor(world,centre,model)
        if not actor then break end
        actor:K2_SetActorLocation(centre,false,{},true);actor:SetActorHiddenInGame(false)
        fx[#fx+1]={actor=actor,loc=centre,v={X=math.cos(angle)*speed,Y=math.sin(angle)*speed,Z=100+(i%5)*50},life=.9+(i%4)*.15}
    end
    -- Start the whole burst after its meshes are ready, including a cold texture import.
    local born=os.clock()
    for i=first,#fx do fx[i].born=born end
    print(string.format("[StellarCraft-TNT] native explosion particles=%d at %.2f,%.2f,%.2f\n",#fx-first+1,x,y,z))
end
local function feedback(rows,time,world)
    feedbackSeq=feedbackSeq+1
    local file=io.open(dir.."entities-feedback."..(feedbackSeq%2)..".txt","w")
    if not file then return end
    file:write(string.format("SCFEED2\n%d %.0f %.0f %d\n",feedbackSeq,time,world,#rows))
    for _,row in ipairs(rows) do file:write(row.."\n") end
    file:write("END "..feedbackSeq.."\n");file:close()
end
function M.tick(pawn,off,worldId)
    if not pawn or not pawn:IsValid() or not off or not worldId then return end
    local world=pawn:GetWorld();M.bind(pawn,worldId)
    local now=os.clock()
    for i=#trail,1,-1 do
        local s=trail[i];local age=now-s.born
        if age>.35 then
            s.actor:SetActorHiddenInGame(true);rocketPool[#rocketPool+1]=s.actor;table.remove(trail,i)
        else
            s.actor:SetActorScale3D({X=1-age/.35,Y=1-age/.35,Z=1-age/.35})
            local pc=UEHelpers.GetPlayerController()
            if pc and pc:IsValid() then s.actor:K2_SetActorRotation(pc.PlayerCameraManager:GetCameraRotation(),true) end
        end
    end
    -- Render interpolation runs at host cadence; native collision traces run at 25 Hz.
    for _,s in pairs(entities) do
        if s.loc and s.from and s.actor:IsValid() then
            local alpha=math.min(1,(now-s.moved)/.04)
            s.actor:K2_SetActorLocation({X=s.from.X+(s.loc.X-s.from.X)*alpha,Y=s.from.Y+(s.loc.Y-s.from.Y)*alpha,Z=s.from.Z+(s.loc.Z-s.from.Z)*alpha},false,{},true)
            local id=s.id
            Visuals.update_entity(s.actor,s,function() return readEntity(id,worldId,s.uuid) end)
        end
    end
    if now-lastRead>=.025 then
        lastRead=now;local bytes=readManifest()
        if bytes and #bytes>=32 then
            local seq,time,count,at=string.unpack("<I4I8I4",bytes,9)
            local marker,frameWorld=string.unpack("<I4I4",bytes,at)
            if count>=1 and count<=33 and count%2==1 and marker==0 and frameWorld==worldId and #bytes==32+count*44 and os.time()*1000-time<2000 and seq~=manifestSeq then
                local parsed={}
                at=at+44
                for i=2,count,2 do
                    local id,x,y,z,vx,vy,vz,width,height,kind,fuse
                    id,x,y,z,vx,vy,vz,width,height,kind,fuse,at=string.unpack("<I4ffffffffff",bytes,at)
                    local uuid=bytes:sub(at+4,at+19);at=at+44
                    parsed[#parsed+1]={id=id,uuid=uuid,x=x,y=y,z=z,vx=vx,vy=vy,vz=vz,width=width,height=height,kind=kind,fuse=fuse}
                end
                entries=parsed;manifestSeq=seq;manifestTime=time;lastSeen=now
            end
        end
    end
    if now-lastSeen>.4 then entries={} end
    if now-lastPhysics<.04 then return end
    lastPhysics=now
    local glib=StaticFindObject("/Script/Engine.Default__KismetSystemLibrary")
    local ignored={pawn};for _,state in pairs(entities) do ignored[#ignored+1]=state.actor end
    local seen,rows={},{}
    for _,e in ipairs(entries) do
        seen[e.id]=true
        local desired=to_ue(e.x,e.y,e.z,off);local s=entities[e.id]
        if s and s.uuid~=e.uuid then destroy(s.actor);entities[e.id]=nil;s=nil end
        if not s or not s.actor:IsValid() then s={id=e.id,uuid=e.uuid,actor=spawn(world,desired),faces={},lastSeen=0,lastRead=0};entities[e.id]=s end
        local previous=s.loc or desired;local adjusted={X=desired.X,Y=desired.Y,Z=desired.Z}
        local half=math.max(8,e.height*50);local radius=math.max(4,math.min(half,e.width*48))
        local hit={};local changed,ground=false,false
        local found=glib:SphereTraceSingle(pawn,{X=previous.X,Y=previous.Y,Z=previous.Z+half},
            {X=desired.X,Y=desired.Y,Z=desired.Z+half},radius,0,false,ignored,0,hit,true,color,color,0)
        if found and hit.Location then
            adjusted={X=hit.Location.X,Y=hit.Location.Y,Z=hit.Location.Z-half};changed=true
            if hit.Normal and hit.Normal.Z>.65 then ground=true end
        end
        if e.kind~=3 and e.kind~=4 then
            local floor={}
            if glib:LineTraceSingle(pawn,{X=adjusted.X,Y=adjusted.Y,Z=math.max(previous.Z,adjusted.Z)+60},
                {X=adjusted.X,Y=adjusted.Y,Z=adjusted.Z-200},0,false,ignored,0,floor,true,color,color,0)
                and floor.Location and floor.Normal and floor.Normal.Z>.65 and adjusted.Z<=floor.Location.Z+2 then
                adjusted.Z=floor.Location.Z+1;changed=true;ground=true
            end
        end
        if not s.loc or math.abs(s.loc.X-adjusted.X)+math.abs(s.loc.Y-adjusted.Y)+math.abs(s.loc.Z-adjusted.Z)>.01 then
            s.from=s.actor:K2_GetActorLocation();s.loc=adjusted;s.moved=now
        end
        if e.kind==4 and now-(s.tailAt or 0)>.05 and #trail<16 then
            if not rocketModel then
                local file=io.open(dir.."firework.model","r")
                if file then rocketModel=tonumber(file:read("*a"));file:close() end
            end
            if rocketModel then
                local spark=table.remove(rocketPool) or particle_actor(world,adjusted,rocketModel)
                if spark then
                    spark:K2_SetActorLocation(adjusted,false,{},true);spark:SetActorHiddenInGame(false)
                    trail[#trail+1]={actor=spark,born=now};s.tailAt=now
                end
            end
        end
        local x,y,z=to_mc(adjusted,off)
        local vx,vy,vz=e.vx,e.vy,e.vz
        if changed then
            if math.abs(adjusted.X-desired.X)>.1 then vx=0 end
            if math.abs(adjusted.Y-desired.Y)>.1 then vz=0 end
            if ground then vy=0 end
        end
        local playerLoc=pawn:K2_GetActorLocation()
        local near=(adjusted.X-playerLoc.X)^2+(adjusted.Y-playerLoc.Y)^2+(adjusted.Z-playerLoc.Z)^2<400^2
        local visible=false
        if near then
            local wall={}
            visible=not glib:LineTraceSingle(pawn,{X=adjusted.X,Y=adjusted.Y,Z=adjusted.Z+half},
                playerLoc,0,false,ignored,0,wall,true,color,color,0)
        end
        local uuidHigh,uuidLow=string.unpack("<i8i8",e.uuid)
        rows[#rows+1]=string.format("%d %.6f %.6f %.6f %.6f %.6f %.6f %d %d %d %016x %016x",e.id,x,y,z,vx,vy,vz,ground and 1 or 0,changed and 1 or 0,visible and 1 or 0,uuidHigh,uuidLow)
    end
    for id,s in pairs(entities) do if not seen[id] then destroy(s.actor);entities[id]=nil end end
    if now-lastFeedback>=.04 and manifestTime>0 then lastFeedback=now;feedback(rows,manifestTime,worldId) end
    for i=#fx,1,-1 do
        local s=fx[i];local age=now-s.born
        if age>s.life or not s.actor:IsValid() then
            if s.actor:IsValid() and #particlePool<12 then s.actor:SetActorHiddenInGame(true);particlePool[#particlePool+1]=s.actor
            else destroy(s.actor) end
            table.remove(fx,i)
        else
            s.actor:K2_SetActorLocation({X=s.loc.X+s.v.X*age,Y=s.loc.Y+s.v.Y*age,Z=s.loc.Z+s.v.Z*age-80*age*age},false,{},true)
            local pc=UEHelpers.GetPlayerController()
            if pc and pc:IsValid() then
                -- MC uses a separate CameraActor; Eve's control rotation can point elsewhere.
                local camera=pc.PlayerCameraManager
                local rotation=camera and camera:IsValid() and camera:GetCameraRotation() or pc:GetControlRotation()
                s.actor:K2_SetActorRotation(rotation,true)
            end
            -- explosion_0's opaque centre occupies only 7.5 cm of the exported quad.
            local scale=6+age*10;s.actor:SetActorScale3D({X=scale,Y=scale,Z=scale})
        end
    end
    -- Prepare at most one hidden mesh per physics tick; reuse it at the next explosion.
    if #particlePool+#fx<12 then
        local model=particle_model()
        if model then
            local actor=particle_actor(world,pawn:K2_GetActorLocation(),model)
            if actor then actor:SetActorHiddenInGame(true);particlePool[#particlePool+1]=actor end
        end
    end
    -- Keep distant lights cheap without losing their placed-block state.
    local at=pawn:K2_GetActorLocation()
    for _,s in pairs(lights) do
        local loc=s.actor:K2_GetActorLocation();local visible=(loc.X-at.X)^2+(loc.Y-at.Y)^2+(loc.Z-at.Z)^2<2400^2
        if s.visible~=visible then s.actor.PointLightComponent:SetVisibility(visible,false);s.visible=visible end
    end
end
return M
