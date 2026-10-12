-- Equipment/cube-to-quad changes must not blend incompatible mesh-local poses.
local source=assert(io.open(arg[1],"rb")):read("*a")
local time,sequence,frame,generation=0,0,nil,0
local transform,calls
local valid={IsValid=function() return true end}
local component=setmetatable({SetCollisionEnabled=function() end,SetCastShadow=function() end,
    ClearAllMeshSections=function() end,CreateMeshSection=function() end,SetMaterial=function() end,
    SetVisibility=function() end,SetHiddenInGame=function() end,
    K2_SetRelativeTransform=function(_,t) transform=t;calls=(calls or 0)+1 end}, {__index=valid})
local lib=setmetatable({TextureParameterValues={ForEach=function(_,fn)
    fn(nil,{get=function() return {ParameterInfo={Name={ToString=function() return "Basecolor_Texture" end},Association=0,Index=0}} end})
end},CreateDynamicMaterialInstance=function(self) return self end,ImportFileAsTexture2D=function(self) return self end,
    SetTextureParameterValueByInfo=function() end,SetTextureParameterValue=function() end,
    K2_GetTextureParameterValue=function(self) return self end,GetFullName=function() return "stub" end}, {__index=valid})
local owner=setmetatable({GetAddress=function() return 100 end,GetWorld=function() return {} end,
    AddComponentByClass=function() return component end}, {__index=valid})
local env=setmetatable({require=function() return {FindOrAddFName=function(name) return name end} end,
    print=function() end,StaticFindObject=function() return lib end,
    package={loadlib=function() return function() return frame end end},
    io={open=function()
        local lines,index={"SCBLOCK1","1","test.png","0"},0
        return {read=function() index=index+1;return lines[index] end,close=function() end}
    end},os={clock=function() return time end,time=os.time}}, {__index=_G})
local visuals=assert(load(source,"@block_visuals.lua","t",env))()
local state={faces={},lastSeen=0,lastRead=0}
local function publish(model,x,scale)
    time=time+.02;sequence=sequence+1
    local serial=sequence|(generation<<24)
    frame="SCPOSE1\n"..string.pack("<I4I8I4",serial,os.time()*1000+math.floor(time*1000),1)
        ..string.pack("<I4ffffffffff",model,x,0,0,0,0,0,1,scale,1,1)..string.pack("<I4I4",serial,0)
    visuals.update_entity(owner,state,function() return frame end)
end
publish(1,0,1)
publish(2,100,50)
assert(transform.Translation.X==100 and transform.Scale3D.X==50,"New mesh used the previous mesh's basis")
publish(2,200,50)
assert(transform.Translation.X==100,"Same-mesh animation must still interpolate")
time=time+.008;visuals.update_entity(owner,state,function() return frame end)
assert(transform.Translation.X>100 and transform.Translation.X<200,"Same-mesh interpolation stopped")
publish(3,transform.Translation.X+.01,50)
assert(transform.Translation.X==state.faces[1].target.Translation.X,"Model switch retained an old animation target")
for i=1,100 do
    publish(i%2+1,i,1+i%2*50)
    assert(transform.Translation.X==i and transform.Scale3D.X==1+i%2*50,"Equipment switch stretched the replacement mesh")
end
generation=7 -- the first replacement frame was dropped; its next frame still carries this generation
publish(1,999,1)
assert(transform.Translation.X==999,"Dropped topology frame blended unrelated same-ID components")
generation=8;publish(1,888,1)
assert(transform.Translation.X==888,"Topology change with an unchanged model ID did not snap")
print("PASS: model replacement snaps its own pose; same-model animation interpolates; 100 equipment switches")
