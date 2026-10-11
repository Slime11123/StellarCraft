package dev.stellarcraft.render;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.List;

/** Recover a vanilla cube's rigid pose after ModelPart has transformed its vertices. */
public final class NativeRigidParts {
    public record Part(float[] transform,float[][][] quads) {}
    private static Vector3f position(float[] v,boolean world) {return world?new Vector3f(-v[0]*100,-v[2]*100,v[1]*100):new Vector3f(-v[2]*100,v[0]*100,v[1]*100);}
    private static Vector3f normal(float[] v,boolean world) {return world?new Vector3f(-v[5],-v[7],v[6]):new Vector3f(-v[7],v[5],v[6]);}
    // Vanilla pixel dimensions and 15/16 player scale are binary fractions. Decimal
    // 0.001 snapping puts them exactly on rounding boundaries and changes cache keys.
    private static float snap(float v) {return Math.round(v*1024)/1024f;}
    public static Part cube(List<float[]> vertices,int offset) {
        return cube(vertices,offset,true);
    }
    public static Part cube(List<float[]> vertices,int offset,boolean world) {
        if(offset+24>vertices.size()) return null;
        Vector3f origin=position(vertices.get(offset),world);
        Vector3f x=position(vertices.get(offset+1),world).sub(origin),y=position(vertices.get(offset+3),world).sub(origin);
        if(x.lengthSquared()<.000001f || y.lengthSquared()<.000001f) return null;
        x.normalize();y.normalize();
        if(Math.abs(x.dot(y))>.001f) return null;
        Vector3f z=new Vector3f(x).cross(y).normalize();
        float[][][] quads=new float[6][4][8];
        int directions=0;
        for(int q=0;q<6;q++) {
            Vector3f n=normal(vertices.get(offset+q*4),world).normalize();
            float nx=n.dot(x),ny=n.dot(y),nz=n.dot(z);
            int axis=Math.abs(nx)>.999f?0:Math.abs(ny)>.999f?1:Math.abs(nz)>.999f?2:-1;
            if(axis<0) return null;
            int bit=1<<(axis*2+((axis==0?nx:axis==1?ny:nz)<0?1:0));
            if((directions&bit)!=0) return null;
            directions|=bit;
            for(int j=0;j<4;j++) {
                float[] v=vertices.get(offset+q*4+j);
                if(normal(v,world).normalize().dot(n)<.999f) return null;
                Vector3f p=position(v,world).sub(origin);
                quads[q][j]=new float[]{snap(p.dot(x)),snap(p.dot(y)),snap(p.dot(z)),v[3],v[4],snap(nx),snap(ny),snap(nz)};
            }
        }
        if(directions!=63) return null;
        Quaternionf rotation=new Quaternionf().setFromNormalized(new Matrix3f(x,y,z)).normalize();
        return new Part(new float[]{origin.x,origin.y,origin.z,rotation.x,rotation.y,rotation.z,rotation.w,1,1,1},quads);
    }
}
