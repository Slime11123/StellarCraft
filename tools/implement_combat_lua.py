from pathlib import Path
p=Path('sb-mod/lua/main.lua');s=p.read_text(encoding='utf-8')
s=s.replace('return string.find(name, "CH_M_NA", 1, true) ~= nil', '''local lower=string.lower(name)
    return string.find(name, "CH_M_NA", 1, true) ~= nil
        or lower=="ch_npc_raven_blueprint_c" or lower=="ch_npc_raven_c" or lower=="ch_npc_raven"''')
s=s.replace('local gActorCandidates, gActorWorld, gActorRefresh = nil, nil, -10', '''local gActorCandidates, gActorWorld, gActorRefresh = nil, nil, -10
local gCombatSources={}
local nativeHits=assert(package.loadlib(DLL_PATH,"sc_native_hits_lua"))
local publishHit=assert(package.loadlib(DLL_PATH,"sc_combat_hit_lua"))
local function enemy_bounds(actor)
    local radius,half
    for _,name in ipairs({"BodyCollision","CapsuleComponent"}) do
        local ok,r,h=pcall(function()
            local component=actor[name]
            return component:GetScaledCapsuleRadius(),component:GetScaledCapsuleHalfHeight()
        end)
        if ok and type(r)=="number" and type(h)=="number" and r>0 and r<=1600 and h>0 and h<=1600 then radius,half=r,h;break end
    end
    return radius and radius*2/100 or .6,half and half*2/100 or 1.8,half or 90
end
local function consume_native_hits()
    local rows=nativeHits()
    for _,hit in ipairs(rows) do
        if hit.world==gBuildWorldId then
            local source=gCombatSources[hit.source]
            local x,y,z,guid,flags=0,0,0,0,0
            if source and source~=false and os.clock()-source.time<.5 then
                -- +0x104 source keys are certified in the executable. Revalidate
                -- the corresponding server identity; the integer is never treated as a GUID.
                local stats=enemy_stats(source.actor)
                if stats and stats.ready and stats.sourceKey==hit.source then
                    local loc=source.actor:K2_GetActorLocation()
                    x,y,z=-loc.X/100+gFrameOff.X,loc.Z/100+gFrameOff.Y,-loc.Y/100+gFrameOff.Z
                    guid,flags=source.guid,1
                end
            end
            publishHit(hit.id,hit.time,hit.player,hit.source,hit.damage,guid,x,y,z,flags,hit.world)
        end
    end
end''')
s=s.replace('local name = clsName .. "@" .. addr', 'local info=actor.BroadcastSpawnInfo\n                        local name = tostring(worldAddress)..":"..tostring(gBuildWorldId)..":"..clsName .. "@" .. addr..":"..info.ActorGuid..":"..info.CharacterDataId')
s=s.replace('    local count = 0\n    gActorsByName = {}','    local count = 0\n    set_actor_count(0)\n    gActorsByName = {}\n    gCombatSources={}')
a=s.index('                        write_actor(uid, 0, mx, my, mz');b=s.index('                        if count >= 256 then break end',a)
s=s[:a]+'''                        if stats and stats.ready and stats.maximum>0 and stats.after>0 then
                            local width,height,half=enemy_bounds(actor)
                            local flags=1
                            local tier=resolve_enemy(actor,clsName)
                            if tier=="Alpha" or tier=="Elder" then flags=flags|4 end
                            local okDamage,canDamage=pcall(function() return actor.bCanBeDamaged end)
                            if stats.invulnerable or okDamage and canDamage==false then flags=flags|16 end
                            write_actor(uid,flags,mx,my-half/100,mz,yaw,width,height,healthFrac,
                                info.ActorGuid,info.CharacterDataId,gBuildWorldId or 0,uid)
                            gActorsByName[uid]=actor
                            if stats.sourceKey and stats.sourceKey>0 then
                                if gCombatSources[stats.sourceKey] then gCombatSources[stats.sourceKey]=false
                                else gCombatSources[stats.sourceKey]={actor=actor,guid=info.ActorGuid,time=now} end
                            end
                            count=count+1
                        end
'''+s[b:]
s=s.replace('    set_actor_count(count)','    set_actor_count(count,gBuildWorldId or 0)')
s=s.replace('local gLastEventLog = 0','local gLastEventLog = 0\nlocal gDamageSerial,gDamagePid=0,0')
a=s.index('            local actor = gActorsByName[ev.uid]');b=s.index('                -- Log sparingly:',a)
s=s[:a]+'''            local actor = gActorsByName[ev.uid]
            local mc=read_mc_frame and read_mc_frame()
            local serial=math.floor(ev.flags or 0)
            if mc and mc.mcPid~=gDamagePid then gDamageSerial,gDamagePid=0,mc.mcPid end
            local fresh=mc and mc.worldId==gBuildWorldId and mc.flags&256~=0
            if actor and fresh and serial>gDamageSerial then
                gDamageSerial=serial
                local damage=ev.a
                if t==6 then
                    damage=damage*NativeWorld.native_exposure(UEHelpers.GetPlayer(),actor,gFrameOff,ev.b,ev.c,ev.d)
                elseif ev.weapon~=5 and not NativeWorld.melee_visible(UEHelpers.GetPlayer(),actor) then damage=0 end
                local applied, nativeDmg, tier, maxHp = damage_actor(actor, damage, ev.d)
'''+s[b:]
# publish per-hit events after fresh registry sampling
s=s.replace('    ts("events")','    consume_native_hits()\n    ts("events")') if '    ts("events")' in s else s
idx=s.index('    local okEv, errEv') if '    local okEv, errEv' in s else -1
if '    consume_native_hits()' not in s:
    target='    pcall(apply_events)'
    assert target in s
    s=s.replace(target,'    consume_native_hits()\n'+target)
p.write_text(s,encoding='utf-8')
