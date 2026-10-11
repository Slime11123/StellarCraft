import dev.stellarcraft.render.NativeRigidParts;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.Arrays;

public class NativeRigidPartsRegression {
    public static void main(String[] args) {
        float[][][] cube={
            {{0,0,0},{1,0,0},{1,1,0},{0,1,0}},
            {{0,0,1},{0,1,1},{1,1,1},{1,0,1}},
            {{0,0,0},{0,1,0},{0,1,1},{0,0,1}},
            {{1,0,0},{1,0,1},{1,1,1},{1,1,0}},
            {{0,0,0},{0,0,1},{1,0,1},{1,0,0}},
            {{0,1,0},{1,1,0},{1,1,1},{0,1,1}}};
        float[][] normals={{0,0,-1},{0,0,1},{-1,0,0},{1,0,0},{0,-1,0},{0,1,0}};
        String geometry=null;
        for(int step=0;step<1000;step++) {
            Quaternionf q=new Quaternionf().rotationXYZ(step*.013f,step*.027f,step*.037f);
            var vertices=new ArrayList<float[]>();
            for(int face=0;face<6;face++) for(int v=0;v<4;v++) {
                Vector3f p=q.transform(new Vector3f(cube[face][v]).mul(.265625f,.703125f,.46875f)).add(1.5f,1.25f,-.3f);
                Vector3f n=q.transform(new Vector3f(normals[face]));
                vertices.add(new float[]{p.x,p.y,p.z,v%2,v/2,n.x,n.y,n.z});
            }
            var part=NativeRigidParts.cube(vertices,0);
            if(part==null) throw new AssertionError("Cube rejected at "+step);
            String shape=Arrays.deepToString(part.quads());
            if(geometry==null) geometry=shape;
            if(!geometry.equals(shape)) throw new AssertionError("Animated pose changed cached geometry at "+step);
            float[] t=part.transform();Quaternionf rotation=new Quaternionf(t[3],t[4],t[5],t[6]);
            for(int face=0;face<6;face++) for(int v=0;v<4;v++) {
                float[] local=part.quads()[face][v];
                Vector3f p=rotation.transform(new Vector3f(local[0],local[1],local[2])).add(t[0],t[1],t[2]);
                float[] source=vertices.get(face*4+v);
                if(p.distance(new Vector3f(-source[0]*100,-source[2]*100,source[1]*100))>.002f)
                    throw new AssertionError("Native vertex reconstruction drift");
            }
            vertices.get(4)[5]=vertices.get(0)[5];vertices.get(4)[6]=vertices.get(0)[6];vertices.get(4)[7]=vertices.get(0)[7];
            if(NativeRigidParts.cube(vertices,0)!=null) throw new AssertionError("Non-cube grouped incorrectly");
        }
        System.out.println("PASS: 1000 animated poses, stable limb geometry, native reconstruction, non-cube rejection");
    }
}
