package dev.stellarcraft.world;

import dev.stellarcraft.client.ControlStateMachine;
import dev.stellarcraft.client.NativeMovement;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** The integrated server owns native entities; hidden MC entities never enter this registry. */
public final class NativeEntities {
    public static final String TAG="stellarcraft.native";
    private static final String EPOCH_TAG="stellarcraft.epoch.";
    private static final ThreadLocal<Boolean> spawning=ThreadLocal.withInitial(()->false);
    private static final ConcurrentHashMap<Integer,Entity> entities=new ConcurrentHashMap<>();
    private static volatile List<Snapshot> snapshot=List.of();
    private static volatile long snapshotEpoch;
    private static volatile int currentWorld;
    private static volatile net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
    private static long feedbackSeq;
    private static int feedbackPid,feedbackWorld;
    private static final ConcurrentHashMap<UUID,Long> feedbackAt=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID,Boolean> visibleToPlayer=new ConcurrentHashMap<>();
    private static final Set<UUID> embedded=ConcurrentHashMap.newKeySet();
    private static final Map<UUID,Set<UUID>> arrowHits=new ConcurrentHashMap<>();
    private static final Map<UUID,Vec3> arrowConfirmed=new ConcurrentHashMap<>();
    private record ArrowImpulse(Vec3 velocity,int ticks) {}
    private static final Map<UUID,ArrowImpulse> arrowImpulses=new ConcurrentHashMap<>();
    private record NativeTarget(int uid,AABB box,float health) {}
    private static volatile List<NativeTarget> nativeTargets=List.of();
    private static volatile long targetsAt;
    private static final Path DIR=Path.of("D:/SteamLibrary/steamapps/common/StellarBlade/SB/Binaries/Win64/ue4ss/Mods/StellarCraft/block-assets");
    public record Snapshot(int id,UUID uuid,double x,double y,double z,double vx,double vy,double vz,
                           float width,float height,int kind,int fuse,float yaw,float headYaw,float bodyYaw,
                           float pitch,int hurtTime,int deathTime,float hurtDir) {}
    private NativeEntities() {}
    private static void stampEpoch(Entity e) {
        e.getTags().removeIf(tag->tag.startsWith(EPOCH_TAG));
        e.addTag(EPOCH_TAG+PlacedBlocks.nativeEpoch());
    }
    private static boolean acceptsEpoch(Set<String> tags,long epoch) {
        return tags.contains(EPOCH_TAG+epoch) || epoch==0 && tags.stream().noneMatch(tag->tag.startsWith(EPOCH_TAG));
    }
    private static boolean currentEpoch(Entity e) {
        long epoch=PlacedBlocks.nativeEpoch();
        if(!acceptsEpoch(e.getTags(),epoch)) return false;
        e.addTag(EPOCH_TAG+epoch);return true;
    }
    /** Integrated server thread only; never touches players or hidden/unregistered mobs. */
    public static void clearForMapChange(ServerLevel level) {
        snapshot=List.of();nativeTargets=List.of();
        for(Entity e:List.copyOf(entities.values()))
            if(e.level()==level && !currentEpoch(e)) e.discard();
        feedbackAt.clear();visibleToPlayer.clear();embedded.clear();arrowHits.clear();arrowConfirmed.clear();arrowImpulses.clear();
    }
    public static boolean owns(Entity e) {
        if(e==null) return false;
        Entity owner=entities.get(e.getId());
        return owner!=null&&owner.getUUID().equals(e.getUUID());
    }
    public static List<Snapshot> snapshots() {return snapshotEpoch==PlacedBlocks.nativeEpoch()?snapshot:List.of();}
    public static boolean simulates(Entity e) {
        return NativeMovement.active()&&dimension!=null&&e.level().dimension().equals(dimension)
            &&System.currentTimeMillis()-feedbackAt.getOrDefault(e.getUUID(),0L)<500;
    }
    public static void init() {
        ServerEntityEvents.ENTITY_LOAD.register((e,level)->{
            boolean tnt=e instanceof PrimedTnt && PlacedBlocks.contains(e.blockPosition().asLong());
            if(tnt&&!e.getTags().contains(TAG)) e.setPos(e.getX(),e.getY()+PlacedBlocks.offset(e.blockPosition().asLong()),e.getZ());
            boolean arrow=e instanceof AbstractArrow a&&!(a instanceof net.minecraft.world.entity.projectile.ThrownTrident)
                &&NativeMovement.owns(a.getOwner())&&ControlStateMachine.isSteve();
            if(spawning.get() || tnt || arrow || e.getTags().contains(TAG)) {
                boolean previouslyNative=e.getTags().contains(TAG);
                if(spawning.get() || !e.getTags().contains(TAG)) stampEpoch(e);
                e.addTag(TAG);entities.put(e.getId(),e);e.noPhysics=true;
                if(e instanceof AbstractArrow) {
                    arrowConfirmed.put(e.getUUID(),e.position());
                    if(e.getTags().contains("stellarcraft.embedded")) embedded.add(e.getUUID());
                }
                if(e instanceof Mob mob) {
                    String originalAi=NativeMobAiState.captureTag(e.getTags(),previouslyNative,mob.isNoAi());
                    if(originalAi!=null) e.addTag(originalAi);
                    mob.setNoAi(true);
                }
                System.out.println("[StellarCraft-Entities] native spawn id="+e.getId()+" type="+e.getType());
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((e,level)->{
            if(entities.remove(e.getId(),e)) {feedbackAt.remove(e.getUUID());visibleToPlayer.remove(e.getUUID());
                embedded.remove(e.getUUID());arrowHits.remove(e.getUUID());arrowConfirmed.remove(e.getUUID());arrowImpulses.remove(e.getUUID());}
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(!NativeMovement.active()) {snapshot=List.of();return;}
            long epoch=PlacedBlocks.nativeEpoch();
            applyFeedback();
            ArrayList<Snapshot> next=new ArrayList<>();
            for(Entity e:entities.values()) {
                if(e.isRemoved()) {entities.remove(e.getId(),e);continue;}
                if(dimension==null||!e.level().dimension().equals(dimension)) continue;
                if(!currentEpoch(e)) {e.discard();entities.remove(e.getId(),e);continue;}
                e.noPhysics=true;e.fallDistance=0;
                if(e instanceof Mob mob && mob.isAlive()&&simulates(e)) {
                    var player=e.level().getNearestPlayer(e,24);
                    boolean hostile=mob instanceof net.minecraft.world.entity.monster.Monster && player!=null
                        &&NativeMovement.owns(player)&&ControlStateMachine.isSteve()&&player.isAlive()
                        &&!player.isCreative()&&!player.isSpectator();
                    // Hidden MC navigation cannot describe SB surfaces. Native traces resolve this wander.
                    if(hostile) {
                        double dx=player.getX()-e.getX(),dz=player.getZ()-e.getZ();
                        e.setYRot((float)Math.toDegrees(Math.atan2(-dx,dz)));
                        mob.setYHeadRot(e.getYRot());
                        double reach=1.2+(e.getBbWidth()+player.getBbWidth())*.5;
                        if(e.tickCount%20==0&&e.distanceToSqr(player)<reach*reach
                            &&visibleToPlayer.getOrDefault(e.getUUID(),false)) {
                            float before=player.getHealth();Vec3 velocity=player.getDeltaMovement();
                            boolean hit=mob.doHurtTarget(player);
                            if(hit) mob.swing(InteractionHand.MAIN_HAND);
                            if(hit) NativeMovement.registeredKnockback(mob,player,player.getDeltaMovement().subtract(velocity));
                            System.out.println("[StellarCraft-PlayerDamage] placed mob="+e.getUUID()+" hit="+hit+" HP="+before+" -> "+player.getHealth());
                        }
                    } else if(e.tickCount%100==0) e.setYRot(mob.getRandom().nextFloat()*360);
                    if(!hostile) mob.setYHeadRot(e.getYRot());
                    mob.setYBodyRot(e.getYRot());
                    double speed=e.onGround()?(hostile?.10:.045):0.0,angle=Math.toRadians(e.getYRot());
                    Vec3 v=e.getDeltaMovement();
                    var impact=arrowImpulses.remove(e.getUUID());
                    Vec3 impulse=impact==null?Vec3.ZERO:impact.velocity();
                    if(impact!=null&&impact.ticks()>1&&impulse.horizontalDistanceSqr()>1e-8)
                        arrowImpulses.put(e.getUUID(),new ArrowImpulse(impulse.scale(e.onGround()?.546:.91),impact.ticks()-1));
                    e.setDeltaMovement(-Math.sin(angle)*speed+impulse.x,Math.max(-3.9,v.y-.08),Math.cos(angle)*speed+impulse.z);
                    e.move(MoverType.SELF,e.getDeltaMovement());
                    mob.calculateEntityAnimation(false);
                    e.setDeltaMovement(e.getDeltaMovement().multiply(.91,.98,.91));
                }
                Vec3 v=e.getDeltaMovement();
                int kind=e instanceof PrimedTnt?2:e instanceof ThrownEnderpearl?3:e instanceof FireworkRocketEntity?4:e instanceof AbstractArrow?5:e instanceof Boat?1:0;
                next.add(new Snapshot(e.getId(),e.getUUID(),e.getX(),e.getY(),e.getZ(),v.x,v.y,v.z,
                    e.getBbWidth(),e.getBbHeight(),kind,e instanceof PrimedTnt t?t.getFuse():embedded.contains(e.getUUID())?1:0,
                    e.getYRot(),e instanceof LivingEntity living?living.yHeadRot:e.getYRot(),
                    e instanceof LivingEntity living?living.yBodyRot:e.getYRot(),e.getXRot(),
                    e instanceof LivingEntity living?living.hurtTime:0,e instanceof LivingEntity living?living.deathTime:0,
                    e instanceof LivingEntity living?living.getHurtDir():0));
            }
            snapshot=List.copyOf(next);
            snapshotEpoch=epoch;
        });
    }
    public static void clientTick() {
        currentWorld=PlacedBlocks.worldId();
        var level=Minecraft.getInstance().level;dimension=level==null?null:level.dimension();
        if(level!=null&&Minecraft.getInstance().player!=null&&NativeMovement.active()) {
            var box=Minecraft.getInstance().player.getBoundingBox().inflate(64);
            nativeTargets=level.getEntitiesOfClass(ActorProxy.class,box).stream()
                .filter(e->!e.isRemoved()&&e.mirroredHealth()>0)
                .map(e->new NativeTarget(e.sbUid(),e.getBoundingBox(),e.mirroredHealth())).toList();
            targetsAt=System.currentTimeMillis();
        } else nativeTargets=List.of();
        if(level!=null) for(var state:snapshots()) {
            var entity=level.getEntity(state.id());
            if(entity==null||!entity.getUUID().equals(state.uuid())) continue;
            if(entity instanceof PrimedTnt t) t.setFuse(state.fuse());
            if(entity instanceof AbstractArrow) {
                entity.yRotO=entity.getYRot();entity.xRotO=entity.getXRot();
                entity.setYRot(entity.yRotO+net.minecraft.util.Mth.wrapDegrees(state.yaw()-entity.yRotO));
                entity.setXRot(entity.xRotO+net.minecraft.util.Mth.wrapDegrees(state.pitch()-entity.xRotO));
            }
            if(!(entity instanceof LivingEntity living)) continue;
            if(state.hurtTime()>living.hurtTime) living.animateHurt(state.hurtDir());
            living.hurtTime=state.hurtTime();living.deathTime=state.deathTime();
            living.yRotO=living.getYRot();living.yHeadRotO=living.yHeadRot;living.yBodyRotO=living.yBodyRot;
            living.setYRot(living.yRotO+net.minecraft.util.Mth.wrapDegrees(state.yaw()-living.yRotO));
            living.yHeadRot=living.yHeadRotO+net.minecraft.util.Mth.wrapDegrees(state.headYaw()-living.yHeadRotO);
            living.yBodyRot=living.yBodyRotO+net.minecraft.util.Mth.wrapDegrees(state.bodyYaw()-living.yBodyRotO);
            // NoAI disables hidden navigation, not the native entity's walking phase.
            living.walkAnimation.update((float)Math.min(1,Math.hypot(state.vx(),state.vz())*4),.4f);
        }
        var bridge=dev.stellarcraft.client.ClientBridge.get();int pid=bridge==null?0:bridge.hostPid();
        if(pid!=feedbackPid||currentWorld!=feedbackWorld) {feedbackSeq=0;feedbackAt.clear();visibleToPlayer.clear();feedbackPid=pid;feedbackWorld=currentWorld;}
    }
    private static void spawn(Runnable action) {
        boolean old=spawning.get();spawning.set(true);try{action.run();}finally{spawning.set(old);}
    }
    public static InteractionResult useOn(net.minecraft.client.player.LocalPlayer player,InteractionHand hand,BlockHitResult hit) {
        var mc=Minecraft.getInstance();var server=mc.getSingleplayerServer();var item=player.getItemInHand(hand).getItem();
        if(!NativeMovement.owns(player) || !ControlStateMachine.isSteve() || server==null) return null;
        if(item==Items.FIREWORK_ROCKET) return dev.stellarcraft.client.ElytraBridge.gliding(player)
            ? use(player,hand) : InteractionResult.PASS;
        if(!(item instanceof SpawnEggItem) && !(item instanceof BoatItem) && !(item instanceof FlintAndSteelItem)
                && item!=Items.FIRE_CHARGE) return null;
        int slot=player.getInventory().selected;Vec3 location=hit.getLocation();
        server.execute(()->{
            var peer=server.getPlayerList().getPlayer(player.getUUID());if(peer==null) return;
            peer.getInventory().selected=slot;var stack=peer.getItemInHand(hand);var level=peer.serverLevel();
            spawn(()->{
                if(stack.getItem() instanceof SpawnEggItem egg) {
                    var type=egg.getType(stack.getTag());Entity entity=type.create(level);
                    if(entity==null || entities.size()>=128) return;
                    entity.moveTo(location.x,location.y+0.02,location.z,peer.getYRot(),0);
                    if(entity instanceof Mob mob) mob.finalizeSpawn(level,level.getCurrentDifficultyAt(entity.blockPosition()),MobSpawnType.SPAWN_EGG,null,stack.getTag());
                    level.addFreshEntity(entity);
                    if(!peer.getAbilities().instabuild) stack.shrink(1);
                } else if(stack.getItem() instanceof BoatItem boatItem) {
                    if(entities.size()>=128) return;
                    String name=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
                    Boat boat=name.contains("chest")?new ChestBoat(level,location.x,location.y+0.05,location.z):new Boat(level,location.x,location.y+0.05,location.z);
                    for(Boat.Type type:Boat.Type.values()) if(name.startsWith(type.getName()+"_")) boat.setVariant(type);
                    boat.setYRot(peer.getYRot());level.addFreshEntity(boat);
                    if(!peer.getAbilities().instabuild) stack.shrink(1);
                } else if(PlacedBlocks.contains(hit.getBlockPos().asLong())) {
                    peer.gameMode.useItemOn(peer,level,stack,hand,hit);
                }
            });
        });
        return InteractionResult.SUCCESS;
    }
    public static InteractionResult use(net.minecraft.world.entity.player.Player player,InteractionHand hand) {
        var mc=Minecraft.getInstance();var server=mc.getSingleplayerServer();
        if(!NativeMovement.owns(player)||!ControlStateMachine.isSteve()||server==null) return null;
        var item=player.getItemInHand(hand).getItem();
        if(item instanceof BoatItem && mc.hitResult instanceof BlockHitResult hit && hit.getType()==HitResult.Type.BLOCK)
            return useOn(mc.player,hand,hit);
        boolean rocket=item==Items.FIREWORK_ROCKET;
        if(item!=Items.ENDER_PEARL&&!rocket) return null;
        if(rocket&&!dev.stellarcraft.client.ElytraBridge.gliding(player)) return InteractionResult.PASS;
        int slot=player.getInventory().selected;float yaw=player.getYRot(),pitch=player.getXRot();
        server.execute(()->{
            var peer=server.getPlayerList().getPlayer(player.getUUID());if(peer==null) return;
            peer.getInventory().selected=slot;peer.setYRot(yaw);peer.setXRot(pitch);
            if(rocket&&!dev.stellarcraft.client.ElytraBridge.gliding(peer)) return;
            spawn(()->peer.gameMode.useItem(peer,peer.serverLevel(),peer.getItemInHand(hand),hand));
        });return InteractionResult.SUCCESS;
    }
    public static boolean tickProjectile(Entity e) {
        if(!owns(e)) return false;
        if(!(e instanceof PrimedTnt) && !(e instanceof ThrownEnderpearl)) return false;
        // Never let a native TNT fall back to hidden MC terrain/explosions during host shutdown.
        if(!simulates(e)) return true;
        e.baseTick();Vec3 v=e.getDeltaMovement().add(0,e instanceof PrimedTnt?-.04:-.03,0);
        e.setDeltaMovement(v);e.move(MoverType.SELF,v);
        e.setDeltaMovement(v.scale(e instanceof PrimedTnt?.98:.99));
        if(e instanceof PrimedTnt t) {t.setFuse(t.getFuse()-1);if(!e.level().isClientSide&&t.getFuse()<=0) {explode(t);t.discard();}}
        else if(e.tickCount>1200) e.discard();
        return true;
    }
    public static boolean tickArrow(AbstractArrow arrow) {
        if(!owns(arrow)) return false;
        if(!simulates(arrow)) return true;
        arrow.baseTick();
        if(arrow.tickCount>1200) {arrow.discard();return true;}
        if(embedded.contains(arrow.getUUID())) {
            ((dev.stellarcraft.world.NativeArrowAccess)(Object)arrow).stellarcraft$embedded(true);
            arrow.setDeltaMovement(Vec3.ZERO);
            return true;
        }
        Vec3 velocity=arrow.getDeltaMovement();
        arrow.move(MoverType.SELF,velocity);
        arrow.yRotO=arrow.getYRot();arrow.xRotO=arrow.getXRot();
        arrow.setYRot((float)Math.toDegrees(Math.atan2(velocity.x,velocity.z)));
        arrow.setXRot((float)Math.toDegrees(Math.atan2(velocity.y,Math.hypot(velocity.x,velocity.z))));
        arrow.setDeltaMovement(velocity.scale(.99).add(0,-.05,0));
        return true;
    }
    public record HitSegment(UUID arrow,Vec3 start,Vec3 end) {}
    private static final ThreadLocal<HitSegment> activeHitSegment=new ThreadLocal<>();
    public static HitSegment hitSegment(AbstractArrow arrow) {
        var segment=activeHitSegment.get();
        return segment!=null&&segment.arrow().equals(arrow.getUUID())?segment:null;
    }
    private static void hitArrowSegment(AbstractArrow arrow,Vec3 end) {
        Vec3 start=arrowConfirmed.put(arrow.getUUID(),end);
        if(start==null||embedded.contains(arrow.getUUID())||start.distanceToSqr(end)<1e-8) return;
        // Sweep only the published native scene segment. Host feedback clips walls
        // before damage, so a delayed trace must not hit something behind a wall.
        var hits=arrowHits.computeIfAbsent(arrow.getUUID(),id->new HashSet<>());
        Entity target=null;double nearest=Double.POSITIVE_INFINITY;
        for(Entity e:entities.values()) {
            if(e==arrow||e==arrow.getOwner()||e.isRemoved()||!e.level().equals(arrow.level())
                ||!simulates(e)||!(e instanceof LivingEntity living)||!living.isAlive()||hits.contains(e.getUUID())) continue;
            var at=e.getBoundingBox().inflate(.15).clip(start,end);
            if(at.isPresent()&&at.get().distanceToSqr(start)<nearest) {nearest=at.get().distanceToSqr(start);target=e;}
        }
        for(var proxy:arrow.level().getEntitiesOfClass(ActorProxy.class,new AABB(start,end).inflate(32))) {
            if(!NativeActors.current(proxy)||hits.contains(proxy.getUUID())) continue;
            var at=proxy.getBoundingBox().inflate(.15).clip(start,end);
            if(at.isPresent()&&at.get().distanceToSqr(start)<nearest) {nearest=at.get().distanceToSqr(start);target=proxy;}
        }
        if(target!=null) {
            hits.add(target.getUUID());
            Vec3 before=target.getDeltaMovement();
            var previous=activeHitSegment.get();
            activeHitSegment.set(new HitSegment(arrow.getUUID(),start,end));
            try {((dev.stellarcraft.world.NativeArrowAccess)(Object)arrow).stellarcraft$hitEntity(new EntityHitResult(target));}
            finally {if(previous==null) activeHitSegment.remove();else activeHitSegment.set(previous);}
            if(target instanceof Mob) {
                Vec3 impulse=target.getDeltaMovement().subtract(before);
                if(impulse.horizontalDistanceSqr()>1e-8) arrowImpulses.put(target.getUUID(),new ArrowImpulse(impulse,80));
            }
            // Vanilla handles successful hits, invulnerability bounces and piercing.
            // Native proxies return false because only SB owns their HP; consume that
            // arrow after its single forwarded hit rather than bouncing/repeating it.
            if(target instanceof ActorProxy) arrow.discard();
        }
    }
    private static void explode(PrimedTnt t) {
        ServerLevel level=(ServerLevel)t.level();Vec3 center=t.position();double radius=4;
        for(long packed:PlacedBlocks.positions()) {
            BlockPos pos=BlockPos.of(packed);var state=PlacedBlocks.state(packed);
            if(state==null||state.getBlock().getExplosionResistance()>=1200||pos.getCenter().add(0,PlacedBlocks.offset(packed),0).distanceToSqr(center)>radius*radius) continue;
            if(state.is(net.minecraft.world.level.block.Blocks.TNT)) spawn(()->{
                PrimedTnt chain=new PrimedTnt(level,pos.getX()+.5,pos.getY(),pos.getZ()+.5,t.getOwner());
                chain.setFuse(10+level.random.nextInt(20));level.addFreshEntity(chain);
            });
            level.setBlock(pos,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),3);
        }
        var source=level.damageSources().explosion(t,t.getOwner());
        for(Entity victim:List.copyOf(entities.values())) {
            if(victim==t||victim.level()!=level||victim.isRemoved()) continue;
            double exposure=Math.max(0,1-victim.position().distanceTo(center)/8);
            if(exposure<=0) continue;
            float damage=(float)((exposure*exposure+exposure)*28+1);
            spawn(()->{victim.hurt(source,damage);});
            victim.setDeltaMovement(victim.getDeltaMovement().add(victim.position().subtract(center).normalize().scale(exposure)));
        }
        ExplosionDamageSink.blast(t.getUUID(),level,center);
        int nativeWorld=NativeWorldEvents.worldToken();
        Minecraft.getInstance().execute(()->NativeWorldEvents.send(3,0,center.x,center.y,center.z,radius,0,nativeWorld));
        System.out.println("[StellarCraft-TNT] native blast at="+center+" damage whitelist=native entities/SB enemies");
    }
    private static void applyFeedback() {
        String[] chosen=null;long best=feedbackSeq;
        int nativeWorld=NativeWorldEvents.worldToken();
        if(nativeWorld==0) return;
        for(int slot=0;slot<2;slot++) try {
            String[] lines=Files.readString(DIR.resolve("entities-feedback."+slot+".txt")).split("\\R");
            if(lines.length<3||!lines[0].equals("SCFEED3")) continue;
            String[] header=lines[1].split(" ");long seq=Long.parseLong(header[0]),time=Long.parseLong(header[1]);
            int world=Integer.parseUnsignedInt(header[2]),count=Integer.parseInt(header[3]);
            if(header.length!=5 || Integer.parseUnsignedInt(header[4])!=nativeWorld) continue;
            if(seq<=best||world!=currentWorld||Math.abs(System.currentTimeMillis()-time)>400||count<0||count>64||lines.length!=count+3||!lines[count+2].equals("END "+seq)) continue;
            chosen=lines;best=seq;
        } catch(Exception ignored) {}
        if(chosen==null) return;
        // Parse the entire batch before mutating any entity.
        ArrayList<double[]> rows=new ArrayList<>();
        ArrayList<UUID> rowIds=new ArrayList<>();
        try {for(int i=2;i<chosen.length-1;i++) {
            String[] parts=chosen[i].split(" ");if(parts.length!=12) return;
            double[] row=new double[10];for(int j=0;j<10;j++) {row[j]=Double.parseDouble(parts[j]);if(!Double.isFinite(row[j])) return;}
            if(Math.abs(row[1])>3e7||Math.abs(row[2])>2e7||Math.abs(row[3])>3e7) return;rows.add(row);
            rowIds.add(new UUID(Long.parseUnsignedLong(parts[10],16),Long.parseUnsignedLong(parts[11],16)));
        }}catch(Exception ignored){return;}
        feedbackSeq=best;
        int index=0;
        for(double[] row:rows) {
            UUID uuid=rowIds.get(index++);
            Entity e=entities.get((int)row[0]);if(e==null||e.isRemoved()) continue;
            if(!e.getUUID().equals(uuid)) continue;
            feedbackAt.put(e.getUUID(),System.currentTimeMillis());
            visibleToPlayer.put(e.getUUID(),row[9]==1);
            if(e instanceof AbstractArrow arrow) {
                hitArrowSegment(arrow,new Vec3(row[1],row[2],row[3]));
                if(e.isRemoved()) continue;
            }
            // Feedback clamps only collisions; free movement remains server tick physics.
            if(row[8]!=0) {
                e.setPos(row[1],row[2],row[3]);e.setDeltaMovement(row[4],row[5],row[6]);e.setOnGround(row[7]!=0);
                if(e instanceof AbstractArrow) {
                    embedded.add(e.getUUID());e.addTag("stellarcraft.embedded");e.setDeltaMovement(Vec3.ZERO);
                    ((dev.stellarcraft.world.NativeArrowAccess)(Object)e).stellarcraft$embedded(true);
                }
                if(e instanceof ThrownEnderpearl pearl && pearl.getOwner() instanceof net.minecraft.server.level.ServerPlayer player) {
                    Vec3 landing=new Vec3(row[1],row[2],row[3]);
                    Minecraft.getInstance().execute(()->NativeWorldEvents.send(8,0,landing.x,landing.y,landing.z,0,0,nativeWorld));
                    e.discard();
                }
            }
        }
    }
}
