package dev.stellarcraft.world;

import dev.stellarcraft.client.ControlStateMachine;
import dev.stellarcraft.client.NativeMovement;
import dev.stellarcraft.link.SharedMemory;
import net.minecraft.client.Minecraft;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import java.util.UUID;

/** Visual jump-strike classification; does not change fallDistance or vanilla damage. */
public final class NativeJumpAttack {
    public record Motion(UUID player,int pid,int world,int map,int teleport,long time,
                         boolean grounded,double y,double vy,boolean jumped,boolean descending) {}
    public record Attack(UUID player,UUID target,int uid,Motion motion,boolean heavy,String reason) {}
    private static volatile Motion latest;
    private static final ThreadLocal<Attack> scoped=new ThreadLocal<>();
    private NativeJumpAttack() {}

    public static Motion next(Motion old,UUID player,int pid,int world,int map,int teleport,long time,
                              boolean grounded,double y,double vy) {
        if(player==null||pid<=0||world==0||map==0||time<=0||!Double.isFinite(y)||!Double.isFinite(vy)) return null;
        boolean same=old!=null&&old.player.equals(player)&&old.pid==pid&&old.world==world&&old.map==map
            &&old.teleport==teleport&&time>=old.time&&time-old.time<=250;
        // Velocity is blocks/MC tick (native cm/s divided by 100*20). Require
        // both actual rising and falling height, not just a jump key or a ledge.
        boolean rose=same&&vy>1e-4&&y>old.y+1e-4;
        boolean jumped=!grounded&&same&&(old.jumped||old.grounded&&rose);
        boolean descending=jumped&&vy< -1e-4&&y<old.y-1e-4;
        return new Motion(player,pid,world,map,teleport,time,grounded,y,vy,jumped,descending);
    }
    public static void update(Minecraft mc,SharedMemory.SbStateSnapshot sb,int pid,long heartbeatAge) {
        var p=mc.player;long now=dev.stellarcraft.NativeLink.tickCount();
        if(p==null||!mc.hasSingleplayerServer()||!NativeMovement.owns(p)||!ControlStateMachine.isSteve()
            ||!p.isAlive()||p.getAbilities().flying||p.isFallFlying()||p.isSpectator()
            ||(sb.flags&0x31)!=0x31||(sb.flags&0x4E)!=0||sb.motionEpoch!=pid
            ||heartbeatAge<0||heartbeatAge>250||sb.motionTimeMs>now||now-sb.motionTimeMs>250) {latest=null;return;}
        var old=latest;
        if(old!=null&&old.time==sb.motionTimeMs&&old.pid==pid&&old.world==PlacedBlocks.worldId()
            &&old.map==sb.levelId&&old.teleport==sb.teleportSeq) return;
        latest=next(old,p.getUUID(),pid,PlacedBlocks.worldId(),sb.levelId,sb.teleportSeq,sb.motionTimeMs,
            (sb.contactFlags&1)!=0,sb.posY,sb.velocityY);
    }
    public static boolean eligible(Motion motion,UUID player,int world,long now,float cooldown,boolean excluded) {
        return motion!=null&&motion.player.equals(player)&&motion.world==world&&now>=motion.time&&now-motion.time<=250
            &&!motion.grounded&&motion.descending&&motion.jumped&&!excluded&&Float.isFinite(cooldown)&&cooldown>.9f;
    }
    public static Attack capture(Player player,Entity target) {
        if(player.level().isClientSide||!(target instanceof ActorProxy proxy)||!NativeMovement.owns(player)) return null;
        var motion=latest;
        boolean excluded=!ControlStateMachine.isSteve()||!player.isAlive()||player.isSpectator()
            ||player.getAbilities().flying||player.isFallFlying()||player.isSwimming()||player.isInWater()
            ||player.onClimbable()||player.isPassenger()||player.hasEffect(MobEffects.BLINDNESS)||player.isSprinting();
        boolean heavy=eligible(motion,player.getUUID(),PlacedBlocks.worldId(),dev.stellarcraft.NativeLink.tickCount(),
            player.getAttackStrengthScale(.5f),excluded);
        return new Attack(player.getUUID(),target.getUUID(),proxy.sbUid(),motion,heavy,heavy?"host descending jump":"ordinary/excluded/stale");
    }
    public static boolean hurt(Attack attack,Entity target,DamageSource source,float amount) {
        var previous=scoped.get();
        if(attack!=null&&attack.target.equals(target.getUUID())) scoped.set(attack);else scoped.remove();
        try {return target.hurt(source,amount);}
        finally {if(previous==null) scoped.remove();else scoped.set(previous);}
    }
    public static boolean heavy(ActorProxy proxy,DamageSource source) {
        var attack=scoped.get();var current=latest;
        if(attack==null||!attack.heavy||attack.motion==null||current==null
            ||!(source.getDirectEntity() instanceof Player player)||source.getEntity()!=player
            ||!attack.player.equals(player.getUUID())||!attack.target.equals(proxy.getUUID())||attack.uid!=proxy.sbUid()) return false;
        var captured=attack.motion;
        return current.pid==captured.pid&&current.world==captured.world&&current.map==captured.map
            &&current.teleport==captured.teleport&&eligible(captured,player.getUUID(),PlacedBlocks.worldId(),
                dev.stellarcraft.NativeLink.tickCount(),1,false);
    }
    public static void log(ActorProxy proxy,boolean heavy) {
        var attack=scoped.get();if(attack==null) return;
        long age=attack.motion==null?-1:dev.stellarcraft.NativeLink.tickCount()-attack.motion.time;
        System.out.printf(java.util.Locale.ROOT,"[StellarCraft-JumpAttack] uid=%d jumpHeavy=%s hostMotionAge=%d reason=%s vanillaDamageUnchanged=true%n",
            proxy.sbUid(),heavy,age,heavy?attack.reason:"ordinary/excluded/stale/identity mismatch");
    }
}
