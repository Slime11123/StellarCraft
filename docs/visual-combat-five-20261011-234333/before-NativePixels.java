package dev.stellarcraft.render;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/** Texel-sized faces sample only texel centres, even with UE materials using shared linear samplers. */
public final class NativePixels {
    private NativePixels() {}

    // Four vertices: position XYZ, UV, normal XYZ. Keep the source winding.
    public static void split(float[][] quad, BufferedImage image, Consumer<float[][]> emit) {
        int w = image.getWidth(), h = image.getHeight();
        int columns = cells(quad[0], quad[1], w, h);
        int rows = cells(quad[0], quad[3], w, h);
        for (int y = 0; y < rows; y++) for (int x = 0; x < columns; x++) {
            float a = (float)x / columns, b = (float)y / rows;
            float c = (float)(x + 1) / columns, d = (float)(y + 1) / rows;
            float[] centre = point(quad, (a + c) / 2, (b + d) / 2);
            int px = Math.max(0, Math.min(w - 1, (int)(centre[3] * w)));
            int py = Math.max(0, Math.min(h - 1, (int)(centre[4] * h)));
            if ((image.getRGB(px, py) >>> 24) < 128) continue;
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
