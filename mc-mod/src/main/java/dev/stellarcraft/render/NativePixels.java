package dev.stellarcraft.render;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/** Texel-sized faces sample only texel centres, even with UE materials using shared linear samplers. */
public final class NativePixels {
    private NativePixels() {}

    /** RGB tint plus vanilla hurt/white overlay, encoded by the captured vertex stream. */
    public static int color(int source,int tint) {
        int r=((source>>>16)&255)*((tint>>>16)&255)/255;
        int g=((source>>>8)&255)*((tint>>>8)&255)/255;
        int b=(source&255)*(tint&255)/255;
        if((tint&0x1000000)!=0) {r=(r*7+255*3)/10;g=g*7/10;b=b*7/10;}
        int white=(tint>>>25)&15;
        if(white!=0) {r=r+(255-r)*white/20;g=g+(255-g)*white/20;b=b+(255-b)*white/20;}
        return (source&0xff000000)|(r<<16)|(g<<8)|b;
    }

    // Four vertices: position XYZ, UV, normal XYZ. Keep the source winding.
    public static void split(float[][] quad, BufferedImage image, Consumer<float[][]> emit) {
        int w = image.getWidth(), h = image.getHeight();
        int columns = cells(quad[0], quad[1], w, h);
        int rows = cells(quad[0], quad[3], w, h);
        for (int y = 0; y < rows; y++) for (int x = 0; x < columns;) {
            float a = (float)x / columns, b = (float)y / rows;
            float c = (float)(x + 1) / columns, d = (float)(y + 1) / rows;
            float[] centre = point(quad, (a + c) / 2, (b + d) / 2);
            int px = Math.max(0, Math.min(w - 1, (int)(centre[3] * w)));
            int py = Math.max(0, Math.min(h - 1, (int)(centre[4] * h)));
            int color=image.getRGB(px,py);
            if ((color >>> 24) < 128) {x++;continue;}
            int end=x+1;
            while(end<columns) {
                float[] next=point(quad,(end+.5f)/columns,(y+.5f)/rows);
                int nx=Math.max(0,Math.min(w-1,(int)(next[3]*w))),ny=Math.max(0,Math.min(h-1,(int)(next[4]*h)));
                if(image.getRGB(nx,ny)!=color) break;
                end++;
            }
            c=(float)end/columns;x=end;
            float[][] face = {point(quad,a,b), point(quad,c,b), point(quad,c,d), point(quad,a,d)};
            for (float[] vertex : face) {
                vertex[3] = (px + .5f) / w;
                vertex[4] = (py + .5f) / h;
            }
            emit.accept(face);
        }
    }

    private static int cells(float[] a, float[] b, int w, int h) {
        return Math.max(1, Math.min(128, Math.round(Math.max(Math.abs(a[3]-b[3])*w, Math.abs(a[4]-b[4])*h))));
    }

    private static float[] point(float[][] q, float x, float y) {
        float[] p = new float[8];
        for (int i = 0; i < 8; i++)
            p[i] = q[0][i]*(1-x)*(1-y) + q[1][i]*x*(1-y) + q[2][i]*x*y + q[3][i]*(1-x)*y;
        return p;
    }
}
