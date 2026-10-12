package dev.stellarcraft.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.stellarcraft.world.NativeEntities;
import net.minecraft.client.Minecraft;
import java.util.*;

/** Bounded captures of marked vanilla entities; geometry is rendered by Stellar Blade. */
public final class EntityMeshBridge {
    private static final NativePoseWriter manifest=new NativePoseWriter();
    private static final Map<Integer,NativePoseWriter> writers=new HashMap<>();
    private static long lastCapture,lastError;
    private static boolean particleExported;
    private EntityMeshBridge() {}
    public static void capture(Minecraft mc,float partial) {
        if(mc.level==null||mc.player==null||!dev.stellarcraft.client.NativeMovement.active()) return;
        long now=System.nanoTime();if(now-lastCapture<15_000_000L) return;lastCapture=now;
        try {
            if(!particleExported) exportParticles(mc);
            var entries=NativeEntities.snapshots().stream().filter(e->mc.player.distanceToSqr(e.x(),e.y(),e.z())<48*48)
                .sorted(Comparator.<NativeEntities.Snapshot>comparingInt(e->e.kind()>=2?0:1)
                    .thenComparingDouble(e->mc.player.distanceToSqr(e.x(),e.y(),e.z()))).limit(16).toList();
            var faces=new ArrayList<HandMeshBridge.Face>();
            faces.add(new HandMeshBridge.Face(0,new float[]{Float.intBitsToFloat(dev.stellarcraft.world.PlacedBlocks.worldId()),0,0,0,0,0,0,0,0,0}));
            mc.getEntityRenderDispatcher().prepare(mc.level,mc.gameRenderer.getMainCamera(),mc.crosshairPickEntity);
            int slot=3;
            for(var e:entries) {
                var entity=mc.level.getEntity(e.id());if(entity==null||!entity.getUUID().equals(e.uuid())) continue;
                var capture=new HandMeshBridge.Capture();
                mc.getEntityRenderDispatcher().getRenderer(entity).render(entity,entity.getViewYRot(partial),partial,new PoseStack(),capture,0);
                writers.computeIfAbsent(e.id(),id->new NativePoseWriter()).publish(slot++,e.id(),e.uuid(),HandMeshBridge.bodySections(capture));
                faces.add(new HandMeshBridge.Face(e.id(),new float[]{(float)e.x(),(float)e.y(),(float)e.z(),
                    (float)e.vx(),(float)e.vy(),(float)e.vz(),e.width(),e.height(),e.kind(),e.fuse()}));
                long high=e.uuid().getMostSignificantBits(),low=e.uuid().getLeastSignificantBits();
                faces.add(new HandMeshBridge.Face(0,new float[]{Float.intBitsToFloat((int)high),Float.intBitsToFloat((int)(high>>>32)),
                    Float.intBitsToFloat((int)low),Float.intBitsToFloat((int)(low>>>32)),0,0,0,0,0,0}));
            }
            writers.keySet().retainAll(entries.stream().map(NativeEntities.Snapshot::id).toList());
            manifest.publish(HandMeshBridge.DIR,"entities",faces);
        } catch(Exception error) {
            if(now-lastError>5_000_000_000L) {lastError=now;System.out.println("[StellarCraft-Entities] capture failed: "+error);}
        }
    }
    private static void exportParticles(Minecraft mc) throws Exception {
        exportSequence(mc,"explosion",false);
        exportSequence(mc,"smoke",true);
        exportParticle(mc,"firework",new net.minecraft.resources.ResourceLocation("minecraft","spark_0"),false);
        particleExported=true;
    }
    private static void exportSequence(Minecraft mc,String stem,boolean dark) throws Exception {
        var resource=mc.getResourceManager().getResource(new net.minecraft.resources.ResourceLocation("minecraft","particles/"+stem+".json")).orElseThrow();
        com.google.gson.JsonArray textures;
        try(var input=resource.openAsReader()) {textures=com.google.gson.JsonParser.parseReader(input).getAsJsonObject().getAsJsonArray("textures");}
        var ids=new StringBuilder();int index=0;
        for(var texture:textures) ids.append(exportParticle(mc,stem+"-"+index++,new net.minecraft.resources.ResourceLocation(texture.getAsString()),dark)).append('\n');
        var target=HandMeshBridge.DIR.resolve(stem+".frames");
        var pending=HandMeshBridge.DIR.resolve(stem+".frames.tmp");
        java.nio.file.Files.writeString(pending,ids.toString());
        java.nio.file.Files.move(pending,target,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    private static long exportParticle(Minecraft mc,String stem,net.minecraft.resources.ResourceLocation texture,boolean dark) throws Exception {
        var resource=mc.getResourceManager().getResource(new net.minecraft.resources.ResourceLocation(texture.getNamespace(),"textures/particle/"+texture.getPath()+".png")).orElseThrow();
        java.awt.image.BufferedImage image;
        try(var input=resource.open()) {image=javax.imageio.ImageIO.read(input);}
        if(dark) for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
            int c=image.getRGB(x,y);image.setRGB(x,y,(c&0xff000000)|(((c>>16)&255)/5<<16)|(((c>>8)&255)/5<<8)|(c&255)/5);
        }
        java.nio.file.Files.createDirectories(HandMeshBridge.DIR);
        javax.imageio.ImageIO.write(image,"png",HandMeshBridge.DIR.resolve("sc-"+stem+".png").toFile());
        float[][] quad={{0,-20,-20,0,1,-1,0,0},{0,-20,20,0,0,-1,0,0},{0,20,20,1,0,-1,0,0},{0,20,-20,1,1,-1,0,0}};
        var pixels=new ArrayList<float[][]>();NativePixels.split(quad,image,pixels::add);
        StringBuilder model=new StringBuilder("SCBLOCK1\n1\nsc-"+stem+".png\n").append(pixels.size()).append('\n');
        for(var face:pixels) {for(var vertex:face) for(int i=0;i<5;i++) model.append(vertex[i]).append(' ');model.append("-1 0 0\n");}
        byte[] bytes=model.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var crc=new java.util.zip.CRC32();crc.update(bytes);long id=crc.getValue();
        java.nio.file.Files.write(HandMeshBridge.DIR.resolve(id+".scmesh"),bytes);
        java.nio.file.Files.writeString(HandMeshBridge.DIR.resolve(stem+".model"),Long.toString(id));
        return id;
    }
}
