package dev.stellarcraft.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.stellarcraft.client.ControlStateMachine;
import net.minecraft.client.Minecraft;
import java.nio.file.Files;
import java.util.List;

/** Captures the vanilla player skin, equipment and animated limbs as native world geometry. */
public final class BodyMeshBridge {
    private static final NativePoseWriter writer=new NativePoseWriter();
    private static long lastPublished,lastSeen,lastError;
    private static boolean visible;
    private BodyMeshBridge() {}
    public static void capture(Minecraft mc,float partialTick) {
        if (!ControlStateMachine.isSteve() || mc.player==null || mc.level==null) return;
        long now=System.nanoTime(); lastSeen=now;
        // First person only needs the hidden body for its native shadow.
        long interval=mc.options.getCameraType().isFirstPerson()?100_000_000L:15_000_000L;
        if (now-lastPublished<interval) return;
        lastPublished=now;
        try {
            Files.createDirectories(HandMeshBridge.DIR);
            var buffers=new HandMeshBridge.Capture();
            var player=mc.player;
            // LevelRenderer normally initializes the dispatcher; our early cancellation
            // skips that call. Prepare its camera before layers/name-distance checks.
            mc.getEntityRenderDispatcher().prepare(mc.level,mc.gameRenderer.getMainCamera(),mc.crosshairPickEntity);
            mc.getEntityRenderDispatcher().getRenderer(player).render(player,
                player.getViewYRot(partialTick),partialTick,new PoseStack(),buffers,0);
            var faces=HandMeshBridge.bodySections(buffers);
            writer.publish(HandMeshBridge.DIR,"body",faces);
            if (!visible && !faces.isEmpty()) System.out.println("[StellarCraft-Body] vanilla model="
                +player.getModelName()+" skin="+player.getSkinTextureLocation()+" native faces="+faces.size()
                +" lighting=StellarBlade");
            visible=!faces.isEmpty();
        } catch (Exception error) {
            if (now-lastError>5_000_000_000L) {
                lastError=now;
                System.out.println("[StellarCraft-Body] capture failed: "+error);
            }
        }
    }
    public static void finishFrame() {
        if (visible && (!ControlStateMachine.isSteve() || System.nanoTime()-lastSeen>250_000_000L)) {
            try {writer.publish(HandMeshBridge.DIR,"body",List.of());} catch (Exception ignored) {}
            visible=false;
        }
    }
}
