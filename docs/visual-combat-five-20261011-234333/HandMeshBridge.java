package dev.stellarcraft.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.stellarcraft.client.ControlStateMachine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.joml.Vector3f;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Captures vanilla hand poses as geometry. No MC draw call or MC lighting reaches the hand. */
public final class HandMeshBridge {
    static final Path DIR = Path.of("D:/SteamLibrary/steamapps/common/StellarBlade/SB/Binaries/Win64/ue4ss/Mods/StellarCraft/block-assets");
    private static final Pattern TEXTURE = Pattern.compile("texture\\[Optional\\[([^\\]]+)\\]");
    private static final Map<ResourceLocation, BufferedImage> images = new HashMap<>();
    private static final Map<String, String> files = new HashMap<>();
    private static final BufferBuilder UNUSED = new BufferBuilder(256);
    private static final Map<String,Integer> shapes = new HashMap<>();
    private static long lastPublished, lastSeen, lastError;
    private static final NativePoseWriter writer=new NativePoseWriter();
    private static boolean visible;
    private HandMeshBridge() {}

    public static void capture(ItemInHandRenderer renderer, float partialTick, PoseStack pose,
                               MultiBufferSource.BufferSource original, LocalPlayer player, int light) {
        if (!ControlStateMachine.isSteve()) {
            renderer.renderHandsWithItems(partialTick, pose, original, player, light);
            return;
        }
        long now = System.nanoTime();
        lastSeen = now;
        // The original buffer is never submitted in Steve mode, including throttled frames.
        if (now-lastPublished < 15_000_000L) return;
        lastPublished = now;
        try {
            Files.createDirectories(DIR);
            Capture buffers = new Capture();
            if (Minecraft.getInstance().screen == null)
                renderer.renderHandsWithItems(partialTick, pose, buffers, player, 0);
            List<Face> sections = sections(buffers,false);
            publish(sections);
        } catch (Exception error) {
            if (now-lastError > 5_000_000_000L) {
                lastError=now;
                System.out.println("[StellarCraft-Hands] capture failed: " + error);
            }
        }
    }

    public static void finishFrame() {
        if (visible && (!ControlStateMachine.isSteve() || System.nanoTime()-lastSeen > 250_000_000L)) {
            try { publish(List.of()); } catch (Exception ignored) { }
        }
    }

    private static BufferedImage image(ResourceLocation location) throws Exception {
        BufferedImage cached=images.get(location);
        if (cached!=null) return cached;
        int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            Minecraft.getInstance().getTextureManager().getTexture(location).bind();
            int w=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH);
            int h=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
            if (w<=0 || h<=0 || w>8192 || h>8192) throw new IllegalStateException("Invalid texture " + location);
            BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
            try(NativeImage pixels=new NativeImage(w,h,false)) {
                pixels.downloadTexture(0,false);
                for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
                    int rgba=pixels.getPixelRGBA(x,y);
                    image.setRGB(x,y,(rgba & 0xFF00FF00) | ((rgba & 255)<<16) | ((rgba>>>16)&255));
                }
            }
            images.put(location,image);
            return image;
        } finally { GlStateManager._bindTexture(previous); }
    }

    private static String textureFile(ResourceLocation location,BufferedImage image,int tint) throws Exception {
        String key=location+"#"+tint;
        if(files.containsKey(key)) return files.get(key);
        String name="hand-"+UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8))+".png";
        BufferedImage tinted=new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
            int c=image.getRGB(x,y);
            int r=((c>>>16)&255)*((tint>>>16)&255)/255;
            int g=((c>>>8)&255)*((tint>>>8)&255)/255;
            int b=(c&255)*(tint&255)/255;
            tinted.setRGB(x,y,(c&0xFF000000)|(r<<16)|(g<<8)|b);
        }
        ImageIO.write(tinted,"png",DIR.resolve(name).toFile());
        files.put(key,name);
        return name;
    }

    record Face(int model,float[] transform) {}

    static List<Face> sections(Capture buffers, boolean world) throws Exception {
        return meshSections(buffers,world);
    }

    // ModelPart in 1.20.1 emits pre-transformed vertices. Recover each six-face cube
    // as one rigid component; don't assume it uses VertexConsumer's matrix overload.
    static List<Face> bodySections(Capture buffers) throws Exception {
        return meshSections(buffers,true);
    }
    private static List<Face> meshSections(Capture buffers,boolean world) throws Exception {
        List<Face> result=new ArrayList<>();
        for (Collector collector:buffers.buffers.values()) {
            if (collector.texture==null) continue;
            BufferedImage image=image(collector.texture);
            for(int i=0;i+3<collector.vertices.size();) {
                var part=NativeRigidParts.cube(collector.vertices,i,world);
                String filename=textureFile(collector.texture,image,collector.tints.get(i));
                if(part!=null) {
                    StringBuilder key=new StringBuilder("part:").append(filename);
                    for(float[][] quad:part.quads()) for(float[] v:quad) for(float f:v) key.append(':').append(f);
                    Integer id=shapes.get(key.toString());
                    if(id==null) {
                        List<float[][]> pixels=new ArrayList<>();
                        for(float[][] quad:part.quads()) NativePixels.split(quad,image,pixels::add);
                        if(pixels.isEmpty()) {i+=24;continue;}
                        StringBuilder mesh=new StringBuilder("SCBLOCK1\n1\n").append(filename).append('\n').append(pixels.size()).append('\n');
                        for(float[][] pixel:pixels) {
                            for(float[] v:pixel) for(int j=0;j<5;j++) mesh.append(v[j]).append(' ');
                            for(int j=5;j<8;j++) mesh.append(pixel[0][j]).append(' ');
                            mesh.append('\n');
                        }
                        byte[] data=mesh.toString().getBytes(StandardCharsets.UTF_8);
                        CRC32 crc=new CRC32();crc.update(data);id=(int)crc.getValue();if(id==0)id=1;
                        Files.write(DIR.resolve(Integer.toUnsignedString(id)+".scmesh"),data);
                        shapes.put(key.toString(),id);
                    }
                    result.add(new Face(id,part.transform()));i+=24;
                } else {
                    float[][] quad=new float[4][];
                    for(int j=0;j<4;j++) quad[j]=collector.vertices.get(i+j);
                    Face face=face(quad,image,filename,world);
                    if(face!=null) result.add(face);
                    i+=4;
                }
            }
        }
        return result;
    }
    private static Vector3f position(float[] v, boolean world) {
        return world ? new Vector3f(-v[0]*100,-v[2]*100,v[1]*100)
                     : new Vector3f(-v[2]*100,v[0]*100,v[1]*100);
    }

    private static Face face(float[][] q,BufferedImage image,String filename,boolean world) throws Exception {
        Vector3f origin=position(q[0],world);
        Vector3f x=position(q[1],world).sub(origin), y=position(q[3],world).sub(origin);
        float sx=x.length(), sy=y.length();
        if(sx<.0001f || sy<.0001f) return null;
        x.div(sx);y.div(sy);
        Vector3f z=new Vector3f(x).cross(y).normalize();
        Vector3f normal=world ? new Vector3f(-q[0][5],-q[0][7],q[0][6])
                             : new Vector3f(-q[0][7],q[0][5],q[0][6]);
        float nz=normal.dot(z)<0 ? -1:1;
        StringBuilder key=new StringBuilder(filename).append(nz);
        for(float[] v:q) key.append(':').append(v[3]).append(':').append(v[4]);
        Integer id=shapes.get(key.toString());
        if(id==null) {
            float[][] local={{0,0,0,q[0][3],q[0][4],0,0,nz}, {1,0,0,q[1][3],q[1][4],0,0,nz},
                {1,1,0,q[2][3],q[2][4],0,0,nz}, {0,1,0,q[3][3],q[3][4],0,0,nz}};
            List<float[][]> pixels=new ArrayList<>();
            NativePixels.split(local,image,pixels::add);
            if(pixels.isEmpty()) return null;
            StringBuilder mesh=new StringBuilder("SCBLOCK1\n1\n").append(filename).append('\n').append(pixels.size()).append('\n');
            for(float[][] pixel:pixels) {
                for(float[] v:pixel) for(int j=0;j<5;j++) mesh.append(v[j]).append(' ');
                mesh.append("0 0 ").append(nz).append('\n');
            }
            byte[] data=mesh.toString().getBytes(StandardCharsets.UTF_8);
            CRC32 crc=new CRC32();crc.update(data);id=(int)crc.getValue();if(id==0)id=1;
            Files.write(DIR.resolve(Integer.toUnsignedString(id)+".scmesh"),data);
            shapes.put(key.toString(),id);
        }
        Matrix3f basis=new Matrix3f(x.x,x.y,x.z,y.x,y.y,y.z,z.x,z.y,z.z);
        Quaternionf rotation=new Quaternionf().setFromNormalized(basis).normalize();
        return new Face(id,new float[]{origin.x,origin.y,origin.z,rotation.x,rotation.y,rotation.z,rotation.w,sx,sy,1});
    }

    private static void publish(List<Face> sections) throws Exception {
        writer.publish(DIR,"hands",sections);
        if(!visible && !sections.isEmpty()) System.out.println("[StellarCraft-Hands] native hand faces="+sections.size()+" cached geometry; transform bytes="+(32+44*sections.size()));
        visible=!sections.isEmpty();
    }

    static final class Capture extends MultiBufferSource.BufferSource {
        final Map<RenderType,Collector> buffers=new LinkedHashMap<>();
        Capture() { super(UNUSED,Map.of()); }
        @Override public VertexConsumer getBuffer(RenderType type) { return buffers.computeIfAbsent(type,Collector::new); }
        @Override public void endBatch() {}
        @Override public void endBatch(RenderType type) {}
        @Override public void endLastBatch() {}
    }

    private static final class Collector implements VertexConsumer {
        final List<float[]> vertices=new ArrayList<>();
        final List<Integer> tints=new ArrayList<>();
        final ResourceLocation texture;
        float[] v=new float[8];
        int tint=0xFFFFFF;
        Collector(RenderType type) {
            var match=TEXTURE.matcher(type.toString());
            // Glint is a separate animated effect, not the item's surface or a light source.
            texture=!type.toString().toLowerCase(Locale.ROOT).contains("glint") && match.find()
                ? new ResourceLocation(match.group(1)):null;
        }
        public VertexConsumer vertex(double x,double y,double z) {v[0]=(float)x;v[1]=(float)y;v[2]=(float)z;return this;}
        public VertexConsumer uv(float u,float w) {v[3]=u;v[4]=w;return this;}
        public VertexConsumer normal(float x,float y,float z) {v[5]=x;v[6]=y;v[7]=z;return this;}
        public VertexConsumer color(int r,int g,int b,int a) {tint=(r<<16)|(g<<8)|b;return this;}
        public VertexConsumer overlayCoords(int u,int v) {return this;}
        public VertexConsumer uv2(int u,int v) {return this;}
        public void defaultColor(int r,int g,int b,int a) {color(r,g,b,a);}
        public void unsetDefaultColor() {tint=0xFFFFFF;}
        public void endVertex() {vertices.add(v);tints.add(tint);v=new float[8];}
    }
}
