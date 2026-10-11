package dev.stellarcraft.client;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.Items;

/** Vanilla owns takeoff, aerodynamics, equipment ticks and flight duration. */
public final class ElytraBridge {
    private static boolean collision, wasGliding;
    private static double lastY;
    private static float fallDistance;
    private ElytraBridge() {}
    public static boolean gliding(Player player) {
        return NativeMovement.owns(player)&&ControlStateMachine.isSteve()&&player.isFallFlying()
            &&player.isAlive()&&!player.getAbilities().flying;
    }
    public static void validate(Player player) {
        var chest=player.getItemBySlot(EquipmentSlot.CHEST);
        if(player.isFallFlying()&&(!NativeMovement.owns(player)||!ControlStateMachine.isSteve()
            ||!player.isAlive()||player.getAbilities().flying||player.onGround()
            ||!chest.is(Items.ELYTRA)||!ElytraItem.isFlyEnabled(chest))) player.stopFallFlying();
    }
    public static void feedback(net.minecraft.client.Minecraft mc,Player player,
            dev.stellarcraft.link.SharedMemory.SbStateSnapshot state) {
        boolean glide=gliding(player),ground=(state.contactFlags&1)!=0;
        boolean wall=glide&&(state.contactFlags&10)!=0;
        var before=player.getDeltaMovement();
        float impact=wall&&!collision?(float)((before.horizontalDistance()
            -Math.hypot(state.velocityX,state.velocityZ))*10-3):0;
        if(glide&&!wasGliding) {fallDistance=0;lastY=state.posY;}
        if(glide||wasGliding) {
            if(state.velocityY>-.5) fallDistance=1; // vanilla checkSlowFallDistance
            else if(state.posY<lastY) fallDistance+=(float)(lastY-state.posY);
        }
        float landing=wasGliding&&ground?fallDistance:0;
        collision=wall;wasGliding=glide&&!ground;lastY=state.posY;
        if(!wasGliding) fallDistance=0;
        if(impact<=0&&landing<=3) return;
        var server=mc.getSingleplayerServer();if(server==null) return;
        var id=player.getUUID();
        server.execute(()->{
            var peer=server.getPlayerList().getPlayer(id);
            if(peer==null||!NativeMovement.owns(peer)||!ControlStateMachine.isSteve()
                ||peer.isCreative()||peer.isSpectator()||!peer.isAlive()) return;
            NativeMovement.withHostDamage(()->{
                if(impact>0) peer.hurt(peer.damageSources().flyIntoWall(),impact);
                if(landing>3) peer.causeFallDamage(landing,1,peer.damageSources().fall());
            });
        });
    }
}
