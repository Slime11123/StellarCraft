from pathlib import Path
root=Path(__file__).resolve().parents[1]
def read(p):return (root/p).read_text(encoding='utf-8')
def write(p,s):(root/p).write_text(s,encoding='utf-8')
def edit(p,a,b):
 s=read(p);assert a in s,(p,a[:60]);write(p,s.replace(a,b))
base='mc-mod/src/main/java/dev/stellarcraft/'
p=base+'world/ActorProxy.java';s=read(p)
s=s.replace('private float mirroredHealth = 1.0f;', '''private float mirroredHealth = 1.0f;
    private int nativePid,nativeWorld;
    private static final EntityDataAccessor<Float> WIDTH=SynchedEntityData.defineId(ActorProxy.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HEIGHT=SynchedEntityData.defineId(ActorProxy.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> FLAGS=SynchedEntityData.defineId(ActorProxy.class,EntityDataSerializers.INT);
    public int nativePid() {return nativePid;}
    public int nativeWorld() {return nativeWorld;}
    public boolean isNativeLive() {return !isRemoved()&&mirroredHealth>0&&(entityData.get(FLAGS)&18)==0;}
    public void syncNative(int pid,dev.stellarcraft.link.SharedMemory.ActorSnapshot a) {
        nativePid=pid;nativeWorld=a.world;setSbUid(a.uid);
        entityData.set(WIDTH,a.width);entityData.set(HEIGHT,a.height);entityData.set(FLAGS,a.flags);
        syncFrom(a.x,a.y,a.z,a.yaw,a.width,a.height,a.healthFrac);
    }
    @Override public net.minecraft.world.entity.EntityDimensions getDimensions(net.minecraft.world.entity.Pose pose) {
        return net.minecraft.world.entity.EntityDimensions.scalable(entityData.get(WIDTH),entityData.get(HEIGHT));
    }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);if(key==WIDTH||key==HEIGHT) refreshDimensions();
    }
    @Override public void tick() {super.tick();setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);}
    @Override public boolean shouldBeSaved() {return false;}
    @Override protected void actuallyHurt(DamageSource source,float amount) {
        DamageBridge.forward(this,source,amount);
    }''')
s=s.replace('p.sbUid = uid;', 'p.setSbUid(uid);p.noPhysics=true;p.setNoGravity(true);\n        p.entityData.set(WIDTH,width);p.entityData.set(HEIGHT,height);')
s=s.replace('this.entityData.define(DATA_SB_UID, -1);','this.entityData.define(DATA_SB_UID, -1);\n        this.entityData.define(WIDTH,.6f);this.entityData.define(HEIGHT,1.8f);this.entityData.define(FLAGS,0);')
s=s.replace('public int sbUid() { return sbUid; }','public int sbUid() { return entityData.get(DATA_SB_UID); }')
begin=s.index('        // 1.20.1: hitbox');end=s.index('        this.mirroredHealth',begin)
s=s[:begin]+'        refreshDimensions();\n'+s[end:]
begin=s.index('        // Forward to SB;');end=s.index('\n    public void applyMirroredHurt',begin)
s=s[:begin]+'''        if(level().isClientSide||!NativeActors.current(this)||!Float.isFinite(amount)||amount<=0
            ||!dev.stellarcraft.client.NativeMovement.owns(source.getEntity())) return false;
        // Vanilla hurt handles cooldown/i-frames and attack feedback; actuallyHurt
        // forwards its final amount without maintaining a second native HP pool.
        return super.hurt(source,amount);
    }
'''+s[end:]
s=s.replace('public boolean isInvulnerable() { return true; }','public boolean isInvulnerable() { return false; }')
write(p,s)

p=base+'link/SharedMemory.java';s=read(p)
s=s.replace('private final ByteBuffer actorTable;', 'private final ByteBuffer actorTable;\n    private final ByteBuffer combat;')
s=s.replace('this.mapping = mapping;', 'this.mapping = mapping;\n        this.combat = slice(Proto.OFF_COMBAT,Proto.COMBAT_BYTES);')
s=s.replace('a.level      = actorTable.getShort(base + Proto.AR_LEVEL);', '''a.level      = actorTable.getShort(base + Proto.AR_LEVEL);
        a.guid=actorTable.getInt(base+40);a.nativeType=actorTable.getInt(base+44);
        a.world=actorTable.getInt(base+48);a.generation=actorTable.getInt(base+52);''')
s=s.replace('public int uid, flags;', 'public int uid, flags,guid,nativeType,world,generation;')
at=s.index('    // ---- Rings (raw byte access;')
s=s[:at]+'''    public java.util.List<ActorSnapshot> readActors(int world) {
        int seq=actorTable.getInt(0);if((seq&1)!=0) return null;
        java.lang.invoke.VarHandle.acquireFence();
        long time=actorTable.getLong(8),now=dev.stellarcraft.NativeLink.tickCount();
        if(actorTable.getInt(16)!=sbPid()||actorTable.getInt(20)!=world||time>now||now-time>500) return java.util.List.of();
        int count=actorCount();if(count<0||count>Proto.MAX_ACTORS) return null;
        var rows=new java.util.ArrayList<ActorSnapshot>();for(int i=0;i<count;i++) rows.add(readActor(i));
        java.lang.invoke.VarHandle.acquireFence();return seq==actorTable.getInt(0)?rows:null;
    }
    public record CombatHit(long id,long time,int player,int rawSource,int world,float damage,int sourceGuid,
                            boolean directional,double x,double y,double z) {}
    public long combatHead() {return combat.getLong(0);}
    public CombatHit readCombatHit(long id) {
        int at=0x80+(int)(id%Proto.COMBAT_SLOTS)*Proto.COMBAT_HIT_SIZE;
        int seq=combat.getInt(at);if((seq&1)!=0) return null;
        java.lang.invoke.VarHandle.acquireFence();
        long actual=combat.getLong(at+8),time=combat.getLong(at+16),now=dev.stellarcraft.NativeLink.tickCount();
        int player=combat.getInt(at+24),raw=combat.getInt(at+28),pid=combat.getInt(at+32),world=combat.getInt(at+36);
        float damage=combat.getFloat(at+40);int guid=combat.getInt(at+44);
        double x=combat.getDouble(at+48),y=combat.getDouble(at+56),z=combat.getDouble(at+64);
        boolean direction=(combat.getInt(at+4)&1)!=0;
        java.lang.invoke.VarHandle.acquireFence();
        if(seq!=combat.getInt(at)||actual!=id||pid!=sbPid()||time>now||now-time>500||player<=0
            ||!Float.isFinite(damage)||damage<=0||damage>20
            ||direction&&(guid<=0||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))) return null;
        return new CombatHit(id,time,player,raw,world,damage,guid,direction,x,y,z);
    }

'''+s[at:]
write(p,s)

p=base+'client/ClientBridge.java';s=read(p)
start=s.index('    private final Map<Integer, ActorProxy> proxies');end=s.index('    private int lastTeleportSeq',start)
s=s[:start]+'    private long combatCursor;\n\n'+s[end:]
s=s.replace('damagePrimed=false;', 'damagePrimed=false;combatCursor=mem.combatHead();dev.stellarcraft.world.NativeActors.clear();')
start=s.index('        var hurt=mem.readPlayerHurt();');end=s.index('        // Cache SB player anchor',start)
s=s[:start]+'''        long head=mem.combatHead();
        if(head<combatCursor) combatCursor=head;
        combatCursor=Math.max(combatCursor,head-Proto.COMBAT_SLOTS);
        while(combatCursor<head) {
            var hit=mem.readCombatHit(++combatCursor);
            if(hit!=null&&nativeScene&&ControlStateMachine.isSteve()&&mc.player!=null
                &&hit.world()==dev.stellarcraft.world.PlacedBlocks.worldId()) DamageBridge.applyNativeHit(mc.player,hit);
        }

'''+s[end:]
start=s.index('        int count = mem.actorCount();');end=s.index('\n    /**',start)
s=s[:start]+'''        var actors=mem.readActors(dev.stellarcraft.world.PlacedBlocks.worldId());
        if(actors!=null) dev.stellarcraft.world.NativeActors.publish(mem.sbPid(),actors);
    }
'''+s[end:]
write(p,s)
edit(base+'StellarCraftMod.java','dev.stellarcraft.world.NativeEntities.init();','dev.stellarcraft.world.NativeEntities.init();\n        dev.stellarcraft.world.NativeActors.init();')

p=base+'world/mixin/TerrainTargetMixin.java';s=read(p)
at=s.index('        // Re-pick native entities explicitly:')
s=s[:at]+'''        for(var proxy:mc.level.getEntitiesOfClass(dev.stellarcraft.world.ActorProxy.class,camera.getBoundingBox().inflate(reach+32))) {
            if(!proxy.isNativeLive()) continue;
            var intersection=proxy.getBoundingBox().inflate(.1).clip(start,end);
            if(intersection.isPresent()&&start.distanceToSqr(intersection.get())<nearest) {
                nearest=start.distanceToSqr(intersection.get());current=new net.minecraft.world.phys.EntityHitResult(proxy,intersection.get());
            }
        }
'''+s[at:]
s=s.replace('&&dev.stellarcraft.world.NativeEntities.owns(entity.getEntity())&&start.distanceToSqr(current.getLocation())<=nearest)', '&&(entity.getEntity() instanceof dev.stellarcraft.world.ActorProxy||dev.stellarcraft.world.NativeEntities.owns(entity.getEntity()))&&start.distanceToSqr(current.getLocation())<=nearest)')
write(p,s)

p=base+'world/NativeEntities.java';s=read(p)
start=s.index('        if(System.currentTimeMillis()-targetsAt<250) for(var e:nativeTargets)');end=s.index('        if(target!=null)',start)
s=s[:start]+'''        for(var proxy:arrow.level().getEntitiesOfClass(ActorProxy.class,new AABB(start,end).inflate(32))) {
            if(!NativeActors.current(proxy)||hits.contains(proxy.getUUID())) continue;
            var at=proxy.getBoundingBox().inflate(.15).clip(start,end);
            if(at.isPresent()&&at.get().distanceToSqr(start)<nearest) {nearest=at.get().distanceToSqr(start);target=proxy;}
        }
'''+s[end:]
write(p,s)

p='mc-mod/build-dx12-jar.ps1';s=read(p)
s=s.replace("$sources += @('dev\\stellarcraft\\world\\ActorProxy.java')", "$sources += @('dev\\stellarcraft\\world\\ActorProxy.java','dev\\stellarcraft\\world\\NativeActors.java','dev\\stellarcraft\\world\\ExplosionDamageSink.java','dev\\stellarcraft\\world\\mixin\\ExplosionMixin.java')")
start=s.index('$bridgeBytecode =');s=s[:start]+'''$bridgeBytecode = (& $javap -classpath $result -p -c dev.stellarcraft.world.NativeActors) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Proxy bytecode inspection failed' }
if ($bridgeBytecode -match 'ActorProxy\\.(isRemoved|discard):') { throw 'Inherited ActorProxy calls were not remapped' }
if ($bridgeBytecode -notmatch 'ActorProxy\\.method_31481:\\(\\)Z' -or $bridgeBytecode -notmatch 'ActorProxy\\.method_31472:\\(\\)V') { throw 'Missing proxy intermediary calls' }
Write-Output 'PASS: server proxy lifecycle uses intermediary names'
Write-Output $result
'''
write(p,s)
