package dev.stellarcraft.world.mixin;

import dev.stellarcraft.client.NativeMovement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A position acknowledgement must not replace the view controlled by forwarded mouse deltas. */
@Mixin(ClientPacketListener.class)
public abstract class NativeClientPositionMixin {
    @Unique private boolean stellarcraft$keepLook;
    @Unique private float stellarcraft$yaw,stellarcraft$pitch;
    @Inject(method="method_11157",remap=false,at=@At("HEAD"))
    private void stellarcraft$rememberLook(ClientboundPlayerPositionPacket packet,CallbackInfo ci) {
        var mc=Minecraft.getInstance();
        if(!mc.isSameThread()) return;
        stellarcraft$keepLook=mc.player!=null&&NativeMovement.owns(mc.player);
        if(stellarcraft$keepLook) {stellarcraft$yaw=mc.player.getYRot();stellarcraft$pitch=mc.player.getXRot();}
    }
    @Inject(method="method_11157",remap=false,at=@At("RETURN"))
    private void stellarcraft$restoreLook(ClientboundPlayerPositionPacket packet,CallbackInfo ci) {
        var p=Minecraft.getInstance().player;
        if(stellarcraft$keepLook&&p!=null&&NativeMovement.owns(p)) {
            p.setYRot(stellarcraft$yaw);p.yRotO=stellarcraft$yaw;
            p.setXRot(stellarcraft$pitch);p.xRotO=stellarcraft$pitch;
        }
        stellarcraft$keepLook=false;
    }
}
