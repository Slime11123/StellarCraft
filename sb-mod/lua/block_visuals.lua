-- Native UE procedural meshes with Minecraft's baked UVs and resource-pack textures.
local UEHelpers = require("UEHelpers")
local M = {}
local assetDir = "D:/SteamLibrary/steamapps/common/StellarBlade/SB/Binaries/Win64/ue4ss/Mods/StellarCraft/block-assets/"
local readHandFrame = assert(package.loadlib(assetDir .. "../stellarcraft_shm.dll", "sc_read_hand_frame_lua"))
local readBodyFrame = assert(package.loadlib(assetDir .. "../stellarcraft_shm.dll", "sc_read_body_frame_lua"))
local models, materials, textures = {}, {}, {}
local parent, colorParameter, parentParameters = nil, nil, nil

-- This ordinary furniture surface was verified with MC pixel meshes in Xion.
-- Load the same asset on every map; a road/restroom shader can transform TexCoord0.
local parentPath="/Game/Art/BG/Actor/Deco/Furniture_003/039_B_MI.039_B_MI"
local function find_parent()
    if parent and parent:IsValid() then return parent end
    parent=StaticFindObject(parentPath)
    if not parent or not parent:IsValid() then parent=LoadAsset(parentPath) end
    assert(parent and parent:IsValid(),"MC material asset unavailable: "..parentPath)
    parentParameters={}
    parent.TextureParameterValues:ForEach(function(_,wrapper)
        local info=wrapper:get().ParameterInfo
        local parameter={name=info.Name:ToString(),association=info.Association,index=info.Index}
        parentParameters[#parentParameters+1]=parameter
        if parameter.name=="Basecolor_Texture" then colorParameter=parameter end
    end)
    assert(colorParameter,"MC material has no Basecolor_Texture slot")
    print("[StellarCraft] fixed MC material parent="..parent:GetFullName().."\n")
    return parent
end

local function texture(world, filename)
    if textures[filename] and textures[filename]:IsValid() then return textures[filename] end
    local lib = StaticFindObject("/Script/Engine.Default__KismetRenderingLibrary")
    local tex = lib:ImportFileAsTexture2D(world, assetDir .. filename)
    if not tex or not tex:IsValid() then error("Texture import failed: " .. filename) end
    pcall(function() tex.Filter = 0; tex.NeverStream = true end) -- TF_Nearest: preserve Minecraft pixel edges.
    textures[filename] = tex
    return tex
end

local function material(world, filename)
    if materials[filename] and materials[filename]:IsValid() then return materials[filename] end
    local lib = StaticFindObject("/Script/Engine.Default__KismetMaterialLibrary")
    local mid = lib:CreateDynamicMaterialInstance(world, find_parent(), UEHelpers.FindOrAddFName("None"), 0)
    if not mid or not mid:IsValid() then error("Material creation failed") end
    for _, parameter in ipairs(parentParameters) do
        local low = parameter.name:lower()
        local file = parameter == colorParameter and filename or nil
        if low:find("normal", 1, true) then file = "sc-flat-normal.png"
        elseif low:find("orm", 1, true) then file = "sc-neutral-orm.png"
        elseif low:find("emissive", 1, true) then file = "sc-black.png" end
        if file then
            local info = { Name = UEHelpers.FindOrAddFName(parameter.name), Association = parameter.association, Index = parameter.index }
            mid:SetTextureParameterValueByInfo(info, texture(world, file))
        end
    end
    -- Inherited slots may not occur in the child MI's TextureParameterValues array.
    for _, name in ipairs({"Normal_Texture", "NormalTexture", "Normal_Map"}) do
        mid:SetTextureParameterValue(UEHelpers.FindOrAddFName(name), texture(world, "sc-flat-normal.png"))
    end
    for _, name in ipairs({"Emissive_Texture", "EmissiveTexture"}) do
        mid:SetTextureParameterValue(UEHelpers.FindOrAddFName(name), texture(world, "sc-black.png"))
    end
    local bound = mid:K2_GetTextureParameterValue(UEHelpers.FindOrAddFName(colorParameter.name))
    print("[StellarCraft] block texture " .. filename .. " bound=" .. (bound and bound:IsValid() and bound:GetFullName() or "invalid") .. "\n")
    materials[filename] = mid
    return mid
end

local function read_model(id)
    if models[id] then return models[id] end
    local filename = assetDir .. string.format("%.0f", id) .. ".scmesh"
    local file = assert(io.open(filename, "r"), "Missing model: " .. filename)
    local sections = {}
    local ok, err = pcall(function()
        assert(file:read("*l") == "SCBLOCK1", "Unsupported native model format")
        local count = assert(tonumber(file:read("*l")))
        assert(count > 0 and count <= 128, "Invalid material count")
        for section = 1, count do
            local data = { filename = assert(file:read("*l")), vertices = {}, uv = {}, normals = {}, triangles = {}, colors = {}, tangents = {} }
            local quads = assert(tonumber(file:read("*l")))
            assert(quads >= 0 and quads <= 131072, "Invalid quad count")
            for quad = 1, quads do
                local values = {}
                for token in assert(file:read("*l")):gmatch("%S+") do values[#values + 1] = assert(tonumber(token)) end
                assert(#values == 23, "Invalid baked quad")
                local base = #data.vertices
                for vertex = 0, 3 do
                    local i = vertex * 5
                    data.vertices[#data.vertices + 1] = { X = values[i + 1], Y = values[i + 2], Z = values[i + 3] }
                    data.uv[#data.uv + 1] = { X = values[i + 4], Y = values[i + 5] }
                    data.normals[#data.normals + 1] = { X = values[21], Y = values[22], Z = values[23] }
                    data.colors[#data.colors + 1] = { R = 255, G = 255, B = 255, A = 255 }
                    local nx, ny, nz = values[21], values[22], values[23]
                    local tx, ty, tz = -ny, nx, 0
                    if nx * nx + ny * ny < 0.01 then tx, ty, tz = 1, 0, 0 end
                    data.tangents[#data.tangents + 1] = { TangentX = { X = tx, Y = ty, Z = tz }, bFlipTangentY = false }
                end
                for _, index in ipairs({0, 1, 2, 0, 2, 3}) do data.triangles[#data.triangles + 1] = base + index end
            end
            sections[#sections + 1] = data
        end
    end)
    file:close()
    if not ok then error(err) end
    models[id] = sections
    return sections
end

function M.attach(actor, cube, modelId, doubleSided)
    if not modelId or modelId == 0 then error("MC did not publish a block model") end
    local sections = read_model(modelId)
    local cls = StaticFindObject("/Script/ProceduralMeshComponent.ProceduralMeshComponent")
    assert(cls and cls:IsValid(), "ProceduralMeshComponent unavailable")
    local transform = { Rotation = { X = 0, Y = 0, Z = 0, W = 1 }, Translation = { X = 0, Y = 0, Z = 0 }, Scale3D = { X = 1, Y = 1, Z = 1 } }
    print("[StellarCraft] block visual: add procedural component\n")
    local component = actor:AddComponentByClass(cls, false, transform, false)
    assert(component and component:IsValid(), "Failed to create procedural component")
    for index, section in ipairs(sections) do
        local triangles = section.triangles
        if doubleSided then
            triangles = {}
            for i = 1, #section.triangles, 3 do
                local a, b, c = section.triangles[i], section.triangles[i+1], section.triangles[i+2]
                for _, vertex in ipairs({a,b,c,c,b,a}) do triangles[#triangles+1] = vertex end
            end
        end
        -- Both corrected world and hand mappings reflect handedness into UE's front winding.
        component:CreateMeshSection(index - 1, section.vertices, triangles, section.normals,
            section.uv, section.colors, section.tangents, false)
        component:SetMaterial(index - 1, material(actor:GetWorld(), section.filename))
    end
    -- Keep native cube collision, hide only its visual; the procedural child stays visible.
    cube:SetHiddenInGame(true, false)
    print(string.format("[StellarCraft] block visual ready model=%.0f sections=%d\n", modelId, #sections))
    return component
end

local hands = {faces={},lastSeen=0,lastRead=0}
local body = {faces={},lastSeen=0,lastRead=0}

local function blend_pose(a,b,t)
    local out={Translation={},Rotation={},Scale3D={}}
    for _,axis in ipairs({"X","Y","Z"}) do
        out.Translation[axis]=a.Translation[axis]+(b.Translation[axis]-a.Translation[axis])*t
        out.Scale3D[axis]=a.Scale3D[axis]+(b.Scale3D[axis]-a.Scale3D[axis])*t
    end
    local dot=0
    for _,axis in ipairs({"X","Y","Z","W"}) do dot=dot+a.Rotation[axis]*b.Rotation[axis] end
    local sign=dot<0 and -1 or 1
    local length=0
    for _,axis in ipairs({"X","Y","Z","W"}) do
        local q=a.Rotation[axis]+(b.Rotation[axis]*sign-a.Rotation[axis])*t
        out.Rotation[axis]=q;length=length+q*q
    end
    length=math.sqrt(length)
    for _,axis in ipairs({"X","Y","Z","W"}) do out.Rotation[axis]=out.Rotation[axis]/math.max(length,0.00001) end
    return out
end

local function hide_pose(state)
    if state.hidden then return end
    for _, face in ipairs(state.faces) do
        if face.component and face.component:IsValid() then face.component:SetVisibility(false, true) end
        face.visible=false
    end
    state.hidden=true
end

local function update_pose(owner, state, reader, isBody, firstPerson)
    if not owner or not owner:IsValid() then return end
    local address=owner:GetAddress()
    if state.ownerAddress ~= address then
        hide_pose(state)
        state.owner,state.sequence,state.faces=owner,nil,{}
        state.ownerAddress=address
        state.lastRead=0
    end
    local now=os.clock()
    if now-state.lastRead >= 0.012 then
        state.lastRead=now;state.bytes=reader()
    end
    local bytes=state.bytes
    if not bytes then
        if now-state.lastSeen>0.3 then hide_pose(state) end
        return
    end
    assert(#bytes>=32 and bytes:sub(1,8)=="SCPOSE1\n", "Invalid native pose")
    local sequence,timestamp,count,offset=string.unpack("<I4I8I4",bytes,9)
    assert(count<=2048 and #bytes==32+count*44,"Invalid native face count")
    if os.time()*1000-timestamp>2000 then hide_pose(state);return end
    local changed=sequence~=state.sequence
    if changed then
        state.interval=math.max(0.008,math.min(0.1,(timestamp-(state.timestamp or timestamp-16))/1000))
        state.timestamp=timestamp;state.sequence,state.lastSeen=sequence,now
    end
    if not changed and now-state.lastSeen>0.3 then hide_pose(state);return end
    state.firstPerson,state.hidden=firstPerson,false
    local cls=StaticFindObject("/Script/ProceduralMeshComponent.ProceduralMeshComponent")
    for index=1,count do
        local poseBytes=bytes:sub(offset,offset+43)
        local model,x,y,z,qx,qy,qz,qw,sx,sy,sz
        model,x,y,z,qx,qy,qz,qw,sx,sy,sz,offset=string.unpack("<I4ffffffffff",bytes,offset)
        local transform={Rotation={X=qx,Y=qy,Z=qz,W=qw},Translation={X=x,Y=y,Z=z},Scale3D={X=sx,Y=sy,Z=sz}}
        local face=state.faces[index]
        if not face or not face.component or not face.component:IsValid() then
            local component=owner:AddComponentByClass(cls,false,transform,false)
            assert(component and component:IsValid(),"Native pose component creation failed")
            component:SetCollisionEnabled(0)
            component:SetCastShadow(isBody)
            if isBody then pcall(function() component.bCastHiddenShadow=true end) end
            face={component=component};state.faces[index]=face
        end
        if face.model ~= model then
            face.component:ClearAllMeshSections()
            for section,data in ipairs(read_model(model)) do
                face.component:CreateMeshSection(section-1,data.vertices,data.triangles,data.normals,data.uv,data.colors,data.tangents,false)
                face.component:SetMaterial(section-1,material(owner:GetWorld(),data.filename))
            end
            face.model=model
        end
        -- Ignore sub-pixel idle changes instead of crossing UE reflection for each face.
        local previous=face.transform
        local dirty=not previous
            or math.abs(x-previous.Translation.X)+math.abs(y-previous.Translation.Y)+math.abs(z-previous.Translation.Z)>0.25
            or math.abs(qx-previous.Rotation.X)+math.abs(qy-previous.Rotation.Y)+math.abs(qz-previous.Rotation.Z)+math.abs(qw-previous.Rotation.W)>0.001
            or math.abs(sx-previous.Scale3D.X)+math.abs(sy-previous.Scale3D.Y)+math.abs(sz-previous.Scale3D.Z)>0.001
        if face.pose ~= poseBytes and dirty then
            face.from=face.transform or transform;face.target=transform;face.started=now
            face.pose=poseBytes;face.alpha=nil
        end
        if face.target then
            local alpha=math.min(1,(now-face.started)/(state.interval or 0.016))
            if face.alpha~=1 or alpha~=1 then
                face.transform=blend_pose(face.from,face.target,alpha)
                face.component:K2_SetRelativeTransform(face.transform,false,{},false)
                face.alpha=alpha
            end
        end
        if not face.visible then face.component:SetVisibility(true,true);face.visible=true end
        local hidden=isBody and firstPerson or false
        if face.hidden~=hidden then face.component:SetHiddenInGame(hidden,false);face.hidden=hidden end
    end
    for index=count+1,#state.faces do
        if state.faces[index].visible then state.faces[index].component:SetVisibility(false,true);state.faces[index].visible=false end
    end
    if changed and state.count~=count then
        state.count=count
        print(string.format("[StellarCraft] native %s faces=%d; lighting=StellarBlade\n",isBody and "body" or "hand",count))
    end
end

function M.update_hand(camera) update_pose(camera,hands,readHandFrame,false,false) end

function M.update_entity(actor,state,reader) update_pose(actor,state,reader,true,false) end

function M.attach_model(actor,modelId,doubleSided)
    return M.attach(actor,actor.StaticMeshComponent,modelId,doubleSided)
end

function M.hide_body()
    hide_pose(body)
    if body.actor and body.actor:IsValid() then body.actor:SetActorHiddenInGame(true);body.actorHidden=true end
end

function M.update_body(pawn,cameraMode,halfHeight)
    if not pawn or not pawn:IsValid() then return end
    local loc=pawn:K2_GetActorLocation()
    loc={X=loc.X,Y=loc.Y,Z=loc.Z-halfHeight}
    local pawnAddress=pawn:GetAddress()
    if body.pawnAddress~=pawnAddress or not body.actor or not body.actor:IsValid() then
        if body.actor and body.actor:IsValid() then body.actor:K2_DestroyActor() end
        local cls=StaticFindObject("/Script/Engine.StaticMeshActor")
        body.actor=pawn:GetWorld():SpawnActor(cls,loc,{Pitch=0,Yaw=0,Roll=0})
        assert(body.actor and body.actor:IsValid(),"Native player actor creation failed")
        local root=body.actor.StaticMeshComponent
        root:SetMobility(2);root:SetCollisionEnabled(0)
        body.pawnAddress=pawnAddress;body.location=nil;body.actorHidden=nil
        print("[StellarCraft] native body actor created for pawn="..tostring(pawnAddress).."\n")
    end
    if not body.location or math.abs(loc.X-body.location.X)+math.abs(loc.Y-body.location.Y)+math.abs(loc.Z-body.location.Z)>0.01 then
        body.actor:K2_SetActorLocation(loc,false,{},true);body.location=loc
    end
    if body.actorHidden~=false then body.actor:SetActorHiddenInGame(false);body.actorHidden=false end
    update_pose(body.actor,body,readBodyFrame,true,cameraMode==0)
end

return M
