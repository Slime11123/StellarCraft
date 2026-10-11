package dev.stellarcraft.world.mixin;

import dev.stellarcraft.client.NativeMovement;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only the owned integrated-server player delegates movement validation to the host capsule. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class NativeServerMovementMixin {
    @Shadow(remap=false) public ServerPlayer field_14140;
    @Shadow(remap=false) private net.minecraft.world.phys.Vec3 field_14119;
    @Shadow(remap=false) private boolean field_14131;
    @Shadow(remap=false) public abstract void method_14372();
    @Inject(method="method_12063",remap=false,at=@At("HEAD"),cancellable=true)
    private void stellarcraft$acceptHostMovement(ServerboundMovePlayerPacket packet,CallbackInfo ci) {
        var player=field_14140;
        if(!NativeMovement.owns(player)) return;
        var listener=(ServerGamePacketListenerImpl)(Object)this;
        PacketUtils.ensureRunningOnSameThread(packet,listener,player.serverLevel());
        // Real login/respawn teleports retain vanilla acknowledgement handling.
        if(field_14119!=null || player.isPassenger() || player.isSleeping()) return;
        double x=packet.getX(player.getX()),y=packet.getY(player.getY()),z=packet.getZ(player.getZ());
        float yaw=packet.getYRot(player.getYRot()),pitch=packet.getXRot(player.getXRot());
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
            ||!Float.isFinite(yaw)||!Float.isFinite(pitch)||Math.abs(x)>3e7||Math.abs(z)>3e7||Math.abs(y)>2e7) return;
        NativeMovement.apply(player);
        player.absMoveTo(x,y,z,yaw,pitch);
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        player.fallDistance=0;
        field_14131=false;
        player.serverLevel().getChunkSource().move(player);
        method_14372();
        ci.cancel();
    }
}
