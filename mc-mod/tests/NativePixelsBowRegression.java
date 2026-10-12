import dev.stellarcraft.render.NativePixels;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

public class NativePixelsBowRegression {
    public static void main(String[] args) throws Exception {
        int base=0xff008000;
        if(NativePixels.color(base,0xffffff)!=base) throw new AssertionError("Neutral texture changed");
        int hurt=NativePixels.color(base,0x1ffffff),white=NativePixels.color(base,0xffffff|(15<<25));
        if(((hurt>>>16)&255)==0||((hurt>>>8)&255)>=128) throw new AssertionError("Hurt overlay missing");
        if(((white>>>16)&255)<190) throw new AssertionError("TNT white overlay missing");
        if((NativePixels.color(0x00800000,0x1ffffff)>>>24)!=0) throw new AssertionError("Overlay filled transparent pixels");
        if(NativePixels.color(base,0xffffff)!=base) throw new AssertionError("Hurt tint leaked to another entity");
        System.out.println("PASS: neutral/hurt/white overlay, alpha and independent appearance");
        float[][] quad={{0,0,0,0,0,0,0,1},{1,0,0,1,0,0,0,1},
            {1,1,0,1,1,0,0,1},{0,1,0,0,1,0,0,1}};
        try(var jar=new ZipFile(args[0])) {
            for(String name:new String[]{"bow","bow_pulling_0","bow_pulling_1","bow_pulling_2"}) {
                BufferedImage image;
                try(var input=jar.getInputStream(jar.getEntry("assets/minecraft/textures/item/"+name+".png"))) {image=ImageIO.read(input);}
                var faces=new ArrayList<float[][]>();NativePixels.split(quad,image,faces::add);
                if(faces.isEmpty()) throw new AssertionError("Missing bow state: "+name);
                int opaque=0;
                for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
                    boolean solid=(image.getRGB(x,y)>>>24)>=128;if(solid) opaque++;
                    float cx=(x+.5f)/image.getWidth(),cy=(y+.5f)/image.getHeight();int covering=0;
                    for(var f:faces) if(cx>=f[0][0]&&cx<f[1][0]&&cy>=f[0][1]&&cy<f[3][1]) {
                        covering++;
                        int px=(int)(f[0][3]*image.getWidth()),py=(int)(f[0][4]*image.getHeight());
                        if(image.getRGB(px,py)!=image.getRGB(x,y)) throw new AssertionError("Merged unlike texels");
                    }
                    if(covering!=(solid?1:0)) throw new AssertionError("Bow pixel hole/overlap at "+name+":"+x+","+y);
                }
                if(faces.size()>opaque) throw new AssertionError("Geometry budget increased");
                System.out.println("PASS: "+name+" opaque="+opaque+" merged quads="+faces.size()+" exact coverage/color");
            }
        }
    }
}
